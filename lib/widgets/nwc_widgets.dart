import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/services/secure_storage_service.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/utils/extensions.dart';

/// Card showing NWC connection status and management
class NWCConnectionCard extends HookConsumerWidget {
  const NWCConnectionCard({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final hasNwc = ref.watch(hasNwcStringProvider);
    final connected = hasNwc.maybeWhen(data: (v) => v, orElse: () => false);

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
                Text('Lightning Wallet', style: context.textTheme.titleMedium),
              ],
            ),
            const SizedBox(height: 12),

            Text(
              'Use Nostr Wallet Connect (NWC) to zap the developers.',
              style: context.textTheme.bodySmall,
            ),

            const SizedBox(height: 12),

            // Connection Status
            hasNwc.when(
              loading: () => Row(
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
              data: (value) => value
                  ? Row(
                      children: [
                        Icon(
                          Icons.check_circle,
                          color: Theme.of(context).colorScheme.primary,
                          size: 16,
                        ),
                        const SizedBox(width: 8),
                        const Expanded(
                          child: Text('Lightning wallet connected'),
                        ),
                      ],
                    )
                  : Row(
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
              error: (error, _) => Row(
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
            ),

            const SizedBox(height: 16),

            // Action Buttons
            Row(
              children: [
                Expanded(
                  child: TextButton.icon(
                    onPressed: () => _showNWCDialog(context, ref),
                    icon: Icon(
                      Icons.flash_on,
                      size: 18,
                      color: Theme.of(context).brightness == Brightness.dark
                          ? Colors.white
                          : Theme.of(context).colorScheme.primary,
                    ),
                    label: Text(
                      connected ? 'Update Connection' : 'Connect Wallet',
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
                if (connected) ...[
                  const SizedBox(width: 8),
                  FilledButton(
                    onPressed: () => _removeNWCConnection(context, ref),
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

  void _showNWCDialog(BuildContext context, WidgetRef ref) async {
    final connected = await showDialog<bool>(
      context: context,
      builder: (context) => NWCConnectionDialog(ref: ref),
    );
    if (connected == true) {
      ref.invalidate(hasNwcStringProvider);
    }
  }

  void _removeNWCConnection(BuildContext context, WidgetRef ref) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: FittedBox(
          fit: BoxFit.scaleDown,
          alignment: Alignment.centerLeft,
          child: Text('Remove Wallet Connection'),
        ),
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
        final secureStorage = ref.read(secureStorageServiceProvider);
        await secureStorage.clearNWCString();
        ref.invalidate(hasNwcStringProvider);
        if (context.mounted) {
          context.showInfo(
            'Wallet disconnected',
            description: 'You can reconnect anytime from Settings.',
          );
        }
      } catch (e) {
        if (context.mounted) {
          context.showError('Failed to disconnect wallet', technicalDetails: '$e');
        }
      }
    }
  }
}

/// Dialog for connecting/updating NWC connection
class NWCConnectionDialog extends HookWidget {
  final WidgetRef ref;

  const NWCConnectionDialog({super.key, required this.ref});

  @override
  Widget build(BuildContext context) {
    final controller = useTextEditingController();
    final isLoading = useState(false);

    return AlertDialog(
      title: Row(
        children: [
          Icon(Icons.flash_on),
          const SizedBox(width: 8),
          Flexible(
            child: FittedBox(
              fit: BoxFit.scaleDown,
              alignment: Alignment.centerLeft,
              child: Text('NWC'),
            ),
          ),
        ],
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
              maxLines: 1,
              enabled: !isLoading.value,
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
      context.showError(
        'Missing connection string',
        description:
            'Get a NWC connection string from your Lightning wallet (e.g., Alby, Zeus, Coinos).',
      );
      return;
    }

    if (!nwcString.trim().startsWith('nostr+walletconnect://')) {
      context.showError(
        'Invalid NWC format',
        description:
            'Connection string should start with nostr+walletconnect://',
      );
      return;
    }

    isLoading.value = true;

    try {
      final secureStorage = ref.read(secureStorageServiceProvider);
      await secureStorage.setNWCString(nwcString.trim());

      if (context.mounted) {
        Navigator.pop(context, true);
        context.showInfo('⚡ Lightning wallet connected successfully!');
      }
    } catch (e) {
      if (context.mounted) {
        context.showError(
          'Wallet connection failed',
          description:
              'Could not connect to the wallet. Verify the connection string and try again.',
          technicalDetails: '$e',
        );
      }
    } finally {
      isLoading.value = false;
    }
  }
}
