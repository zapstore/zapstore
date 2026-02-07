import 'dart:async';

import 'package:auto_size_text/auto_size_text.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/updates_service.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/batch_progress_banner.dart';
import 'package:zapstore/widgets/common/badges.dart';
import 'package:zapstore/widgets/app_card.dart';

/// Screen for managing app updates
class UpdatesScreen extends ConsumerWidget {
  const UpdatesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final categorized = ref.watch(categorizedUpdatesProvider);

    // Show skeleton only on cold start (no installed apps matched yet)
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
        child: _UpdatesContent(categorized: categorized),
      ),
    );
  }
}

/// Loading skeleton shown while fetching updates on cold start
class _LoadingSkeleton extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return ListView(
      children: [
        // "Checking for updates..." indicator at top
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
        // Skeleton app cards using existing AppCard skeleton
        const AppCard(isLoading: true),
        const AppCard(isLoading: true),
        const AppCard(isLoading: true),
      ],
    );
  }
}

/// Shows when updates were last checked
class _LastCheckedIndicator extends HookConsumerWidget {
  const _LastCheckedIndicator();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pollerState = ref.watch(updatePollerProvider);
    final lastCheckTime = pollerState.lastCheckTime;
    final isChecking = pollerState.isChecking;

    // Force rebuild every minute to keep relative time fresh
    final ticker = useState(0);
    useEffect(() {
      final timer = Timer.periodic(const Duration(minutes: 1), (_) {
        ticker.value++;
      });
      return timer.cancel;
    }, const []);

    // Show spinner if actively checking OR if first check hasn't completed yet
    final showSpinner = isChecking || lastCheckTime == null;

    final statusText = showSpinner
        ? 'Checking for updates...'
        : 'Last checked: ${_formatRelativeTime(lastCheckTime)}';

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          if (showSpinner)
            const SizedBox(
              width: 12,
              height: 12,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          else
            Icon(
              Icons.schedule,
              size: 14,
              color: Theme.of(
                context,
              ).colorScheme.onSurface.withValues(alpha: 0.5),
            ),
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

/// Item types for the updates list
enum _UpdatesItemType {
  lastChecked,
  installingHeader,
  installingApp,
  automaticHeader,
  automaticApp,
  manualHeader,
  manualApp,
  upToDateHeader,
  upToDateApp,
  uncatalogedHeader,
  uncatalogedApp,
}

class _UpdatesItem {
  final _UpdatesItemType type;
  final App? app;
  final PackageInfo? packageInfo;

  const _UpdatesItem(this.type, {this.app, this.packageInfo});
}

class _UpdatesContent extends HookConsumerWidget {
  const _UpdatesContent({required this.categorized});

  final CategorizedUpdates categorized;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final automaticUpdates = categorized.automaticUpdates;
    final manualUpdates = categorized.manualUpdates;
    final upToDateApps = categorized.upToDateApps;
    final uncatalogedApps = categorized.uncatalogedApps;

    // Watch operations from PackageManager
    final operations = ref.watch(
      packageManagerProvider.select((s) => s.operations),
    );
    final activeAppIds = operations.entries
        .where((entry) => entry.value.isActive)
        .map((entry) => entry.key)
        .toSet();

    // Always use the same widget type to preserve scroll position
    return _UpdatesListBodyWithInstallingAppIds(
      installingAppIds: activeAppIds,
      automaticUpdates: automaticUpdates,
      manualUpdates: manualUpdates,
      upToDateApps: upToDateApps,
      uncatalogedApps: uncatalogedApps,
    );
  }
}

class _UpdatesListBodyWithInstallingAppIds extends ConsumerWidget {
  const _UpdatesListBodyWithInstallingAppIds({
    required this.installingAppIds,
    required this.automaticUpdates,
    required this.manualUpdates,
    required this.upToDateApps,
    required this.uncatalogedApps,
  });

  final Set<String> installingAppIds;
  final List<App> automaticUpdates;
  final List<App> manualUpdates;
  final List<App> upToDateApps;
  final List<PackageInfo> uncatalogedApps;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final updateAppIds = {
      ...automaticUpdates.map((a) => a.identifier),
      ...manualUpdates.map((a) => a.identifier),
    };

    // Only query for installing apps if there are active operations
    final List<App> installingApps;
    if (installingAppIds.isEmpty) {
      installingApps = const [];
    } else {
      final installingAppsState = ref.watch(
        query<App>(
          tags: {'#d': installingAppIds},
          and: (app) => {app.latestRelease.query()},
          source: const LocalAndRemoteSource(relays: 'AppCatalog'),
          subscriptionPrefix: 'app-installing-apps',
        ),
      );
      installingApps = installingAppsState.models
          .where(
            (app) =>
                installingAppIds.contains(app.identifier) &&
                !updateAppIds.contains(app.identifier),
          )
          .toList();
    }

    return _UpdatesListBody(
      automaticUpdates: automaticUpdates,
      manualUpdates: manualUpdates,
      installingApps: installingApps,
      upToDateApps: upToDateApps,
      uncatalogedApps: uncatalogedApps,
    );
  }
}

class _UpdatesListBody extends HookConsumerWidget {
  const _UpdatesListBody({
    required this.automaticUpdates,
    required this.manualUpdates,
    required this.installingApps,
    required this.upToDateApps,
    required this.uncatalogedApps,
  });

  final List<App> automaticUpdates;
  final List<App> manualUpdates;
  final List<App> installingApps;
  final List<App> upToDateApps;
  final List<PackageInfo> uncatalogedApps;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (automaticUpdates.isEmpty &&
        manualUpdates.isEmpty &&
        installingApps.isEmpty &&
        upToDateApps.isEmpty &&
        uncatalogedApps.isEmpty) {
      final theme = Theme.of(context);

      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.center,
          mainAxisSize: MainAxisSize.min,
          children: [
            ColorFiltered(
              colorFilter: const ColorFilter.matrix(<double>[
                0.2126,
                0.7152,
                0.0722,
                0,
                0,
                0.2126,
                0.7152,
                0.0722,
                0,
                0,
                0.2126,
                0.7152,
                0.0722,
                0,
                0,
                0,
                0,
                0,
                1,
                0,
              ]),
              child: const Text('🎉', style: TextStyle(fontSize: 48)),
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

    // Build flat list of items for ListView.builder
    final items = <_UpdatesItem>[];

    // Add last checked indicator first
    items.add(const _UpdatesItem(_UpdatesItemType.lastChecked));

    if (installingApps.isNotEmpty) {
      items.add(const _UpdatesItem(_UpdatesItemType.installingHeader));
      for (final app in installingApps) {
        items.add(_UpdatesItem(_UpdatesItemType.installingApp, app: app));
      }
    }

    if (automaticUpdates.isNotEmpty) {
      items.add(const _UpdatesItem(_UpdatesItemType.automaticHeader));
      for (final app in automaticUpdates) {
        items.add(_UpdatesItem(_UpdatesItemType.automaticApp, app: app));
      }
    }

    if (manualUpdates.isNotEmpty) {
      items.add(const _UpdatesItem(_UpdatesItemType.manualHeader));
      for (final app in manualUpdates) {
        items.add(_UpdatesItem(_UpdatesItemType.manualApp, app: app));
      }
    }

    if (upToDateApps.isNotEmpty) {
      items.add(const _UpdatesItem(_UpdatesItemType.upToDateHeader));
      for (final app in upToDateApps) {
        items.add(_UpdatesItem(_UpdatesItemType.upToDateApp, app: app));
      }
    }

    if (uncatalogedApps.isNotEmpty) {
      items.add(const _UpdatesItem(_UpdatesItemType.uncatalogedHeader));
      for (final pkg in uncatalogedApps) {
        items.add(
          _UpdatesItem(_UpdatesItemType.uncatalogedApp, packageInfo: pkg),
        );
      }
    }

    // Combine all updates for the Update All button
    final allUpdates = [...automaticUpdates, ...manualUpdates];

    // Watch batch progress and track "All done" state
    final progress = ref.watch(batchProgressProvider);
    final showAllDone = useState(false);
    final wasInProgress = useRef(false);

    // Detect transition from in-progress to all-complete
    useEffect(() {
      if (progress != null && progress.hasInProgress) {
        wasInProgress.value = true;
        showAllDone.value = false; // Reset if new operations start
      } else if (wasInProgress.value &&
          progress != null &&
          progress.isAllComplete) {
        // Just finished - show "All done" until dismissed
        showAllDone.value = true;
        wasInProgress.value = false;
      } else if (progress == null) {
        // Operations cleared externally
        wasInProgress.value = false;
        showAllDone.value = false;
      }
      return null;
    }, [progress?.hasInProgress, progress?.isAllComplete]);

    // Determine what to show (mutually exclusive states):
    // - "All done" banner after completion
    // - Progress indicator during operations
    // - Update All button when idle with updates available
    final showStickyAllDone = showAllDone.value;
    final activeProgress = progress != null && progress.hasInProgress
        ? progress
        : null;

    return RefreshIndicator(
      // Hide the spinner - _LastCheckedIndicator already shows "Checking for updates..."
      color: Colors.transparent,
      backgroundColor: Colors.transparent,
      elevation: 0,
      strokeWidth: 0,
      onRefresh: () => ref.read(updatePollerProvider.notifier).checkNow(),
      child: CustomScrollView(
        slivers: [
          // Sticky status banner (all done or progress) - only for batch operations (>1)
          if (showStickyAllDone && (progress?.total ?? 0) > 1)
            SliverPersistentHeader(
              pinned: true,
              delegate: _StickyBannerDelegate(
                child: _StatusBannerContent(
                  icon: Icons.check_circle_rounded,
                  iconColor: Colors.green.shade400,
                  text: 'All done (${progress?.completed ?? 0} updated)',
                  failedCount: progress?.failed ?? 0,
                  showDismiss: true,
                  onTap: () {
                    showAllDone.value = false;
                    ref
                        .read(packageManagerProvider.notifier)
                        .clearCompletedOperations();
                  },
                ),
              ),
            )
          else if (activeProgress != null && activeProgress.total > 1)
            SliverPersistentHeader(
              pinned: true,
              delegate: _StickyBannerDelegate(
                child: _StatusBannerContent(
                  isLoading: true,
                  text: activeProgress.statusText,
                  failedCount: activeProgress.failed,
                ),
              ),
            )
          // Update All button (when idle with 2+ updates available)
          else if (allUpdates.length > 1)
            SliverToBoxAdapter(child: UpdateAllRow(allUpdates: allUpdates)),
          // Main content
          SliverList(
            delegate: SliverChildBuilderDelegate((context, index) {
              final item = items[index];
              switch (item.type) {
                case _UpdatesItemType.lastChecked:
                  return const _LastCheckedIndicator();
                case _UpdatesItemType.installingHeader:
                  return _SectionHeader(
                    icon: Icons.downloading,
                    title: 'Installing',
                    count: installingApps.length,
                  );
                case _UpdatesItemType.installingApp:
                  return AppCard(
                    key: ValueKey('installing_${item.app?.identifier}'),
                    app: item.app,
                    showUpdateArrow: false,
                    showUpdateButton: true,
                    showZapEncouragement: true,
                    showDescription: false,
                  );
                case _UpdatesItemType.automaticHeader:
                  return _SectionHeader(
                    icon: Icons.system_update,
                    title: 'Updates',
                    count: automaticUpdates.length,
                  );
                case _UpdatesItemType.automaticApp:
                  return AppCard(
                    key: ValueKey('automatic_${item.app?.identifier}'),
                    app: item.app,
                    showUpdateArrow: true,
                    showUpdateButton: true,
                    showZapEncouragement: true,
                    showDescription: false,
                  );
                case _UpdatesItemType.manualHeader:
                  return _SectionHeader(
                    icon: Icons.touch_app,
                    title: 'Manual Updates',
                    count: manualUpdates.length,
                    trailing: _ManualUpdatesHelpIcon(),
                  );
                case _UpdatesItemType.manualApp:
                  return AppCard(
                    key: ValueKey('manual_${item.app?.identifier}'),
                    app: item.app,
                    showUpdateArrow: true,
                    showUpdateButton: true,
                    showZapEncouragement: true,
                    showDescription: false,
                  );
                case _UpdatesItemType.upToDateHeader:
                  return _SectionHeader(
                    icon: Icons.check_circle,
                    title: 'Up to date',
                    count: upToDateApps.length,
                  );
                case _UpdatesItemType.upToDateApp:
                  return AppCard(
                    key: ValueKey('uptodate_${item.app?.identifier}'),
                    app: item.app,
                    showUpdateArrow: false,
                    showDescription: false,
                  );
                case _UpdatesItemType.uncatalogedHeader:
                  return _SectionHeader(
                    icon: Icons.help_outline,
                    title: 'Other installed',
                    count: uncatalogedApps.length,
                    iconColor: AppColors.darkOnSurfaceSecondary,
                  );
                case _UpdatesItemType.uncatalogedApp:
                  return _UncatalogedAppCard(
                    key: ValueKey('uncataloged_${item.packageInfo?.appId}'),
                    packageInfo: item.packageInfo!,
                  );
              }
            }, childCount: items.length),
          ),
        ],
      ),
    );
  }
}

/// Delegate for sticky status banner.
/// Uses SizedBox.expand to guarantee we fill the declared extent.
class _StickyBannerDelegate extends SliverPersistentHeaderDelegate {
  const _StickyBannerDelegate({required this.child});

  final Widget child;

  // Matches UpdateAllRow: 44px content + 8px bottom margin = 52px
  static const double _height = 52.0;

  @override
  Widget build(
    BuildContext context,
    double shrinkOffset,
    bool overlapsContent,
  ) {
    // SizedBox.expand guarantees we fill exactly maxExtent
    return SizedBox.expand(
      child: ColoredBox(
        color: Theme.of(context).scaffoldBackgroundColor,
        // Match UpdateAllRow margin: 16 left/right, 0 top, 8 bottom
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
          child: child,
        ),
      ),
    );
  }

  @override
  double get maxExtent => _height;

  @override
  double get minExtent => _height;

  @override
  bool shouldRebuild(covariant _StickyBannerDelegate oldDelegate) =>
      child != oldDelegate.child;
}

/// Status banner content (used inside sticky header or standalone).
/// When used standalone, wrap in appropriate padding.
class _StatusBannerContent extends StatelessWidget {
  const _StatusBannerContent({
    required this.text,
    this.icon,
    this.iconColor,
    this.isLoading = false,
    this.failedCount = 0,
    this.showDismiss = false,
    this.onTap,
  });

  final String text;
  final IconData? icon;
  final Color? iconColor;
  final bool isLoading;
  final int failedCount;
  final bool showDismiss;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        decoration: BoxDecoration(
          color: AppColors.darkPillBackground,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            // Leading indicator (spinner or icon)
            if (isLoading)
              const SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(
                  strokeWidth: 3,
                  valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                ),
              )
            else if (icon != null)
              Icon(icon, size: 18, color: iconColor ?? Colors.white),
            const SizedBox(width: 12),

            // Main text - bold
            Expanded(
              child: AutoSizeText(
                text,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 14,
                  fontWeight: FontWeight.bold,
                  fontFamily: kFontFamily,
                ),
                maxLines: 1,
                minFontSize: 10,
              ),
            ),

            // Failed badge (if any)
            if (failedCount > 0) ...[
              const SizedBox(width: 8),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: Colors.red.shade700,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  '$failedCount failed',
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 12,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
            ],

            // Dismiss icon
            if (showDismiss) ...[
              const SizedBox(width: 8),
              Icon(
                Icons.close,
                size: 16,
                color: Colors.white.withValues(alpha: 0.6),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Section header with icon, title, count badge, and optional trailing widget.
class _SectionHeader extends StatelessWidget {
  const _SectionHeader({
    required this.icon,
    required this.title,
    required this.count,
    this.iconColor,
    this.trailing,
  });

  final IconData icon;
  final String title;
  final int count;
  final Color? iconColor;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Row(
        children: [
          Icon(icon, size: 20, color: iconColor ?? AppColors.darkActionPrimary),
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
    );
  }
}

/// Help icon that shows explanation for Manual Updates section.
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

class _UncatalogedAppCard extends StatelessWidget {
  const _UncatalogedAppCard({super.key, required this.packageInfo});

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
          // Generic app icon
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
                // Version pill
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
