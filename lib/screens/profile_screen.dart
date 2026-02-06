import 'dart:async';
import 'dart:convert';

import 'package:async_button_builder/async_button_builder.dart';
import 'package:auto_size_text/auto_size_text.dart';
import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/app_restart_service.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/services/bookmarks_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/common/profile_identity_row.dart';
import 'package:zapstore/widgets/app_card.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/widgets/common/note_parser.dart';
import 'package:zapstore/widgets/nwc_widgets.dart';
import 'package:zapstore/widgets/relay_management_card.dart';

/// Profile screen for authentication and app settings
class ProfileScreen extends ConsumerWidget {
  const ProfileScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      body: ListView(
        children: [
          // Authentication Section
          const _AuthenticationSection(),

          const SizedBox(height: 24),

          // Saved Apps Heading
          const _SavedAppsHeading(),

          const SizedBox(height: 16),

          // Saved Apps Section
          const _SavedAppsSection(),

          const SizedBox(height: 24),

          // Settings Heading
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16.0),
            child: Text('Settings', style: context.textTheme.headlineSmall),
          ),

          const SizedBox(height: 16),

          // Lightning Wallet Section
          const NWCConnectionCard(),

          const SizedBox(height: 16),

          // App Catalog Relay Management Section
          const RelayManagementCard(),

          const SizedBox(height: 16),

          // Data Management Section
          const _DataManagementSection(),

          const SizedBox(height: 24),

          // About Section
          _AboutSection(),

          const SizedBox(height: 24),

          // Debug Messages Section
          const _DebugMessagesSection(),
          const SizedBox(height: 24),
        ],
      ),
    );
  }
}

class _AuthenticationSection extends ConsumerWidget {
  const _AuthenticationSection();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pubkey = ref.watch(Signer.activePubkeyProvider);

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (pubkey != null)
              Consumer(
                builder: (context, ref, _) {
                  final profileState = ref.watch(
                    query<Profile>(
                      authors: {pubkey},
                      limit: 1,
                      and: (profile) => {profile.contactList.query()},
                      source: const LocalAndRemoteSource(
                        relays: {'social', 'vertex'},
                        stream: false,
                        cachedFor: Duration(hours: 2),
                      ),
                    ),
                  );
                  final profile = profileState.models.firstOrNull;
                  return _buildSignedInProfile(context, ref, pubkey, profile);
                },
              )
            else
              _buildSignInOptions(context, ref),
          ],
        ),
      ),
    );
  }

  Widget _buildSignedInProfile(
    BuildContext context,
    WidgetRef ref,
    String pubkey,
    Profile? profile,
  ) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Profile identity row (avatar, name, npub, nip05)
        ProfileIdentityRow(
          pubkey: pubkey,
          profile: profile,
          avatarRadius: 32,
          onCopiedNpub: null,
        ),
        const SizedBox(height: 12),
        // Profile bio with NoteParser (non-interactive)
        if ((profile?.about?.trim().isNotEmpty ?? false)) ...[
          NoteParser.parse(
            context,
            profile!.about!,
            textStyle: context.textTheme.bodySmall,
            onNostrEntity: (entity) => NostrEntityWidget(
              entity: entity,
              colorPair: [
                Theme.of(context).colorScheme.primary,
                Theme.of(context).colorScheme.secondary,
              ],
            ),
          ),
          const SizedBox(height: 8),
        ],
        // Link to full profile view
        InkWell(
          onTap: () => context.push('/profile/user/$pubkey'),
          child: Row(
            children: [
              Text(
                'View Full Profile',
                style: context.textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.primary,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(width: 4),
              Icon(
                Icons.arrow_forward,
                size: 14,
                color: Theme.of(context).colorScheme.primary,
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        // Sign Out button
        FilledButton.icon(
          onPressed: () => _signOut(context, ref),
          icon: const Icon(Icons.logout, size: 14),
          label: const Text(
            'Sign Out',
            style: TextStyle(fontSize: 12),
          ),
          style: FilledButton.styleFrom(
            backgroundColor: Colors.red.shade900,
            foregroundColor: Colors.white,
            padding: const EdgeInsets.symmetric(
              horizontal: 12,
              vertical: 6,
            ),
            minimumSize: const Size(0, 0),
            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
          ),
        ),
      ],
    );
  }

  Widget _buildSignInOptions(BuildContext context, WidgetRef ref) {
    final pmState = ref.watch(packageManagerProvider);
    final isAmberInstalled = pmState.installed.containsKey(kAmberPackageId);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Benefits Section
        Row(
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
                'Sign in to unlock social features',
                style: Theme.of(
                  context,
                ).textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w500),
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        SizedBox(
          width: double.infinity,
          child: _SignInButtonWithAmberCheck(
            isAmberInstalled: isAmberInstalled,
          ),
        ),
      ],
    );
  }

  Future<void> _signOut(BuildContext context, WidgetRef ref) async {
    try {
      await ref.read(amberSignerProvider).signOut();
    } catch (e) {
      if (context.mounted) {
        context.showError('Sign out failed', technicalDetails: '$e');
      }
    }
  }
}

// Sign in button with Amber installation check
class _SignInButtonWithAmberCheck extends ConsumerWidget {
  const _SignInButtonWithAmberCheck({required this.isAmberInstalled});

  final bool isAmberInstalled;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return AsyncButtonBuilder(
      onPressed: () async {
        if (!isAmberInstalled) {
          context.push('/profile/app/$kAmberNaddr');
        } else {
          await ref.read(amberSignerProvider).signIn();
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

        return FilledButton(
          onPressed: onPressed,
          style: FilledButton.styleFrom(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          ),
          child: loading
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.login, size: 16),
                    const SizedBox(width: 8),
                    Text(
                      isAmberInstalled
                          ? 'Sign in with Amber'
                          : 'Install Amber to sign in',
                      style: const TextStyle(fontSize: 13),
                    ),
                  ],
                ),
        );
      },
      child: const SizedBox.shrink(),
    );
  }
}

/// Debug Section - shows relay connections, active requests, and debug messages
class _DebugMessagesSection extends HookConsumerWidget {
  const _DebugMessagesSection();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final poolState = ref.watch(poolStateProvider);
    final selectedTab = useState(0); // 0=Subscriptions, 1=Relays
    final now = useState(DateTime.now());
    final expandedSubs = useState<Set<String>>({});

    // Used to refresh the "time ago" labels
    useEffect(() {
      final timer = Timer.periodic(const Duration(seconds: 1), (_) {
        now.value = DateTime.now();
      });
      return timer.cancel;
    }, const []);

    final subscriptions = poolState?.subscriptions ?? {};
    final closedSubscriptions = poolState?.closedSubscriptions ?? {};
    final logs = poolState?.logs ?? const [];

    // Calculate unique relay URLs from subscriptions and logs (same logic as tab content)
    final allRelayUrls = <String>{};
    for (final sub in subscriptions.values) {
      allRelayUrls.addAll(sub.relays.keys);
    }
    for (final log in logs) {
      if (log.relayUrl != null) {
        allRelayUrls.add(log.relayUrl!);
      }
    }

    void toggleSubscription(String id) {
      final next = {...expandedSubs.value};
      if (!next.remove(id)) {
        next.add(id);
      }
      expandedSubs.value = next;
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
                Icon(
                  Icons.bug_report,
                  color: Theme.of(context).colorScheme.primary,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Debug Info',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),

            // Tab selector
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _TabButton(
                  label: 'Subscriptions (${subscriptions.length})',
                  isSelected: selectedTab.value == 0,
                  onTap: () => selectedTab.value = 0,
                ),
                _TabButton(
                  label: 'Relays (${allRelayUrls.length})',
                  isSelected: selectedTab.value == 1,
                  onTap: () => selectedTab.value = 1,
                ),
              ],
            ),
            const SizedBox(height: 12),

            // Tab content
            if (selectedTab.value == 0)
              _buildSubscriptionsTab(
                context,
                subscriptions,
                closedSubscriptions,
                logs,
                now.value,
                expandedSubs.value,
                toggleSubscription,
              ),
            if (selectedTab.value == 1)
              _buildRelaysTab(
                context,
                logs,
                subscriptions,
                closedSubscriptions,
                expandedSubs.value,
                toggleSubscription,
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildSubscriptionsTab(
    BuildContext context,
    Map<String, RelaySubscription> subscriptions,
    Map<String, RelaySubscription> closedSubscriptions,
    List<LogEntry> allLogs,
    DateTime now,
    Set<String> expandedSubs,
    void Function(String id) onToggleSub,
  ) {
    if (subscriptions.isEmpty && closedSubscriptions.isEmpty) {
      return _EmptyState(message: 'No subscriptions');
    }

    final sortedSubs = subscriptions.entries.toList()
      ..sort((a, b) => a.key.compareTo(b.key));

    // Sort closed by closedAt (newest first)
    final sortedClosedSubs = closedSubscriptions.entries.toList()
      ..sort((a, b) {
        final aTime = a.value.closedAt ?? a.value.startedAt;
        final bTime = b.value.closedAt ?? b.value.startedAt;
        return bTime.compareTo(aTime);
      });

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Active subscriptions
        ...sortedSubs.map(
          (entry) => _buildSubscriptionCard(
            context,
            entry,
            allLogs,
            now,
            expandedSubs,
            onToggleSub,
            isHistorical: false,
          ),
        ),
        // Historical subscriptions header
        if (sortedClosedSubs.isNotEmpty) ...[
          const SizedBox(height: 16),
          Row(
            children: [
              Icon(
                Icons.history,
                size: 14,
                color: Theme.of(
                  context,
                ).colorScheme.onSurface.withValues(alpha: 0.5),
              ),
              const SizedBox(width: 6),
              Text(
                'History (${sortedClosedSubs.length})',
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w600,
                  color: Theme.of(
                    context,
                  ).colorScheme.onSurface.withValues(alpha: 0.5),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          // Historical subscriptions
          ...sortedClosedSubs.map(
            (entry) => _buildSubscriptionCard(
              context,
              entry,
              allLogs,
              now,
              expandedSubs,
              onToggleSub,
              isHistorical: true,
            ),
          ),
        ],
      ],
    );
  }

  Widget _buildSubscriptionCard(
    BuildContext context,
    MapEntry<String, RelaySubscription> entry,
    List<LogEntry> allLogs,
    DateTime now,
    Set<String> expandedSubs,
    void Function(String id) onToggleSub, {
    required bool isHistorical,
  }) {
    final sub = entry.value;
    final relays = sub.relays;
    final isExpanded = expandedSubs.contains(sub.id);

    final totalRelays = sub.totalRelayCount;
    final activeRelays = sub.activeRelayCount;
    final allEose = sub.allEoseReceived;

    final relayEntries = relays.entries.toList()
      ..sort((a, b) => a.key.compareTo(b.key));

    // For historical: calculate duration
    final duration = isHistorical && sub.closedAt != null
        ? sub.closedAt!.difference(sub.startedAt)
        : null;

    return InkWell(
      onTap: () => onToggleSub(sub.id),
      borderRadius: BorderRadius.circular(8),
      child: Container(
        margin: const EdgeInsets.only(bottom: 8),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest
              .withValues(alpha: isHistorical ? 0.15 : 0.3),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: Theme.of(context).colorScheme.outline.withValues(alpha: 0.2),
          ),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  isHistorical
                      ? Icons.check_circle_outline
                      : (sub.stream ? Icons.stream : Icons.download),
                  size: 16,
                  color: isHistorical
                      ? Colors.blueGrey
                      : (allEose ? Colors.green : Colors.amber.shade700),
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    entry.key,
                    style: TextStyle(
                      fontSize: 11,
                      fontFamily: 'monospace',
                      fontWeight: FontWeight.w600,
                      color: isHistorical
                          ? Theme.of(
                              context,
                            ).colorScheme.onSurface.withValues(alpha: 0.6)
                          : null,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                const SizedBox(width: 8),
                _StatusChip(
                  icon: Icons.event,
                  label: '${sub.eventCount}',
                  color: isHistorical
                      ? Colors.blueGrey
                      : Theme.of(context).colorScheme.primary,
                ),
                const SizedBox(width: 6),
                if (isHistorical && duration != null)
                  _StatusChip(
                    icon: Icons.timer,
                    label: _formatDuration(duration),
                    color: Colors.blueGrey,
                  )
                else
                  _StatusChip(
                    icon: Icons.cloud_done,
                    label: '$activeRelays/$totalRelays',
                    color: allEose ? Colors.green : Colors.amber.shade700,
                  ),
                const SizedBox(width: 6),
                Icon(
                  isExpanded ? Icons.expand_less : Icons.expand_more,
                  size: 16,
                  color: Theme.of(
                    context,
                  ).colorScheme.onSurface.withValues(alpha: 0.6),
                ),
              ],
            ),
            if (!isHistorical && relayEntries.isNotEmpty) ...[
              const SizedBox(height: 10),
              Container(
                width: double.infinity,
                decoration: BoxDecoration(
                  color: Theme.of(
                    context,
                  ).colorScheme.surface.withValues(alpha: 0.5),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(
                    color: Theme.of(
                      context,
                    ).colorScheme.outline.withValues(alpha: 0.15),
                  ),
                ),
                child: Column(
                  children: relayEntries.map((relayEntry) {
                    final relay = relayEntry.value;
                    final phase = relay.phase;
                    final phaseColor = switch (phase) {
                      RelaySubPhase.streaming => Colors.green,
                      RelaySubPhase.loading => Colors.blue,
                      RelaySubPhase.connecting => Colors.orange,
                      RelaySubPhase.waiting => Colors.amber,
                      RelaySubPhase.failed => Colors.red,
                      RelaySubPhase.disconnected => Colors.grey,
                      RelaySubPhase.closed => Colors.blueGrey,
                    };
                    final phaseIcon = switch (phase) {
                      RelaySubPhase.streaming => Icons.cloud_done,
                      RelaySubPhase.loading => Icons.cloud_sync,
                      RelaySubPhase.connecting => Icons.wifi_find,
                      RelaySubPhase.waiting => Icons.pause_circle,
                      RelaySubPhase.failed => Icons.error,
                      RelaySubPhase.disconnected => Icons.cloud_off,
                      RelaySubPhase.closed => Icons.check_circle_outline,
                    };

                    final shortUrl = relayEntry.key
                        .replaceAll('wss://', '')
                        .replaceAll('ws://', '')
                        .replaceAll(RegExp(r'/$'), '');

                    final streamingSince = relay.streamingSince;
                    final connectedFor =
                        (phase == RelaySubPhase.streaming ||
                                phase == RelaySubPhase.loading) &&
                            streamingSince != null
                        ? _formatDuration(now.difference(streamingSince))
                        : null;

                    return Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 10,
                        vertical: 8,
                      ),
                      decoration: BoxDecoration(
                        border: Border(
                          bottom: BorderSide(
                            color: Theme.of(
                              context,
                            ).colorScheme.outline.withValues(alpha: 0.1),
                          ),
                        ),
                      ),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Icon(phaseIcon, size: 16, color: phaseColor),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  shortUrl,
                                  style: const TextStyle(
                                    fontSize: 11,
                                    fontFamily: 'monospace',
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  '${phase.name}'
                                  '${relay.reconnectAttempts > 0 ? ' · retry ${relay.reconnectAttempts}' : ''}'
                                  '${connectedFor != null ? ' · connected for $connectedFor' : ''}',
                                  style: TextStyle(
                                    fontSize: 10,
                                    color: phaseColor,
                                  ),
                                ),
                                if (relay.lastError != null) ...[
                                  const SizedBox(height: 2),
                                  Text(
                                    relay.lastError!,
                                    style: const TextStyle(
                                      fontSize: 10,
                                      color: Colors.white,
                                      fontFamily: 'monospace',
                                    ),
                                  ),
                                ],
                              ],
                            ),
                          ),
                        ],
                      ),
                    );
                  }).toList(),
                ),
              ),
            ],
            if (isExpanded) ...[
              _buildRequestView(context, sub),
              _buildSubscriptionLogs(context, sub.id, allLogs),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildRelaysTab(
    BuildContext context,
    List<LogEntry> logs,
    Map<String, RelaySubscription> subscriptions,
    Map<String, RelaySubscription> closedSubscriptions,
    Set<String> expandedSubs,
    void Function(String id) onToggleSub,
  ) {
    // Collect all unique relay URLs from subscriptions and logs
    final allRelayUrls = <String>{};
    for (final sub in subscriptions.values) {
      allRelayUrls.addAll(sub.relays.keys);
    }
    for (final log in logs) {
      if (log.relayUrl != null) {
        allRelayUrls.add(log.relayUrl!);
      }
    }

    if (allRelayUrls.isEmpty) {
      return _EmptyState(message: 'No relays connected');
    }

    // Group logs by relay URL
    final logsByRelay = <String, List<LogEntry>>{};
    for (final relayUrl in allRelayUrls) {
      logsByRelay[relayUrl] = [];
    }

    for (final log in logs) {
      if (log.relayUrl != null && logsByRelay.containsKey(log.relayUrl)) {
        logsByRelay[log.relayUrl]!.add(log);
      }
    }

    // Sort relays alphabetically and logs by timestamp (newest first)
    final sortedRelays = logsByRelay.keys.toList()..sort();
    for (final url in sortedRelays) {
      logsByRelay[url]!.sort((a, b) => b.timestamp.compareTo(a.timestamp));
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: sortedRelays.map((relayUrl) {
        final relayLogs = logsByRelay[relayUrl]!;
        final shortUrl = relayUrl
            .replaceAll('wss://', '')
            .replaceAll('ws://', '')
            .replaceAll(RegExp(r'/$'), '');

        final errorCount = relayLogs
            .where((l) => l.level == LogLevel.error)
            .length;
        final warningCount = relayLogs
            .where((l) => l.level == LogLevel.warning)
            .length;

        return Container(
          margin: const EdgeInsets.only(bottom: 8),
          decoration: BoxDecoration(
            color: Theme.of(
              context,
            ).colorScheme.surfaceContainerHighest.withValues(alpha: 0.3),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
              color: Theme.of(
                context,
              ).colorScheme.outline.withValues(alpha: 0.2),
            ),
          ),
          child: Theme(
            data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
            child: ExpansionTile(
              tilePadding: const EdgeInsets.symmetric(
                horizontal: 12,
                vertical: 0,
              ),
              childrenPadding: EdgeInsets.zero,
              leading: Icon(
                Icons.dns,
                size: 18,
                color: errorCount > 0
                    ? Colors.red
                    : warningCount > 0
                    ? Colors.orange
                    : Theme.of(context).colorScheme.primary,
              ),
              title: Text(
                shortUrl,
                style: const TextStyle(
                  fontSize: 12,
                  fontFamily: 'monospace',
                  fontWeight: FontWeight.w600,
                ),
              ),
              subtitle: Row(
                children: [
                  Text(
                    '${relayLogs.length} logs',
                    style: TextStyle(
                      fontSize: 10,
                      color: Theme.of(
                        context,
                      ).colorScheme.onSurface.withValues(alpha: 0.6),
                    ),
                  ),
                  if (errorCount > 0) ...[
                    const SizedBox(width: 8),
                    _StatusChip(
                      icon: Icons.error,
                      label: '$errorCount',
                      color: Colors.red,
                    ),
                  ],
                  if (warningCount > 0) ...[
                    const SizedBox(width: 4),
                    _StatusChip(
                      icon: Icons.warning,
                      label: '$warningCount',
                      color: Colors.orange,
                    ),
                  ],
                ],
              ),
              children: [
                Container(
                  decoration: BoxDecoration(
                    border: Border(
                      top: BorderSide(
                        color: Theme.of(
                          context,
                        ).colorScheme.outline.withValues(alpha: 0.1),
                      ),
                    ),
                  ),
                  child: relayLogs.isEmpty
                      ? Padding(
                          padding: const EdgeInsets.all(16),
                          child: Text(
                            'No logs for this relay',
                            style: TextStyle(
                              fontSize: 11,
                              color: Theme.of(
                                context,
                              ).colorScheme.onSurface.withValues(alpha: 0.5),
                            ),
                          ),
                        )
                      : Column(
                          children: relayLogs.map((log) {
                            final time = _formatTime(log.timestamp);
                            final levelName = log.level.name.toUpperCase();
                            final color = switch (log.level) {
                              LogLevel.error => Colors.red,
                              LogLevel.warning => Colors.orange,
                              LogLevel.info => Theme.of(
                                context,
                              ).colorScheme.primary,
                            };

                            final subId = log.subscriptionId;
                            final sub = subId != null
                                ? (subscriptions[subId] ??
                                      closedSubscriptions[subId])
                                : null;
                            final isExpanded =
                                subId != null && expandedSubs.contains(subId);

                            return InkWell(
                              onTap: subId != null
                                  ? () => onToggleSub(subId)
                                  : null,
                              child: Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 12,
                                  vertical: 8,
                                ),
                                decoration: BoxDecoration(
                                  border: Border(
                                    bottom: BorderSide(
                                      color: Theme.of(context)
                                          .colorScheme
                                          .outline
                                          .withValues(alpha: 0.1),
                                    ),
                                  ),
                                ),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Row(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.start,
                                      children: [
                                        Container(
                                          padding: const EdgeInsets.symmetric(
                                            horizontal: 6,
                                            vertical: 2,
                                          ),
                                          decoration: BoxDecoration(
                                            color: color.withValues(
                                              alpha: 0.12,
                                            ),
                                            borderRadius: BorderRadius.circular(
                                              4,
                                            ),
                                          ),
                                          child: Text(
                                            levelName,
                                            style: TextStyle(
                                              fontSize: 9,
                                              fontWeight: FontWeight.w700,
                                              color: color,
                                            ),
                                          ),
                                        ),
                                        const SizedBox(width: 6),
                                        Expanded(
                                          child: Column(
                                            crossAxisAlignment:
                                                CrossAxisAlignment.start,
                                            children: [
                                              Text(
                                                '[$time] ${log.message}',
                                                style: const TextStyle(
                                                  fontSize: 10,
                                                  fontFamily: 'monospace',
                                                ),
                                              ),
                                              if (subId != null)
                                                Text(
                                                  'Sub: $subId',
                                                  style: TextStyle(
                                                    fontSize: 9,
                                                    color: Theme.of(context)
                                                        .colorScheme
                                                        .onSurface
                                                        .withValues(alpha: 0.6),
                                                  ),
                                                ),
                                              if (log.exception != null)
                                                Text(
                                                  log.exception!.toString(),
                                                  style: TextStyle(
                                                    fontSize: 9,
                                                    color: Colors.red
                                                        .withValues(alpha: 0.8),
                                                  ),
                                                ),
                                            ],
                                          ),
                                        ),
                                        if (subId != null)
                                          Icon(
                                            isExpanded
                                                ? Icons.expand_less
                                                : Icons.expand_more,
                                            size: 16,
                                            color: Theme.of(context)
                                                .colorScheme
                                                .onSurface
                                                .withValues(alpha: 0.5),
                                          ),
                                      ],
                                    ),
                                    // Show request when expanded
                                    if (isExpanded && sub != null) ...[
                                      const SizedBox(height: 8),
                                      _buildRequestView(context, sub),
                                    ],
                                  ],
                                ),
                              ),
                            );
                          }).toList(),
                        ),
                ),
              ],
            ),
          ),
        );
      }).toList(),
    );
  }

  String _formatTime(DateTime timestamp) {
    return '${timestamp.hour.toString().padLeft(2, '0')}:'
        '${timestamp.minute.toString().padLeft(2, '0')}:'
        '${timestamp.second.toString().padLeft(2, '0')}';
  }

  String _formatDuration(Duration duration) {
    if (duration.inSeconds < 60) {
      return '${duration.inSeconds}s';
    }
    if (duration.inMinutes < 60) {
      final minutes = duration.inMinutes;
      final seconds = duration.inSeconds % 60;
      return seconds > 0 ? '${minutes}m ${seconds}s' : '${minutes}m';
    }
    if (duration.inHours < 24) {
      final hours = duration.inHours;
      final minutes = duration.inMinutes % 60;
      return minutes > 0 ? '${hours}h ${minutes}m' : '${hours}h';
    }
    final days = duration.inDays;
    final hours = duration.inHours % 24;
    return hours > 0 ? '${days}d ${hours}h' : '${days}d';
  }

  Widget _buildRequestView(BuildContext context, RelaySubscription sub) {
    final req = _formatReq(sub);
    return Container(
      margin: const EdgeInsets.only(top: 10),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface.withValues(alpha: 0.7),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: Theme.of(context).colorScheme.outline.withValues(alpha: 0.2),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(
                'REQ',
                style: Theme.of(
                  context,
                ).textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w700),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  sub.id,
                  style: TextStyle(
                    fontSize: 11,
                    fontFamily: 'monospace',
                    color: Theme.of(
                      context,
                    ).colorScheme.onSurface.withValues(alpha: 0.7),
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              IconButton(
                icon: const Icon(Icons.copy, size: 16),
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
                onPressed: () {
                  Clipboard.setData(ClipboardData(text: req));
                },
              ),
            ],
          ),
          const SizedBox(height: 8),
          SelectableText(
            req,
            style: const TextStyle(
              fontSize: 11,
              height: 1.4,
              fontFamily: 'monospace',
            ),
          ),
        ],
      ),
    );
  }

  String _formatReq(RelaySubscription sub) {
    final payload = ['REQ', sub.id, ...sub.request.toMaps()];
    try {
      return const JsonEncoder.withIndent('  ').convert(payload);
    } catch (_) {
      return payload.toString();
    }
  }

  Widget _buildSubscriptionLogs(
    BuildContext context,
    String subscriptionId,
    List<LogEntry> allLogs,
  ) {
    final subLogs =
        allLogs.where((log) => log.subscriptionId == subscriptionId).toList()
          ..sort((a, b) => b.timestamp.compareTo(a.timestamp));

    if (subLogs.isEmpty) {
      return const SizedBox.shrink();
    }

    return Container(
      margin: const EdgeInsets.only(top: 10),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface.withValues(alpha: 0.7),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: Theme.of(context).colorScheme.outline.withValues(alpha: 0.2),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'LOGS (${subLogs.length})',
            style: Theme.of(
              context,
            ).textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 8),
          ...subLogs.map((log) {
            final time = _formatTime(log.timestamp);
            final levelName = log.level.name.toUpperCase();
            final color = switch (log.level) {
              LogLevel.error => Colors.red,
              LogLevel.warning => Colors.orange,
              LogLevel.info => Theme.of(context).colorScheme.primary,
            };

            final parts = [
              if (log.relayUrl != null)
                log.relayUrl!
                    .replaceAll('wss://', '')
                    .replaceAll('ws://', '')
                    .replaceAll(RegExp(r'/$'), ''),
              if (log.exception != null) log.exception!,
            ];

            return Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 6,
                      vertical: 2,
                    ),
                    decoration: BoxDecoration(
                      color: color.withValues(alpha: 0.12),
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: Text(
                      levelName,
                      style: TextStyle(
                        fontSize: 9,
                        fontWeight: FontWeight.w700,
                        color: color,
                      ),
                    ),
                  ),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '[$time] ${log.message}',
                          style: const TextStyle(
                            fontSize: 10,
                            fontFamily: 'monospace',
                          ),
                        ),
                        if (parts.isNotEmpty)
                          Text(
                            parts.join(' • '),
                            style: TextStyle(
                              fontSize: 9,
                              color: Theme.of(
                                context,
                              ).colorScheme.onSurface.withValues(alpha: 0.6),
                            ),
                          ),
                      ],
                    ),
                  ),
                ],
              ),
            );
          }),
        ],
      ),
    );
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip({
    required this.icon,
    required this.label,
    required this.color,
  });

  final IconData icon;
  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 12, color: color),
          const SizedBox(width: 2),
          Text(
            label,
            style: TextStyle(
              fontSize: 10,
              fontWeight: FontWeight.w600,
              color: color,
            ),
          ),
        ],
      ),
    );
  }
}

class _TabButton extends StatelessWidget {
  const _TabButton({
    required this.label,
    required this.isSelected,
    required this.onTap,
  });

  final String label;
  final bool isSelected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        decoration: BoxDecoration(
          color: isSelected
              ? Theme.of(context).colorScheme.primaryContainer
              : Theme.of(
                  context,
                ).colorScheme.surfaceContainerHighest.withValues(alpha: 0.5),
          borderRadius: BorderRadius.circular(16),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: 11,
            fontWeight: isSelected ? FontWeight.w600 : FontWeight.normal,
            color: isSelected
                ? Theme.of(context).colorScheme.onPrimaryContainer
                : Theme.of(
                    context,
                  ).colorScheme.onSurface.withValues(alpha: 0.7),
          ),
        ),
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          children: [
            Icon(
              Icons.check_circle_outline,
              size: 36,
              color: Theme.of(
                context,
              ).colorScheme.onSurface.withValues(alpha: 0.3),
            ),
            const SizedBox(height: 12),
            Text(
              message,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: Theme.of(
                  context,
                ).colorScheme.onSurface.withValues(alpha: 0.5),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _DataManagementSection extends ConsumerWidget {
  const _DataManagementSection();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Data Management',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 16),
            ListTile(
              leading: CircleAvatar(
                radius: 18,
                backgroundColor: Theme.of(
                  context,
                ).colorScheme.error.withValues(alpha: 0.12),
                child: Icon(
                  Icons.delete_sweep,
                  color: Theme.of(context).colorScheme.error,
                ),
              ),
              title: AutoSizeText(
                'Clear local storage',
                style: TextStyle(
                  color: Theme.of(context).colorScheme.error,
                  fontWeight: FontWeight.w600,
                ),
                maxLines: 1,
                minFontSize: 12,
              ),
              contentPadding: EdgeInsets.zero,
              onTap: () => _showClearAllDataDialog(context, ref),
            ),
          ],
        ),
      ),
    );
  }

  void _showClearAllDataDialog(BuildContext context, WidgetRef ref) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Row(
          children: [
            Icon(Icons.warning, color: Theme.of(context).colorScheme.error),
            const SizedBox(width: 8),
            Flexible(
              child: FittedBox(
                fit: BoxFit.scaleDown,
                alignment: Alignment.centerLeft,
                child: Text('Clear local storage'),
              ),
            ),
          ],
        ),
        content: const Text(
          'Clears all cached data and restarts the app. '
          'Your sign-in and wallet connection will be preserved.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () async {
              // Close the confirmation dialog first
              Navigator.pop(context);
              // Proceed with clearing data
              await _clearAllData(context, ref);
            },
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(context).colorScheme.error,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            ),
            child: const Text('Clear storage'),
          ),
        ],
      ),
    );
  }

  Future<void> _clearAllData(BuildContext context, WidgetRef ref) async {
    try {
      // Show loading dialog
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

      // Native restart - storage will be cleared on next launch
      await restartApp();
    } catch (e) {
      if (context.mounted) {
        Navigator.of(context, rootNavigator: true).pop();
        context.showError('Restart failed', technicalDetails: e.toString());
      }
    }
  }
}

class _SavedAppsHeading extends ConsumerWidget {
  const _SavedAppsHeading();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);

    if (signedInPubkey == null) {
      return const SizedBox.shrink();
    }

    // Always show heading when signed in
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16.0),
      child: Text('Saved Apps', style: context.textTheme.headlineSmall),
    );
  }
}

class _SavedAppsSection extends ConsumerWidget {
  const _SavedAppsSection();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);
    if (signedInPubkey == null) return const SizedBox.shrink();

    final savedAppsAsync = ref.watch(bookmarksProvider);

    // Keep previous value during refresh, if available.
    final addressableIds = savedAppsAsync.valueOrNull;

    // Show loading only on first load (when no value exists yet).
    if (addressableIds == null) {
      return _savedAppsLoadingCard(context);
    }

    final identifiers = _toIdentifiers(addressableIds);
    return _SavedAppsList(identifiers: identifiers);
  }

  Set<String> _toIdentifiers(Set<String> addressableIds) {
    return addressableIds
        .map((id) => id.split(':'))
        .where((parts) => parts.length >= 3)
        .map((parts) => parts[2])
        .toSet();
  }

  Widget _savedAppsLoadingCard(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Center(
        child: CircularProgressIndicator(
          color: Theme.of(context).colorScheme.primary,
        ),
      ),
    ),
  );
}

class _SavedAppsList extends ConsumerWidget {
  const _SavedAppsList({required this.identifiers});

  final Set<String> identifiers;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // No bookmarks saved - show empty state without querying
    if (identifiers.isEmpty) {
      return _emptyState(context);
    }

    final savedAppsState = ref.watch(
      query<App>(
        tags: {'#d': identifiers},
        and: (app) => {app.latestRelease.query()},
        source: const LocalSource(),
        subscriptionPrefix: 'profile-saved-apps',
      ),
    );

    final isLoading = savedAppsState is StorageLoading;

    final savedApps = savedAppsState.models.toList()
      ..sort(
        (a, b) => (a.name ?? a.identifier).toLowerCase().compareTo(
          (b.name ?? b.identifier).toLowerCase(),
        ),
      );

    // Show spinner only when we truly have nothing to render yet
    // If we're refreshing but still have models, keep showing the list
    if (isLoading && savedApps.isEmpty) {
      return _loadingCard(context);
    }

    if (savedApps.isEmpty) {
      return _emptyState(context);
    }

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (final app in savedApps)
              AppCard(app: app, showUpdateArrow: false, showDescription: false),
          ],
        ),
      ),
    );
  }

  Widget _loadingCard(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Center(
          child: CircularProgressIndicator(
            color: Theme.of(context).colorScheme.primary,
          ),
        ),
      ),
    );
  }

  Widget _emptyState(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Text(
        'No saved apps yet',
        style: theme.textTheme.bodyMedium?.copyWith(
          color: theme.colorScheme.onSurface.withValues(alpha: 0.5),
        ),
      ),
    );
  }
}

class _AboutSection extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pmState = ref.watch(packageManagerProvider);
    final zsPackage = pmState.installed[kZapstoreAppIdentifier];

    if (zsPackage == null) {
      final isLoading = pmState.installed.isEmpty;
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('About', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 16),
              if (isLoading) ...[
                Row(
                  children: [
                    SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        'Loading Zapstore build information…',
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                    ),
                  ],
                ),
              ] else ...[
                Text(
                  'Zapstore version details are unavailable right now.',
                  style: Theme.of(
                    context,
                  ).textTheme.bodyMedium?.copyWith(color: Colors.orange[700]),
                ),
                const SizedBox(height: 8),
                TextButton.icon(
                  onPressed: () {
                    unawaited(
                      ref
                          .read(packageManagerProvider.notifier)
                          .syncInstalledPackages(),
                    );
                  },
                  icon: const Icon(Icons.refresh),
                  label: const Text('Retry'),
                ),
              ],
            ],
          ),
        ),
      );
    }

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('About', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 16),
            ListTile(
              leading: ClipOval(
                child: Image.asset(
                  'assets/images/logo.png',
                  width: 28,
                  height: 28,
                  fit: BoxFit.cover,
                  color: Colors.grey,
                  colorBlendMode: BlendMode.saturation,
                ),
              ),
              title: Text('Version'),
              subtitle: Text('${zsPackage.version}+${zsPackage.versionCode}'),
              contentPadding: EdgeInsets.zero,
            ),
            ListTile(
              leading: const Icon(Icons.code),
              title: const Text('Source Code'),
              subtitle: const Text('View on GitHub'),
              trailing: const Icon(Icons.open_in_new),
              contentPadding: EdgeInsets.zero,
              onTap: () {
                launchUrl(Uri.parse('https://github.com/zapstore/zapstore'));
              },
            ),
          ],
        ),
      ),
    );
  }
}
