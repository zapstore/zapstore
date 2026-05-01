import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart' show kDebugMode;
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart' show StorageError;
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

/// Severity levels for [LogService] entries.
///
/// Ordered from most to least verbose. Setting a [LogService.level] of
/// [warn] will drop [trace], [debug], and [info] entries before they
/// reach the ring buffer or the file.
enum LogLevel {
  trace,
  debug,
  info,
  warn,
  error,
  fatal;

  bool operator >=(LogLevel other) => index >= other.index;

  String get short {
    switch (this) {
      case LogLevel.trace:
        return 'T';
      case LogLevel.debug:
        return 'D';
      case LogLevel.info:
        return 'I';
      case LogLevel.warn:
        return 'W';
      case LogLevel.error:
        return 'E';
      case LogLevel.fatal:
        return 'F';
    }
  }

  static LogLevel? parse(String? name) {
    if (name == null) return null;
    for (final l in LogLevel.values) {
      if (l.name == name) return l;
    }
    return null;
  }
}

/// A single log record. Immutable, JSON-serialisable.
class LogEntry {
  final DateTime ts;
  final LogLevel level;
  final String tag;
  final String msg;
  final Map<String, Object?>? fields;
  final String? err;
  final String? stack;
  final String isolate;

  const LogEntry({
    required this.ts,
    required this.level,
    required this.tag,
    required this.msg,
    this.fields,
    this.err,
    this.stack,
    required this.isolate,
  });

  /// Encode as a single NDJSON line (without trailing newline).
  String toJsonLine() {
    final m = <String, Object?>{
      'ts': ts.toUtc().toIso8601String(),
      'level': level.name,
      'tag': tag,
      'msg': msg,
      'isolate': isolate,
    };
    if (fields != null && fields!.isNotEmpty) m['fields'] = fields;
    if (err != null) m['err'] = err;
    if (stack != null) m['stack'] = stack;
    return jsonEncode(m);
  }

  /// Decode a single NDJSON line. Returns null on malformed input so callers
  /// can skip corrupted lines without aborting a whole file read.
  static LogEntry? tryDecode(String line) {
    if (line.isEmpty) return null;
    try {
      final m = jsonDecode(line) as Map<String, dynamic>;
      final tsRaw = m['ts'] as String?;
      final levelName = m['level'] as String?;
      if (tsRaw == null || levelName == null) return null;
      final ts = DateTime.tryParse(tsRaw);
      final level = LogLevel.parse(levelName);
      if (ts == null || level == null) return null;
      return LogEntry(
        ts: ts,
        level: level,
        tag: (m['tag'] as String?) ?? '',
        msg: (m['msg'] as String?) ?? '',
        fields: (m['fields'] as Map?)?.cast<String, Object?>(),
        err: m['err'] as String?,
        stack: m['stack'] as String?,
        isolate: (m['isolate'] as String?) ?? 'unknown',
      );
    } catch (_) {
      return null;
    }
  }
}

/// Maximum size of the active log file before rotation.
const int kLogMaxFileBytes = 10 * 1024 * 1024; // 10 MB

/// Maximum number of historical (rotated) log files retained.
const int kLogMaxRotations = 5;

/// Maximum length of any single string field value in a [LogEntry].
/// Longer strings are truncated with a `…(truncated)` marker.
const int kLogMaxFieldBytes = 4 * 1024;

/// Maximum entries kept in the in-memory ring buffer.
const int kLogRingBufferSize = 500;

/// Append-only structured logger.
///
/// `LogService` is designed so that:
///   * The UI thread never performs disk I/O (writes are batched and
///     flushed on a background `Future`).
///   * Multiple isolates can write to the same file safely (each batch
///     takes an advisory file lock for the duration of the write).
///   * Logs survive a crash: writes flush on a microtask boundary and
///     [flushSync] can be called from a fatal handler before re-throwing.
///
/// Use [LogService.I] (the singleton) from app code. In tests use
/// [LogService.forTesting] to construct an instance with a custom
/// directory.
class LogService {
  /// The active singleton, available after [init] has been awaited at
  /// least once. Calls before [init] go to the in-memory ring buffer
  /// only.
  static final LogService I = LogService._();

  LogService._();

  /// Public for tests only.
  LogService.forTesting({
    required Directory directory,
    required String isolate,
    this.level = LogLevel.debug,
  })  : _dir = directory,
        _isolateName = isolate,
        _initialised = true {
    _activeFile = File(p.join(directory.path, _activeFileName));
  }

  static const String _activeFileName = 'zapstore.log';

  // ---------------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------------

  Directory? _dir;
  File? _activeFile;
  String _isolateName = 'main';

  /// Minimum severity that will be recorded. Entries below this level
  /// are dropped before they reach the ring buffer or disk.
  LogLevel level = LogLevel.debug;

  bool _initialised = false;
  bool _diskDisabled = false;

  /// Ring buffer of recent entries, newest at the end.
  final Queue<LogEntry> _ring = Queue<LogEntry>();

  /// Pending entries waiting to be flushed.
  final List<LogEntry> _pending = [];

  /// Single-flight flush future; set while a flush is in flight.
  Future<void>? _flushFuture;

  /// True while a flush is scheduled but not yet running.
  bool _flushScheduled = false;

  /// Last time we logged a "disk full" stderr warning. Rate-limited to
  /// at most one per minute so a runaway logger does not spam stderr.
  DateTime? _lastDiskFullWarn;

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  String get isolateName => _isolateName;

  /// Whether disk writes have been disabled this session due to an
  /// unrecoverable I/O error (e.g. read-only `logs/`). The ring buffer
  /// still works.
  bool get diskDisabled => _diskDisabled;

  /// Initialise the singleton. Safe to call multiple times; only the
  /// first call has an effect. [isolateName] tags every entry written
  /// from this isolate so cross-isolate logs are distinguishable.
  Future<void> init({
    required String isolateName,
    LogLevel level = LogLevel.debug,
  }) async {
    if (_initialised) return;
    _isolateName = isolateName;
    this.level = level;
    try {
      final base = await getApplicationSupportDirectory();
      final logDir = Directory(p.join(base.path, 'logs'));
      if (!logDir.existsSync()) {
        logDir.createSync(recursive: true);
      }
      _dir = logDir;
      _activeFile = File(p.join(logDir.path, _activeFileName));
      // Probe write access so we fail fast if the dir is read-only.
      _activeFile!.openSync(mode: FileMode.append).closeSync();
    } catch (e, st) {
      _diskDisabled = true;
      // Do not throw — logging must never crash the app. Surface a
      // single warning entry to the ring buffer.
      _ringAdd(_makeEntry(
        level: LogLevel.warn,
        tag: 'log_service',
        msg: 'Disk logging disabled',
        err: e.toString(),
        stack: st.toString(),
      ));
    } finally {
      _initialised = true;
    }
  }

  void trace(String msg,
          {String tag = 'app',
          Map<String, Object?>? fields,
          Object? err,
          StackTrace? stack}) =>
      log(LogLevel.trace, msg,
          tag: tag, fields: fields, err: err, stack: stack);
  void debug(String msg,
          {String tag = 'app',
          Map<String, Object?>? fields,
          Object? err,
          StackTrace? stack}) =>
      log(LogLevel.debug, msg,
          tag: tag, fields: fields, err: err, stack: stack);
  void info(String msg,
          {String tag = 'app',
          Map<String, Object?>? fields,
          Object? err,
          StackTrace? stack}) =>
      log(LogLevel.info, msg,
          tag: tag, fields: fields, err: err, stack: stack);
  void warn(String msg,
          {String tag = 'app',
          Map<String, Object?>? fields,
          Object? err,
          StackTrace? stack}) =>
      log(LogLevel.warn, msg,
          tag: tag, fields: fields, err: err, stack: stack);
  void error(String msg,
          {String tag = 'app',
          Map<String, Object?>? fields,
          Object? err,
          StackTrace? stack}) =>
      log(LogLevel.error, msg,
          tag: tag, fields: fields, err: err, stack: stack);
  void fatal(String msg,
          {String tag = 'app',
          Map<String, Object?>? fields,
          Object? err,
          StackTrace? stack}) =>
      log(LogLevel.fatal, msg,
          tag: tag, fields: fields, err: err, stack: stack);

  /// Record a log entry. Always non-blocking. The entry is added to the
  /// ring buffer immediately; disk write is scheduled on a microtask.
  void log(
    LogLevel level,
    String msg, {
    String tag = 'app',
    Map<String, Object?>? fields,
    Object? err,
    StackTrace? stack,
  }) {
    if (level.index < this.level.index) return;

    final entry = _makeEntry(
      level: level,
      tag: tag,
      msg: msg,
      fields: fields,
      err: err?.toString(),
      stack: stack?.toString(),
    );

    _ringAdd(entry);

    if (kDebugMode) {
      // Mirror to stderr in debug builds so `flutter run` shows the entry.
      // Use separate print() calls per field so adb logcat's per-line
      // truncation (~4 KB) does not eat the stack trace — which is the
      // most useful diagnostic when an error is logged.
      // ignore: avoid_print
      print('[${entry.level.short}/${entry.tag}] ${entry.msg}');
      if (entry.err != null) {
        // ignore: avoid_print
        print('  err: ${entry.err}');
      }
      if (entry.stack != null) {
        // ignore: avoid_print
        print('  stack:\n${entry.stack}');
      }
    }

    if (_diskDisabled) return;
    _pending.add(entry);
    _scheduleFlush();
  }

  /// A read-only snapshot of the in-memory ring buffer, oldest first.
  List<LogEntry> ringSnapshot() => List.unmodifiable(_ring);

  /// All log files currently on disk, newest (active) first.
  /// Returns an empty list if disk logging is disabled or the directory
  /// does not exist.
  List<File> currentFiles() {
    final dir = _dir;
    if (dir == null || !dir.existsSync()) return const [];
    final files = <File>[];
    final active = File(p.join(dir.path, _activeFileName));
    if (active.existsSync()) files.add(active);
    for (var i = 1; i <= kLogMaxRotations; i++) {
      final f = File(p.join(dir.path, '$_activeFileName.$i'));
      if (f.existsSync()) files.add(f);
    }
    return files;
  }

  /// Read recent entries from disk (newest last), capped at [max] lines
  /// counted from the tail of the active file. Skips malformed lines.
  Future<List<LogEntry>> readTail({int max = 1000}) async {
    final file = _activeFile;
    if (file == null || !file.existsSync()) return const [];
    final lines = await file.readAsLines();
    final start = lines.length > max ? lines.length - max : 0;
    final out = <LogEntry>[];
    for (var i = start; i < lines.length; i++) {
      final e = LogEntry.tryDecode(lines[i]);
      if (e != null) out.add(e);
    }
    return out;
  }

  /// Wait for any pending flush to complete and drain any remaining
  /// entries. Used in tests and from fatal handlers.
  ///
  /// Concurrency-safe: if multiple callers invoke [flush] in the same
  /// tick they all observe the same in-flight write and any newly
  /// queued entries are drained in order.
  Future<void> flush() async {
    while (true) {
      final f = _flushFuture;
      if (f != null) {
        await f;
        continue;
      }
      if (_pending.isEmpty) return;
      // No flush in flight but entries pending — kick one off and
      // record it as the active future so concurrent callers join.
      _flushFuture = _runFlush();
      await _flushFuture;
    }
  }

  /// Synchronous flush. Use only from fatal handlers where the isolate
  /// is about to die. Blocks the calling isolate's event loop.
  void flushSync() {
    if (_diskDisabled || _pending.isEmpty) return;
    final file = _activeFile;
    if (file == null) return;
    try {
      _writeBatchSync(file, _pending);
      _pending.clear();
      _maybeRotateSync();
    } catch (_) {
      // Last-resort: drop pending. Logging must never crash the app.
    }
  }

  /// Delete all log files and clear the ring buffer.
  Future<void> clear() async {
    _ring.clear();
    _pending.clear();
    final dir = _dir;
    if (dir == null) return;
    for (final f in currentFiles()) {
      try {
        f.deleteSync();
      } catch (_) {}
    }
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  LogEntry _makeEntry({
    required LogLevel level,
    required String tag,
    required String msg,
    Map<String, Object?>? fields,
    String? err,
    String? stack,
  }) {
    return LogEntry(
      ts: DateTime.now(),
      level: level,
      tag: tag,
      msg: LogRedactor.scrub(msg),
      fields: fields == null ? null : LogRedactor.scrubFields(fields),
      err: err == null ? null : LogRedactor.scrub(err),
      stack: stack == null ? null : LogRedactor.scrub(stack),
      isolate: _isolateName,
    );
  }

  void _ringAdd(LogEntry e) {
    _ring.addLast(e);
    while (_ring.length > kLogRingBufferSize) {
      _ring.removeFirst();
    }
  }

  void _scheduleFlush() {
    if (_flushScheduled || _flushFuture != null) return;
    _flushScheduled = true;
    scheduleMicrotask(() {
      _flushScheduled = false;
      // Another caller (e.g. `flush()`) may have started the flush
      // already; if so, do nothing — entries added after that will
      // re-schedule in the `_runFlush` finally block.
      if (_flushFuture != null) return;
      _flushFuture = _runFlush();
    });
  }

  Future<void> _runFlush() async {
    if (_pending.isEmpty || _diskDisabled) {
      _flushFuture = null;
      return;
    }
    final file = _activeFile;
    if (file == null) {
      _pending.clear();
      _flushFuture = null;
      return;
    }
    final batch = List<LogEntry>.from(_pending);
    _pending.clear();
    try {
      await _writeBatchAsync(file, batch);
      await _maybeRotateAsync();
    } on FileSystemException catch (e) {
      _onDiskWriteFailure(e);
    } catch (_) {
      // Swallow — logging must never crash the app.
    } finally {
      _flushFuture = null;
      // If new entries arrived while we were flushing, schedule again.
      if (_pending.isNotEmpty) _scheduleFlush();
    }
  }

  Future<void> _writeBatchAsync(File file, List<LogEntry> batch) async {
    final raf = await file.open(mode: FileMode.append);
    try {
      // Advisory exclusive lock — coordinates writes across isolates.
      await raf.lock(FileLock.blockingExclusive);
      final buffer = StringBuffer();
      for (final e in batch) {
        buffer
          ..write(e.toJsonLine())
          ..write('\n');
      }
      await raf.writeString(buffer.toString());
      await raf.flush();
      await raf.unlock();
    } finally {
      await raf.close();
    }
  }

  void _writeBatchSync(File file, List<LogEntry> batch) {
    final raf = file.openSync(mode: FileMode.append);
    try {
      raf.lockSync(FileLock.blockingExclusive);
      final buffer = StringBuffer();
      for (final e in batch) {
        buffer
          ..write(e.toJsonLine())
          ..write('\n');
      }
      raf.writeStringSync(buffer.toString());
      raf.flushSync();
      raf.unlockSync();
    } finally {
      raf.closeSync();
    }
  }

  Future<void> _maybeRotateAsync() async {
    final file = _activeFile;
    if (file == null) return;
    try {
      final size = await file.length();
      if (size < kLogMaxFileBytes) return;
      _rotateFiles();
    } catch (_) {}
  }

  void _maybeRotateSync() {
    final file = _activeFile;
    if (file == null) return;
    try {
      if (file.lengthSync() < kLogMaxFileBytes) return;
      _rotateFiles();
    } catch (_) {}
  }

  /// Rotate `zapstore.log` → `.1` → `.2` → … → `.N`. The oldest is
  /// deleted. Sync I/O — only called from a flush boundary, never the
  /// UI thread directly.
  void _rotateFiles() {
    final dir = _dir;
    if (dir == null) return;
    try {
      // Delete the oldest if it exists.
      final oldest =
          File(p.join(dir.path, '$_activeFileName.$kLogMaxRotations'));
      if (oldest.existsSync()) oldest.deleteSync();

      // Shift .N-1 → .N, .N-2 → .N-1, ... .1 → .2
      for (var i = kLogMaxRotations - 1; i >= 1; i--) {
        final src = File(p.join(dir.path, '$_activeFileName.$i'));
        if (src.existsSync()) {
          src.renameSync(p.join(dir.path, '$_activeFileName.${i + 1}'));
        }
      }

      // Active → .1
      final active = File(p.join(dir.path, _activeFileName));
      if (active.existsSync()) {
        active.renameSync(p.join(dir.path, '$_activeFileName.1'));
      }
    } catch (_) {
      // Rotation failures are non-fatal; the active file just keeps growing
      // until the next attempt.
    }
  }

  void _onDiskWriteFailure(FileSystemException e) {
    final now = DateTime.now();
    final last = _lastDiskFullWarn;
    if (last == null || now.difference(last) > const Duration(minutes: 1)) {
      _lastDiskFullWarn = now;
      // ignore: avoid_print
      print('[LogService] disk write failed: ${e.message}');
    }
    // Try to free space by rotating + dropping the oldest.
    try {
      _rotateFiles();
    } catch (_) {}
  }
}

/// Redacts known-sensitive patterns from log strings before they hit
/// the ring buffer or disk. Redaction is mandatory at write time so
/// the on-disk log is already safe to share.
///
/// Patterns covered (as defined in FEAT-005):
///   * `nsec1…` Bech32 secret keys
///   * `ncryptsec1…` encrypted secret keys
///   * `nostr+walletconnect://…` NWC URIs
///
/// Plaintext of NIP-04 / NIP-44 / NIP-17 events (kinds 4, 13, 1059) is
/// the responsibility of the call site — pass [redactPlaintext] around
/// the relevant value. The redactor below cannot detect them from a
/// raw string because the kind is structural, not lexical.
class LogRedactor {
  static final RegExp _nsec = RegExp(r'\bnsec1[ac-hj-np-z02-9]{6,}\b');
  static final RegExp _ncryptsec =
      RegExp(r'\bncryptsec1[ac-hj-np-z02-9]{6,}\b');
  static final RegExp _nwc =
      RegExp(r'nostr\+walletconnect://[^\s"\\]+', caseSensitive: false);

  /// Replace all known secret patterns in [s] with `[REDACTED:*]`
  /// markers. Also enforces [kLogMaxFieldBytes] truncation.
  static String scrub(String s) {
    var out = s
        .replaceAll(_nsec, '[REDACTED:nsec]')
        .replaceAll(_ncryptsec, '[REDACTED:ncryptsec]')
        .replaceAll(_nwc, '[REDACTED:nwc]');
    if (out.length > kLogMaxFieldBytes) {
      out = '${out.substring(0, kLogMaxFieldBytes)}…(truncated)';
    }
    return out;
  }

  /// Scrub every value in a fields map. Nested maps and lists are
  /// walked recursively. Non-string scalar values pass through
  /// unchanged.
  static Map<String, Object?> scrubFields(Map<String, Object?> fields) {
    final out = <String, Object?>{};
    fields.forEach((k, v) {
      out[k] = _scrubValue(v);
    });
    return out;
  }

  static Object? _scrubValue(Object? v) {
    if (v == null) return null;
    if (v is String) return scrub(v);
    if (v is Map) {
      return scrubFields(v.cast<String, Object?>());
    }
    if (v is Iterable) {
      return v.map(_scrubValue).toList();
    }
    return v;
  }

  /// Convenience for call sites that have already-decrypted plaintext
  /// they should never pass to the logger. Always returns the marker.
  static String redactPlaintext({String kind = 'plaintext'}) =>
      '[REDACTED:$kind]';
}

/// Riverpod observer that funnels provider failures and `StorageError`
/// states into [LogService].
///
/// - `providerDidFail` (provider build threw, or async source emitted an
///   error) is logged at `error` level.
/// - `didUpdateProvider` is logged at `warn` level when the new value
///   is a `StorageError<*>` — these are common in normal operation
///   (network blips, relay errors) so they would be too noisy at
///   `error`.
class LoggingProviderObserver extends ProviderObserver {
  const LoggingProviderObserver();

  @override
  void providerDidFail(
    ProviderBase<Object?> provider,
    Object error,
    StackTrace stackTrace,
    ProviderContainer container,
  ) {
    LogService.I.error(
      'provider failed',
      tag: 'riverpod',
      fields: {'provider': _providerLabel(provider)},
      err: error,
      stack: stackTrace,
    );
  }

  @override
  void didUpdateProvider(
    ProviderBase<Object?> provider,
    Object? previousValue,
    Object? newValue,
    ProviderContainer container,
  ) {
    if (newValue is StorageError) {
      LogService.I.warn(
        'storage error',
        tag: 'riverpod',
        fields: {'provider': _providerLabel(provider)},
        err: newValue.exception,
        stack: newValue.stackTrace,
      );
    }
  }

  static String _providerLabel(ProviderBase<Object?> provider) {
    final name = provider.name;
    if (name != null && name.isNotEmpty) return name;
    return provider.runtimeType.toString();
  }
}
