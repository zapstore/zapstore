import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/services/app_restart_service.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/services/secure_storage_service.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/utils/extensions.dart';

/// Provider for the user's app catalog relay list (from signed 10067 event).
/// Returns null when signed out.
final _appCatalogRelayListProvider =
    Provider<StorageState<AppCatalogRelayList>?>((ref) {
      final pubkey = ref.watch(Signer.activePubkeyProvider);

      if (pubkey == null) {
        return null;
      }

      return ref.watch(
        query<AppCatalogRelayList>(
          authors: {pubkey},
          limit: 1,
          source: const LocalAndRemoteSource(
            relays: 'bootstrap',
            stream: false,
          ),
          subscriptionPrefix: 'user-appcatalog-relays',
        ),
      );
    });


/// App Catalog Relay Management Card - manages app catalog relays (kind 10067)
/// These are relays for discovering apps, NOT social relays like Damus/Primal.
///
/// Changes are accumulated in memory and applied with "Apply Changes" which
/// publishes the relay list and restarts the app with a fresh database.
class RelayManagementCard extends HookConsumerWidget {
  const RelayManagementCard({super.key});

  static const _kDefaultRelay = 'wss://relay.zapstore.dev';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);
    final isSignedIn = signedInPubkey != null;

    final relayUrlController = useTextEditingController();
    final hasText = useState(false);
    final isApplying = useState(false);

    // Watch pool state for relay connection status
    final poolState = ref.watch(poolStateProvider);

    // Load local relays once - they only change on app restart
    final localRelaysFuture = useMemoized(
      () => ref.read(secureStorageServiceProvider).getAppCatalogRelays(),
    );
    final localRelaysSnapshot = useFuture(localRelaysFuture);
    final localRelays = localRelaysSnapshot.data?.toList()?..sort();
    final hasLocalRelays = localRelays != null && localRelays.isNotEmpty;

    // Only check 10067 if no local relays are stored
    // This makes secure storage the authoritative local source
    final relayListState = hasLocalRelays
        ? null
        : ref.watch(_appCatalogRelayListProvider);
    final existingRelayList = relayListState?.models.firstOrNull;
    final remoteRelays = (existingRelayList?.readRelays ?? <String>{}).toList()
      ..sort();

    // Determine effective saved relays:
    // 1. Local secure storage (if set) - always wins
    // 2. Remote 10067 (if signed in and no local relays)
    // 3. Default relay
    List<String> effectiveSavedRelays;
    if (hasLocalRelays) {
      effectiveSavedRelays = localRelays;
    } else if (remoteRelays.isNotEmpty) {
      effectiveSavedRelays = remoteRelays;
    } else {
      effectiveSavedRelays = [_kDefaultRelay];
    }

    // Local pending state - initialized from effective saved relays
    final pendingRelays = useState<List<String>?>(null);

    // Determine if data is still loading
    final isLoading = localRelaysSnapshot.connectionState == ConnectionState.waiting ||
        (!hasLocalRelays && relayListState is StorageLoading);

    // Initialize pending from effective saved when first loaded
    useEffect(() {
      if (pendingRelays.value == null && !isLoading) {
        pendingRelays.value = effectiveSavedRelays;
      }
      return null;
    }, [isLoading, effectiveSavedRelays]);

    // Current display relays (pending if modified, else effective saved)
    final displayRelays = pendingRelays.value ?? effectiveSavedRelays;
    final hasChanges =
        pendingRelays.value != null &&
        !const ListEquality<String>().equals(
          pendingRelays.value,
          effectiveSavedRelays,
        );

    // Listen to text changes to enable/disable add button
    useEffect(() {
      void listener() {
        hasText.value = relayUrlController.text.trim().isNotEmpty;
      }

      relayUrlController.addListener(listener);
      return () => relayUrlController.removeListener(listener);
    }, [relayUrlController]);

    void addRelay(String relayUrl) {
      // Validate and normalize URL
      final normalizedUrl = _validateAndNormalizeRelayUrl(relayUrl);
      if (normalizedUrl == null) {
        context.showError(
          'Invalid relay URL',
          description: 'Must be a valid WebSocket URL (ws:// or wss://)',
        );
        return;
      }

      // Check for duplicates
      final currentRelays = pendingRelays.value ?? effectiveSavedRelays;
      if (_isDuplicateRelay(normalizedUrl, currentRelays.toSet())) {
        context.showError(
          'Relay already exists',
          description: 'This relay is already in your list.',
        );
        return;
      }

      pendingRelays.value = [...currentRelays, normalizedUrl]..sort();
      relayUrlController.clear();
    }

    void removeRelay(String relayUrl) {
      final currentRelays = pendingRelays.value ?? effectiveSavedRelays;
      final newRelays = currentRelays.where((r) => r != relayUrl).toList();
      // App catalog relays can never be empty - show error if trying to remove last
      if (newRelays.isEmpty) {
        context.showError(
          'Cannot remove last relay',
          description:
              'App catalog relays cannot be empty. '
              'Add another relay before removing this one.',
        );
        return;
      }
      pendingRelays.value = newRelays;
    }

    Future<void> applyChanges() async {
      // Show confirmation dialog with privacy option (only when signed in)
      final result = await showDialog<({bool confirmed, bool makePrivate})>(
        context: context,
        builder: (context) => _ApplyRelayChangesDialog(isSignedIn: isSignedIn),
      );

      if (result?.confirmed != true || !context.mounted) return;

      isApplying.value = true;
      var loadingDialogShown = false;

      try {
        final secureStorage = ref.read(secureStorageServiceProvider);
        final relaysToSave = displayRelays.toSet();

        // Always save to local secure storage (works signed out or in)
        await secureStorage.setAppCatalogRelays(relaysToSave);

        // If signed in, also publish 10067 event for cross-device sync
        if (isSignedIn) {
          final signer = ref.read(Signer.activeSignerProvider);
          if (signer == null) {
            isApplying.value = false;
            if (context.mounted) {
              context.showError('Sign in required to publish relay list');
            }
            return;
          }

          // Create relay list (private or public based on user choice)
          final PartialAppCatalogRelayList partialRelayList;
          if (result!.makePrivate) {
            // Encrypted: all relays in content field
            partialRelayList = PartialAppCatalogRelayList.withEncryptedRelays(
              publicRelays: {},
              privateRelays: relaysToSave,
            );
          } else {
            // Public: relays in r tags
            partialRelayList = PartialAppCatalogRelayList();
            for (final relay in displayRelays) {
              partialRelayList.addReadRelay(relay);
            }
          }
          final signedRelayList = await partialRelayList.signWith(signer);

          // Publish to bootstrap relays
          await ref.storage.publish({
            signedRelayList,
          }, source: const RemoteSource(relays: 'bootstrap'));
        }

        // Show loading dialog
        if (context.mounted) {
          loadingDialogShown = true;
          showDialog(
            context: context,
            barrierDismissible: false,
            builder: (dialogContext) => const AlertDialog(
              content: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  CircularProgressIndicator(
                    color: AppColors.darkSkeletonHighlight,
                    backgroundColor: AppColors.darkSkeletonBase,
                  ),
                  SizedBox(height: 16),
                  Text('Restarting...'),
                ],
              ),
            ),
          );
        }

        // Restart app with database clear
        await restartApp();
      } catch (e) {
        isApplying.value = false;
        if (context.mounted) {
          if (loadingDialogShown) {
            Navigator.of(context, rootNavigator: true).pop();
          }
          context.showError(
            'Failed to apply relay changes',
            description: '$e',
            actions: [('Retry', () => applyChanges())],
          );
        }
      }
    }

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Header
            Row(
              children: [
                Icon(Icons.dns, color: Theme.of(context).colorScheme.primary),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'App Catalog Relays',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),

            // Info text
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
                      size: 16,
                      color: Theme.of(context).colorScheme.primary,
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      isSignedIn
                          ? 'These relays are used to discover apps, not social content. Modify this list at your own risk.'
                          : 'These relays are used to discover apps. Sign in to sync relay settings across devices.',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(
                          context,
                        ).colorScheme.onSurface.withValues(alpha: 0.7),
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),

            // Current relays list
            if (displayRelays.isEmpty)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                  'Using default catalog relays. Add a relay to override.',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(
                      context,
                    ).colorScheme.onSurface.withValues(alpha: 0.55),
                  ),
                ),
              )
            else
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 200),
                child: ListView.separated(
                  shrinkWrap: true,
                  physics: displayRelays.length > 4
                      ? const BouncingScrollPhysics()
                      : const NeverScrollableScrollPhysics(),
                  itemCount: displayRelays.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 6),
                  itemBuilder: (context, index) {
                    final relayUrl = displayRelays[index];

                    // Get relay connection status from pool state
                    final statusColor = _getRelayStatusColor(
                      poolState,
                      relayUrl,
                    );

                    return Container(
                      width: double.infinity,
                      padding: const EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 8,
                      ),
                      decoration: BoxDecoration(
                        color: Theme.of(context)
                            .colorScheme
                            .surfaceContainerHighest
                            .withValues(alpha: 0.3),
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(
                          color: Theme.of(
                            context,
                          ).colorScheme.outline.withValues(alpha: 0.2),
                        ),
                      ),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          // Status dot
                          Container(
                            width: 10,
                            height: 10,
                            margin: const EdgeInsets.only(left: 4, right: 4),
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              color: statusColor,
                              boxShadow: statusColor != Colors.grey
                                  ? [
                                      BoxShadow(
                                        color: statusColor.withValues(
                                          alpha: 0.4,
                                        ),
                                        blurRadius: 4,
                                        spreadRadius: 1,
                                      ),
                                    ]
                                  : null,
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              relayUrl,
                              style: const TextStyle(
                                fontSize: 13,
                                fontFamily: 'monospace',
                                fontWeight: FontWeight.w500,
                              ),
                            ),
                          ),
                          SizedBox(
                            width: 24,
                            height: 24,
                            child: IconButton(
                              icon: Icon(
                                Icons.close,
                                size: 18,
                                color: Theme.of(context).colorScheme.error,
                              ),
                              padding: EdgeInsets.zero,
                              constraints: const BoxConstraints.tightFor(
                                width: 24,
                                height: 24,
                              ),
                              onPressed: isApplying.value
                                  ? null
                                  : () => removeRelay(relayUrl),
                              tooltip: 'Remove relay',
                            ),
                          ),
                        ],
                      ),
                    );
                  },
                ),
              ),

            const SizedBox(height: 8),

            // Add relay input
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: relayUrlController,
                    enabled: !isApplying.value,
                    decoration: InputDecoration(
                      hintText: 'wss://relay.example.com',
                      hintStyle: TextStyle(
                        fontSize: 12,
                        color: Theme.of(
                          context,
                        ).colorScheme.onSurface.withValues(alpha: 0.4),
                      ),
                      contentPadding: const EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 10,
                      ),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(8),
                      ),
                      isDense: true,
                    ),
                    style: const TextStyle(
                      fontSize: 13,
                      fontFamily: 'monospace',
                    ),
                    onSubmitted: isApplying.value
                        ? null
                        : (value) {
                            if (value.trim().isNotEmpty) {
                              addRelay(value.trim());
                            }
                          },
                  ),
                ),
                const SizedBox(width: 8),
                IconButton(
                  onPressed: isApplying.value || !hasText.value
                      ? null
                      : () {
                          final url = relayUrlController.text.trim();
                          if (url.isNotEmpty) {
                            addRelay(url);
                          }
                        },
                  icon: const Icon(Icons.add),
                  tooltip: 'Add relay',
                  style: IconButton.styleFrom(
                    backgroundColor: isApplying.value || !hasText.value
                        ? Theme.of(context).colorScheme.surfaceContainerHighest
                              .withValues(alpha: 0.3)
                        : Theme.of(context).colorScheme.primaryContainer,
                    foregroundColor: isApplying.value || !hasText.value
                        ? Theme.of(
                            context,
                          ).colorScheme.onSurface.withValues(alpha: 0.4)
                        : Theme.of(context).colorScheme.onPrimaryContainer,
                  ),
                ),
              ],
            ),

            // Apply Changes button (only shown when there are changes)
            if (hasChanges) ...[
              const SizedBox(height: 16),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: isApplying.value ? null : applyChanges,
                  icon: isApplying.value
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : const Icon(Icons.refresh),
                  label: Text(
                    isApplying.value ? 'Applying...' : 'Apply Changes',
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  /// Validates and normalizes a relay URL.
  /// Uses wss:// unless ws:// is explicitly specified.
  /// If no protocol is provided, wss:// is assumed.
  /// Normalizes default ports (443 for wss, 80 for ws) by omitting them.
  static String? _validateAndNormalizeRelayUrl(String input) {
    var url = input.trim();

    // Add wss:// if no protocol specified
    if (!url.startsWith('ws://') && !url.startsWith('wss://')) {
      url = 'wss://$url';
    }

    final uri = Uri.tryParse(url);
    if (uri == null || uri.host.isEmpty) return null;

    // Use wss:// unless ws:// is explicitly specified
    final scheme = uri.scheme == 'ws' ? 'ws' : 'wss';
    final path = uri.path.endsWith('/')
        ? uri.path.substring(0, uri.path.length - 1)
        : uri.path;

    // Normalize default ports: omit 443 for wss and 80 for ws
    final isDefaultPort =
        (scheme == 'wss' && uri.port == 443) ||
        (scheme == 'ws' && uri.port == 80);
    final normalizedPort = uri.hasPort && !isDefaultPort ? uri.port : null;

    return Uri(
      scheme: scheme,
      host: uri.host.toLowerCase(),
      port: normalizedPort,
      path: path.isEmpty ? null : path,
    ).toString();
  }

  /// Checks if a relay URL already exists in the set (case-insensitive).
  static bool _isDuplicateRelay(
    String normalizedUrl,
    Set<String> existingRelays,
  ) {
    final newLower = normalizedUrl.toLowerCase();
    for (final existing in existingRelays) {
      if (existing.toLowerCase() == newLower) {
        return true;
      }
    }
    return false;
  }

  /// Gets the best connection status color for a relay from pool state.
  /// Returns green for streaming/loading, yellow for connecting/waiting,
  /// red for failed, grey for disconnected/not found.
  static Color _getRelayStatusColor(PoolState? poolState, String relayUrl) {
    if (poolState == null) return Colors.grey;

    final subscriptions = poolState.subscriptions;
    final relayLower = relayUrl.toLowerCase();

    RelaySubPhase? bestPhase;

    for (final sub in subscriptions.values) {
      for (final entry in sub.relays.entries) {
        if (entry.key.toLowerCase() == relayLower) {
          final phase = entry.value.phase;
          // Prioritize: streaming > loading > connecting > waiting > others
          if (bestPhase == null ||
              _phasePriority(phase) > _phasePriority(bestPhase)) {
            bestPhase = phase;
          }
        }
      }
    }

    if (bestPhase == null) return Colors.grey;

    return switch (bestPhase) {
      RelaySubPhase.streaming => Colors.green,
      RelaySubPhase.loading => Colors.green,
      RelaySubPhase.connecting => Colors.amber,
      RelaySubPhase.waiting => Colors.amber,
      RelaySubPhase.failed => Colors.red,
      RelaySubPhase.disconnected => Colors.grey,
      RelaySubPhase.closed => Colors.grey,
    };
  }

  static int _phasePriority(RelaySubPhase phase) {
    return switch (phase) {
      RelaySubPhase.streaming => 6,
      RelaySubPhase.loading => 5,
      RelaySubPhase.connecting => 4,
      RelaySubPhase.waiting => 3,
      RelaySubPhase.failed => 2,
      RelaySubPhase.disconnected => 1,
      RelaySubPhase.closed => 0,
    };
  }
}

/// Dialog for confirming relay changes with privacy option.
class _ApplyRelayChangesDialog extends HookWidget {
  const _ApplyRelayChangesDialog({required this.isSignedIn});

  final bool isSignedIn;

  @override
  Widget build(BuildContext context) {
    final makePrivate = useState(false);

    return AlertDialog(
      title: Row(
        children: [
          Icon(Icons.dns, color: Theme.of(context).colorScheme.primary),
          const SizedBox(width: 8),
          Flexible(
            child: FittedBox(
              fit: BoxFit.scaleDown,
              alignment: Alignment.centerLeft,
              child: Text('Apply Relay Changes'),
            ),
          ),
        ],
      ),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Changing app catalog relays will clear cached app data and restart the app. '
            'Your sign-in and wallet connection will be preserved.',
          ),
          if (isSignedIn) ...[
            const SizedBox(height: 16),
            CheckboxListTile(
              value: makePrivate.value,
              onChanged: (v) => makePrivate.value = v ?? false,
              title: const Text('Make relay selection private'),
              subtitle: const Text(
                'Encrypted — only you can see these relays',
              ),
              dense: true,
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
            ),
          ],
          if (!isSignedIn) ...[
            const SizedBox(height: 16),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                children: [
                  Icon(
                    Icons.info_outline,
                    size: 16,
                    color: Theme.of(context).colorScheme.primary,
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      'Sign in to sync relay settings across devices.',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, null),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(
            context,
            (confirmed: true, makePrivate: makePrivate.value),
          ),
          child: const Text('Apply Changes'),
        ),
      ],
    );
  }
}
