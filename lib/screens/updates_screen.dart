import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:flutter_slidable/flutter_slidable.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/ignored_apps_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/updates_service.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/batch_progress_banner.dart';
import 'package:zapstore/widgets/common/badges.dart';
import 'package:zapstore/widgets/app_card.dart';

class UpdatesScreen extends ConsumerWidget {
  const UpdatesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final categorized = ref.watch(categorizedUpdatesProvider);

    if (categorized.showSkeleton) {
      return Scaffold(
        body: Padding(
          padding: const EdgeInsets.only(top: 16),
          child: _LoadingSkeleton(),
        ),
      );
    }

    return Scaffold(
      body: Padding(
        padding: const EdgeInsets.only(top: 16),
        child: _UpdatesList(categorized: categorized),
      ),
    );
  }
}

class _LoadingSkeleton extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return ListView(
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
              const SizedBox(width: 10),
              Text(
                'Checking for updates...',
                style: context.textTheme.bodySmall?.copyWith(
                  color: Theme.of(
                    context,
                  ).colorScheme.onSurface.withValues(alpha: 0.6),
                ),
              ),
            ],
          ),
        ),
        const AppCard(isLoading: true),
        const AppCard(isLoading: true),
        const AppCard(isLoading: true),
      ],
    );
  }
}

class _LastCheckedIndicator extends HookConsumerWidget {
  const _LastCheckedIndicator();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pollerState = ref.watch(updatePollerProvider);
    final lastCheckTime = pollerState.lastCheckTime;
    final isChecking = pollerState.isChecking;
    final lastError = pollerState.lastError;

    // Force rebuild every minute to keep relative time fresh
    final ticker = useState(0);
    useEffect(() {
      final timer = Timer.periodic(const Duration(minutes: 1), (_) {
        ticker.value++;
      });
      return timer.cancel;
    }, const []);

    final showSpinner = isChecking || lastCheckTime == null;

    final statusText = showSpinner
        ? 'Checking for updates...'
        : 'Last checked: ${_formatRelativeTime(lastCheckTime)}';

    final mutedColor =
        Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.5);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              if (showSpinner)
                const SizedBox(
                  width: 12,
                  height: 12,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              else
                Icon(Icons.schedule, size: 14, color: mutedColor),
              const SizedBox(width: 8),
              Text(
                statusText,
                style: context.textTheme.bodySmall?.copyWith(
                  color: Theme.of(
                    context,
                  ).colorScheme.onSurface.withValues(alpha: 0.6),
                ),
              ),
            ],
          ),
          if (lastError != null && !isChecking)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    Icons.warning_amber_rounded,
                    size: 13,
                    color: Colors.amber.shade700,
                  ),
                  const SizedBox(width: 6),
                  Text(
                    lastError,
                    style: context.textTheme.bodySmall?.copyWith(
                      color: Colors.amber.shade700,
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  String _formatRelativeTime(DateTime time) {
    final diff = DateTime.now().difference(time);
    if (diff.inMinutes < 1) return 'just now';
    if (diff.inMinutes == 1) return '1 minute ago';
    if (diff.inMinutes < 60) return '${diff.inMinutes} minutes ago';
    if (diff.inHours == 1) return '1 hour ago';
    return '${diff.inHours} hours ago';
  }
}

class _UpdatesList extends ConsumerWidget {
  const _UpdatesList({required this.categorized});

  final CategorizedUpdates categorized;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final automaticUpdates = categorized.automaticUpdates;
    final manualUpdates = categorized.manualUpdates;
    final upToDateApps = categorized.upToDateApps;
    final uncatalogedApps = categorized.uncatalogedApps;
    final unmanagedApps = categorized.unmanagedApps;

    // Resolve installing apps (active operations not already in update lists)
    final operations = ref.watch(
      packageManagerProvider.select((s) => s.operations),
    );
    final activeAppIds = operations.entries
        .where((entry) => entry.value.isActive)
        .map((entry) => entry.key)
        .toSet();

    final updateAppIds = {
      ...automaticUpdates.map((a) => a.identifier),
      ...manualUpdates.map((a) => a.identifier),
    };

    final List<App> installingApps;
    if (activeAppIds.isEmpty) {
      installingApps = const [];
    } else {
      final installingAppsState = ref.watch(
        query<App>(
          tags: {'#d': activeAppIds},
          and: (app) => {app.latestRelease.query()},
          source: const LocalAndRemoteSource(relays: 'AppCatalog'),
          subscriptionPrefix: 'app-installing-apps',
        ),
      );
      installingApps = installingAppsState.models
          .where(
            (app) =>
                activeAppIds.contains(app.identifier) &&
                !updateAppIds.contains(app.identifier),
          )
          .toList();
    }

    // Empty state: only show if there are truly no apps at all (unmanaged ones don't count)
    if (automaticUpdates.isEmpty &&
        manualUpdates.isEmpty &&
        installingApps.isEmpty &&
        upToDateApps.isEmpty &&
        uncatalogedApps.isEmpty &&
        unmanagedApps.isEmpty) {
      final theme = Theme.of(context);
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          mainAxisSize: MainAxisSize.min,
          children: [
            ColorFiltered(
              colorFilter: const ColorFilter.matrix(<double>[
                0.2126, 0.7152, 0.0722, 0, 0,
                0.2126, 0.7152, 0.0722, 0, 0,
                0.2126, 0.7152, 0.0722, 0, 0,
                0, 0, 0, 1, 0,
              ]),
              child: const Text('\u{1F389}', style: TextStyle(fontSize: 48)),
            ),
            const SizedBox(height: 12),
            Text(
              'No apps installed yet',
              style: theme.textTheme.titleMedium,
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 6),
            Text(
              'Install some apps to get started!',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurface.withValues(alpha: 0.6),
              ),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      );
    }

    final allUpdates = [...automaticUpdates, ...manualUpdates];

    return RefreshIndicator(
      color: Colors.transparent,
      backgroundColor: Colors.transparent,
      elevation: 0,
      strokeWidth: 0,
      onRefresh: () => ref.read(updatePollerProvider.notifier).checkNow(),
      child: CustomScrollView(
        slivers: [
          if (allUpdates.length > 1)
            SliverToBoxAdapter(child: UpdateAllRow(allUpdates: allUpdates)),
          const SliverToBoxAdapter(child: _LastCheckedIndicator()),
          if (installingApps.isNotEmpty)
            _AppSection(
              icon: Icons.downloading,
              title: 'Installing',
              apps: installingApps,
              keyPrefix: 'installing',
              showUpdateButton: true,
              showZapEncouragement: true,
            ),
          if (automaticUpdates.isNotEmpty)
            _AppSection(
              icon: Icons.system_update,
              title: 'Updates',
              apps: automaticUpdates,
              keyPrefix: 'automatic',
              showUpdateArrow: true,
              showUpdateButton: true,
              showZapEncouragement: true,
            ),
          if (manualUpdates.isNotEmpty)
            _AppSection(
              icon: Icons.touch_app,
              title: 'Manual Updates',
              apps: manualUpdates,
              keyPrefix: 'manual',
              showUpdateArrow: true,
              showUpdateButton: true,
              showZapEncouragement: true,
              headerTrailing: _ManualUpdatesHelpIcon(),
            ),
          if (upToDateApps.isNotEmpty)
            _AppSection(
              icon: Icons.check_circle,
              title: 'Up to date',
              apps: upToDateApps,
              keyPrefix: 'uptodate',
            ),
          if (uncatalogedApps.isNotEmpty) ...[
            SliverToBoxAdapter(
              child: _SectionHeader(
                icon: Icons.help_outline,
                title: 'Other installed',
                count: uncatalogedApps.length,
                iconColor: AppColors.darkOnSurfaceSecondary,
                hint: 'Swipe left to stop managing these apps',
              ),
            ),
            SliverList(
              delegate: SliverChildBuilderDelegate(
                (context, index) => _SlidablePackageCard(
                  key: ValueKey('uncataloged_${uncatalogedApps[index].appId}'),
                  packageInfo: uncatalogedApps[index],
                  actionLabel: 'Unmanage',
                  actionIcon: Icons.do_not_disturb_on_outlined,
                  actionColor: Colors.orange.shade800,
                  onAction: (ref) => toggleUnmanagedApp(
                    ref,
                    uncatalogedApps[index].appId,
                    unmanage: true,
                  ),
                ),
                childCount: uncatalogedApps.length,
              ),
            ),
          ],
          if (unmanagedApps.isNotEmpty) ...[
            SliverToBoxAdapter(
              child: _SectionHeader(
                icon: Icons.visibility_off,
                title: 'Unmanaged Apps',
                count: unmanagedApps.length,
                iconColor: AppColors.darkOnSurfaceSecondary,
                hint: 'Swipe left to manage again',
              ),
            ),
            SliverList(
              delegate: SliverChildBuilderDelegate(
                (context, index) => _SlidablePackageCard(
                  key: ValueKey('unmanaged_${unmanagedApps[index].appId}'),
                  packageInfo: unmanagedApps[index],
                  actionLabel: 'Manage',
                  actionIcon: Icons.visibility_outlined,
                  actionColor: AppColors.darkActionPrimary,
                  onAction: (ref) => toggleUnmanagedApp(
                    ref,
                    unmanagedApps[index].appId,
                    unmanage: false,
                  ),
                ),
                childCount: unmanagedApps.length,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// A section: header + list of AppCards with swipe-to-ignore, rendered as a single sliver.
class _AppSection extends ConsumerWidget {
  const _AppSection({
    required this.icon,
    required this.title,
    required this.apps,
    required this.keyPrefix,
    this.showUpdateArrow = false,
    this.showUpdateButton = false,
    this.showZapEncouragement = false,
    this.headerTrailing,
  });

  final IconData icon;
  final String title;
  final List<App> apps;
  final String keyPrefix;
  final bool showUpdateArrow;
  final bool showUpdateButton;
  final bool showZapEncouragement;
  final Widget? headerTrailing;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final hasDeviceKey = ref.watch(devicePubkeyProvider) != null;

    return SliverList(
      delegate: SliverChildBuilderDelegate(
        (context, index) {
          if (index == 0) {
            return _SectionHeader(
              icon: icon,
              title: title,
              count: apps.length,
              trailing: headerTrailing,
            );
          }
          final app = apps[index - 1];
          final card = AppCard(
            key: ValueKey('${keyPrefix}_${app.identifier}'),
            app: app,
            showUpdateArrow: showUpdateArrow,
            showUpdateButton: showUpdateButton,
            showZapEncouragement: showZapEncouragement,
            showDescription: false,
          );

          if (!hasDeviceKey) return card;

          return Slidable(
            key: ValueKey('slidable_${keyPrefix}_${app.identifier}'),
            endActionPane: ActionPane(
              motion: const BehindMotion(),
              extentRatio: 0.22,
              children: [
                SlidableAction(
                  onPressed: (_) => toggleUnmanagedApp(
                    ref,
                    app.identifier,
                    unmanage: true,
                  ),
                  backgroundColor: Colors.orange.shade800,
                  foregroundColor: Colors.white,
                  icon: Icons.do_not_disturb_on_outlined,
                  label: 'Unmanage',
                  borderRadius: BorderRadius.circular(16),
                ),
              ],
            ),
            child: card,
          );
        },
        childCount: apps.length + 1,
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({
    required this.icon,
    required this.title,
    required this.count,
    this.iconColor,
    this.trailing,
    this.hint,
  });

  final IconData icon;
  final String title;
  final int count;
  final Color? iconColor;
  final Widget? trailing;
  final String? hint;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 16, 16, hint != null ? 4 : 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Icon(
                icon,
                size: 20,
                color: iconColor ?? AppColors.darkActionPrimary,
              ),
              const SizedBox(width: 8),
              Text(title, style: context.textTheme.titleMedium),
              const SizedBox(width: 8),
              CountBadge(count: count, color: AppColors.darkPillBackground),
              if (trailing != null) ...[
                const SizedBox(width: 4),
                trailing!,
              ],
            ],
          ),
          if (hint != null) ...[
            const SizedBox(height: 4),
            Text(
              hint!,
              style: context.textTheme.bodySmall?.copyWith(
                color: Theme.of(
                  context,
                ).colorScheme.onSurface.withValues(alpha: 0.45),
                fontStyle: FontStyle.italic,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _ManualUpdatesHelpIcon extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () => showDialog(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Manual Updates'),
          content: const Text(
            'Apps not installed or updated by the latest Zapstore will show here '
            'and require manual confirmation of the Android system prompt once per app.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Got it'),
            ),
          ],
        ),
      ),
      child: Icon(
        Icons.help_outline,
        size: 18,
        color: Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.5),
      ),
    );
  }
}

/// A package card (uncataloged or unmanaged) with a swipe-left action pane.
///
/// When the device key is not ready, no swipe action is shown.
class _SlidablePackageCard extends ConsumerWidget {
  const _SlidablePackageCard({
    super.key,
    required this.packageInfo,
    required this.actionLabel,
    required this.actionIcon,
    required this.actionColor,
    required this.onAction,
  });

  final PackageInfo packageInfo;
  final String actionLabel;
  final IconData actionIcon;
  final Color actionColor;
  final Future<void> Function(WidgetRef ref) onAction;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final hasDeviceKey = ref.watch(devicePubkeyProvider) != null;
    final card = _PackageCard(packageInfo: packageInfo);

    if (!hasDeviceKey) return card;

    return Slidable(
      key: ValueKey('slidable_pkg_${packageInfo.appId}'),
      endActionPane: ActionPane(
        motion: const BehindMotion(),
        extentRatio: 0.22,
        children: [
          SlidableAction(
            onPressed: (_) => onAction(ref),
            backgroundColor: actionColor,
            foregroundColor: Colors.white,
            icon: actionIcon,
            label: actionLabel,
            borderRadius: BorderRadius.circular(16),
          ),
        ],
      ),
      child: card,
    );
  }
}

class _PackageCard extends StatelessWidget {
  const _PackageCard({required this.packageInfo});

  final PackageInfo packageInfo;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        color: Theme.of(context).colorScheme.surface.withValues(alpha: 0.6),
        border: Border.all(
          color: Theme.of(context).colorScheme.outline.withValues(alpha: 0.2),
        ),
      ),
      child: Row(
        children: [
          Container(
            width: 50,
            height: 50,
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(14),
            ),
            child: Icon(
              Icons.android,
              color: AppColors.darkOnSurfaceSecondary,
              size: 28,
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  packageInfo.name ?? packageInfo.appId,
                  style: context.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w900,
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 4),
                Text(
                  packageInfo.appId,
                  style: context.textTheme.bodySmall?.copyWith(
                    color: AppColors.darkOnSurfaceSecondary,
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 8),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 9,
                    vertical: 5,
                  ),
                  decoration: BoxDecoration(
                    color: AppColors.darkPillBackground,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    packageInfo.version,
                    style: context.textTheme.labelSmall?.copyWith(
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
