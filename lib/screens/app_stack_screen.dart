import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/nostr_route.dart';
import 'package:zapstore/utils/stack_app_ids.dart';
import 'package:zapstore/widgets/app_card.dart';
import 'package:zapstore/widgets/app_stack_container.dart';
import 'package:zapstore/widgets/author_container.dart';
import 'package:zapstore/widgets/comments_section.dart';
import 'package:zapstore/widgets/common/badges.dart';
import 'package:zapstore/widgets/common/time_utils.dart';
import 'package:zapstore/widgets/floating_overflow_menu.dart';
import 'package:zapstore/theme.dart';

class AppStackScreen extends ConsumerWidget {
  const AppStackScreen({super.key, required this.stackId, this.authorPubkey});

  final String stackId;
  final String? authorPubkey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isDeviceOwned =
        authorPubkey != null && authorPubkey == ref.watch(devicePubkeyProvider);
    final stackState = ref.watch(
      query<AppStack>(
        authors: authorPubkey != null ? {authorPubkey!} : null,
        tags: {
          '#d': {stackId},
        },
        limit: 1,
        source: isDeviceOwned
            ? const LocalSource()
            : const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'app-stack-detail-$stackId',
      ),
    );

    if (stackState case StorageError(:final exception)) {
      return _ErrorScaffold(message: exception.toString());
    }

    final stack = stackState.models.firstOrNull;

    if (stack == null) {
      // Still loading — show skeleton
      if (stackState is StorageLoading) {
        return Scaffold(
          body: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: _AppStackSkeleton(),
          ),
        );
      }
      // Loading complete but stack not found
      return _NotFoundScaffold(stackId: stackId);
    }

    return _AppStackContentWithApps(stack: stack);
  }
}

/// Intermediate widget that loads apps with their release relationships
class _AppStackContentWithApps extends ConsumerWidget {
  final AppStack stack;

  const _AppStackContentWithApps({required this.stack});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isEncrypted = stack.content.isNotEmpty;
    if (isEncrypted && !stack.isDecrypted) {
      return _AppStackContent(
        stack: stack,
        entries: const [],
        errorMessage:
            'This private stack could not be decrypted on this device',
      );
    }

    // Public stacks use `a` tags (addressable IDs). Private stacks may store
    // addressable IDs (Saved Apps) or bare package IDs (Unmanaged Apps).
    final orderedIds = isEncrypted
        ? stack.privateAppIds
        : stack.event
              .getTagSetValues('a')
              .where((id) => id.startsWith('32267:'))
              .toList();

    if (orderedIds.isEmpty) {
      return _AppStackContent(stack: stack, entries: const []);
    }

    final (:addressableIds, :packageIds) = partitionStackAppIds(orderedIds);
    final (:authors, :identifiers) = decomposeAddressableIds(addressableIds);
    final platform = ref.read(packageManagerProvider.notifier).platform;
    final installed = ref.watch(
      packageManagerProvider.select((s) => s.installed),
    );

    final addressableState = addressableIds.isNotEmpty
        ? ref.watch(
            query<App>(
              authors: authors,
              tags: {
                '#d': identifiers,
                '#f': {platform},
              },
              and: (app) => {
                app.latestRelease.query(
                  and: (release) => {
                    release.latestMetadata.query(),
                    release.latestAsset.query(),
                  },
                ),
              },
              source: const LocalAndRemoteSource(
                relays: 'AppCatalog',
                stream: false,
              ),
              subscriptionPrefix: 'app-stack-apps-${stack.identifier}',
            ),
          )
        : null;

    final packageState = packageIds.isNotEmpty
        ? ref.watch(
            query<App>(
              tags: {
                '#d': packageIds.toSet(),
                '#f': {platform},
              },
              and: (app) => {
                app.latestRelease.query(
                  and: (release) => {
                    release.latestMetadata.query(),
                    release.latestAsset.query(),
                  },
                ),
              },
              source: const LocalAndRemoteSource(
                relays: 'AppCatalog',
                stream: false,
              ),
              subscriptionPrefix: 'app-stack-pkgs-${stack.identifier}',
            ),
          )
        : null;

    final appsByAddressableId = {
      for (final app in addressableState?.models ?? const <App>[]) app.id: app,
    };
    final appsByPackageId = <String, App>{};
    for (final app in packageState?.models ?? const <App>[]) {
      appsByPackageId.putIfAbsent(app.identifier, () => app);
    }

    final resolutions = resolveStackAppIds(
      orderedIds: orderedIds,
      foundAddressableIds: appsByAddressableId.keys.toSet(),
      foundPackageIds: appsByPackageId.keys.toSet(),
    );

    final entries = <_StackAppEntry>[
      for (final resolution in resolutions)
        switch (resolution.kind) {
          StackAppResolveKind.catalogAddressable => _CatalogedStackAppEntry(
            appsByAddressableId[resolution.rawId]!,
          ),
          StackAppResolveKind.catalogPackage => _CatalogedStackAppEntry(
            appsByPackageId[resolution.rawId]!,
          ),
          StackAppResolveKind.packageFallback => _PackageStackAppEntry(
            installed[resolution.rawId] ??
                PackageInfo(
                  appId: resolution.rawId,
                  version: 'Unknown',
                  versionCode: null,
                ),
          ),
        },
    ];

    return _AppStackContent(stack: stack, entries: entries);
  }
}

/// A resolved row for the stack detail list.
sealed class _StackAppEntry {
  const _StackAppEntry();

  String get key;
}

class _CatalogedStackAppEntry extends _StackAppEntry {
  const _CatalogedStackAppEntry(this.app);

  final App app;

  @override
  String get key => app.identifier;
}

class _PackageStackAppEntry extends _StackAppEntry {
  const _PackageStackAppEntry(this.packageInfo);

  final PackageInfo packageInfo;

  @override
  String get key => packageInfo.appId;
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

class _NotFoundScaffold extends StatelessWidget {
  final String stackId;
  const _NotFoundScaffold({required this.stackId});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('App Stack')),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                Icons.apps_outlined,
                size: 64,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
              const SizedBox(height: 16),
              Text(
                'Stack not found',
                style: Theme.of(context).textTheme.titleLarge,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 8),
              Text(
                'This stack may have been deleted or is not available',
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Internal widget that displays stack details
class _AppStackContent extends HookConsumerWidget {
  final AppStack stack;
  final List<_StackAppEntry> entries;
  final String? errorMessage;

  const _AppStackContent({
    required this.stack,
    required this.entries,
    this.errorMessage,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isPrivate = stack.content.isNotEmpty;

    // Author is only shown for public stacks; skip the query for private ones.
    final authorState = isPrivate
        ? null
        : ref.watch(
            query<Profile>(
              authors: {stack.pubkey},
              source: const LocalAndRemoteSource(
                relays: {'social', 'vertex'},
                cachedFor: Duration(hours: 2),
              ),
              subscriptionPrefix: 'app-stack-profile',
            ),
          );
    final author = authorState?.models.firstOrNull;
    final isAuthorLoading =
        authorState is StorageLoading && author == null;

    final packageManager = ref.watch(packageManagerProvider.notifier);
    final sortedEntries = _sortEntriesUninstalledFirst(entries, packageManager);
    final totalApps = sortedEntries.length;

    return Scaffold(
      body: SafeArea(
        child: Stack(
          children: [
            SingleChildScrollView(
              padding: const EdgeInsets.only(top: 16, bottom: 32),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Stack header
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: _StackHeader(
                      stack: stack,
                      author: author,
                      isAuthorLoading: isAuthorLoading,
                    ),
                  ),
                  if (errorMessage != null) ...[
                    const SizedBox(height: 16),
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      child: Text(
                        errorMessage!,
                        style: context.textTheme.bodyMedium?.copyWith(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    ),
                  ],
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
                  if (sortedEntries.isEmpty)
                    _EmptyAppsPlaceholder()
                  else
                    ...sortedEntries.map((entry) => switch (entry) {
                      _CatalogedStackAppEntry(:final app) => AppCard(
                        app: app,
                        showUpdateArrow: app.hasUpdate,
                      ),
                      _PackageStackAppEntry(:final packageInfo) =>
                        _StackPackageCard(packageInfo: packageInfo),
                    }),
                  // Comments section - hidden for private/encrypted stacks
                  if (stack.content.isEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 24),
                      child: StackCommentsSection(stack: stack),
                    ),
                ],
              ),
            ),
            FloatingOverflowMenu(
              shareUrl: getStackShareUrl(stack),
              publisherPubkey: stack.pubkey,
            ),
          ],
        ),
      ),
    );
  }

  /// Sort so uninstalled entries come first, keeping original order otherwise.
  List<_StackAppEntry> _sortEntriesUninstalledFirst(
    List<_StackAppEntry> entries,
    PackageManager packageManager,
  ) {
    final uninstalled = <_StackAppEntry>[];
    final installed = <_StackAppEntry>[];

    for (final entry in entries) {
      final id = entry.key;
      if (packageManager.isInstalled(id)) {
        installed.add(entry);
      } else {
        uninstalled.add(entry);
      }
    }

    return [...uninstalled, ...installed];
  }
}

/// Fallback card for bare package IDs with no catalog App metadata.
class _StackPackageCard extends StatelessWidget {
  const _StackPackageCard({required this.packageInfo});

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

/// Stack header with name and author
class _StackHeader extends StatelessWidget {
  const _StackHeader({
    required this.stack,
    this.author,
    this.isAuthorLoading = false,
  });

  final AppStack stack;
  final Profile? author;
  final bool isAuthorLoading;

  bool get _isEncrypted => stack.content.isNotEmpty;

  @override
  Widget build(BuildContext context) {
    final subtitleColor = Theme.of(context).colorScheme.onSurfaceVariant;
    final subtitleStyle = Theme.of(
      context,
    ).textTheme.bodySmall?.copyWith(color: subtitleColor);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Stack name with padlock for private stacks
        Row(
          children: [
            Flexible(
              child: Text(
                stack.name ?? stack.identifier,
                style: context.textTheme.headlineMedium,
              ),
            ),
            if (_isEncrypted) ...[
              const SizedBox(width: 8),
              Icon(
                Icons.lock,
                size: 20,
                color: Theme.of(context).colorScheme.onSurface,
              ),
            ],
          ],
        ),
        if (!_isEncrypted) ...[
          const SizedBox(height: 8),
          AuthorContainer(
            profile: author,
            pubkey: stack.pubkey,
            beforeText: 'Published by',
            oneLine: true,
            size: 14,
            isLoading: isAuthorLoading,
            onTap: () => pushUser(context, stack.pubkey),
          ),
        ],
        const SizedBox(height: 4),
        // Metadata row: updated timestamp + private indicator
        Row(
          children: [
            Icon(Icons.update, size: 14, color: subtitleColor),
            const SizedBox(width: 4),
            Text('Updated ', style: subtitleStyle),
            TimeAgoText(stack.event.createdAt, style: subtitleStyle),
            if (_isEncrypted) ...[
              const SizedBox(width: 8),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                decoration: BoxDecoration(
                  color: subtitleColor.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.lock_outline, size: 12, color: subtitleColor),
                    const SizedBox(width: 4),
                    Text(
                      'Private',
                      style: subtitleStyle?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
        if (stack.description case final description?) ...[
          const SizedBox(height: 12),
          Text(
            description,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ],
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
