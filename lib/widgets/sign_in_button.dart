import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/widgets/auth_widgets.dart';

class SignInButton extends ConsumerWidget {
  const SignInButton({
    super.key,
    this.label = 'Sign in with Amber', // kept for API compatibility; currently unused by UnifiedSignInButton
    this.minimal = false,
    this.requireNip55 = true, // kept for API compatibility; currently unused
  });

  final String label;
  final bool minimal;
  final bool requireNip55;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // If minimal is requested, we could customize UnifiedSignInButton or just use it as is.
    // For now, we'll use UnifiedSignInButton to ensure the Amber check logic is consistent.
    return UnifiedSignInButton(isFullWidth: false);
  }
}
