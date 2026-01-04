import 'dart:async';
import 'dart:convert';

import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:zapstore/services/app_restart_service.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/services/bookmarks_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/common/profile_avatar.dart';
import 'package:zapstore/widgets/app_card.dart';
import 'package:zapstore/widgets/auth_widgets.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/widgets/common/note_parser.dart';
import 'package:zapstore/widgets/nwc_widgets.dart';

// Note: Relay debugging features have been removed as they depend on internal APIs
// that are no longer public in purplebase 0.3.3+

/// Profile screen for authentication and app settings
class ProfileScreen extends ConsumerWidget {
  const ProfileScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      body: ListView(
        children: [
          // Authentication Section
          _AuthenticationSection(),

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
        padding: const EdgeInsets.all(16),
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
                      and: (profile) => {profile.contactList},
                      source: const LocalAndRemoteSource(
                        relays: {'social', 'vertex'},
                        stream: false,
                        cachedFor: Duration(hours: 2),
                      ),
                    ),
                  );

                  final profile = profileState.models.firstOrNull;
                  final isLoading = profileState is StorageLoading && profile == null;

                  return Skeletonizer(
                    enabled: isLoading,
                    child: _buildSignedInProfile(context, ref, pubkey, profile),
                  );
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
        Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            ProfileAvatar(
              profile: profile,
              radius: 36,
              borderColors: [
                Theme.of(context).colorScheme.primary,
                Theme.of(context).colorScheme.secondary,
              ],
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    profile?.nameOrNpub ?? 'Nostr User',
                    style: context.textTheme.titleLarge?.copyWith(
                      fontWeight: FontWeight.w900,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 4),
                  if (profile?.nip05 != null)
                    Text(
                      profile!.nip05!,
                      style: context.textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.primary,
                        fontWeight: FontWeight.w600,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    )
                  else
                    Text(
                      pubkey.abbreviateNpub(),
                      style: context.textTheme.bodySmall,
                    ),
                ],
              ),
            ),
            IconButton(
              onPressed: () => _signOut(context, ref),
              icon: const Icon(Icons.logout_rounded, size: 20),
              color: Theme.of(context).colorScheme.error,
              tooltip: 'Sign Out',
            ),
          ],
        ),
        if (profile?.about?.trim().isNotEmpty ?? false) ...[
          const SizedBox(height: 16),
          NoteParser.parse(
            context,
            profile!.about!,
            textStyle: context.textTheme.bodyMedium,
            onNostrEntity: (entity) => NostrEntityWidget(
              entity: entity,
              colorPair: [
                Theme.of(context).colorScheme.primary,
                Theme.of(context).colorScheme.secondary,
              ],
            ),
          ),
        ],
        const SizedBox(height: 16),
        InkWell(
          onTap: () => context.push('/profile/user/$pubkey'),
          borderRadius: BorderRadius.circular(8),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  'View Full Profile',
                  style: context.textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.primary,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 0.5,
                  ),
                ),
                const SizedBox(width: 4),
                Icon(
                  Icons.arrow_forward_rounded,
                  size: 14,
                  color: Theme.of(context).colorScheme.primary,
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildSignInOptions(BuildContext context, WidgetRef ref) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.1),
                shape: BoxShape.circle,
              ),
              child: Icon(
                Icons.account_circle_outlined,
                size: 24,
                color: Theme.of(context).colorScheme.primary,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Sign In',
                    style: context.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                  Text(
                    'Unlock social features and sync your data',
                    style: context.textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 20),
        UnifiedSignInButton(),
      ],
    );
  }

  Future<void> _signOut(BuildContext context, WidgetRef ref) async {
    try {
      await ref.read(amberSignerProvider).signOut();
    } catch (e) {
      if (context.mounted) {
        context.showError('Sign out failed', description: '$e');
      }
    }
  }
}

/// Debug Section - shows relay connections, active requests, and debug messages
class _DebugMessagesSection extends HookConsumerWidget {
  const _DebugMessagesSection();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final poolState = ref.watch(poolStateProvider);
    final selectedTab = useState(0); // 0=Subscriptions, 1=Logs
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
    final logs = poolState?.logs ?? const [];

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
            Row(
              children: [
                _TabButton(
                  label: 'Subscriptions (${subscriptions.length})',
                  isSelected: selectedTab.value == 0,
                  onTap: () => selectedTab.value = 0,
                ),
                const SizedBox(width: 8),
                _TabButton(
                  label: 'Log (${logs.length})',
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
                now.value,
                expandedSubs.value,
                toggleSubscription,
              ),
            if (selectedTab.value == 1) _buildLogsTab(context, logs),
          ],
        ),
      ),
    );
  }

  Widget _buildSubscriptionsTab(
    BuildContext context,
    Map<String, RelaySubscription> subscriptions,
    DateTime now,
    Set<String> expandedSubs,
    void Function(String id) onToggleSub,
  ) {
    if (subscriptions.isEmpty) {
      return _EmptyState(message: 'No active subscriptions');
    }

    final sortedSubs = subscriptions.entries.toList()
      ..sort((a, b) => a.key.compareTo(b.key));

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: sortedSubs.map((entry) {
        final sub = entry.value;
        final relays = sub.relays;
        final isExpanded = expandedSubs.contains(sub.id);

        final totalRelays = sub.totalRelayCount;
        final activeRelays = sub.activeRelayCount;
        final allEose = sub.allEoseReceived;

        final relayEntries = relays.entries.toList()
          ..sort((a, b) => a.key.compareTo(b.key));

        return InkWell(
          onTap: () => onToggleSub(sub.id),
          borderRadius: BorderRadius.circular(8),
          child: Container(
            margin: const EdgeInsets.only(bottom: 8),
            padding: const EdgeInsets.all(12),
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
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(
                      sub.stream ? Icons.stream : Icons.download,
                      size: 16,
                      color: allEose ? Colors.green : Colors.amber.shade700,
                    ),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text(
                        entry.key,
                        style: const TextStyle(
                          fontSize: 11,
                          fontFamily: 'monospace',
                          fontWeight: FontWeight.w600,
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    const SizedBox(width: 8),
                    _StatusChip(
                      icon: Icons.event,
                      label: '${sub.eventCount}',
                      color: Theme.of(context).colorScheme.primary,
                    ),
                    const SizedBox(width: 6),
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
                if (relayEntries.isNotEmpty) ...[
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
                        };
                        final phaseIcon = switch (phase) {
                          RelaySubPhase.streaming => Icons.cloud_done,
                          RelaySubPhase.loading => Icons.cloud_sync,
                          RelaySubPhase.connecting => Icons.wifi_find,
                          RelaySubPhase.waiting => Icons.pause_circle,
                          RelaySubPhase.failed => Icons.error,
                          RelaySubPhase.disconnected => Icons.cloud_off,
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
                if (isExpanded) _buildRequestView(context, sub),
              ],
            ),
          ),
        );
      }).toList(),
    );
  }

  Widget _buildLogsTab(BuildContext context, List<LogEntry> logs) {
    if (logs.isEmpty) {
      return _EmptyState(message: 'No logs yet');
    }

    final reversedLogs = logs.reversed.toList();

    return Container(
      decoration: BoxDecoration(
        color: Theme.of(
          context,
        ).colorScheme.surfaceContainerHighest.withValues(alpha: 0.3),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: Theme.of(context).colorScheme.outline.withValues(alpha: 0.2),
        ),
      ),
      child: Column(
        children: reversedLogs.map((log) {
          final time = _formatTime(log.timestamp);
          final levelName = log.level.name.toUpperCase();
          final color = switch (log.level) {
            LogLevel.error => Colors.red,
            LogLevel.warning => Colors.orange,
            LogLevel.info => Theme.of(context).colorScheme.primary,
          };

          final parts = [
            if (log.subscriptionId != null) 'Sub: ${log.subscriptionId}',
            if (log.relayUrl != null) 'Relay: ${log.relayUrl}',
            if (log.exception != null) 'Error: ${log.exception}',
          ];

          return Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
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
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 8,
                    vertical: 4,
                  ),
                  decoration: BoxDecoration(
                    color: color.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text(
                    levelName,
                    style: TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w700,
                      color: color,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '[$time] ${log.message}',
                        style: const TextStyle(
                          fontSize: 11,
                          fontFamily: 'monospace',
                        ),
                      ),
                      if (parts.isNotEmpty)
                        Padding(
                          padding: const EdgeInsets.only(top: 2),
                          child: Text(
                            parts.join(' • '),
                            style: TextStyle(
                              fontSize: 10,
                              color: Theme.of(
                                context,
                              ).colorScheme.onSurface.withValues(alpha: 0.65),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.copy, size: 14),
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(),
                  onPressed: () {
                    final text = [
                      levelName,
                      time,
                      log.message,
                      if (log.subscriptionId != null)
                        'Sub: ${log.subscriptionId}',
                      if (log.relayUrl != null) 'Relay: ${log.relayUrl}',
                      if (log.exception != null) 'Error: ${log.exception}',
                    ].where((e) => e.isNotEmpty).join(' | ');
                    Clipboard.setData(ClipboardData(text: text));
                    context.showInfo('Debug info copied');
                  },
                ),
              ],
            ),
          );
        }).toList(),
      ),
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
                  context.showInfo(
                    'REQ filter copied',
                    description:
                        'Paste into a Nostr client to debug this query.',
                  );
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
              title: Text(
                'Clear local storage',
                style: TextStyle(
                  color: Theme.of(context).colorScheme.error,
                  fontWeight: FontWeight.w600,
                ),
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
            const Text('Clear local storage'),
          ],
        ),
        content: const Text(
          'Clears all cached data (except NWC secret) and restarts the app. '
          'You will be signed out.',
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
        context.showError('Restart failed', description: e.toString());
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

    final savedAppsAsync = ref.watch(bookmarksProvider);

    return savedAppsAsync.when(
      loading: () => const SizedBox.shrink(),
      error: (_, __) => const SizedBox.shrink(),
      data: (addressableIds) {
        if (addressableIds.isEmpty) {
          return const SizedBox.shrink();
        }

        final identifiers = addressableIds
            .map((id) {
              final parts = id.split(':');
              return parts.length >= 3 ? parts[2] : null;
            })
            .whereType<String>()
            .toSet();

        if (identifiers.isEmpty) {
          return const SizedBox.shrink();
        }

        return Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16.0),
          child: Text('Saved Apps', style: context.textTheme.headlineSmall),
        );
      },
    );
  }
}

class _SavedAppsSection extends HookConsumerWidget {
  const _SavedAppsSection();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);
    final hasLoadedOnce = useState(false);

    if (signedInPubkey == null) {
      return const SizedBox.shrink();
    }

    final savedAppsAsync = ref.watch(bookmarksProvider);

    return savedAppsAsync.when(
      loading: () => _savedAppsLoadingCard(context),
      error: (_, __) => const SizedBox.shrink(),
      data: (addressableIds) {
        if (addressableIds.isEmpty) {
          return const SizedBox.shrink();
        }

        final identifiers = addressableIds
            .map((id) {
              final parts = id.split(':');
              return parts.length >= 3 ? parts[2] : null;
            })
            .whereType<String>()
            .toSet();

        if (identifiers.isEmpty) {
          return const SizedBox.shrink();
        }

        return _SavedAppsList(
          identifiers: identifiers,
          hasLoadedOnce: hasLoadedOnce,
        );
      },
    );
  }

  Widget _savedAppsLoadingCard(BuildContext context) {
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
}

class _SavedAppsList extends ConsumerWidget {
  const _SavedAppsList({
    required this.identifiers,
    required this.hasLoadedOnce,
  });

  final Set<String> identifiers;
  final ValueNotifier<bool> hasLoadedOnce;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final savedAppsState = ref.watch(
      query<App>(
        tags: {'#d': identifiers},
        and: (app) => {app.latestRelease},
        source: const LocalSource(),
        subscriptionPrefix: 'profile-saved-apps',
      ),
    );

    final savedApps = savedAppsState.models.toList()
      ..sort(
        (a, b) => (a.name ?? a.identifier).toLowerCase().compareTo(
          (b.name ?? b.identifier).toLowerCase(),
        ),
      );

    // Check if we have loaded at least once - defer state update to after build
    if (savedApps.isNotEmpty && !hasLoadedOnce.value) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        hasLoadedOnce.value = true;
      });
    }

    // Show loading state only if we haven't loaded any apps yet
    if (savedApps.isEmpty && !hasLoadedOnce.value) {
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

    // Hide if no apps after loading
    if (savedApps.isEmpty) {
      return const SizedBox.shrink();
    }

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: savedApps
              .map(
                (app) => AppCard(
                  app: app,
                  showUpdateArrow: false,
                  showDescription: false,
                ),
              )
              .toList(),
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
