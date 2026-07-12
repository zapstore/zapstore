import 'dart:async';
import 'dart:io';

import 'package:archive/archive_io.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/services/settings_service.dart';

/// Full-screen diagnostics view: viewer, export, clear, level selector.
///
/// Backed entirely by [LogService] — no network access, no opt-in
/// telemetry. Export uses the OS share sheet via `share_plus`.
class DiagnosticsScreen extends HookConsumerWidget {
  const DiagnosticsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tickRefresh = useState(0);
    final scrollController = useScrollController();

    // Re-read disk tail every time the screen is opened so the user
    // sees recent entries that may have been flushed asynchronously.
    final tailFuture = useMemoized<Future<List<LogEntry>>>(
      () => LogService.I.readTail(max: 1000),
      [tickRefresh.value],
    );
    final tailSnapshot = useFuture(tailFuture, initialData: const <LogEntry>[]);
    final ringEntries = LogService.I.ringSnapshot();

    final entries = _mergeEntries(ringEntries, tailSnapshot.data ?? const []);
    final settingsAsync = ref.watch(localSettingsProvider);
    final currentLevel = settingsAsync.valueOrNull?.logLevel ?? LogLevel.info;
    final filtered = _filterEntries(entries, level: currentLevel);
    final newestEntryKey = filtered.isEmpty ? null : _entryKey(filtered.last);

    // The newest log entry is rendered at offset zero. Keep that position
    // sticky when a refresh adds entries, but never pull someone reading
    // older entries back to the top.
    useEffect(() {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (scrollController.hasClients &&
            scrollController.offset <= _kStickyTopThreshold) {
          scrollController.jumpTo(0);
        }
      });
      return null;
    }, [newestEntryKey, scrollController]);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Diagnostics'),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(1),
          child: Divider(
            height: 1,
            color: Theme.of(context).colorScheme.outlineVariant,
          ),
        ),
      ),
      body: Column(
        children: [
          _Toolbar(
            level: currentLevel,
            onLevelChanged: (level) async {
              await ref
                  .read(settingsServiceProvider)
                  .update((s) => s.copyWith(logLevel: level));
              LogService.I.level = level;
              ref.invalidate(localSettingsProvider);
            },
            onRefresh: () => tickRefresh.value++,
            onExport: () => _exportLogs(context),
            onClear: () => _confirmAndClear(context, () {
              tickRefresh.value++;
            }),
            entryCount: filtered.length,
          ),
          Expanded(
            child: filtered.isEmpty
                ? const _EmptyState()
                : _LogList(entries: filtered, controller: scrollController),
          ),
        ],
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Logic
  // ---------------------------------------------------------------------------

  static List<LogEntry> _mergeEntries(
    List<LogEntry> ring,
    List<LogEntry> disk,
  ) {
    // Disk has older history, ring has newest in-memory. Merge by
    // timestamp + isolate + msg fingerprint to suppress exact duplicates.
    final seen = <String>{};
    final merged = <LogEntry>[];
    for (final e in [...disk, ...ring]) {
      final key =
          '${e.ts.microsecondsSinceEpoch}|${e.isolate}|${e.level}|${e.tag}|${e.msg}';
      if (seen.add(key)) merged.add(e);
    }
    merged.sort((a, b) => a.ts.compareTo(b.ts));
    return merged;
  }

  static List<LogEntry> _filterEntries(
    List<LogEntry> entries, {
    required LogLevel level,
  }) {
    return entries
        .where((e) => e.level.index >= level.index)
        .toList(growable: false);
  }

  static const _kStickyTopThreshold = 24.0;

  static String _entryKey(LogEntry entry) =>
      '${entry.ts.microsecondsSinceEpoch}|${entry.isolate}|${entry.level}|'
      '${entry.tag}|${entry.msg}';

  Future<void> _exportLogs(BuildContext context) async {
    final files = LogService.I.currentFiles();
    if (files.isEmpty) {
      if (context.mounted) {
        context.showInfo('No logs to export');
      }
      return;
    }
    try {
      // Snapshot files into the cache dir before zipping so the export
      // is consistent even if writes continue in the background.
      final cacheDir = await getApplicationCacheDirectory();
      final stamp = DateTime.now()
          .toUtc()
          .toIso8601String()
          .replaceAll(':', '-')
          .replaceAll('.', '-');
      final outDir = Directory(p.join(cacheDir.path, 'log_exports'));
      if (!outDir.existsSync()) outDir.createSync(recursive: true);
      final zipPath = p.join(outDir.path, 'zapstore-logs-$stamp.zip');

      final encoder = ZipFileEncoder();
      encoder.create(zipPath);
      for (final f in files) {
        if (!f.existsSync()) continue;
        await encoder.addFile(f, p.basename(f.path));
      }
      await encoder.close();

      if (!context.mounted) return;
      final size = File(zipPath).lengthSync();
      // Surface size before sharing so the user can cancel a large transfer.
      context.showInfo('Exported ${_humanBytes(size)}');

      await SharePlus.instance.share(
        ShareParams(
          files: [XFile(zipPath, mimeType: 'application/zip')],
          subject: 'Zapstore diagnostic logs',
          text: 'Zapstore diagnostic logs (local export, no telemetry).',
        ),
      );
    } catch (e, st) {
      LogService.I.error(
        'log export failed',
        tag: 'diagnostics',
        err: e,
        stack: st,
      );
      if (context.mounted) {
        context.showError('Failed to export logs', technicalDetails: '$e');
      }
    }
  }

  Future<void> _confirmAndClear(
    BuildContext context,
    VoidCallback onCleared,
  ) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Clear logs?'),
        content: const Text(
          'This deletes all local diagnostic logs from this device. '
          'Already-exported files are not affected.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Clear'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    await LogService.I.clear();
    onCleared();
    if (context.mounted) context.showInfo('Logs cleared');
  }

  static String _humanBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) {
      return '${(bytes / 1024).toStringAsFixed(1)} KB';
    }
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
}

// =============================================================================
// Sub-widgets
// =============================================================================

/// Single compact row containing level filter + actions + entry count.
class _Toolbar extends StatelessWidget {
  const _Toolbar({
    required this.level,
    required this.onLevelChanged,
    required this.onRefresh,
    required this.onExport,
    required this.onClear,
    required this.entryCount,
  });

  final LogLevel level;
  final ValueChanged<LogLevel> onLevelChanged;
  final VoidCallback onRefresh;
  final VoidCallback onExport;
  final VoidCallback onClear;
  final int entryCount;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 8, 10),
      child: Row(
        children: [
          Icon(Icons.terminal_rounded, size: 18, color: colors.primary),
          const SizedBox(width: 8),
          DropdownButtonHideUnderline(
            child: DropdownButton<LogLevel>(
              value: level,
              isDense: true,
              style: theme.textTheme.labelLarge,
              items: const [
                DropdownMenuItem(value: LogLevel.debug, child: Text('Debug')),
                DropdownMenuItem(value: LogLevel.info, child: Text('Info')),
                DropdownMenuItem(value: LogLevel.warn, child: Text('Warn')),
              ],
              onChanged: (v) {
                if (v != null) onLevelChanged(v);
              },
            ),
          ),
          const Spacer(),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: colors.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Text(
              '$entryCount entries',
              style: theme.textTheme.labelSmall,
            ),
          ),
          const SizedBox(width: 4),
          IconButton(
            tooltip: 'Refresh',
            onPressed: onRefresh,
            iconSize: 18,
            visualDensity: VisualDensity.compact,
            padding: const EdgeInsets.all(6),
            constraints: const BoxConstraints(),
            icon: const Icon(Icons.refresh),
          ),
          const SizedBox(width: 4),
          IconButton(
            tooltip: 'Export logs',
            onPressed: onExport,
            iconSize: 18,
            visualDensity: VisualDensity.compact,
            padding: const EdgeInsets.all(6),
            constraints: const BoxConstraints(),
            icon: const Icon(Icons.ios_share),
          ),
          const SizedBox(width: 4),
          IconButton(
            tooltip: 'Clear logs',
            onPressed: onClear,
            iconSize: 18,
            visualDensity: VisualDensity.compact,
            padding: const EdgeInsets.all(6),
            constraints: const BoxConstraints(),
            icon: const Icon(Icons.delete_outline),
          ),
        ],
      ),
    );
  }
}

class _LogList extends StatelessWidget {
  const _LogList({required this.entries, required this.controller});

  final List<LogEntry> entries;
  final ScrollController controller;

  @override
  Widget build(BuildContext context) {
    return ListView.separated(
      controller: controller,
      padding: const EdgeInsets.symmetric(vertical: 8),
      itemCount: entries.length,
      separatorBuilder: (_, __) => const SizedBox(height: 4),
      itemBuilder: (context, index) {
        final e = entries[entries.length - 1 - index];
        return Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: _LogTile(entry: e),
        );
      },
    );
  }
}

class _LogTile extends StatelessWidget {
  const _LogTile({required this.entry});

  final LogEntry entry;

  Color _levelColor(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    switch (entry.level) {
      case LogLevel.fatal:
      case LogLevel.error:
        return cs.error;
      case LogLevel.warn:
        return cs.tertiary;
      case LogLevel.info:
        return cs.primary;
      case LogLevel.debug:
      case LogLevel.trace:
        return cs.onSurface.withValues(alpha: 0.6);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final time =
        '${entry.ts.toLocal().hour.toString().padLeft(2, '0')}:'
        '${entry.ts.toLocal().minute.toString().padLeft(2, '0')}:'
        '${entry.ts.toLocal().second.toString().padLeft(2, '0')}';
    final fields = entry.fields;
    final levelColor = _levelColor(context);
    return Material(
      color: colors.surfaceContainerLow,
      borderRadius: BorderRadius.circular(10),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => _copyToClipboard(context),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    width: 7,
                    height: 7,
                    decoration: BoxDecoration(
                      color: levelColor,
                      shape: BoxShape.circle,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    entry.level.short.toUpperCase(),
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: levelColor,
                      fontFamily: 'monospace',
                      fontWeight: FontWeight.w700,
                      letterSpacing: 0.8,
                    ),
                  ),
                  const SizedBox(width: 10),
                  Text(
                    time,
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: colors.onSurfaceVariant,
                      fontFamily: 'monospace',
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      entry.tag,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: colors.secondary,
                        fontFamily: 'monospace',
                      ),
                    ),
                  ),
                  Icon(
                    Icons.copy_rounded,
                    size: 14,
                    color: colors.onSurfaceVariant,
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(entry.msg, style: theme.textTheme.bodyMedium),
              if (fields != null && fields.isNotEmpty) ...[
                const SizedBox(height: 6),
                Text(
                  fields.toString(),
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: colors.onSurfaceVariant,
                    fontFamily: 'monospace',
                  ),
                ),
              ],
              if (entry.err != null) ...[
                const SizedBox(height: 6),
                Text(
                  entry.err!,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: colors.error,
                    fontFamily: 'monospace',
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  void _copyToClipboard(BuildContext context) {
    Clipboard.setData(ClipboardData(text: entry.toJsonLine()));
    if (context.mounted) {
      context.showInfo('Entry copied');
    }
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.terminal_rounded,
            size: 40,
            color: Theme.of(
              context,
            ).colorScheme.onSurface.withValues(alpha: 0.4),
          ),
          const SizedBox(height: 12),
          Text(
            'No matching log entries',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 4),
          Text(
            'Try a lower log level or refresh the log tail.',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }
}
