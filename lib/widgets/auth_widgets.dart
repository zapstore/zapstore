import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/debug_utils.dart';

/// Reusable sign-in prompt widget for dialogs and bottom sheets.
/// Displays a styled tappable message prompting the user to sign in.
class SignInPrompt extends HookConsumerWidget {
  const SignInPrompt({super.key, required this.message});

  /// Description of what signing in enables
  final String message;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final pmState = ref.watch(packageManagerProvider);
    final isAmberInstalled = pmState.installed.containsKey(kAmberPackageId);
    final isLoading = useState(false);

    Future<void> handleSignIn() async {
      if (isLoading.value) return;

      if (!isAmberInstalled) {
        context.showInfo(
          'Install Amber to sign in with your Nostr identity',
          actions: [
            (
              'Open Amber',
              () async => context.push('/search/app/$kAmberNaddr'),
            ),
          ],
        );
      } else {
        isLoading.value = true;
        try {
          await ref.read(amberSignerProvider).signIn();
          onSignInSuccess(ref.read(refProvider));
        } catch (e) {
          if (context.mounted) {
            context.showError(
              'Sign-in failed',
              description:
                  'Amber could not complete the sign-in. Make sure Amber is installed and try again.',
              technicalDetails: '$e',
            );
          }
        } finally {
          if (context.mounted) {
            isLoading.value = false;
          }
        }
      }
    }

    return Material(
      color: theme.colorScheme.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        onTap: handleSignIn,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              if (isLoading.value)
                SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: theme.colorScheme.primary,
                  ),
                )
              else
                Icon(Icons.login, size: 20, color: theme.colorScheme.primary),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  message,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: theme.colorScheme.onSurface,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Icon(
                Icons.chevron_right,
                size: 20,
                color: theme.colorScheme.onSurface.withValues(alpha: 0.5),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
