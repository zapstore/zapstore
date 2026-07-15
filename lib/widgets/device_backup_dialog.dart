import 'package:flutter/material.dart';
import 'package:zapstore/widgets/common/base_dialog.dart';

/// Dialog shown when Amber can recover a previous device key.
class DeviceBackupRestoreDialog extends StatelessWidget {
  const DeviceBackupRestoreDialog({
    super.key,
    required this.onRestore,
    required this.onKeepCurrent,
  });

  final VoidCallback onRestore;
  final VoidCallback onKeepCurrent;

  @override
  Widget build(BuildContext context) {
    return BaseDialog(
      title: const BaseDialogTitle('Restore Device Key'),
      titleIcon: const Icon(Icons.restore, size: 20),
      content: BaseDialogContent(
        children: [
          const Text(
            'Amber has a device key backup. Restore it to recover your '
            'bookmarks and portable settings.',
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: onKeepCurrent,
          child: const Text('Keep current key'),
        ),
        FilledButton(onPressed: onRestore, child: const Text('Restore')),
      ],
    );
  }
}
