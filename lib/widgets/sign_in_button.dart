import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/utils/debug_utils.dart';
import 'package:zapstore/widgets/signer_picker_sheet.dart';

class SignInButton extends ConsumerWidget {
  const SignInButton({
    super.key,
    this.label = 'Sign in with Nostr',
    this.minimal = false,
    this.requireNip55 = true,
  });

  final String label;
  final bool minimal;
  final bool requireNip55; // kept for API compatibility; currently unused

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final availableSigners = ref.watch(availableSignersProvider).valueOrNull ?? [];

    return AsyncButtonBuilder(
      onPressed: () async {
        if (availableSigners.isEmpty) {
          // No NIP-55 signer installed - prompt to install Amber as fallback
          context.showInfo(
            'Install a Nostr signer to sign in',
            actions: [
              (
                'Install Amber',
                () async => context.push('/search/app/$kAmberNaddr'),
              ),
            ],
          );
        } else {
          try {
            String? packageName;

            // If multiple signers, let user choose
            if (availableSigners.length > 1) {
              final selected = await showSignerPicker(context, availableSigners);
              if (selected == null) return; // User cancelled
              packageName = selected.packageName;
            }

            await ref.read(nip55SignerProvider).signIn(packageName: packageName);
            await onSignInSuccess(ref.read(refProvider));
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
