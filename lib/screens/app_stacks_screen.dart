import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/paged_subscription_notifier.dart';
import 'package:zapstore/widgets/app_stack_container.dart';

// ---------------------------------------------------------------------------
// Notifier & provider
// ---------------------------------------------------------------------------

class StacksNotifier extends PagedSubscriptionNotifier<AppStack> {
  StacksNotifier(super.ref, {required this.platform});

  @override
  int get pageSize => 20;

  final String platform;
  ProviderSubscription<StorageState<AppStack>>? _sub;

  Map<String, Set<String>> get _tags => {
    '#f': {platform},
    '#h': {kZapstoreCommunityPubkey},
  };

  @override
  void startSubscription() {
    _sub?.close();
    _sub = ref.listen(
      query<AppStack>(
        tags: _tags,
        until: DateTime.now(),
        limit: pageSize,
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: true),
        subscriptionPrefix: 'app-stacks',
      ),
      (_, next) => updateFirstPage(next),
      fireImmediately: true,
    );
  }

  @override
  Future<({List<AppStack> items, int count})> fetchOlderPage(
    DateTime until,
  ) async {
    final storage = ref.read(storageNotifierProvider.notifier);
    final items = await storage.query(
      RequestFilter<AppStack>(
        tags: _tags,
        until: until,
        limit: pageSize,
      ).toRequest(),
      source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
      subscriptionPrefix: 'app-stacks-older',
    );
    return (items: items, count: items.length);
  }

  @override
  String getId(AppStack item) => item.id;

  @override
  DateTime getCreatedAt(AppStack item) => item.event.createdAt;

  @override
  void dispose() {
    _sub?.close();
    super.dispose();
  }
}

final stacksProvider =
    StateNotifierProvider.autoDispose<StacksNotifier, PagedState<AppStack>>((
      ref,
    ) {
      final platform = ref.read(packageManagerProvider.notifier).platform;
      return StacksNotifier(ref, platform: platform);
    });

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Returns true if the stack is public (no encrypted content) and is missing
/// required tags: h (community) or f (platform).
bool stackNeedsMigration(AppStack stack, String platform) {
  // Skip private/encrypted stacks
  if (stack.content.isNotEmpty) return false;

  // Skip the user's saved-apps bookmark stack
  if (stack.identifier == kAppBookmarksIdentifier) return false;

  final hTags = stack.event.getTagSetValues('h');
  final fTags = stack.event.getTagSetValues('f');

  final hasH = hTags.contains(kZapstoreCommunityPubkey);
  final hasF = fTags.contains(platform);

  return !hasH || !hasF;
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

class AppStacksScreen extends HookConsumerWidget {
  const AppStacksScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scrollController = useScrollController();
    final platform = ref.read(packageManagerProvider.notifier).platform;

    final state = ref.watch(stacksProvider);
    final items = state.combined;

    // Query signed-in user's stacks that may need migration
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);
    final userStacksState = signedInPubkey != null
        ? ref.watch(
            query<AppStack>(
              authors: {signedInPubkey},
              where: (s) => stackNeedsMigration(s, platform),
              source: LocalAndRemoteSource(
                relays: {'social', 'AppCatalog'},
                stream: false,
              ),
              subscriptionPrefix: 'app-user-stacks-migration',
            ),
          )
        : null;

    final unmigrated = userStacksState?.models.toList() ?? [];

    final authorPubkeys = items.map((s) => s.event.pubkey).toSet();
    final authorsState = authorPubkeys.isNotEmpty
        ? ref.watch(
            query<Profile>(
              authors: authorPubkeys,
              source: const LocalAndRemoteSource(
                relays: {'social', 'vertex'},
                cachedFor: Duration(hours: 2),
              ),
              subscriptionPrefix: 'app-all-stacks-authors',
            ),
          )
        : null;
    final authorsMap = {
      for (final profile in authorsState?.models ?? <Profile>[])
        profile.pubkey: profile,
    };
    final isAuthorsLoading = authorsState is StorageLoading;

    final allPreviewIds = <String>{};
    final stackPreviewIds = <String, List<String>>{};
    for (final stack in items) {
      final ids = getPreviewAddressableIds(stack);
      stackPreviewIds[stack.id] = ids;
      allPreviewIds.addAll(ids);
    }

    final (:authors, :identifiers) = decomposeAddressableIds(allPreviewIds);

    final previewAppsState = allPreviewIds.isNotEmpty
        ? ref.watch(
            query<App>(
              authors: authors,
              tags: {'#d': identifiers},
              source: const LocalAndRemoteSource(
                relays: 'AppCatalog',
                stream: false,
              ),
              subscriptionPrefix: 'app-all-stacks-preview-apps',
            ),
          )
        : null;

    final appsMap = {
      for (final app in previewAppsState?.models ?? <App>[]) app.id: app,
    };

    final isInitialLoading = state.firstPage is StorageLoading && items.isEmpty;

    useEffect(() {
      void onScroll() {
        if (!scrollController.hasClients) return;
        final s = ref.read(stacksProvider);
        if (s.isLoadingMore || !s.hasMore) return;
        final pos = scrollController.position;
        if (pos.pixels >= pos.maxScrollExtent - 300) {
          ref.read(stacksProvider.notifier).loadMore();
        }
      }

      scrollController.addListener(onScroll);
      return () => scrollController.removeListener(onScroll);
    }, [scrollController]);

    useEffect(() {
      if (state.firstPage is StorageLoading ||
          state.isLoadingMore ||
          !state.hasMore) {
        return null;
      }
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!scrollController.hasClients) return;
        final pos = scrollController.position;
        if (pos.maxScrollExtent <= 0) {
          ref.read(stacksProvider.notifier).loadMore();
        }
      });
      return null;
    }, [state.combined.length, state.isLoadingMore]);

    return Scaffold(
      body: CustomScrollView(
        controller: scrollController,
        slivers: [
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
              child: Text(
                'App Stacks',
                style: context.textTheme.headlineMedium,
              ),
            ),
          ),
          if (unmigrated.isNotEmpty)
            SliverToBoxAdapter(
              child: _MigrationBanner(stacks: unmigrated, platform: platform),
            ),
          if (isInitialLoading)
            SliverPadding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              sliver: SliverGrid(
                gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: 2,
                  crossAxisSpacing: 10,
                  mainAxisSpacing: 10,
                  childAspectRatio: 1.15,
                ),
                delegate: SliverChildBuilderDelegate(
                  (context, index) => SkeletonizerConfig(
                    data: AppColors.getSkeletonizerConfig(
                      Theme.of(context).brightness,
                    ),
                    child: const Skeletonizer(child: StackCardSkeleton()),
                  ),
                  childCount: 10,
                ),
              ),
            )
          else if (items.isEmpty)
            SliverFillRemaining(
              child: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      Icons.layers_outlined,
                      size: 48,
                      color: Colors.grey[400],
                    ),
                    const SizedBox(height: 16),
                    Text(
                      'No stacks found',
                      style: context.textTheme.titleMedium,
                    ),
                  ],
                ),
              ),
            )
          else
            SliverPadding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              sliver: SliverGrid(
                gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: 2,
                  crossAxisSpacing: 10,
                  mainAxisSpacing: 10,
                  childAspectRatio: 1.15,
                ),
                delegate: SliverChildBuilderDelegate((context, index) {
                  final stack = items[index];
                  final author = authorsMap[stack.event.pubkey];
                  return StackCard(
                    stack: stack,
                    author: author,
                    isAuthorLoading: isAuthorsLoading && author == null,
                    previewIdentifiers: stackPreviewIds[stack.id] ?? [],
                    appsMap: appsMap,
                    showAuthor: true,
                  );
                }, childCount: items.length),
              ),
            ),
          if (state.isLoadingMore)
            const SliverToBoxAdapter(
              child: Padding(
                padding: EdgeInsets.all(16.0),
                child: Center(child: CircularProgressIndicator()),
              ),
            ),
          const SliverToBoxAdapter(child: SizedBox(height: 16)),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Migration Banner
// ---------------------------------------------------------------------------

class _MigrationBanner extends HookConsumerWidget {
  const _MigrationBanner({required this.stacks, required this.platform});

  final List<AppStack> stacks;
  final String platform;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isLoading = useState(false);
    final progressCount = useState(0);

    // Query authors for the pending stacks
    final authorPubkeys = stacks.map((s) => s.event.pubkey).toSet();
    final authorsState = authorPubkeys.isNotEmpty
        ? ref.watch(
            query<Profile>(
              authors: authorPubkeys,
              source: const LocalAndRemoteSource(
                relays: {'social', 'vertex'},
                cachedFor: Duration(hours: 2),
              ),
              subscriptionPrefix: 'app-migration-stacks-authors',
            ),
          )
        : null;
    final authorsMap = {
      for (final profile in authorsState?.models ?? <Profile>[])
        profile.pubkey: profile,
    };

    final allPreviewIds = <String>{};
    final stackPreviewIds = <String, List<String>>{};
    for (final stack in stacks) {
      final ids = getPreviewAddressableIds(stack);
      stackPreviewIds[stack.id] = ids;
      allPreviewIds.addAll(ids);
    }

    final (:authors, :identifiers) = decomposeAddressableIds(allPreviewIds);

    // Query preview apps
    final previewAppsState = allPreviewIds.isNotEmpty
        ? ref.watch(
            query<App>(
              authors: authors,
              tags: {'#d': identifiers},
              source: const LocalAndRemoteSource(
                relays: 'AppCatalog',
                stream: false,
              ),
              subscriptionPrefix: 'app-migration-stacks-preview-apps',
            ),
          )
        : null;
    final appsMap = {
      for (final app in previewAppsState?.models ?? <App>[]) app.id: app,
    };

    return Container(
      margin: const EdgeInsets.fromLTRB(12, 0, 12, 12),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Theme.of(
          context,
        ).colorScheme.primaryContainer.withValues(alpha: 0.4),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.3),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                Icons.info_outline,
                size: 20,
                color: Theme.of(context).colorScheme.primary,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  'You have ${stacks.length} stack${stacks.length == 1 ? '' : 's'} that need${stacks.length == 1 ? 's' : ''} updating',
                  style: context.textTheme.titleSmall?.copyWith(
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            'Update your stacks so they appear in the community feed. '
            'Coming soon: ability to delete them.',
            style: context.textTheme.bodySmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 16),
          GridView.builder(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 2,
              crossAxisSpacing: 10,
              mainAxisSpacing: 10,
              childAspectRatio: 1.15,
            ),
            itemCount: stacks.length,
            itemBuilder: (context, index) {
              final stack = stacks[index];
              final author = authorsMap[stack.event.pubkey];
              return StackCard(
                stack: stack,
                author: author,
                isAuthorLoading:
                    authorsState is StorageLoading && author == null,
                previewIdentifiers: stackPreviewIds[stack.id] ?? [],
                appsMap: appsMap,
                showAuthor: false,
              );
            },
          ),
          const SizedBox(height: 16),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: isLoading.value
                  ? null
                  : () => _migrateStacks(
                      context,
                      ref,
                      isLoading,
                      progressCount,
                      stacks,
                    ),
              icon: isLoading.value
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.upgrade, size: 18),
              label: Text(
                isLoading.value
                    ? 'Updating ${progressCount.value}/${stacks.length}...'
                    : 'Update stacks',
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _migrateStacks(
    BuildContext context,
    WidgetRef ref,
    ValueNotifier<bool> isLoading,
    ValueNotifier<int> progressCount,
    List<AppStack> pendingStacks,
  ) async {
    isLoading.value = true;
    progressCount.value = 0;

    try {
      final signer = ref.read(Signer.activeSignerProvider);
      if (signer == null) {
        if (context.mounted) {
          context.showError('Sign in required');
        }
        return;
      }

      for (final stack in pendingStacks) {
        final appIds = stack.event.getTagSetValues('a').toList();

        final partialStack = PartialAppStack(
          name: stack.name ?? stack.identifier,
          identifier: stack.identifier,
          description: stack.description,
          platform: platform,
        );
        partialStack.addCommunityKey(kZapstoreCommunityPubkey);

        for (final appId in appIds) {
          partialStack.addApp(appId);
        }

        // Add one second so relays accept the replacement
        partialStack.event.createdAt = stack.event.createdAt.add(
          const Duration(seconds: 1),
        );

        final signedStack = await partialStack.signWith(signer);
        await ref.storage.save({signedStack});
        await ref.storage.publish({signedStack}, relays: {'AppCatalog'});

        progressCount.value++;
      }

      if (context.mounted) {
        context.showInfo(
          'Updated ${progressCount.value} stack${progressCount.value == 1 ? '' : 's'}',
        );
      }
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to update stacks', technicalDetails: '$e');
      }
    } finally {
      isLoading.value = false;
    }
  }
}
