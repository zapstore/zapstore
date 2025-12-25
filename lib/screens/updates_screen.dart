import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/updates_service.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/common/badges.dart';
import 'package:zapstore/widgets/app_card.dart';

/// Screen for managing app updates
class UpdatesScreen extends HookConsumerWidget {
  const UpdatesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final categorized = ref.watch(categorizedAppsProvider);

    if (categorized.isLoading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }

    return Scaffold(
      body: Padding(
        padding: const EdgeInsets.only(top: 16),
        child: _UpdatesContent(categorized: categorized),
      ),
    );
  }
}

/// Item types for the updates list
enum _UpdatesItemType {
  installingHeader,
  installingApp,
  automaticHeader,
  automaticApp,
  manualHeader,
  manualInfoBox,
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

  final CategorizedApps categorized;

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

    if (activeAppIds.isEmpty) {
      return _UpdatesListBody(
        automaticUpdates: automaticUpdates,
        manualUpdates: manualUpdates,
        installingApps: const [],
        upToDateApps: upToDateApps,
        uncatalogedApps: uncatalogedApps,
      );
    }

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
    final installingAppsState = ref.watch(
      query<App>(
        tags: {'#d': installingAppIds},
        and: (app) => {app.latestRelease},
        source: const LocalAndRemoteSource(relays: 'AppCatalog'),
        subscriptionPrefix: 'installing-apps',
      ),
    );

    final updateAppIds = {
      ...automaticUpdates.map((a) => a.identifier),
      ...manualUpdates.map((a) => a.identifier),
    };

    final installingApps = installingAppsState.models
        .where(
          (app) =>
              installingAppIds.contains(app.identifier) &&
              !updateAppIds.contains(app.identifier),
        )
        .toList();

    return _UpdatesListBody(
      automaticUpdates: automaticUpdates,
      manualUpdates: manualUpdates,
      installingApps: installingApps,
      upToDateApps: upToDateApps,
      uncatalogedApps: uncatalogedApps,
    );
  }
}

class _UpdatesListBody extends StatelessWidget {
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
  Widget build(BuildContext context) {
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
      items.add(const _UpdatesItem(_UpdatesItemType.manualInfoBox));
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

    return ListView.builder(
      key: const PageStorageKey<String>('updates_list'),
      itemCount: items.length,
      itemBuilder: (context, index) {
        final item = items[index];
        switch (item.type) {
          case _UpdatesItemType.installingHeader:
            return _InstallingHeader(count: installingApps.length);
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
            return _UpdatesSectionHeader(
              icon: Icons.system_update,
              title: 'Updates',
              count: automaticUpdates.length,
              apps: automaticUpdates,
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
            return _UpdatesSectionHeader(
              icon: Icons.touch_app,
              title: 'Manual Updates',
              count: manualUpdates.length,
              apps: manualUpdates,
            );
          case _UpdatesItemType.manualInfoBox:
            return _ManualUpdatesInfoBox();
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
            return _UpToDateHeader(count: upToDateApps.length);
          case _UpdatesItemType.upToDateApp:
            return AppCard(
              key: ValueKey('uptodate_${item.app?.identifier}'),
              app: item.app,
              showUpdateArrow: false,
              showDescription: false,
            );
          case _UpdatesItemType.uncatalogedHeader:
            return _UncatalogedHeader(count: uncatalogedApps.length);
          case _UpdatesItemType.uncatalogedApp:
            return _UncatalogedAppCard(
              key: ValueKey('uncataloged_${item.packageInfo?.appId}'),
              packageInfo: item.packageInfo!,
            );
        }
      },
    );
  }
}

class _InstallingHeader extends StatelessWidget {
  const _InstallingHeader({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Row(
        children: [
          Icon(Icons.downloading, size: 20, color: AppColors.darkActionPrimary),
          const SizedBox(width: 8),
          Text('Installing', style: context.textTheme.titleMedium),
          const SizedBox(width: 8),
          CountBadge(count: count, color: AppColors.darkPillBackground),
        ],
      ),
    );
  }
}

class _UpdatesSectionHeader extends ConsumerWidget {
  const _UpdatesSectionHeader({
    required this.icon,
    required this.title,
    required this.count,
    required this.apps,
  });

  final IconData icon;
  final String title;
  final int count;
  final List<App> apps;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Wrap(
        spacing: 8,
        runSpacing: 8,
        alignment: WrapAlignment.spaceBetween,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 20, color: AppColors.darkActionPrimary),
              const SizedBox(width: 8),
              Flexible(
                child: Text(title, style: context.textTheme.titleMedium),
              ),
              const SizedBox(width: 8),
              CountBadge(count: count, color: AppColors.darkPillBackground),
            ],
          ),
          AsyncButtonBuilder(
            onPressed: () async {
              final pm = ref.read(packageManagerProvider.notifier);
              final items = apps
                  .where((app) => app.latestFileMetadata != null)
                  .map(
                    (app) => (
                      appId: app.identifier,
                      target: app.latestFileMetadata!,
                      displayName: app.name,
                    ),
                  )
                  .toList();
              await pm.queueDownloads(items);
            },
            builder: (context, child, callback, buttonState) {
              const pillBg = AppColors.darkPillBackground;
              const pillText = Colors.white;

              return TextButton.icon(
                onPressed: buttonState.maybeWhen(
                  loading: () => null,
                  orElse: () => callback,
                ),
                icon: buttonState.maybeWhen(
                  loading: () => const SizedBox(
                    width: 12,
                    height: 12,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: pillText,
                    ),
                  ),
                  orElse: () =>
                      const Icon(Icons.download, size: 14, color: pillText),
                ),
                label: const Text(
                  'Update All',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w500,
                    color: pillText,
                  ),
                ),
                style: TextButton.styleFrom(
                  backgroundColor: pillBg,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 6,
                  ),
                  minimumSize: Size.zero,
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
              );
            },
            child: const SizedBox.shrink(),
          ),
        ],
      ),
    );
  }
}

class _ManualUpdatesInfoBox extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 8),
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
              size: 18,
              color: Theme.of(
                context,
              ).colorScheme.onSurface.withValues(alpha: 0.6),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              'Apps not installed or updated by the latest Zapstore will show here '
              'and require manual confirmation of the Android system prompt once per app.',
              style: context.textTheme.bodyMedium?.copyWith(
                color: Theme.of(
                  context,
                ).colorScheme.onSurface.withValues(alpha: 0.6),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _UpToDateHeader extends StatelessWidget {
  const _UpToDateHeader({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Row(
        children: [
          Icon(
            Icons.check_circle,
            size: 20,
            color: AppColors.darkActionPrimary,
          ),
          const SizedBox(width: 8),
          Text('Up to date', style: context.textTheme.titleMedium),
          const SizedBox(width: 8),
          CountBadge(count: count, color: AppColors.darkPillBackground),
        ],
      ),
    );
  }
}

class _UncatalogedHeader extends StatelessWidget {
  const _UncatalogedHeader({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Row(
        children: [
          Icon(
            Icons.help_outline,
            size: 20,
            color: AppColors.darkOnSurfaceSecondary,
          ),
          const SizedBox(width: 8),
          Text('Other installed', style: context.textTheme.titleMedium),
          const SizedBox(width: 8),
          CountBadge(count: count, color: AppColors.darkPillBackground),
        ],
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
