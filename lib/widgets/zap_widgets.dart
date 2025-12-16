import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/services/secure_storage_service.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/auth_widgets.dart';
import 'package:zapstore/widgets/common/base_dialog.dart';
import 'package:zapstore/widgets/common/profile_avatar.dart';

/// Zap button for apps - shows a button to zap the app or relay
class ZapButton extends HookConsumerWidget {
  const ZapButton({super.key, required this.app, required this.author});

  final App app;
  final Profile? author;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Check if author (relay or developer) has lightning address
    final hasLud16 = author?.lud16?.trim().isNotEmpty ?? false;
    final canZap = author != null && hasLud16;

    // Determine button text based on app signing
    final buttonText = app.isRelaySigned ? 'Zap' : 'Zap this app';

    return SizedBox(
      height: 36,
      child: FilledButton(
        onPressed: canZap ? () => _showZapDialog(context, ref, app) : null,
        style: FilledButton.styleFrom(
          backgroundColor: Theme.of(context).brightness == Brightness.dark
              ? const Color(0xFF1F2937)
              : const Color(0xFF111827),
          foregroundColor: Colors.white,
          padding: const EdgeInsets.symmetric(horizontal: 16),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Text(
              '⚡️',
              style: TextStyle(color: Colors.orange, fontSize: 16),
            ),
            const SizedBox(width: 8),
            Flexible(
              child: Text(
                buttonText,
                style: Theme.of(context).textTheme.labelLarge?.copyWith(
                  color: Colors.white,
                  fontWeight: FontWeight.bold,
                ),
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<bool> _showZapDialog(
    BuildContext context,
    WidgetRef ref,
    App app,
  ) async {
    return showZapDialog(context, ref, app, author);
  }
}

/// Horizontal list of zappers with their profile avatars and amounts
class ZappersHorizontalList extends StatelessWidget {
  const ZappersHorizontalList({super.key, required this.zaps});

  final List<Zap> zaps;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color = theme.textTheme.bodySmall?.color?.withValues(alpha: 0.8);

    // Group sats per zapper (prefer zapRequest.author over wallet author)
    final Map<String, int> satsPerPubkey = <String, int>{};
    final Map<String, Profile> profileByPubkey = <String, Profile>{};

    for (final zap in zaps) {
      final requestAuthor = zap.zapRequest.value?.author.value;
      final walletAuthor = zap.author.value;
      final chosenAuthor = requestAuthor ?? walletAuthor;
      final pubkey = chosenAuthor?.pubkey;
      if (pubkey == null || chosenAuthor == null) continue;

      satsPerPubkey[pubkey] = (satsPerPubkey[pubkey] ?? 0) + zap.amount;
      profileByPubkey[pubkey] = chosenAuthor;
    }

    final entries = satsPerPubkey.entries.toList()
      ..sort((a, b) => b.value.compareTo(a.value));

    if (entries.isEmpty) return const SizedBox.shrink();

    final totalSats = satsPerPubkey.values.fold<int>(0, (sum, v) => sum + v);

    return SizedBox(
      height: 44,
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(
          children: [
            Row(
              children: [
                Text(
                  '⚡️${formatSatsCompact(totalSats)}',
                  style: theme.textTheme.titleLarge?.copyWith(
                    color: Colors.white,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(width: 16),
              ],
            ),
            for (final e in entries) ...[
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  ProfileAvatar(profile: profileByPubkey[e.key], radius: 12),
                  const SizedBox(width: 6),
                  Text(
                    formatSatsCompact(e.value),
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: color,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ],
              ),
              const SizedBox(width: 12),
            ],
          ],
        ),
      ),
    );
  }
}

/// Format sats with compact notation (k, m)
String formatSatsCompact(int sats) {
  if (sats >= 1000000) {
    final value = sats / 1000000;
    return value % 1 == 0
        ? '${value.toInt()}m'
        : '${value.toStringAsFixed(1)}m';
  }
  if (sats >= 1000) {
    final value = sats / 1000;
    return value % 1 == 0
        ? '${value.toInt()}k'
        : '${value.toStringAsFixed(1)}k';
  }
  return sats.toString();
}

/// Format sats with thousand separators (commas)
String formatSatsWithSeparators(int sats) {
  final s = sats.toString();
  final reg = RegExp(r'\B(?=(\d{3})+(?!\d))');
  return s.replaceAllMapped(reg, (m) => ',');
}

/// Show zap dialog for an app
Future<bool> showZapDialog(
  BuildContext context,
  WidgetRef ref,
  App app,
  Profile? author,
) async {
  final result = await showBaseDialog<bool>(
    context: context,
    dialog: ZapAmountDialog(
      app: app,
      isRelaySigned: app.isRelaySigned,
      author: author,
    ),
  );
  return result ?? false;
}

/// Dialog for selecting zap amount and sending zap
class ZapAmountDialog extends HookConsumerWidget {
  const ZapAmountDialog({
    super.key,
    required this.app,
    required this.isRelaySigned,
    required this.author,
  });

  final App app;
  final bool isRelaySigned;
  final Profile? author;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selectedAmount = useState<int>(2100);
    final commentController = useTextEditingController();
    final customAmount = useState<int?>(null);
    final quickAmounts = useMemoized(
      () => [
        (label: '🤙 2100', value: 2100),
        (label: '💜 10k', value: 10_000),
        (label: '🤩 21k', value: 21_000),
        (label: '🏃 42k', value: 42_000),
        (label: '💯 100k', value: 100_000),
      ],
    );
    final pubkey = ref.watch(Signer.activePubkeyProvider);
    final secureStorage = ref.watch(secureStorageServiceProvider);
    final hasWalletConnection = useState<bool>(false);
    useEffect(() {
      secureStorage
          .hasNWCString()
          .then((hasNwc) => hasWalletConnection.value = hasNwc)
          .catchError((_) => hasWalletConnection.value = false);
      return null;
    }, []);

    return BaseDialog(
      titleIcon: const Text('⚡️'),
      titleIconColor: Colors.orange,
      title: Text(
        isRelaySigned ? 'Zap the relay' : 'Zap ${app.name}',
        style: Theme.of(context).textTheme.headlineSmall,
      ),
      content: Padding(
        padding: EdgeInsets.only(
          bottom: MediaQuery.of(context).viewInsets.bottom,
        ),
        child: BaseDialogContent(
          children: [
            const SizedBox(height: 4),

            // Relay-signed app explanation
            if (isRelaySigned) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Icon(
                        Icons.info_outline,
                        size: 20,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text.rich(
                        TextSpan(
                          style: Theme.of(context).textTheme.bodyMedium,
                          children: [
                            const TextSpan(text: 'This app was indexed by '),
                            if (author != null) ...[
                              WidgetSpan(
                                alignment: PlaceholderAlignment.middle,
                                child: ProfileAvatar(
                                  profile: author,
                                  radius: 9,
                                ),
                              ),
                              const WidgetSpan(
                                alignment: PlaceholderAlignment.middle,
                                child: SizedBox(width: 4),
                              ),
                              TextSpan(
                                text: author!.nameOrNpub,
                                style: Theme.of(context).textTheme.bodyMedium
                                    ?.copyWith(fontWeight: FontWeight.bold),
                              ),
                            ] else
                              TextSpan(
                                text: 'a relay',
                                style: Theme.of(context).textTheme.bodyMedium
                                    ?.copyWith(fontWeight: FontWeight.bold),
                              ),
                            const TextSpan(
                              text:
                                  ', not directly published by a developer. Your zap will help support the service.\nIf you know the developer, ask them to self-publish to earn sats!',
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
            ],

            if (pubkey == null) ...[
              const SignInPrompt(
                message:
                    'Zapping anonymously. Sign in to zap with your identity.',
              ),
              const SizedBox(height: 12),
            ],

            // Quick amount buttons (always visible)
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                ...quickAmounts.map((q) {
                  final isSelected = selectedAmount.value == q.value;
                  return FilterChip(
                    label: Text(q.label),
                    selected: isSelected,
                    showCheckmark: false,
                    side: BorderSide.none,
                    onSelected: (selected) {
                      selectedAmount.value = q.value;
                    },
                  );
                }),
                FilterChip(
                  label: Text(
                    customAmount.value == null
                        ? '💎 Custom'
                        : '💎 ${formatSatsCompact(customAmount.value!)}',
                  ),
                  selected:
                      customAmount.value != null &&
                      selectedAmount.value == customAmount.value,
                  showCheckmark: false,
                  side: BorderSide.none,
                  onSelected: (selected) async {
                    final amount = await showBaseDialog<int>(
                      context: context,
                      dialog: const CustomAmountDialog(),
                    );
                    if (amount != null && amount > 0) {
                      customAmount.value = amount;
                      selectedAmount.value = amount;
                    }
                  },
                ),
              ],
            ),

            const SizedBox(height: 12),
            TextField(
              controller: commentController,
              decoration: const InputDecoration(
                labelText: 'Add a comment (optional)',
                border: OutlineInputBorder(),
              ),
              maxLines: 2,
            ),
          ],
        ),
      ),
      actions: [
        BaseDialogAction(
          onPressed: () => Navigator.pop(context, false),
          child: const Text('Cancel'),
        ),
        AsyncButtonBuilder(
          onPressed: selectedAmount.value > 0
              ? () async {
                  try {
                    final navigator = Navigator.of(context);

                    // Prepare signer (ephemeral if needed)
                    var signer = ref.read(Signer.activeSignerProvider);
                    if (signer == null) {
                      signer = Bip340PrivateKeySigner(
                        Utils.generateRandomHex64(),
                        ref.ref,
                      );
                      await signer.signIn(registerSigner: false);
                    }

                    // Read NWC from secure storage
                    final nwcString = await secureStorage.getNWCString();

                    // Build zap request
                    final latestMetadata = app.latestFileMetadata;
                    final author = app.author.value;

                    if (latestMetadata == null || author == null) {
                      throw Exception(
                        'App or author not ready. Please try again.',
                      );
                    }

                    final socialRelays = await ref
                        .read(storageNotifierProvider.notifier)
                        .resolveRelays('social');

                    final zapRequest = PartialZapRequest();
                    zapRequest.amount = selectedAmount.value * 1000; // msats
                    final c = commentController.text.trim();
                    if (c.isNotEmpty) zapRequest.comment = c;
                    zapRequest.linkProfileByPubkey(author.pubkey);
                    zapRequest.linkModel(app);
                    zapRequest.linkModel(latestMetadata);
                    zapRequest.relays = socialRelays;

                    final signedZapRequest = await zapRequest.signWith(signer);

                    if (nwcString != null && nwcString.isNotEmpty) {
                      final amount = selectedAmount.value;

                      // Execute payment and wait for result
                      try {
                        await _executeZapPayment(
                          signedZapRequest,
                          nwcString,
                          ref.ref,
                        );
                        if (context.mounted) {
                          context.showInfo('⚡ Zap sent! $amount sats');
                          navigator.pop(true);
                        }
                      } catch (e) {
                        if (context.mounted) {
                          context.showError('Zap failed', description: '$e');
                          navigator.pop(false);
                        }
                      }
                    } else {
                      final invoice = await signedZapRequest.getInvoice();
                      if (context.mounted) {
                        navigator.pop(true);
                        context.showInfo(
                          'Lightning invoice ready',
                          description:
                              'Copy the invoice and pay with your Lightning wallet. Setup NWC to zap directly from the app.',
                          actions: [
                            (
                              'Copy Invoice',
                              () async {
                                await Clipboard.setData(
                                  ClipboardData(text: invoice),
                                );
                              },
                            ),
                            (
                              'Setup NWC',
                              () async {
                                await showBaseDialog<bool>(
                                  context: context,
                                  dialog: const NWCConnectionDialogInline(),
                                );
                              },
                            ),
                          ],
                        );
                      }
                    }
                  } catch (e) {
                    if (context.mounted) {
                      context.showError('Zap failed', description: '$e');
                      Navigator.of(context).pop(false);
                    }
                  }
                }
              : null,
          builder: (context, child, callback, state) {
            return FilledButton(
              onPressed: state.maybeWhen(
                loading: () => null,
                orElse: () => callback,
              ),
              child: state.maybeWhen(
                loading: () => Row(
                  mainAxisSize: MainAxisSize.min,
                  children: const [
                    SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  ],
                ),
                orElse: () => Text(
                  'Send ${formatSatsWithSeparators(selectedAmount.value)} sats',
                ),
              ),
            );
          },
          child: const SizedBox.shrink(),
        ),
      ],
    );
  }
}

/// Execute zap payment using NWC connection string from secure storage
Future<PayInvoiceResult> _executeZapPayment(
  ZapRequest signedZapRequest,
  String nwcString,
  Ref ref,
) async {
  final lightningInvoice = await signedZapRequest.getInvoice();
  final command = PayInvoiceCommand(invoice: lightningInvoice);

  final result = await command.execute(
    connectionUri: nwcString,
    ref: ref,
    timeout: const Duration(seconds: 30),
  );

  return result;
}

/// Dialog for entering a custom zap amount
class CustomAmountDialog extends HookWidget {
  const CustomAmountDialog({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = useTextEditingController();
    final amount = useState<int?>(null);

    return BaseDialog(
      title: const BaseDialogTitle('Enter custom amount'),
      content: BaseDialogContent(
        children: [
          TextField(
            controller: controller,
            decoration: const InputDecoration(
              labelText: 'Amount in sats',
              border: OutlineInputBorder(),
            ),
            keyboardType: TextInputType.number,
            onChanged: (value) => amount.value = int.tryParse(value),
          ),
        ],
      ),
      actions: [
        BaseDialogAction(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: (amount.value != null && amount.value! > 0)
              ? () => Navigator.pop(context, amount.value)
              : null,
          child: const Text('Done'),
        ),
      ],
    );
  }
}

/// Inline NWC connection dialog used in zap flow
class NWCConnectionDialogInline extends HookConsumerWidget {
  const NWCConnectionDialogInline({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final controller = useTextEditingController();
    final isLoading = useState(false);
    final secureStorage = ref.watch(secureStorageServiceProvider);

    return BaseDialog(
      titleIcon: const Text('⚡️'),
      titleIconColor: Colors.orange,
      title: const BaseDialogTitle('Nostr Wallet Connect'),
      maxWidth: double.maxFinite,
      content: BaseDialogContent(
        children: [
          Text(
            'Enter your NWC connection string from your Lightning wallet:',
            style: Theme.of(context).textTheme.bodyMedium,
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
                  final t = data?.text;
                  if (t != null) controller.text = t;
                },
              ),
            ),
            maxLines: 3,
            enabled: !isLoading.value,
          ),
          const SizedBox(height: 12),
          Text(
            'Alby, Coinos, and others support NWC.',
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
      actions: [
        BaseDialogAction(
          onPressed: isLoading.value
              ? null
              : () => Navigator.pop(context, false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: isLoading.value
              ? null
              : () async {
                  final nwcString = controller.text;
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
                    await secureStorage.setNWCString(nwcString.trim());
                    if (context.mounted) {
                      Navigator.pop(context, true);
                      context.showInfo(
                        '⚡ Lightning wallet connected successfully!',
                      );
                    }
                  } catch (e) {
                    if (context.mounted) {
                      context.showError(
                        'Wallet connection failed',
                        description:
                            'Could not connect to the wallet. Verify the connection string and try again.\n\n$e',
                      );
                    }
                  } finally {
                    isLoading.value = false;
                  }
                },
          child: isLoading.value
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Text('Connect'),
        ),
      ],
    );
  }
}
