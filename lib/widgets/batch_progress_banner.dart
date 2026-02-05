import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/utils/extensions.dart';

/// "Update All" button row.
/// Progress and "All done" states are handled by sticky banners in _UpdatesListBody.
class UpdateAllRow extends ConsumerWidget {
  const UpdateAllRow({super.key, required this.allUpdates});

  final List<App> allUpdates;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (allUpdates.length < 2) {
      return const SizedBox.shrink();
    }

    return Container(
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 8),
      width: double.infinity,
      child: TextButton.icon(
        onPressed: () async {
          final pm = ref.read(packageManagerProvider.notifier);
          final items = allUpdates
              .where((app) => app.latestFileMetadata != null)
              .map(
                (app) => (
                  appId: app.identifier,
                  target: app.latestFileMetadata!,
                  displayName: app.name,
                ),
              )
              .toList();
          await pm.queueDownloads(items);
        },
        icon: const Icon(Icons.download, size: 16, color: Colors.white),
        label: Text(
          'Update All (${allUpdates.length})',
          style: const TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w600,
            color: Colors.white,
          ),
        ),
        style: TextButton.styleFrom(
          backgroundColor: AppColors.darkPillBackground,
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        ),
      ),
    );
  }
}
