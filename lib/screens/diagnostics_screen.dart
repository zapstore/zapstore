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
    final selectedLevel = useState<LogLevel?>(null);
    final filterText = useState<String>('');
    final tickRefresh = useState(0);

    // Re-read disk tail every time the screen is opened so the user
    // sees recent entries that may have been flushed asynchronously.
    final tailFuture = useMemoized<Future<List<LogEntry>>>(
      () => LogService.I.readTail(max: 1000),
      [tickRefresh.value],
    );
    final tailSnapshot = useFuture(tailFuture, initialData: const <LogEntry>[]);
    final ringEntries = LogService.I.ringSnapshot();

    final entries = _mergeEntries(ringEntries, tailSnapshot.data ?? const []);
    final filtered = _filterEntries(
      entries,
      level: selectedLevel.value,
      query: filterText.value,
    );

    return Scaffold(
      appBar: AppBar(title: const Text('Diagnostics')),
      body: Column(
        children: [
          _LogLevelControl(),
          const Divider(height: 1),
          _ToolbarRow(
            onExport: () => _exportLogs(context),
            onClear: () => _confirmAndClear(context, () {
              tickRefresh.value++;
            }),
            onRefresh: () => tickRefresh.value++,
            entryCount: filtered.length,
          ),
          const Divider(height: 1),
          Padding(
            padding:
                const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    onChanged: (v) => filterText.value = v,
                    decoration: const InputDecoration(
                      hintText: 'Filter…',
                      prefixIcon: Icon(Icons.search),
                      isDense: true,
                      border: OutlineInputBorder(),
                    ),
                  ),
                ),
              ],
            ),
          ),
          _LevelChips(
            selected: selectedLevel.value,
            onSelected: (l) => selectedLevel.value = l,
          ),
          const Divider(height: 1),
          Expanded(
            child: filtered.isEmpty
                ? const _EmptyState()
                : _LogList(entries: filtered),
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
    LogLevel? level,
    String? query,
  }) {
    final q = (query ?? '').trim().toLowerCase();
    return entries.where((e) {
      if (level != null && e.level.index < level.index) return false;
      if (q.isEmpty) return true;
      return e.msg.toLowerCase().contains(q) ||
          e.tag.toLowerCase().contains(q) ||
          (e.err?.toLowerCase().contains(q) ?? false) ||
          (e.fields?.toString().toLowerCase().contains(q) ?? false);
    }).toList(growable: false);
  }

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

      await SharePlus.instance.share(ShareParams(
        files: [XFile(zipPath, mimeType: 'application/zip')],
        subject: 'Zapstore diagnostic logs',
        text:
            'Zapstore diagnostic logs (local export, no telemetry).',
      ));
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

class _LogLevelControl extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settingsAsync = ref.watch(localSettingsProvider);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          const Icon(Icons.tune, size: 18),
          const SizedBox(width: 8),
          const Text('Log level'),
          const Spacer(),
          settingsAsync.when(
            data: (settings) => DropdownButton<LogLevel>(
              value: settings.logLevel,
              underline: const SizedBox.shrink(),
              items: const [
                DropdownMenuItem(
                    value: LogLevel.debug, child: Text('Debug (verbose)')),
                DropdownMenuItem(value: LogLevel.info, child: Text('Info')),
                DropdownMenuItem(value: LogLevel.warn, child: Text('Warn')),
              ],
              onChanged: (level) async {
                if (level == null) return;
                await ref.read(settingsServiceProvider).update(
                      (s) => s.copyWith(logLevel: level),
                    );
                LogService.I.level = level;
                ref.invalidate(localSettingsProvider);
              },
            ),
            loading: () => const SizedBox(
              width: 14,
              height: 14,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            error: (_, __) => const Text('—'),
          ),
        ],
      ),
    );
  }
}

class _ToolbarRow extends StatelessWidget {
  const _ToolbarRow({
    required this.onExport,
    required this.onClear,
    required this.onRefresh,
    required this.entryCount,
  });

  final VoidCallback onExport;
  final VoidCallback onClear;
  final VoidCallback onRefresh;
  final int entryCount;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      child: Row(
        children: [
          IconButton(
            tooltip: 'Refresh',
            onPressed: onRefresh,
            icon: const Icon(Icons.refresh),
          ),
          IconButton(
            tooltip: 'Export logs',
            onPressed: onExport,
            icon: const Icon(Icons.ios_share),
          ),
          IconButton(
            tooltip: 'Clear logs',
            onPressed: onClear,
            icon: const Icon(Icons.delete_outline),
          ),
          const Spacer(),
          Text('$entryCount entries',
              style: Theme.of(context).textTheme.labelSmall),
          const SizedBox(width: 8),
        ],
      ),
    );
  }
}

class _LevelChips extends StatelessWidget {
  const _LevelChips({required this.selected, required this.onSelected});

  final LogLevel? selected;
  final ValueChanged<LogLevel?> onSelected;

  @override
  Widget build(BuildContext context) {
    Widget chip(String label, LogLevel? value) {
      final isSelected = selected == value;
      return Padding(
        padding: const EdgeInsets.only(right: 6),
        child: FilterChip(
          label: Text(label),
          selected: isSelected,
          onSelected: (_) => onSelected(value),
        ),
      );
    }

    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: Row(
        children: [
          chip('All', null),
          chip('Debug+', LogLevel.debug),
          chip('Info+', LogLevel.info),
          chip('Warn+', LogLevel.warn),
          chip('Error+', LogLevel.error),
        ],
      ),
    );
  }
}

class _LogList extends StatelessWidget {
  const _LogList({required this.entries});

  final List<LogEntry> entries;

  @override
  Widget build(BuildContext context) {
    return ListView.separated(
      reverse: true,
      itemCount: entries.length,
      separatorBuilder: (_, __) => const Divider(height: 1),
      itemBuilder: (context, index) {
        final e = entries[entries.length - 1 - index];
        return _LogTile(entry: e);
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
    final time =
        '${entry.ts.toLocal().hour.toString().padLeft(2, '0')}:'
        '${entry.ts.toLocal().minute.toString().padLeft(2, '0')}:'
        '${entry.ts.toLocal().second.toString().padLeft(2, '0')}';
    final fields = entry.fields;
    return ListTile(
      dense: true,
      onTap: () => _copyToClipboard(context),
      title: Row(
        children: [
          Text(
            entry.level.short,
            style: TextStyle(
              fontWeight: FontWeight.bold,
              color: _levelColor(context),
              fontFamily: 'monospace',
            ),
          ),
          const SizedBox(width: 8),
          Text(time, style: const TextStyle(fontFamily: 'monospace')),
          const SizedBox(width: 8),
          Flexible(
            child: Text(
              entry.tag,
              overflow: TextOverflow.ellipsis,
              style:
                  TextStyle(color: Theme.of(context).colorScheme.secondary),
            ),
          ),
        ],
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(entry.msg),
          if (fields != null && fields.isNotEmpty)
            Text(
              fields.toString(),
              style: const TextStyle(fontFamily: 'monospace', fontSize: 11),
            ),
          if (entry.err != null)
            Text(
              entry.err!,
              style:
                  TextStyle(color: Theme.of(context).colorScheme.error),
            ),
        ],
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
          Icon(Icons.notes,
              size: 48,
              color: Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.4)),
          const SizedBox(height: 12),
          const Text('No logs match the current filter'),
        ],
      ),
    );
  }
}
