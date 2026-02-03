import 'package:flutter/material.dart';
import 'package:nip55_signer/nip55_signer.dart';

/// Shows a bottom sheet for selecting a NIP-55 signer app.
///
/// Returns the selected [SignerAppInfo] or null if dismissed.
Future<SignerAppInfo?> showSignerPicker(
  BuildContext context,
  List<SignerAppInfo> signers,
) async {
  return showModalBottomSheet<SignerAppInfo>(
    context: context,
    builder: (context) => SignerPickerSheet(signers: signers),
  );
}

/// Bottom sheet widget for picking between multiple NIP-55 signer apps.
class SignerPickerSheet extends StatelessWidget {
  const SignerPickerSheet({super.key, required this.signers});

  final List<SignerAppInfo> signers;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Text(
              'Choose a Nostr signer',
              style: theme.textTheme.titleLarge,
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: Text(
              'Select which app to use for signing',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          const Divider(),
          ...signers.map((signer) => ListTile(
                leading: const Icon(Icons.key),
                title: Text(signer.name),
                subtitle: Text(
                  signer.packageName,
                  style: theme.textTheme.bodySmall,
                ),
                onTap: () => Navigator.pop(context, signer),
              )),
          const SizedBox(height: 8),
        ],
      ),
    );
  }
}
