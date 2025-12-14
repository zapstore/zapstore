import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:async_button_builder/async_button_builder.dart';
import 'package:zapstore/services/profile_service.dart';
import 'package:zapstore/utils/extensions.dart';
import 'app_card.dart';

class LatestReleasesContainer extends HookConsumerWidget {
  const LatestReleasesContainer({
    super.key,
    required this.scrollController,
    this.showSkeleton = false,
  });

  final ScrollController scrollController;
  final bool showSkeleton;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (showSkeleton) {
      return _buildSkeletonState(context);
    }

    final state = ref.watch(latestReleasesProvider);
    final storage = state.storage;

    // Combine live storage models (newest) with paged older apps
    final combinedApps = [...storage.models, ...state.olderApps];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16.0),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text('Latest Releases', style: context.textTheme.headlineSmall),
              if (storage.models.isNotEmpty)
                TextButton(
                  onPressed: () {
                    scrollController.animateTo(
                      scrollController.position.maxScrollExtent,
                      duration: const Duration(milliseconds: 300),
                      curve: Curves.easeInOut,
                    );
                  },
                  child: const Text('See more'),
                ),
            ],
          ),
        ),

        const SizedBox(height: 12),

        if (storage is StorageLoading<App> || storage.models.isEmpty)
          Column(
            children: List.generate(
              3,
              (index) => const AppCard(isLoading: true),
            ),
          )
        else if (storage is StorageError<App>)
          _buildErrorState(context, storage.exception.toString())
        else
          _buildAppsList(
            context,
            ref,
            combinedApps,
            state.isLoadingMore,
            state.hasMore,
            ref.read(latestReleasesProvider.notifier).loadMore,
          ),
      ],
    );
  }

  Widget _buildErrorState(BuildContext context, String error) {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Center(
        child: Column(
          children: [
            Icon(Icons.error_outline, size: 48, color: Colors.red[400]),
            const SizedBox(height: 16),
            Text('Error loading apps', style: context.textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(
              error,
              style: context.textTheme.bodySmall,
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAppsList(
    BuildContext context,
    WidgetRef ref,
    List<App> apps,
    bool isLoadingMore,
    bool hasMoreApps,
    Future<void> Function() onLoadMore,
  ) {
    return Column(
      children: [
        ...apps.map((app) {
          final releaseAuthor = app.latestRelease.value?.author.value;
          return AppCard(
            app: app,
            author: releaseAuthor,
            showUpdateArrow: app.hasUpdate,
          );
        }),

        const SizedBox(height: 10),

        if (hasMoreApps)
          AsyncButtonBuilder(
            loadingWidget: SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(
                strokeWidth: 3,
                color: Theme.of(context).colorScheme.onSurface,
                backgroundColor: Theme.of(
                  context,
                ).colorScheme.surfaceContainerHighest,
              ),
            ),
            onPressed: isLoadingMore ? null : onLoadMore,
            builder: (context, child, callback, state) {
              return Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: SizedBox(
                  width: double.infinity,
                  child: FilledButton(
                    onPressed: callback,
                    style: FilledButton.styleFrom(
                      backgroundColor: Theme.of(
                        context,
                      ).colorScheme.surfaceContainerHighest,
                      foregroundColor: Theme.of(context).colorScheme.onSurface,
                      padding: const EdgeInsets.symmetric(vertical: 12),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(8),
                      ),
                    ),
                    child: child,
                  ),
                ),
              );
            },
            child: Text(isLoadingMore ? 'Loading...' : 'Load more'),
          )
        else
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Center(
              child: Text(
                'No more apps to load',
                style: Theme.of(
                  context,
                ).textTheme.bodyMedium?.copyWith(color: Colors.grey[600]),
              ),
            ),
          ),

        const SizedBox(height: 24),
      ],
    );
  }

  static Widget _buildSkeletonState(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16.0),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              // Match actual content: headlineSmall, not headlineMedium
              Text('Latest Releases', style: context.textTheme.headlineSmall),
              // Match actual content: show button but make it invisible to reserve space
              Opacity(
                opacity: 0,
                child: TextButton(
                  onPressed: null,
                  child: const Text('See more'),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        // Show 3 skeleton app cards
        Column(children: List.generate(3, (index) => AppCard(isLoading: true))),
      ],
    );
  }
}

// Provider and Notifier for latest releases pagination and relationship loading

class LatestReleasesState {
  final StorageState<App> storage;
  final List<App> olderApps;
  final bool isLoadingMore;
  final bool hasMore;

  const LatestReleasesState({
    required this.storage,
    required this.olderApps,
    required this.isLoadingMore,
    required this.hasMore,
  });

  factory LatestReleasesState.initial() => LatestReleasesState(
    storage: StorageLoading<App>(const []),
    olderApps: const [],
    isLoadingMore: false,
    hasMore: true,
  );

  LatestReleasesState copyWith({
    StorageState<App>? storage,
    List<App>? olderApps,
    bool? isLoadingMore,
    bool? hasMore,
  }) {
    return LatestReleasesState(
      storage: storage ?? this.storage,
      olderApps: olderApps ?? this.olderApps,
      isLoadingMore: isLoadingMore ?? this.isLoadingMore,
      hasMore: hasMore ?? this.hasMore,
    );
  }
}

final latestReleasesProvider =
    StateNotifierProvider<LatestReleasesNotifier, LatestReleasesState>(
      (ref) => LatestReleasesNotifier(ref),
    );

class LatestReleasesNotifier extends StateNotifier<LatestReleasesState> {
  LatestReleasesNotifier(this.ref) : super(LatestReleasesState.initial()) {
    _startQuery();
  }

  final Ref ref;
  static const int _pageSize = 10;
  ProviderSubscription<StorageState<App>>? _sub;
  // We keep a fixed live head window from query(); older pages are appended

  void _startQuery() {
    _sub?.close();

    _sub = ref.listen<StorageState<App>>(
      query<App>(
        limit: _pageSize,
        tags: {
          '#f': {'android-arm64-v8a'},
        },
        and: (app) => {
          app.latestRelease,
          app.latestRelease.value?.latestMetadata,
        },
        // NOTE: It must stream=true
        source: const LocalAndRemoteSource(
          relays: 'AppCatalog',
          stream: true,
          background: true,
        ),
        andSource: const LocalAndRemoteSource(
          relays: 'AppCatalog',
          stream: false,
        ), // No streaming for relationships
        subscriptionPrefix: 'latest',
      ),
      (previous, next) async {
        // Always mirror storage state and ensure olderApps don't duplicate the live head
        if (next is StorageData<App>) {
          final liveIds = next.models.map((a) => a.id).toSet();
          final filteredOlder = state.olderApps
              .where((a) => !liveIds.contains(a.id))
              .toList();
          state = state.copyWith(storage: next, olderApps: filteredOlder);
        } else {
          state = state.copyWith(storage: next);
        }

        if (next is StorageError<App>) {
          state = state.copyWith(isLoadingMore: false);
        }
      },
      fireImmediately: true,
    );
  }

  /// Fetch authors for a page of apps (used during pagination)
  Future<void> _loadRelationshipsFor(List<App> appsPage) async {
    final releases = appsPage
        .map((a) => a.latestRelease.value)
        .whereType<Release>()
        .toList();

    if (releases.isEmpty) return;

    // Only need to query release authors from 'social' group (different relay group)
    final authorPubkeys = releases.map((r) => r.event.pubkey).toSet();
    if (authorPubkeys.isNotEmpty) {
      await ref.read(profileServiceProvider).fetchProfiles(authorPubkeys);
    }
  }

  Future<void> loadMore() async {
    final live = state.storage.models;
    final combined = [...live, ...state.olderApps];
    if (state.isLoadingMore || !state.hasMore || combined.isEmpty) return;

    final oldest = combined
        .map((a) => a.event.createdAt)
        .reduce((a, b) => a.isBefore(b) ? a : b)
        .subtract(const Duration(milliseconds: 1));

    state = state.copyWith(isLoadingMore: true);

    try {
      // Fetch older apps
      final olderPage = await ref.storage.query(
        RequestFilter<App>(
          until: oldest,
          limit: _pageSize,
          tags: {
            '#f': {'android-arm64-v8a'},
          },
        ).toRequest(),
        source: const LocalAndRemoteSource(stream: false, background: false),
      );

      if (olderPage.isNotEmpty) {
        // Load relationships: releases and their file metadata (same relay group)
        final releases = await ref.storage.query(
          Request<Release>(
            olderPage
                .map((app) => app.latestRelease.req?.filters.firstOrNull)
                .nonNulls
                .toList(),
          ),
          source: const RemoteSource(stream: false),
        );

        // Load file metadata for the releases
        if (releases.isNotEmpty) {
          await ref.storage.query(
            Request<FileMetadata>(
              releases
                  .map((r) => r.latestMetadata.req?.filters.firstOrNull)
                  .nonNulls
                  .toList(),
            ),
            source: const RemoteSource(stream: false),
          );
        }

        // Load release authors from different relay group (social)
        await _loadRelationshipsFor(olderPage);

        final existingIds = combined.map((a) => a.id).toSet();
        final uniqueOlder = olderPage
            .where((a) => !existingIds.contains(a.id))
            .toList();
        state = state.copyWith(
          olderApps: [...state.olderApps, ...uniqueOlder],
          isLoadingMore: false,
          hasMore: olderPage.length >= _pageSize,
        );
      } else {
        state = state.copyWith(isLoadingMore: false, hasMore: false);
      }
    } catch (_) {
      state = state.copyWith(isLoadingMore: false);
    }
  }

  @override
  void dispose() {
    _sub?.close();
    super.dispose();
  }
}
