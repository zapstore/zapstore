import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/utils/extensions.dart';

/// NWC connection status states
enum NWCStatus { notSignedIn, checking, connected, disconnected, error }

/// Card showing NWC connection status and management
class NWCConnectionCard extends HookConsumerWidget {
  const NWCConnectionCard({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signer = ref.watch(Signer.activeSignerProvider);
    final pubkey = ref.watch(Signer.activePubkeyProvider);
    final isSignedIn = pubkey != null;

    // Reactive NWC state via CustomData keyed by Signer.kNwcConnectionString
    final nwcState = pubkey == null
        ? null
        : ref.watch(
            query<CustomData>(
              authors: {pubkey},
              tags: {
                '#d': {Signer.kNwcConnectionString},
              },
              limit: 1,
              source: const LocalSource(),
              subscriptionPrefix: 'nwc-data',
            ),
          );

    final NWCStatus nwcStatus = () {
      if (signer == null || pubkey == null) return NWCStatus.notSignedIn;
      final state = nwcState;
      if (state == null) return NWCStatus.checking;
      return switch (state) {
        StorageLoading() => NWCStatus.checking,
        StorageError() => NWCStatus.error,
        StorageData(:final models) =>
          (models.isNotEmpty && models.first.content.trim().isNotEmpty)
              ? NWCStatus.connected
              : NWCStatus.disconnected,
      };
    }();

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  Icons.flash_on,
                  color: Theme.of(context).colorScheme.primary,
                ),
                const SizedBox(width: 8),
                Text(
                  'Lightning Wallet',
                  style: context.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),

            Text(
              isSignedIn
                  ? 'Use Nostr Wallet Connect (NWC) to zap the developers.'
                  : 'Connect a Lightning wallet to send zaps. Sign in required.',
              style: context.textTheme.bodySmall,
            ),

            const SizedBox(height: 12),

            // Connection Status
            switch (nwcStatus) {
              NWCStatus.notSignedIn => Row(
                children: [
                  Icon(
                    Icons.warning,
                    color: Theme.of(
                      context,
                    ).colorScheme.onSurface.withValues(alpha: 0.6),
                    size: 16,
                  ),
                  const SizedBox(width: 8),
                  const Expanded(child: Text('Sign in to connect wallet')),
                ],
              ),
              NWCStatus.checking => Row(
                children: [
                  SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      valueColor: AlwaysStoppedAnimation<Color>(
                        Theme.of(context).colorScheme.primary,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  const Text('Checking wallet connection...'),
                ],
              ),

              NWCStatus.connected => Row(
                children: [
                  Icon(
                    Icons.check_circle,
                    color: Theme.of(context).colorScheme.primary,
                    size: 16,
                  ),
                  const SizedBox(width: 8),
                  const Expanded(child: Text('Lightning wallet connected')),
                ],
              ),
              NWCStatus.disconnected => Row(
                children: [
                  Icon(
                    Icons.warning,
                    color: Theme.of(
                      context,
                    ).colorScheme.onSurface.withValues(alpha: 0.6),
                    size: 16,
                  ),
                  const SizedBox(width: 8),
                  const Expanded(child: Text('No wallet connected')),
                ],
              ),
              NWCStatus.error => Row(
                children: [
                  Icon(
                    Icons.error,
                    color: Theme.of(context).colorScheme.error,
                    size: 16,
                  ),
                  const SizedBox(width: 8),
                  const Expanded(
                    child: Text('Error checking wallet connection'),
                  ),
                ],
              ),
            },

            const SizedBox(height: 16),

            // Action Buttons
            if (isSignedIn)
              Row(
                children: [
                  Expanded(
                    child: TextButton.icon(
                      onPressed: nwcStatus != NWCStatus.notSignedIn
                          ? () => _showNWCDialog(context, ref, signer)
                          : null,
                      icon: Icon(
                        Icons.flash_on,
                        size: 18,
                        color: Theme.of(context).brightness == Brightness.dark
                            ? Colors.white
                            : Theme.of(context).colorScheme.primary,
                      ),
                      label: Text(
                        nwcStatus == NWCStatus.connected
                            ? 'Update Connection'
                            : 'Connect Wallet',
                      ),
                      style: TextButton.styleFrom(
                        backgroundColor: AppColors.darkPillBackground,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 10,
                        ),
                      ),
                    ),
                  ),
                  if (nwcStatus == NWCStatus.connected) ...[
                    const SizedBox(width: 8),
                    FilledButton(
                      onPressed: () =>
                          _removeNWCConnection(context, ref, signer),
                      style: FilledButton.styleFrom(
                        backgroundColor: Theme.of(context).colorScheme.error,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 10,
                        ),
                        minimumSize: const Size(0, 0),
                      ),
                      child: const Icon(Icons.delete_outline, size: 18),
                    ),
                  ],
                ],
              ),
          ],
        ),
      ),
    );
  }

  void _showNWCDialog(BuildContext context, WidgetRef ref, Signer? signer) {
    if (signer == null) return;

    showDialog(
      context: context,
      builder: (context) => NWCConnectionDialog(signer: signer),
    );
  }

  void _removeNWCConnection(
    BuildContext context,
    WidgetRef ref,
    Signer? signer,
  ) async {
    if (signer == null) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Remove Wallet Connection'),
        content: const Text(
          'Are you sure you want to remove your Lightning wallet connection? You won\'t be able to send zaps until you reconnect.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(context).colorScheme.error,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            ),
            child: const Text('Remove'),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      try {
        await signer.setNWCString('');
        if (context.mounted) {
          context.showInfo('Wallet connection removed');
        }
      } catch (e) {
        if (context.mounted) {
          context.showError('Failed to remove connection: $e');
        }
      }
    }
  }
}

/// Dialog for connecting/updating NWC connection
class NWCConnectionDialog extends HookWidget {
  final Signer signer;

  const NWCConnectionDialog({super.key, required this.signer});

  @override
  Widget build(BuildContext context) {
    final controller = useTextEditingController();
    final isLoading = useState(false);

    return AlertDialog(
      title: const Row(
        children: [Icon(Icons.flash_on), SizedBox(width: 8), Text('NWC')],
      ),
      content: SizedBox(
        width: double.maxFinite,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Enter your NWC (Nostr Wallet Connect) connection string from your Lightning wallet:',
              style: context.textTheme.bodyMedium,
            ),
            const SizedBox(height: 16),
            TextField(
              controller: controller,
              decoration: InputDecoration(
                labelText: 'NWC Connection String',
                hintText: 'nostr+walletconnect://...',
                border: const OutlineInputBorder(),
                suffixIcon: IconButton(
                  icon: const Icon(Icons.paste),
                  onPressed: () async {
                    final data = await Clipboard.getData('text/plain');
                    if (data?.text != null) {
                      controller.text = data!.text!;
                    }
                  },
                ),
              ),
              maxLines: 3,
              enabled: !isLoading.value,
            ),
            const SizedBox(height: 12),
            Text(
              'Compatible wallets: Alby, Coinos, and others that support NWC.',
              style: context.textTheme.bodySmall,
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: isLoading.value ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: isLoading.value
              ? null
              : () => _connectWallet(context, controller.text, isLoading),
          style: FilledButton.styleFrom(
            foregroundColor: Colors.white,
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          ),
          child: isLoading.value
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                  ),
                )
              : const Text('Connect'),
        ),
      ],
    );
  }

  Future<void> _connectWallet(
    BuildContext context,
    String nwcString,
    ValueNotifier<bool> isLoading,
  ) async {
    if (nwcString.trim().isEmpty) {
      context.showError('Please enter a valid NWC connection string');
      return;
    }

    if (!nwcString.trim().startsWith('nostr+walletconnect://')) {
      context.showError(
        'Invalid NWC format. Should start with nostr+walletconnect://',
      );
      return;
    }

    isLoading.value = true;

    try {
      await signer.setNWCString(nwcString.trim());

      if (context.mounted) {
        Navigator.pop(context);
        context.showInfo('⚡ Lightning wallet connected successfully!');
      }
    } catch (e) {
      if (context.mounted) {
        context.showError('Connection failed: $e');
      }
    } finally {
      isLoading.value = false;
    }
  }
}

