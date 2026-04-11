import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/updates_service.dart';
import 'package:zapstore/utils/app_query.dart';
import 'package:zapstore/utils/extensions.dart';
import 'app_card.dart';

const _kPageSize = 5;

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

class LatestReleasesState {
  final List<Release> firstPage;
  final List<Release> olderPages;
  final Map<String, App> appsByIdentifier;
  final bool isLoadingMore;
  final bool hasMore;
  final Object? error;

  const LatestReleasesState({
    required this.firstPage,
    required this.olderPages,
    required this.appsByIdentifier,
    required this.isLoadingMore,
    required this.hasMore,
    this.error,
  });

  factory LatestReleasesState.loading() => const LatestReleasesState(
        firstPage: [],
        olderPages: [],
        appsByIdentifier: {},
        isLoadingMore: false,
        hasMore: true,
      );

  bool get isLoading =>
      firstPage.isEmpty && olderPages.isEmpty && error == null;

  List<Release> get allReleases => [...firstPage, ...olderPages];

  LatestReleasesState copyWith({
    List<Release>? firstPage,
    List<Release>? olderPages,
    Map<String, App>? appsByIdentifier,
    bool? isLoadingMore,
    bool? hasMore,
    Object? error,
  }) =>
      LatestReleasesState(
        firstPage: firstPage ?? this.firstPage,
        olderPages: olderPages ?? this.olderPages,
        appsByIdentifier: appsByIdentifier ?? this.appsByIdentifier,
        isLoadingMore: isLoadingMore ?? this.isLoadingMore,
        hasMore: hasMore ?? this.hasMore,
        error: error,
      );
}

// ---------------------------------------------------------------------------
// Notifier
// ---------------------------------------------------------------------------

class LatestReleasesNotifier extends StateNotifier<LatestReleasesState> {
  LatestReleasesNotifier(this.ref) : super(LatestReleasesState.loading()) {
    _subscribe();
  }

  final Ref ref;
  ProviderSubscription<StorageState<Release>>? _sub;

  void _subscribe() {
    _sub?.close();
    _sub = ref.listen(
      query<Release>(
        limit: _kPageSize,
        where: (r) => r.event.getTagSetValues('f').contains('android-arm64-v8a'),
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: true),
        subscriptionPrefix: 'app-latest-releases',
      ),
      (_, next) async {
        if (next is StorageData<Release>) {
          final liveIds = next.models.map((r) => r.id).toSet();
          final filteredOlder =
              state.olderPages.where((r) => !liveIds.contains(r.id)).toList();
          final unresolved = next.models
              .where((r) =>
                  !state.appsByIdentifier.containsKey(r.appIdentifier))
              .toList();
          final apps = await _resolveRelated(unresolved);
          if (mounted) {
            state = state.copyWith(
              firstPage: next.models,
              olderPages: filteredOlder,
              appsByIdentifier: {...state.appsByIdentifier, ...apps},
              error: null,
            );
          }
        } else if (next is StorageError<Release>) {
          state = state.copyWith(error: next.exception);
        }
      },
      fireImmediately: true,
    );
  }

  Future<void> loadMore() async {
    final all = state.allReleases;
    if (state.isLoadingMore || !state.hasMore || all.isEmpty) return;

    final oldest = all
        .map((r) => r.event.createdAt)
        .reduce((a, b) => a.isBefore(b) ? a : b)
        .subtract(const Duration(milliseconds: 1));

    state = state.copyWith(isLoadingMore: true);

    try {
      final storage = ref.read(storageNotifierProvider.notifier);
      final releases = await storage.query(
        RequestFilter<Release>(until: oldest, limit: _kPageSize).toRequest(),
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'app-latest-releases-older',
      );

      final filtered = releases
          .where((r) => r.event.getTagSetValues('f').contains('android-arm64-v8a'))
          .toList();

      if (filtered.isEmpty) {
        state = state.copyWith(isLoadingMore: false, hasMore: false);
        return;
      }

      final apps = await _resolveRelated(filtered);

      final existingIds = all.map((r) => r.id).toSet();
      final unique =
          filtered.where((r) => !existingIds.contains(r.id)).toList();
      state = state.copyWith(
        olderPages: [...state.olderPages, ...unique],
        appsByIdentifier: {...state.appsByIdentifier, ...apps},
        isLoadingMore: false,
        hasMore: releases.length >= _kPageSize,
      );
    } catch (_) {
      state = state.copyWith(isLoadingMore: false);
    }
  }

  /// Parallel fetch: assets by e-tag IDs + apps by i-tag identifiers.
  /// Returns resolved apps keyed by identifier.
  Future<Map<String, App>> _resolveRelated(List<Release> releases) async {
    if (releases.isEmpty) return const {};
    final storage = ref.read(storageNotifierProvider.notifier);

    final assetIds =
        releases.expand((r) => r.event.getTagSetValues('e')).toSet();
    final appIds = releases
        .map((r) => r.appIdentifier)
        .where((id) => id.isNotEmpty)
        .toSet();

    await Future.wait([
      if (assetIds.isNotEmpty)
        storage.query(
          RequestFilter<SoftwareAsset>(
            ids: assetIds,
            tags: {'#f': {'android-arm64-v8a'}},
          ).toRequest(),
          source: const LocalAndRemoteSource(
            relays: 'AppCatalog',
            stream: false,
          ),
          subscriptionPrefix: 'app-latest-releases-assets',
        ),
      if (appIds.isNotEmpty)
        storage.query(
          RequestFilter<App>(
            tags: {'#d': appIds, '#f': {'android-arm64-v8a'}},
          ).toRequest(),
          source: const LocalAndRemoteSource(
            relays: 'AppCatalog',
            stream: false,
          ),
          subscriptionPrefix: 'app-latest-releases-apps',
        ),
    ]);

    if (appIds.isEmpty) return const {};

    final apps = appIds
        .expand((id) => storage.querySync(
              RequestFilter<App>(tags: {'#d': {id}}, limit: 1).toRequest(),
            ))
        .cast<App>()
        .toList();
    await loadAuthors(storage, apps, 'app-latest-releases-authors');

    return {for (final app in apps) app.identifier: app};
  }

  @override
  void dispose() {
    _sub?.close();
    super.dispose();
  }
}

final latestReleasesProvider =
    StateNotifierProvider<LatestReleasesNotifier, LatestReleasesState>((ref) {
  return LatestReleasesNotifier(ref);
});

// ---------------------------------------------------------------------------
// Widget
// ---------------------------------------------------------------------------

class LatestReleasesContainer extends HookConsumerWidget {
  const LatestReleasesContainer({
    super.key,
    this.showSkeleton = false,
    required this.scrollController,
  });

  final bool showSkeleton;
  final ScrollController scrollController;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = showSkeleton ? null : ref.watch(latestReleasesProvider);
    final releases = state?.allReleases ?? [];
    final appsById = state?.appsByIdentifier ?? const {};

    final categorized = ref.watch(categorizedUpdatesProvider);
    final pinnedApps = [
      ...categorized.automaticUpdates,
      ...categorized.manualUpdates,
    ].where((a) => a.isZapstoreApp).toList();
    final pinnedIds = pinnedApps.map((a) => a.identifier).toSet();

    final seenAppIds = <String>{...pinnedIds};
    final dedupedApps = <App>[];
    for (final release in releases) {
      final app = appsById[release.appIdentifier];
      if (app != null && seenAppIds.add(app.identifier)) {
        dedupedApps.add(app);
      }
    }

    final combinedApps = [...pinnedApps, ...dedupedApps];

    useEffect(() {
      if (state == null) return null;
      void onScroll() {
        final s = ref.read(latestReleasesProvider);
        if (s.isLoadingMore || !s.hasMore) return;
        if (scrollController.position.pixels >=
            scrollController.position.maxScrollExtent - 300) {
          ref.read(latestReleasesProvider.notifier).loadMore();
        }
      }
      scrollController.addListener(onScroll);
      return () => scrollController.removeListener(onScroll);
    }, [scrollController, state]);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _buildHeader(context),
        const SizedBox(height: 8),
        if (showSkeleton ||
            state == null ||
            (state.isLoading && combinedApps.isEmpty))
          Column(
            children: List.generate(3, (_) => const AppCard(isLoading: true)),
          )
        else if (state.error != null && combinedApps.isEmpty)
          _buildError(context, state.error.toString())
        else ...[
          ...combinedApps.map(
            (app) => AppCard(app: app, showUpdateArrow: app.hasUpdate),
          ),
          if (state.isLoadingMore)
            const Padding(
              padding: EdgeInsets.all(16.0),
              child: Center(child: CircularProgressIndicator()),
            ),
        ],
        const SizedBox(height: 24),
      ],
    );
  }

  Widget _buildHeader(BuildContext context) {
    final divider = Expanded(
      child: Container(
        height: 1,
        color: Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.2),
      ),
    );
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16.0),
      child: Row(
        children: [
          divider,
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
            child: Text(
              'LATEST RELEASES',
              style: context.textTheme.labelLarge?.copyWith(
                color: Theme.of(context)
                    .colorScheme
                    .onSurface
                    .withValues(alpha: 0.85),
                letterSpacing: 1.5,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
          divider,
        ],
      ),
    );
  }

  Widget _buildError(BuildContext context, String error) {
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
}
