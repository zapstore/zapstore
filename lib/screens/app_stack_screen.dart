import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/app_card.dart';
import 'package:zapstore/widgets/author_container.dart';
import 'package:zapstore/widgets/comments_section.dart';
import 'package:zapstore/widgets/common/badges.dart';
import 'package:zapstore/theme.dart';

class AppStackScreen extends HookConsumerWidget {
  const AppStackScreen({super.key, required this.stackId, this.authorPubkey});

  final String stackId;
  final String? authorPubkey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Query stack with apps relationship
    final stackState = ref.watch(
      query<AppStack>(
        authors: authorPubkey != null ? {authorPubkey!} : null,
        tags: {
          '#d': {stackId},
        },
        limit: 1,
        and: (pack) => {pack.apps},
        source: LocalAndRemoteSource(stream: true, relays: 'social'),
        andSource: const LocalAndRemoteSource(
          relays: 'AppCatalog',
          stream: false,
        ),
        subscriptionPrefix: authorPubkey != null
            ? 'app-stack-${authorPubkey!}-$stackId'
            : 'app-stack-$stackId',
      ),
    );

    return switch (stackState) {
      StorageError(:final exception) => _ErrorScaffold(
        message: exception.toString(),
      ),
      StorageData(:final models) => _AppStackContentWithApps(
        stack: models.firstOrNull,
      ),
      StorageLoading() => Scaffold(
        body: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: _AppStackSkeleton(),
          ),
        ),
      ),
    };
  }
}

/// Intermediate widget that loads apps with their release relationships
class _AppStackContentWithApps extends HookConsumerWidget {
  final AppStack? stack;

  const _AppStackContentWithApps({required this.stack});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (stack == null) {
      return const _ErrorScaffold(message: 'Stack not found');
    }

    // Get app identifiers from stack's apps relationship
    final stackApps = stack!.apps.toList();
    final appIdentifiers = stackApps.map((app) => app.identifier).toSet();

    if (appIdentifiers.isEmpty) {
      return _AppStackContent(stack: stack!, apps: const []);
    }

    // Query apps with release and metadata relationships (same pattern as search/user screens)
    final appsState = ref.watch(
      query<App>(
        tags: {'#d': appIdentifiers},
        and: (app) => {
          app.latestRelease,
          app.latestRelease.value?.latestMetadata,
          app.latestRelease.value?.latestAsset,
        },
        source: const LocalAndRemoteSource(relays: 'AppCatalog'),
        subscriptionPrefix: 'app-stack-apps-${stack!.identifier}',
      ),
    );

    // Map loaded apps by identifier for ordering
    final appsMap = {for (final app in appsState.models) app.identifier: app};

    // Preserve stack order, using loaded apps with their relationships
    final orderedApps = stackApps
        .map((app) => appsMap[app.identifier] ?? app)
        .toList();

    return _AppStackContent(stack: stack!, apps: orderedApps);
  }
}

class _ErrorScaffold extends StatelessWidget {
  final String message;
  const _ErrorScaffold({required this.message});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('App Stack')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.error_outline, size: 64),
            const SizedBox(height: 12),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24),
              child: Text(message, textAlign: TextAlign.center),
            ),
          ],
        ),
      ),
    );
  }
}

/// Internal widget that displays stack details
class _AppStackContent extends HookConsumerWidget {
  final AppStack stack;
  final List<App> apps;

  const _AppStackContent({required this.stack, required this.apps});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Query author profile from social relays
    final authorState = ref.watch(
      query<Profile>(
        authors: {stack.pubkey},
        source: const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          cachedFor: Duration(hours: 2),
        ),
      ),
    );
    final author = switch (authorState) {
      StorageData(:final models) => models.firstOrNull,
      _ => null,
    };

    // Sort apps: uninstalled first, keeping original order otherwise
    final packageManager = ref.watch(packageManagerProvider.notifier);
    final sortedApps = _sortAppsUninstalledFirst(apps, packageManager);
    final totalApps = sortedApps.length;

    return Scaffold(
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.only(top: 16, bottom: 32),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Stack header
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: _StackHeader(stack: stack, author: author),
              ),
              const SizedBox(height: 24),
              // Apps section header with count badge
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Row(
                  children: [
                    Text(
                      'Apps in this stack',
                      style: context.textTheme.titleLarge,
                    ),
                    const SizedBox(width: 8),
                    CountBadge(
                      count: totalApps,
                      color: AppColors.darkPillBackground,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 12),
              // Apps list
              if (sortedApps.isEmpty)
                _EmptyAppsPlaceholder()
              else
                ...sortedApps.map(
                  (app) => AppCard(app: app, showUpdateArrow: app.hasUpdate),
                ),
              // Comments section
              Padding(
                padding: const EdgeInsets.only(top: 24),
                child: StackCommentsSection(stack: stack),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Sort apps so uninstalled ones come first, keeping original order otherwise
  List<App> _sortAppsUninstalledFirst(
    List<App> apps,
    PackageManager packageManager,
  ) {
    final uninstalled = <App>[];
    final installed = <App>[];

    for (final app in apps) {
      if (packageManager.isInstalled(app.identifier)) {
        installed.add(app);
      } else {
        uninstalled.add(app);
      }
    }

    return [...uninstalled, ...installed];
  }
}

/// Stack header with name and author
class _StackHeader extends StatelessWidget {
  const _StackHeader({required this.stack, this.author});

  final AppStack stack;
  final Profile? author;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Stack name
        Text(
          stack.name ?? stack.identifier,
          style: context.textTheme.headlineMedium,
        ),
        const SizedBox(height: 8),
        // Published by author (same style as app_detail_screen)
        if (author != null)
          AuthorContainer(
            profile: author!,
            beforeText: 'Published by',
            oneLine: true,
            size: 14,
            onTap: () {
              final segments = GoRouterState.of(context).uri.pathSegments;
              final first = segments.isNotEmpty ? segments.first : 'search';
              context.push('/$first/user/${stack.pubkey}');
            },
          ),
      ],
    );
  }
}

/// Empty state when no apps in stack
class _EmptyAppsPlaceholder extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(32),
      child: Column(
        children: [
          Icon(
            Icons.apps_outlined,
            size: 48,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
          const SizedBox(height: 12),
          Text(
            'No apps in this stack',
            style: context.textTheme.bodyLarge?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }
}

/// Skeleton loading state for the stack screen
class _AppStackSkeleton extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return SkeletonizerConfig(
      data: AppColors.getSkeletonizerConfig(Theme.of(context).brightness),
      child: Skeletonizer(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Header skeleton
            Container(
              height: 28,
              width: 180,
              decoration: BoxDecoration(
                color: AppColors.darkSkeletonBase,
                borderRadius: BorderRadius.circular(4),
              ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Container(
                  width: 28,
                  height: 28,
                  decoration: BoxDecoration(
                    color: AppColors.darkSkeletonBase,
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
                const SizedBox(width: 8),
                Container(
                  height: 16,
                  width: 100,
                  decoration: BoxDecoration(
                    color: AppColors.darkSkeletonBase,
                    borderRadius: BorderRadius.circular(4),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 24),
            // Section header with count badge
            Row(
              children: [
                Container(
                  height: 20,
                  width: 140,
                  decoration: BoxDecoration(
                    color: AppColors.darkSkeletonBase,
                    borderRadius: BorderRadius.circular(4),
                  ),
                ),
                const SizedBox(width: 8),
                Container(
                  height: 20,
                  width: 24,
                  decoration: BoxDecoration(
                    color: AppColors.darkSkeletonBase,
                    borderRadius: BorderRadius.circular(10),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            // App cards skeleton (using AppCard skeleton)
            ...List.generate(3, (_) => const AppCard(isLoading: true)),
          ],
        ),
      ),
    );
  }
}
