import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
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

    // Infinite scroll: trigger loadMore when near bottom
    useEffect(() {
      void onScroll() {
        final state = ref.read(latestReleasesProvider);
        if (state.isLoadingMore || !state.hasMore) return;

        // Trigger load when 300px from bottom
        if (scrollController.position.pixels >=
            scrollController.position.maxScrollExtent - 300) {
          ref.read(latestReleasesProvider.notifier).loadMore();
        }
      }

      scrollController.addListener(onScroll);
      return () => scrollController.removeListener(onScroll);
    }, [scrollController]);

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
            children: [
              Expanded(
                child: Container(
                  height: 1,
                  color: Theme.of(
                    context,
                  ).colorScheme.onSurface.withValues(alpha: 0.2),
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Text(
                  'LATEST RELEASES',
                  style: context.textTheme.labelLarge?.copyWith(
                    color: Theme.of(
                      context,
                    ).colorScheme.onSurface.withValues(alpha: 0.85),
                    letterSpacing: 1.5,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
              Expanded(
                child: Container(
                  height: 1,
                  color: Theme.of(
                    context,
                  ).colorScheme.onSurface.withValues(alpha: 0.2),
                ),
              ),
            ],
          ),
        ),

        const SizedBox(height: 8),

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
            combinedApps,
            state.isLoadingMore,
            state.hasMore,
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
    List<App> apps,
    bool isLoadingMore,
    bool hasMoreApps,
  ) {
    return Column(
      children: [
        ...apps.map((app) {
          return AppCard(app: app, showUpdateArrow: app.hasUpdate);
        }),

        const SizedBox(height: 10),

        // Show loading indicator when fetching more
        if (isLoadingMore)
          Padding(
            padding: const EdgeInsets.all(16.0),
            child: Center(
              child: SizedBox(
                width: 24,
                height: 24,
                child: CircularProgressIndicator(
                  strokeWidth: 3,
                  color: Theme.of(context).colorScheme.primary,
                ),
              ),
            ),
          )
        else if (!hasMoreApps && apps.isNotEmpty)
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
          padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 12),
          child: Row(
            children: [
              Expanded(
                child: Container(
                  height: 1,
                  color: Theme.of(
                    context,
                  ).colorScheme.onSurface.withValues(alpha: 0.2),
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Text(
                  'LATEST RELEASES',
                  style: context.textTheme.labelLarge?.copyWith(
                    color: Theme.of(
                      context,
                    ).colorScheme.onSurface.withValues(alpha: 0.35),
                    letterSpacing: 1.5,
                    fontWeight: FontWeight.w300,
                  ),
                ),
              ),
              Expanded(
                child: Container(
                  height: 1,
                  color: Theme.of(
                    context,
                  ).colorScheme.onSurface.withValues(alpha: 0.2),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 8),
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
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: true),
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
  /// Note: Profiles are now loaded reactively via `query<Profile>` in individual widgets
  Future<void> _loadRelationshipsFor(List<App> appsPage) async {
    // No-op: profiles are now loaded reactively via `query<Profile>` with caching
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
        source: const LocalAndRemoteSource(stream: false),
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
    } catch (e) {
      state = state.copyWith(isLoadingMore: false);
      rethrow;
    }
  }

  @override
  void dispose() {
    _sub?.close();
    super.dispose();
  }
}
