import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/app_stack_container.dart';

const int _kPageSize = 6;

class StacksScreen extends HookConsumerWidget {
  const StacksScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scrollController = useScrollController();
    final visibleCount = useState(_kPageSize);

    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);
    final platform = ref.read(packageManagerProvider.notifier).platform;

    final appStacksState = ref.watch(
      query<AppStack>(
        limit: 100,
        tags: {
          '#f': {platform},
        },
        source: const LocalAndRemoteSource(relays: 'AppCatalog'),
        subscriptionPrefix: 'app-stacks',
        schemaFilter: appStackEventFilter,
      ),
    );

    final contactListState = signedInPubkey != null
        ? ref.watch(
            query<ContactList>(
              authors: {signedInPubkey},
              limit: 1,
              source: const LocalAndRemoteSource(
                relays: 'social',
                stream: false,
                cachedFor: Duration(hours: 1),
              ),
              subscriptionPrefix: 'app-all-stacks-contacts',
            ),
          )
        : null;

    final followingPubkeys =
        contactListState?.models.firstOrNull?.followingPubkeys;

    final allStacks = appStacksState.models.toList();

    final sortedStacks = _sortStacks(
      allStacks,
      signedInPubkey: signedInPubkey,
      followingPubkeys: followingPubkeys,
    );

    final displayedStacks = sortedStacks.take(visibleCount.value).toList();
    final hasMore = visibleCount.value < sortedStacks.length;

    // Batch load author profiles for displayed stacks
    final authorPubkeys = displayedStacks.map((s) => s.event.pubkey).toSet();
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

    // Batch load preview apps for displayed stacks
    final allPreviewIdentifiers = <String>{};
    final stackPreviewIds = <String, List<String>>{};
    for (final stack in displayedStacks) {
      final ids = getPreviewIdentifiers(stack);
      stackPreviewIds[stack.id] = ids;
      allPreviewIdentifiers.addAll(ids);
    }

    final previewAppsState = allPreviewIdentifiers.isNotEmpty
        ? ref.watch(
            query<App>(
              tags: {'#d': allPreviewIdentifiers},
              source: const LocalAndRemoteSource(
                relays: 'AppCatalog',
                stream: false,
              ),
              subscriptionPrefix: 'app-all-stacks-preview-apps',
            ),
          )
        : null;

    final appsMap = {
      for (final app in previewAppsState?.models ?? <App>[])
        app.identifier: app,
    };

    // Infinite scroll: load more when near bottom
    useEffect(() {
      void onScroll() {
        if (!hasMore) return;
        if (scrollController.position.pixels >=
            scrollController.position.maxScrollExtent - 300) {
          visibleCount.value = (visibleCount.value + _kPageSize).clamp(
            0,
            sortedStacks.length,
          );
        }
      }

      scrollController.addListener(onScroll);
      return () => scrollController.removeListener(onScroll);
    }, [scrollController, sortedStacks.length, hasMore]);

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
          if (appStacksState is StorageLoading && sortedStacks.isEmpty)
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
                  childCount: _kPageSize,
                ),
              ),
            )
          else if (sortedStacks.isEmpty)
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
                  final stack = displayedStacks[index];
                  final author = authorsMap[stack.event.pubkey];
                  return StackCard(
                    stack: stack,
                    author: author,
                    isAuthorLoading: isAuthorsLoading && author == null,
                    previewIdentifiers: stackPreviewIds[stack.id] ?? [],
                    appsMap: appsMap,
                  );
                }, childCount: displayedStacks.length),
              ),
            ),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 16),
              child: hasMore
                  ? const Center(
                      child: SizedBox(
                        width: 24,
                        height: 24,
                        child: CircularProgressIndicator(strokeWidth: 3),
                      ),
                    )
                  : sortedStacks.isNotEmpty
                  ? Center(
                      child: Text(
                        'No more stacks to load',
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color: Colors.grey[600],
                        ),
                      ),
                    )
                  : const SizedBox.shrink(),
            ),
          ),
          const SliverToBoxAdapter(child: SizedBox(height: 16)),
        ],
      ),
    );
  }
}

List<AppStack> _sortStacks(
  List<AppStack> stacks, {
  String? signedInPubkey,
  Set<String>? followingPubkeys,
}) {
  if (signedInPubkey != null &&
      followingPubkeys != null &&
      followingPubkeys.isNotEmpty) {
    final followed = <AppStack>[];
    final others = <AppStack>[];

    for (final stack in stacks) {
      if (followingPubkeys.contains(stack.pubkey)) {
        followed.add(stack);
      } else {
        others.add(stack);
      }
    }

    followed.sort((a, b) => b.event.createdAt.compareTo(a.event.createdAt));
    others.sort((a, b) => b.event.createdAt.compareTo(a.event.createdAt));
    return [...followed, ...others];
  } else {
    final franzapStacks = stacks
        .where((s) => s.pubkey == kFranzapPubkey)
        .toList();
    final otherStacks = stacks
        .where((s) => s.pubkey != kFranzapPubkey)
        .toList();

    franzapStacks.sort(
      (a, b) => b.event.createdAt.compareTo(a.event.createdAt),
    );
    otherStacks.sort((a, b) => b.event.createdAt.compareTo(a.event.createdAt));
    return [...franzapStacks, ...otherStacks];
  }
}
