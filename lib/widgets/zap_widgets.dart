import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';
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
    final selectedAmount = useState<int>(210);
    final commentController = useTextEditingController();
    final customAmount = useState<int?>(null);
    final quickAmounts = useMemoized(
      () => [
        (label: '🤙 210', value: 210),
        (label: '💜 2100', value: 2100),
        (label: '🤩 4200', value: 4200),
        (label: '🚀 21k', value: 21000),
        (label: '💯 100k', value: 100000),
      ],
    );
    final pubkey = ref.watch(Signer.activePubkeyProvider);
    final signer = ref.watch(Signer.activeSignerProvider);
    final hasWalletConnection = useState<bool>(false);
    useEffect(() {
      if (signer == null) {
        hasWalletConnection.value = false;
        return null;
      }
      signer
          .getNWCString()
          .then(
            (value) => hasWalletConnection.value = (value?.isNotEmpty == true),
          )
          .catchError((_) => hasWalletConnection.value = false);
      return null;
    }, [signer]);

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
              InkWell(
                onTap: () async {
                  final packageManager = ref.read(packageManagerProvider);
                  const amberPackageId = 'com.greenart7c3.nostrsigner';
                  final isAmberInstalled = packageManager.any(
                    (p) => p.appId == amberPackageId,
                  );

                  if (!isAmberInstalled) {
                    // Close the zap dialog first
                    if (context.mounted) {
                      Navigator.of(context).pop();
                    }
                    // Navigate to Amber app page
                    if (context.mounted) {
                      context.showInfo(
                        'Install Amber to sign in and unlock zapping features',
                      );
                      context.push(
                        '/profile/apps/naddr1qqdkxmmd9enhyet9deshyaphvvejumn0wd68yumfvahx2uszyp6hjpmdntls5n8aa7n7ypzlyjrv0ewch33ml3452wtjx0smhl93jqcyqqq8uzcgpp6ky',
                      );
                    }
                  } else {
                    try {
                      await ref.read(amberSignerProvider).signIn();
                      // After successful sign-in, the dialog will update automatically
                      // because pubkey will no longer be null
                    } catch (e) {
                      if (context.mounted) {
                        context.showError('Sign-in failed: $e');
                      }
                    }
                  }
                },
                borderRadius: BorderRadius.circular(8),
                child: Padding(
                  padding: const EdgeInsets.all(12),
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
                        child: Text(
                          'Tap to sign in. You can zap anonymously or sign in for attribution.',
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 12),
            ],

            if (pubkey != null && !hasWalletConnection.value) ...[
              InkWell(
                onTap: signer == null
                    ? null
                    : () async {
                        final connected = await showBaseDialog<bool>(
                          context: context,
                          dialog: NWCConnectionDialogInline(signer: signer),
                        );
                        if (connected == true) {
                          hasWalletConnection.value = true;
                        }
                      },
                borderRadius: BorderRadius.circular(8),
                child: Padding(
                  padding: const EdgeInsets.all(12),
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
                        child: Text(
                          'Tap to connect NWC for zapping, otherwise an invoice will be copied to the clipboard.',
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                      ),
                    ],
                  ),
                ),
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
                    // Prepare signer (anonymous if needed)
                    var signer = ref.read(Signer.activeSignerProvider);
                    if (signer == null) {
                      signer = Bip340PrivateKeySigner(
                        kAnonymousPrivateKey,
                        ref.ref,
                      );
                      await signer.signIn(registerSigner: false);
                    }

                    // Read NWC state (may be empty)
                    final nwcString = await signer.getNWCString();

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
                      if (context.mounted) Navigator.of(context).pop(true);
                      // Continue payment in background (no await)
                      // ignore: unawaited_futures
                      signedZapRequest
                          .pay()
                          .then((_) {
                            if (context.mounted) {
                              context.showInfo(
                                '⚡ Zap successful! ${selectedAmount.value} sats sent',
                              );
                            }
                          })
                          .catchError((e) {
                            if (context.mounted) {
                              context.showError('Zap failed: $e');
                            }
                          });
                    } else {
                      final invoice = await signedZapRequest.getInvoice();
                      if (context.mounted) Navigator.of(context).pop(true);
                      await Clipboard.setData(ClipboardData(text: invoice));
                      if (context.mounted) {
                        context.showInfo('Invoice copied to clipboard');
                      }
                    }
                  } catch (e) {
                    if (context.mounted) {
                      context.showError('Zap failed: $e');
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
class NWCConnectionDialogInline extends HookWidget {
  final Signer signer;

  const NWCConnectionDialogInline({super.key, required this.signer});

  @override
  Widget build(BuildContext context) {
    final controller = useTextEditingController();
    final isLoading = useState(false);

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
                      'Please enter a valid NWC connection string',
                    );
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
                      Navigator.pop(context, true);
                      context.showInfo(
                        '⚡ Lightning wallet connected successfully!',
                      );
                    }
                  } catch (e) {
                    if (context.mounted) {
                      context.showError('Connection failed: $e');
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
