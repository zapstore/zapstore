import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/debug_utils.dart';

class SignInButton extends ConsumerWidget {
  const SignInButton({
    super.key,
    this.label = 'Sign in with Amber',
    this.minimal = false,
    this.requireNip55 = true,
  });

  final String label;
  final bool minimal;
  final bool requireNip55; // kept for API compatibility; currently unused

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final packageManager = ref.watch(packageManagerProvider);
    final isAmberInstalled = packageManager.any(
      (p) => p.appId == kAmberPackageId,
    );

    return AsyncButtonBuilder(
      onPressed: () async {
        if (!isAmberInstalled) {
          context.showInfo(
            'Install Amber to sign in with your Nostr identity',
            actions: [
              ('Open Amber', () async => context.push('/search/app/$kAmberNaddr')),
            ],
          );
        } else {
          try {
            await ref.read(amberSignerProvider).signIn();
            onSignInSuccess(ref.read(refProvider));
          } catch (e) {
            if (context.mounted) {
              context.showError('Sign-in failed: $e');
            }
          }
        }
      },
      builder: (context, child, callback, state) {
        final onPressed = state.maybeWhen(
          loading: () => null,
          orElse: () => callback,
        );
        final loading = state.maybeWhen(
          loading: () => true,
          orElse: () => false,
        );

        final buttonChild = loading
            ? const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.login, size: 18),
                  const SizedBox(width: 8),
                  Flexible(
                    child: Text(
                      label,
                      overflow: TextOverflow.visible,
                      softWrap: true,
                    ),
                  ),
                ],
              );

        if (minimal) {
          return TextButton(onPressed: onPressed, child: buttonChild);
        } else {
          return FilledButton(onPressed: onPressed, child: buttonChild);
        }
      },
      child: const SizedBox.shrink(),
    );
  }
}
