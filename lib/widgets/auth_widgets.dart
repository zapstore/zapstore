import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/utils/debug_utils.dart';
import 'package:zapstore/theme.dart';

/// Reusable sign-in prompt widget for dialogs and bottom sheets.
class SignInPrompt extends HookConsumerWidget {
  const SignInPrompt({super.key, required this.message});

  final String message;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: theme.colorScheme.outline.withValues(alpha: 0.2),
        ),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Icon(
                Icons.info_outline_rounded,
                size: 20,
                color: theme.colorScheme.primary,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  message,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: theme.colorScheme.onSurface,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          UnifiedSignInButton(),
        ],
      ),
    );
  }
}

/// A unified sign-in button that handles both Amber installation check and signing in.
class UnifiedSignInButton extends ConsumerWidget {
  const UnifiedSignInButton({super.key, this.isFullWidth = true});

  final bool isFullWidth;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pmState = ref.watch(packageManagerProvider);
    final isAmberInstalled = pmState.installed.containsKey(kAmberPackageId);

    return AsyncButtonBuilder(
      onPressed: () async {
        if (!isAmberInstalled) {
          context.push('/search/app/$kAmberNaddr');
        } else {
          try {
            await ref.read(amberSignerProvider).signIn();
            onSignInSuccess(ref.read(refProvider));
          } catch (e) {
            if (context.mounted) {
              context.showError(
                'Sign-in failed',
                description: 'Amber could not complete the sign-in.\n\n$e',
              );
            }
          }
        }
      },
      builder: (context, child, callback, state) {
        final loading = state.maybeWhen(loading: () => true, orElse: () => false);

        return SizedBox(
          width: isFullWidth ? double.infinity : null,
          child: FilledButton(
            onPressed: loading ? null : callback,
            style: FilledButton.styleFrom(
              backgroundColor: isAmberInstalled 
                ? AppColors.darkActionPrimary 
                : Colors.amber.shade900,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            child: loading
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        isAmberInstalled ? Icons.login_rounded : Icons.download_rounded,
                        size: 18,
                      ),
                      const SizedBox(width: 10),
                      Text(
                        isAmberInstalled ? 'Sign in with Amber' : 'Install Amber to Sign In',
                        style: const TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ],
                  ),
          ),
        );
      },
      child: const SizedBox.shrink(),
    );
  }
}