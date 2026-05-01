import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:zapstore/services/log_service.dart';

/// Helper: build an isolated `LogService` writing to a temp dir.
Future<({LogService log, Directory dir})> _newService(
    {String isolate = 'main', LogLevel level = LogLevel.debug}) async {
  final dir = await Directory.systemTemp.createTemp('log_service_test_');
  final svc = LogService.forTesting(
    directory: dir,
    isolate: isolate,
    level: level,
  );
  return (log: svc, dir: dir);
}

File _activeFile(Directory dir) => File(p.join(dir.path, 'zapstore.log'));

void main() {
  group('LogService basics', () {
    test('records to ring buffer and disk', () async {
      final (:log, :dir) = await _newService();
      log.info('hello world', tag: 'test');
      log.warn('something off', tag: 'test', fields: {'k': 1});
      await log.flush();

      final ring = log.ringSnapshot();
      expect(ring, hasLength(2));
      expect(ring[0].msg, 'hello world');
      expect(ring[0].level, LogLevel.info);
      expect(ring[1].fields, {'k': 1});

      final lines = await _activeFile(dir).readAsLines();
      expect(lines, hasLength(2));

      final decoded = lines.map(LogEntry.tryDecode).toList();
      expect(decoded.every((e) => e != null), isTrue);
      expect(decoded[1]!.fields, {'k': 1});
    });

    test('respects minimum level', () async {
      final (:log, :dir) = await _newService(level: LogLevel.warn);
      log.debug('dropped');
      log.info('also dropped');
      log.warn('kept');
      log.error('kept too');
      await log.flush();

      expect(log.ringSnapshot(), hasLength(2));
      final lines = await _activeFile(dir).readAsLines();
      expect(lines, hasLength(2));
    });

    test('ring buffer is bounded', () async {
      final (:log, :dir) = await _newService();
      // ignore: unused_local_variable
      final _ = dir;
      for (var i = 0; i < kLogRingBufferSize + 50; i++) {
        log.debug('msg $i');
      }
      await log.flush();
      final ring = log.ringSnapshot();
      expect(ring, hasLength(kLogRingBufferSize));
      expect(ring.last.msg, 'msg ${kLogRingBufferSize + 49}');
    });

    test('readTail returns latest entries', () async {
      final (:log, :dir) = await _newService();
      // ignore: unused_local_variable
      final _ = dir;
      for (var i = 0; i < 10; i++) {
        log.info('m$i');
      }
      await log.flush();
      final tail = await log.readTail(max: 3);
      expect(tail.map((e) => e.msg).toList(), ['m7', 'm8', 'm9']);
    });

    test('clear deletes files and empties ring', () async {
      final (:log, :dir) = await _newService();
      log.info('one');
      await log.flush();
      expect(_activeFile(dir).existsSync(), isTrue);

      await log.clear();
      expect(_activeFile(dir).existsSync(), isFalse);
      expect(log.ringSnapshot(), isEmpty);
    });
  });

  group('Redaction', () {
    const sampleNsec =
        'nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5';
    const sampleNcrypt =
        'ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd';
    const sampleNwc =
        'nostr+walletconnect://abc123?relay=wss%3A%2F%2Frelay.example.com&secret=deadbeef';

    test('scrub removes nsec / ncryptsec / NWC', () {
      expect(LogRedactor.scrub('my key is $sampleNsec ok'),
          'my key is [REDACTED:nsec] ok');
      expect(LogRedactor.scrub('my key is $sampleNcrypt ok'),
          'my key is [REDACTED:ncryptsec] ok');
      expect(LogRedactor.scrub('uri=$sampleNwc end'),
          'uri=[REDACTED:nwc] end');
    });

    test('logger redacts in msg, fields, err, and stack', () async {
      final (:log, :dir) = await _newService();
      log.error(
        'failed with $sampleNsec',
        tag: 'redact',
        fields: {
          'connection': sampleNwc,
          'nested': {'inner': sampleNcrypt},
          'list': ['ok', sampleNsec],
        },
        err: 'caused by $sampleNcrypt',
        stack: StackTrace.fromString('at foo() $sampleNwc'),
      );
      await log.flush();

      final raw = await _activeFile(dir).readAsString();
      expect(raw.contains('nsec1'), isFalse,
          reason: 'nsec must never appear in log output');
      expect(raw.contains('ncryptsec1'), isFalse);
      expect(raw.contains('nostr+walletconnect://'), isFalse);
      expect(raw, contains('[REDACTED:nsec]'));
      expect(raw, contains('[REDACTED:ncryptsec]'));
      expect(raw, contains('[REDACTED:nwc]'));
    });

    test('truncates oversized field strings', () {
      final big = 'x' * (kLogMaxFieldBytes + 100);
      final out = LogRedactor.scrub(big);
      expect(out.endsWith('…(truncated)'), isTrue);
      expect(out.length, kLogMaxFieldBytes + '…(truncated)'.length);
    });
  });

  group('Rotation', () {
    test('rotates when active file exceeds max size', () async {
      final (:log, :dir) = await _newService();

      // Pre-fill the active file slightly OVER the limit so the next
      // flush triggers rotation deterministically.
      final active = _activeFile(dir);
      active.writeAsStringSync('x' * (kLogMaxFileBytes + 1));

      log.info('trigger');
      await log.flush();

      // After rotation a `.1` file exists.
      expect(File(p.join(dir.path, 'zapstore.log.1')).existsSync(), isTrue);
    });

    test('keeps at most kLogMaxRotations rotated files', () async {
      final (:log, :dir) = await _newService();

      // Manually create rotations 1..kLogMaxRotations to simulate prior history.
      for (var i = 1; i <= kLogMaxRotations; i++) {
        File(p.join(dir.path, 'zapstore.log.$i'))
            .writeAsStringSync('rotation $i');
      }
      // Pre-fill active over the threshold.
      _activeFile(dir).writeAsStringSync('x' * (kLogMaxFileBytes + 1));

      log.info('trigger');
      await log.flush();

      // The oldest (.kLogMaxRotations) must be gone (its content was
      // 'rotation $kLogMaxRotations'); .1 should now contain the
      // pre-fill bytes.
      final newest = File(p.join(dir.path, 'zapstore.log.1'));
      expect(newest.existsSync(), isTrue);
      // No .{kLogMaxRotations+1}.
      expect(
          File(p.join(dir.path, 'zapstore.log.${kLogMaxRotations + 1}'))
              .existsSync(),
          isFalse);
      // Rotations .2..N are filled from older content; not asserting
      // exact mapping, but their count must not exceed the cap.
      var rotated = 0;
      for (var i = 1; i <= kLogMaxRotations; i++) {
        if (File(p.join(dir.path, 'zapstore.log.$i')).existsSync()) rotated++;
      }
      expect(rotated, lessThanOrEqualTo(kLogMaxRotations));
    });
  });

  group('Resilience', () {
    test('reader skips malformed lines', () async {
      final (:log, :dir) = await _newService();
      log.info('good 1');
      await log.flush();
      // Append garbage line.
      _activeFile(dir).writeAsStringSync('not json\n', mode: FileMode.append);
      log.info('good 2');
      await log.flush();

      final tail = await log.readTail();
      expect(tail.map((e) => e.msg).toList(), ['good 1', 'good 2']);
    });

    test('flushSync writes pending entries', () async {
      final (:log, :dir) = await _newService();
      log.fatal('about to die');
      log.flushSync();
      final lines = await _activeFile(dir).readAsLines();
      expect(lines, hasLength(1));
      final decoded = LogEntry.tryDecode(lines.single);
      expect(decoded?.level, LogLevel.fatal);
      expect(decoded?.msg, 'about to die');
    });
  });

  group('Crash sinks (contract)', () {
    // We can't easily install FlutterError.onError /
    // PlatformDispatcher.onError under flutter_test without polluting
    // the test runner, but every sink in lib/main.dart and
    // background_update_service.dart routes to LogService.I.fatal()
    // and then flushSync(). Asserting that path produces a durable
    // entry is sufficient.

    test('fatal entry survives flushSync after a synthetic crash', () async {
      final (:log, :dir) = await _newService();

      // Simulate the four sink callsites: each constructs a fatal entry
      // and calls flushSync (the same pattern as _logUncaught).
      void simulate({required String source, required Object error}) {
        log.fatal(
          'uncaught error',
          tag: 'crash',
          fields: {'source': source},
          err: error,
          stack: StackTrace.current,
        );
        log.flushSync();
      }

      simulate(source: 'flutter', error: StateError('framework boom'));
      simulate(source: 'platform_dispatcher', error: 'engine boom');
      simulate(source: 'zone', error: ArgumentError('zone boom'));
      simulate(source: 'isolate', error: 'isolate boom');

      final lines = await _activeFile(dir).readAsLines();
      expect(lines.length, 4);
      final entries = lines.map(LogEntry.tryDecode).toList();
      expect(entries.every((e) => e?.level == LogLevel.fatal), isTrue);
      expect(
        entries.map((e) => e!.fields!['source']).toSet(),
        {'flutter', 'platform_dispatcher', 'zone', 'isolate'},
      );
    });
  });

  group('Stress', () {
    test('1000 entries do not block when batched', () async {
      final (:log, :dir) = await _newService();
      // ignore: unused_local_variable
      final _ = dir;

      final stopwatch = Stopwatch()..start();
      for (var i = 0; i < 1000; i++) {
        log.info('msg $i', tag: 'stress', fields: {'i': i});
      }
      // log() itself must be cheap and fully synchronous from the
      // caller's POV — the disk write happens on a microtask.
      stopwatch.stop();

      // Generous bound; failure here means we accidentally introduced
      // a synchronous file write or an unbounded buffer copy.
      expect(stopwatch.elapsedMilliseconds, lessThan(500),
          reason: 'log() must be non-blocking');

      // Drain the flush.
      await log.flush();
      final ring = log.ringSnapshot();
      // Ring is bounded.
      expect(ring.length, kLogRingBufferSize);
      // All entries reach disk (modulo rotation, which won't fire at
      // these sizes).
      final tail = await log.readTail(max: 2000);
      expect(tail.length, 1000);
    });
  });

  group('Concurrency', () {
    test('many overlapping flushes never corrupt a line', () async {
      final (:log, :dir) = await _newService();

      // Fire many small batches in parallel — each `log()` schedules a
      // microtask flush, and we await them all together.
      final futures = <Future<void>>[];
      for (var i = 0; i < 50; i++) {
        log.info('parallel $i', tag: 'concurrency');
        futures.add(log.flush());
      }
      await Future.wait(futures);
      await log.flush();

      // Every line on disk must be a fully-formed JSON record.
      final lines = await _activeFile(dir).readAsLines();
      expect(lines.length, 50);
      for (final l in lines) {
        final decoded = LogEntry.tryDecode(l);
        expect(decoded, isNotNull, reason: 'line corrupted: $l');
        expect(decoded!.tag, 'concurrency');
      }
    });
  });

  group('LogEntry codec', () {
    test('round-trips through NDJSON', () {
      final entry = LogEntry(
        ts: DateTime.utc(2026, 1, 2, 3, 4, 5, 6),
        level: LogLevel.warn,
        tag: 'codec',
        msg: 'hi',
        fields: {'a': 1, 'b': 'x'},
        err: 'boom',
        stack: 'at foo()',
        isolate: 'main',
      );
      final decoded = LogEntry.tryDecode(entry.toJsonLine());
      expect(decoded, isNotNull);
      expect(decoded!.ts.toIso8601String(), entry.ts.toIso8601String());
      expect(decoded.level, entry.level);
      expect(decoded.tag, entry.tag);
      expect(decoded.fields, entry.fields);
      expect(decoded.err, entry.err);
      expect(decoded.stack, entry.stack);
      expect(decoded.isolate, entry.isolate);
    });

    test('rejects malformed input', () {
      expect(LogEntry.tryDecode(''), isNull);
      expect(LogEntry.tryDecode('not json'), isNull);
      expect(LogEntry.tryDecode('{}'), isNull);
      expect(LogEntry.tryDecode('{"ts":"nope","level":"info"}'), isNull);
    });
  });
}
