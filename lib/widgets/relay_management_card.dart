import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/services/app_restart_service.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/services/secure_storage_service.dart';

/// App Catalog Relay Management Card - manages app catalog relays.
/// These are relays for discovering apps, NOT social relays like Damus/Primal.
///
/// Relay configuration is stored locally in secure storage.
/// Changes are accumulated in memory and applied with "Apply Changes" which
/// saves the relay list and restarts the app with a fresh database.
class RelayManagementCard extends HookConsumerWidget {
  const RelayManagementCard({super.key});

  static const _kDefaultRelay = 'wss://relay.zapstore.dev';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
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

    // Use local relays if set, otherwise default
    final effectiveSavedRelays = (localRelays != null && localRelays.isNotEmpty)
        ? localRelays
        : [_kDefaultRelay];

    // Local pending state - initialized from effective saved relays
    final pendingRelays = useState<List<String>?>(null);

    // Determine if data is still loading
    final isLoading =
        localRelaysSnapshot.connectionState == ConnectionState.waiting;

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
      // Show confirmation dialog
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
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
          content: const Text(
            'Changing app catalog relays will clear cached app data and restart the app. '
            'Your sign-in and wallet connection will be preserved.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Apply Changes'),
            ),
          ],
        ),
      );

      if (confirmed != true || !context.mounted) return;

      isApplying.value = true;

      try {
        final secureStorage = ref.read(secureStorageServiceProvider);
        final relaysToSave = displayRelays.toSet();

        // Save to local secure storage
        await secureStorage.setAppCatalogRelays(relaysToSave);

        // Verify write succeeded by reading back
        final verified = await secureStorage.getAppCatalogRelays();
        if (verified == null || !verified.containsAll(relaysToSave)) {
          throw StateError('Failed to persist relay configuration');
        }

        // Delay to ensure the platform-side write is fully committed
        // before the native restart kills the process
        await Future<void>.delayed(const Duration(milliseconds: 800));

        // Restart app with database clear
        await restartApp();
      } catch (e) {
        isApplying.value = false;
        if (context.mounted) {
          context.showError(
            'Failed to apply relay changes',
            technicalDetails: '$e',
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
                      'These relays are used to discover apps, not social content. Modify this list at your own risk.',
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
                      hintText: 'relay.example.com',
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
