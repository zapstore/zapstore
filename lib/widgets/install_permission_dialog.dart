import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:zapstore/widgets/common/base_dialog.dart';

/// Dialog that explains the Android "Install unknown apps" permission
/// before the user sees the system permission prompt for the first time.
class InstallPermissionDialog extends StatelessWidget {
  const InstallPermissionDialog({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return BaseDialog(
      title: const BaseDialogTitle('Permission Required'),
      titleIcon: Icon(
        Icons.security_outlined,
        color: theme.colorScheme.primary,
      ),
      titleIconColor: theme.colorScheme.primary,
      content: BaseDialogContent(
        children: [
          Text(
            'Zapstore needs permission to install apps on your device.',
            style: theme.textTheme.bodyMedium,
          ),
          const Gap(16),
          Text(
            'On the next screen, turn on "Allow from this source" and come back to continue.',
            style: theme.textTheme.bodyMedium?.copyWith(
              fontWeight: FontWeight.w500,
            ),
          ),
          const Gap(12),
          Text(
            'You can revoke this permission at any time in Android Settings.',
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(true),
          style: FilledButton.styleFrom(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          ),
          child: const Text('Continue'),
        ),
      ],
    );
  }
}
