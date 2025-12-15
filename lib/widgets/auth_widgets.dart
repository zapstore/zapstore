import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/widgets/common/base_dialog.dart';

/// Dialog prompting user to sign in for bookmark features
class SignInPromptDialog extends HookConsumerWidget {
  const SignInPromptDialog({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return BaseDialog(
      titleIcon: const Icon(Icons.bookmark),
      title: Text(
        'Sign in to bookmark',
        style: Theme.of(context).textTheme.headlineSmall,
      ),
      content: BaseDialogContent(
        children: [
          const SizedBox(height: 8),
          Text(
            'Sign in to bookmark apps and create app packs.',
            style: Theme.of(context).textTheme.bodyMedium,
          ),
        ],
      ),
      actions: [
        BaseDialogAction(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        AsyncButtonBuilder(
          onPressed: () async {
            try {
              await ref.read(amberSignerProvider).signIn();
              onSignInSuccess(ref);
              if (context.mounted) {
                Navigator.pop(context);
              }
            } catch (e) {
              if (context.mounted) {
                context.showError(
                  'Sign-in failed',
                  description:
                      'Amber could not complete the sign-in. Make sure Amber is installed and try again.\n\n$e',
                );
              }
            }
          },
          builder: (context, child, callback, buttonState) {
            return FilledButton.icon(
              onPressed: buttonState.maybeWhen(
                loading: () => null,
                orElse: () => callback,
              ),
              icon: buttonState.maybeWhen(
                loading: () => const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
                orElse: () => const Icon(Icons.login, size: 18),
              ),
              label: const Text('Sign in'),
            );
          },
          child: const SizedBox.shrink(),
        ),
      ],
    );
  }
}
