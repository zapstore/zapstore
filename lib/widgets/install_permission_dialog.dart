import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/widgets/common/base_dialog.dart';

/// Dialog that explains the Android "Install unknown apps" permission
/// before the user sees the system permission prompt for the first time.
///
/// Stays open while user is in settings and auto-closes when permission is granted.
class InstallPermissionDialog extends HookConsumerWidget {
  const InstallPermissionDialog({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final isRequestingPermission = useState(false);
    final permissionDenied = useState(false);

    // Listen for app lifecycle to detect when user returns from settings
    useEffect(() {
      if (!isRequestingPermission.value) return null;

      void listener(AppLifecycleState state) async {
        if (state == AppLifecycleState.resumed &&
            isRequestingPermission.value) {
          // User returned from settings - check if permission was granted
          final packageManager = ref.read(packageManagerProvider.notifier);
          final hasPermission = await packageManager.hasPermission();

          if (!context.mounted) return;

          if (hasPermission) {
            // Permission granted - close dialog and proceed
            Navigator.of(context).pop(true);
          } else {
            // Permission was not granted - show message and allow retry
            isRequestingPermission.value = false;
            permissionDenied.value = true;
          }
        }
      }

      final observer = _LifecycleObserver(listener);
      final binding = WidgetsBinding.instance;
      binding.addObserver(observer);

      return () {
        binding.removeObserver(observer);
      };
    }, [isRequestingPermission.value]);

    return BaseDialog(
      title: const BaseDialogTitle('Permission required'),
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
          if (permissionDenied.value) ...[
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: theme.colorScheme.errorContainer,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                children: [
                  Icon(
                    Icons.warning_amber_rounded,
                    color: theme.colorScheme.onErrorContainer,
                    size: 20,
                  ),
                  const Gap(8),
                  Expanded(
                    child: Text(
                      'Permission was not granted. Please try again and enable "Allow from this source".',
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: theme.colorScheme.onErrorContainer,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const Gap(16),
          ],
          if (!isRequestingPermission.value)
            Text(
              'Tap "Open Settings" to enable "Allow from this source", then return here.',
              style: theme.textTheme.bodyMedium?.copyWith(
                fontWeight: FontWeight.w500,
              ),
            )
          else
            Row(
              children: [
                SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: theme.colorScheme.primary,
                  ),
                ),
                const Gap(12),
                Expanded(
                  child: Text(
                    'Waiting for permission...\nReturn here after enabling the permission.',
                    style: theme.textTheme.bodyMedium?.copyWith(
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
              ],
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
          onPressed: isRequestingPermission.value
              ? null
              : () => Navigator.of(context).pop(false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: isRequestingPermission.value
              ? null
              : () async {
                  isRequestingPermission.value = true;
                  permissionDenied.value = false;

                  try {
                    final packageManager = ref.read(
                      packageManagerProvider.notifier,
                    );
                    await packageManager.requestPermission();
                  } catch (e) {
                    // If request fails, reset state
                    if (context.mounted) {
                      isRequestingPermission.value = false;
                    }
                  }
                },
          style: FilledButton.styleFrom(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          ),
          child: Text(
            isRequestingPermission.value ? 'Waiting...' : 'Open Settings',
          ),
        ),
      ],
    );
  }
}

/// Helper class to observe app lifecycle changes
class _LifecycleObserver extends WidgetsBindingObserver {
  _LifecycleObserver(this.onStateChange);

  final void Function(AppLifecycleState state) onStateChange;

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    onStateChange(state);
  }
}
