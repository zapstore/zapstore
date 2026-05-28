import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/services/device_backup_service.dart';
import 'package:zapstore/widgets/common/base_dialog.dart';

/// Dialog shown on first Amber sign-in when other device backups are found.
/// Offers to restore a device key from a previous device.
class DeviceBackupRestoreDialog extends ConsumerWidget {
  const DeviceBackupRestoreDialog({super.key, required this.backups});

  final List<DeviceBackupInfo> backups;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return BaseDialog(
      title: const BaseDialogTitle('Restore Device Key'),
      titleIcon: const Icon(Icons.restore, size: 20),
      content: BaseDialogContent(
        children: [
          const Text(
            'Found device keys from other devices linked to this identity. '
            'Restore one to sync your bookmarks and settings.',
          ),
          const SizedBox(height: 16),
          ...backups.map((info) => _BackupTile(
                info: info,
                onRestore: () {
                  Navigator.pop(context, info);
                },
              )),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Keep current key'),
        ),
      ],
    );
  }
}

class _BackupTile extends StatelessWidget {
  const _BackupTile({required this.info, this.onRestore});

  final DeviceBackupInfo info;
  final VoidCallback? onRestore;

  @override
  Widget build(BuildContext context) {
    String? dateStr;
    if (info.backedUpAt != null) {
      final dt = info.backedUpAt!;
      dateStr =
          '${dt.year}-${dt.month.toString().padLeft(2, '0')}-${dt.day.toString().padLeft(2, '0')}';
    }

    return Card(
      child: ListTile(
        leading: const Icon(Icons.smartphone),
        title: Text(info.deviceName),
        subtitle: dateStr != null ? Text('Backed up: $dateStr') : null,
        trailing: FilledButton.tonal(
          onPressed: onRestore,
          child: const Text('Restore'),
        ),
      ),
    );
  }
}
