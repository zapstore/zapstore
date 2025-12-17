import 'package:async_button_builder/async_button_builder.dart';
import 'package:background_downloader/background_downloader.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/bookmarks_service.dart';
import 'package:zapstore/services/download/download_service.dart';
import 'package:zapstore/services/updates_service.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/app_card.dart';

/// Screen for managing app updates
class UpdatesScreen extends HookConsumerWidget {
  const UpdatesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final categorized = ref.watch(categorizedAppsProvider);
    final updatesCount =
        categorized.automaticUpdates.length + categorized.manualUpdates.length;
    final upToDateCount = categorized.upToDateApps.length;
    final bookmarksAsync = ref.watch(bookmarksProvider);
    final bookmarksCount = bookmarksAsync.maybeWhen(
      data: (ids) => ids.length,
      orElse: () => 0,
    );

    final baseTabStyle = context.textTheme.titleMedium;
    final tabLabelStyle = (baseTabStyle ?? const TextStyle()).copyWith(
      fontSize: (baseTabStyle?.fontSize ?? 16) * 0.85,
      fontWeight: FontWeight.bold,
    );

    return DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          automaticallyImplyLeading: false,
          toolbarHeight: 0,
          bottom: TabBar(
            labelStyle: tabLabelStyle,
            unselectedLabelStyle: tabLabelStyle,
            tabs: [
              Tab(
                child: _TabLabelWithBadge(
                  label: 'Updates',
                  count: updatesCount,
                  textStyle: tabLabelStyle,
                  badgeColor: Colors.red.withValues(alpha: 0.4),
                ),
              ),
              Tab(
                child: _TabLabelWithBadge(
                  label: 'Up to date',
                  count: upToDateCount,
                  textStyle: tabLabelStyle,
                  badgeColor: Colors.blue.shade700.withValues(alpha: 0.4),
                ),
              ),
              Tab(
                child: _TabLabelWithBadge(
                  label: 'Bookmarks',
                  count: bookmarksCount,
                  textStyle: tabLabelStyle,
                  badgeColor: Colors.blue.shade700.withValues(alpha: 0.4),
                ),
              ),
            ],
          ),
        ),
        body: const Padding(
          padding: EdgeInsets.only(top: 16),
          child: TabBarView(
            children: [_UpdatesTab(), _UpToDateTab(), _BookmarksTab()],
          ),
        ),
      ),
    );
  }
}

class _UpdatesTab extends HookConsumerWidget {
  const _UpdatesTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final categorized = ref.watch(categorizedAppsProvider);

    if (categorized.isLoading) {
      return const Center(child: CircularProgressIndicator());
    }

    return _UpdatesContent(categorized: categorized);
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
}

class _UpdatesItem {
  final _UpdatesItemType type;
  final App? app;

  const _UpdatesItem(this.type, [this.app]);
}

class _UpdatesContent extends HookConsumerWidget {
  const _UpdatesContent({required this.categorized});

  final CategorizedApps categorized;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final automaticUpdates = categorized.automaticUpdates;
    final manualUpdates = categorized.manualUpdates;

    final downloads = ref.watch(downloadServiceProvider);
    final installingAppIds = downloads.entries
        .where(
          (entry) =>
              entry.value.isInstalling ||
              entry.value.status == TaskStatus.running ||
              entry.value.status == TaskStatus.enqueued ||
              entry.value.status == TaskStatus.paused ||
              entry.value.status == TaskStatus.waitingToRetry,
        )
        .map((entry) => entry.key)
        .toSet();

    final installingAppsState = installingAppIds.isNotEmpty
        ? ref.watch(
            query<App>(
              tags: {'#d': installingAppIds},
              and: (app) => {app.latestRelease},
              source: const LocalAndRemoteSource(relays: 'AppCatalog'),
              subscriptionPrefix: 'installing-apps',
            ),
          )
        : StorageData<App>(const []);

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

    if (automaticUpdates.isEmpty &&
        manualUpdates.isEmpty &&
        installingApps.isEmpty) {
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
              'All apps are up to date',
              style: theme.textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.bold,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 6),
            Text(
              'Great job staying current!',
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
        items.add(_UpdatesItem(_UpdatesItemType.installingApp, app));
      }
    }

    if (automaticUpdates.isNotEmpty) {
      items.add(const _UpdatesItem(_UpdatesItemType.automaticHeader));
      for (final app in automaticUpdates) {
        items.add(_UpdatesItem(_UpdatesItemType.automaticApp, app));
      }
    }

    if (manualUpdates.isNotEmpty) {
      items.add(const _UpdatesItem(_UpdatesItemType.manualHeader));
      items.add(const _UpdatesItem(_UpdatesItemType.manualInfoBox));
      for (final app in manualUpdates) {
        items.add(_UpdatesItem(_UpdatesItemType.manualApp, app));
      }
    }

    return ListView.builder(
      itemCount: items.length,
      itemBuilder: (context, index) {
        final item = items[index];
        switch (item.type) {
          case _UpdatesItemType.installingHeader:
            return _InstallingHeader(count: installingApps.length);
          case _UpdatesItemType.installingApp:
            return AppCard(
              app: item.app,
              showUpdateArrow: false,
              showUpdateButton: true,
              showZapEncouragement: true,
            );
          case _UpdatesItemType.automaticHeader:
            return _AutomaticUpdatesHeader(
              count: automaticUpdates.length,
              apps: automaticUpdates,
            );
          case _UpdatesItemType.automaticApp:
            return AppCard(
              app: item.app,
              showUpdateArrow: true,
              showUpdateButton: true,
              showZapEncouragement: true,
            );
          case _UpdatesItemType.manualHeader:
            return _ManualUpdatesHeader(
              count: manualUpdates.length,
              apps: manualUpdates,
            );
          case _UpdatesItemType.manualInfoBox:
            return _ManualUpdatesInfoBox();
          case _UpdatesItemType.manualApp:
            return AppCard(
              app: item.app,
              showUpdateArrow: true,
              showUpdateButton: true,
              showZapEncouragement: true,
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
          Text(
            'Installing',
            style: context.textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
            decoration: BoxDecoration(
              color: AppColors.darkPillBackground,
              borderRadius: BorderRadius.circular(10),
            ),
            constraints: const BoxConstraints(minWidth: 18, minHeight: 18),
            child: Text(
              count > 99 ? '99+' : count.toString(),
              style: const TextStyle(
                color: Colors.white,
                fontSize: 10,
                fontWeight: FontWeight.bold,
              ),
              textAlign: TextAlign.center,
            ),
          ),
        ],
      ),
    );
  }
}

class _AutomaticUpdatesHeader extends ConsumerWidget {
  const _AutomaticUpdatesHeader({required this.count, required this.apps});

  final int count;
  final List<App> apps;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Row(
        children: [
          Icon(
            Icons.system_update,
            size: 20,
            color: AppColors.darkActionPrimary,
          ),
          const SizedBox(width: 8),
          Text(
            'Updates',
            style: context.textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
            decoration: BoxDecoration(
              color: AppColors.darkPillBackground,
              borderRadius: BorderRadius.circular(10),
            ),
            constraints: const BoxConstraints(minWidth: 18, minHeight: 18),
            child: Text(
              count > 99 ? '99+' : count.toString(),
              style: const TextStyle(
                color: Colors.white,
                fontSize: 10,
                fontWeight: FontWeight.bold,
              ),
              textAlign: TextAlign.center,
            ),
          ),
          const Spacer(),
          AsyncButtonBuilder(
            onPressed: () async {
              final downloadService = ref.read(
                downloadServiceProvider.notifier,
              );
              for (final app in apps) {
                final release = app.latestRelease.value;
                if (release != null) {
                  try {
                    await downloadService.downloadApp(app, release);
                  } catch (_) {
                    // Ignore download start failures per-app to keep others going
                  }
                }
              }
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

class _ManualUpdatesHeader extends ConsumerWidget {
  const _ManualUpdatesHeader({required this.count, required this.apps});

  final int count;
  final List<App> apps;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Row(
        children: [
          Icon(Icons.touch_app, size: 20, color: AppColors.darkActionPrimary),
          const SizedBox(width: 8),
          Text(
            'Manual Updates',
            style: context.textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
            decoration: BoxDecoration(
              color: AppColors.darkPillBackground,
              borderRadius: BorderRadius.circular(10),
            ),
            constraints: const BoxConstraints(minWidth: 18, minHeight: 18),
            child: Text(
              count > 99 ? '99+' : count.toString(),
              style: const TextStyle(
                color: Colors.white,
                fontSize: 10,
                fontWeight: FontWeight.bold,
              ),
              textAlign: TextAlign.center,
            ),
          ),
          const Spacer(),
          AsyncButtonBuilder(
            onPressed: () async {
              final downloadService = ref.read(
                downloadServiceProvider.notifier,
              );
              for (final app in apps) {
                final release = app.latestRelease.value;
                if (release != null) {
                  try {
                    await downloadService.downloadApp(app, release);
                  } catch (_) {
                    // Ignore download start failures per-app to keep others going
                  }
                }
              }
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

class _UpToDateTab extends HookConsumerWidget {
  const _UpToDateTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final categorized = ref.watch(categorizedAppsProvider);

    if (categorized.isLoading) {
      return const Center(child: CircularProgressIndicator());
    }

    final upToDateApps = categorized.upToDateApps;

    if (upToDateApps.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(
            'No up-to-date apps yet',
            style: context.textTheme.bodyMedium?.copyWith(
              color: Theme.of(
                context,
              ).colorScheme.onSurface.withValues(alpha: 0.6),
            ),
          ),
        ),
      );
    }

    return ListView.builder(
      itemCount: upToDateApps.length,
      itemBuilder: (context, index) {
        return AppCard(app: upToDateApps[index], showUpdateArrow: false);
      },
    );
  }
}

class _BookmarksTab extends HookConsumerWidget {
  const _BookmarksTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);

    if (signedInPubkey == null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(
            'Sign in to view bookmarks',
            style: context.textTheme.bodyMedium?.copyWith(
              color: Theme.of(
                context,
              ).colorScheme.onSurface.withValues(alpha: 0.6),
            ),
          ),
        ),
      );
    }

    final bookmarksAsync = ref.watch(bookmarksProvider);

    return bookmarksAsync.when(
      loading: () => Center(
        child: CircularProgressIndicator(
          color: Theme.of(context).colorScheme.primary,
        ),
      ),
      error: (_, __) => Center(
        child: Text(
          'Error loading bookmarks',
          style: context.textTheme.bodyMedium?.copyWith(
            color: Theme.of(context).colorScheme.error,
          ),
        ),
      ),
      data: (addressableIds) {
        final identifiers = addressableIds
            .map((id) {
              final parts = id.split(':');
              return parts.length >= 3 ? parts[2] : null;
            })
            .whereType<String>()
            .toSet();

        final bookmarkedAppsState = identifiers.isNotEmpty
            ? ref.watch(
                query<App>(
                  tags: {'#d': identifiers},
                  and: (app) => {app.latestRelease},
                  source: const LocalSource(),
                  subscriptionPrefix: 'bookmark-apps',
                ),
              )
            : StorageData<App>(const []);

        final savedApps = bookmarkedAppsState.models.toList()
          ..sort(
            (a, b) => (a.name ?? a.identifier).toLowerCase().compareTo(
              (b.name ?? b.identifier).toLowerCase(),
            ),
          );

        if (savedApps.isEmpty) {
          return Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Text(
                'No bookmarked apps yet',
                style: context.textTheme.bodyMedium?.copyWith(
                  color: Theme.of(
                    context,
                  ).colorScheme.onSurface.withValues(alpha: 0.5),
                  fontStyle: FontStyle.italic,
                ),
              ),
            ),
          );
        }

        return ListView.builder(
          padding: EdgeInsets.zero,
          itemCount: savedApps.length,
          itemBuilder: (context, index) {
            return AppCard(app: savedApps[index], showUpdateArrow: false);
          },
        );
      },
    );
  }
}

class _TabLabelWithBadge extends StatelessWidget {
  const _TabLabelWithBadge({
    required this.label,
    required this.count,
    this.textStyle,
    this.badgeColor,
  });

  final String label;
  final int count;
  final TextStyle? textStyle;
  final Color? badgeColor;

  @override
  Widget build(BuildContext context) {
    return DefaultTextStyle.merge(
      style: textStyle,
      child: Padding(
        padding: const EdgeInsets.only(right: 16),
        child: Stack(
          clipBehavior: Clip.none,
          alignment: Alignment.centerLeft,
          children: [
            Text(label),
            if (count > 0)
              Positioned(
                top: -6,
                right: -18,
                child: _CountBadge(count: count, color: badgeColor),
              ),
          ],
        ),
      ),
    );
  }
}

class _CountBadge extends StatelessWidget {
  const _CountBadge({required this.count, this.color});

  final int count;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final display = count > 99 ? '99+' : count.toString();

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color ?? Colors.red.withValues(alpha: 0.4),
        borderRadius: BorderRadius.circular(9),
      ),
      constraints: const BoxConstraints(minWidth: 18, minHeight: 18),
      child: Text(
        display,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 10,
          fontWeight: FontWeight.bold,
        ),
        textAlign: TextAlign.center,
      ),
    );
  }
}
