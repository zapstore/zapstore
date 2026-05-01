import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/updates_service.dart';
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
  }) => LatestReleasesState(
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

/// Local-first, reactive notifier for the "Latest Releases" home section.
///
/// Design:
/// - The outer `query<Release>(...)` subscription carries an `and:` chain that
///   loads `release.app` (+ nested `app.author`) and `release.softwareAssets`
///   in the background via `NestedQueryManager`. Relationship queries are
///   fire-and-forget; they NEVER block the state update.
/// - The listener reacts to every emission — `StorageLoading(localReleases)`
///   during `awaitingRemote` and `StorageData` once EOSE lands. Local Releases
///   are displayed within one frame regardless of network state.
/// - `appsByIdentifier` is computed synchronously from local storage
///   (`storage.querySync`) on every emission. As the `and:` relationship
///   queries populate storage in the background, subsequent emissions pick
///   up the newly-available Apps.
///
/// INVARIANTS (see spec/guidelines/INVARIANTS.md):
/// - Local data renders immediately; no await on any network path.
/// - Remote failures degrade gracefully; rows without a resolved App are
///   simply skipped by the consumer widget.
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
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: true),
        subscriptionPrefix: 'app-latest-releases',
        and: (release) => {
          release.app.query(
            source: const LocalAndRemoteSource(
              relays: 'AppCatalog',
              stream: false,
            ),
            and: (app) => {
              app.author.query(
                source: const LocalAndRemoteSource(
                  relays: {'vertex', 'social'},
                  cachedFor: Duration(hours: 2),
                ),
              ),
            },
          ),
          release.softwareAssets.query(
            source: const LocalAndRemoteSource(
              relays: 'AppCatalog',
              stream: false,
            ),
          ),
        },
      ),
      (_, next) {
        if (next is StorageError<Release>) {
          state = state.copyWith(error: next.exception);
          return;
        }
        _applyFirstPage(next.models);
      },
      fireImmediately: true,
    );
  }

  void _applyFirstPage(List<Release> releases) {
    final storage = ref.read(storageNotifierProvider.notifier);
    final liveIds = releases.map((r) => r.id).toSet();
    final filteredOlder = state.olderPages
        .where((r) => !liveIds.contains(r.id))
        .toList();

    final keepIds = {
      ...releases.map((r) => r.appIdentifier),
      ...filteredOlder.map((r) => r.appIdentifier),
    }..removeWhere((id) => id.isEmpty);

    final appsByIdentifier = _resolveAppsFromLocal(storage, keepIds);

    state = state.copyWith(
      firstPage: releases,
      olderPages: filteredOlder,
      appsByIdentifier: appsByIdentifier,
      error: null,
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
      final req =
          RequestFilter<Release>(until: oldest, limit: _kPageSize).toRequest();

      // Local-first: read whatever is already cached, so offline scroll
      // shows older items immediately without waiting on the relay.
      final localReleases = storage.querySync(req);

      List<Release> releases;
      if (localReleases.isNotEmpty) {
        releases = localReleases;
        // Background hydrate: bring in any older items the relay has that
        // aren't cached yet. Results land via the live subscription's
        // general storage-update path; next scroll picks them up.
        unawaited(
          storage
              .query(
                req,
                source: const LocalAndRemoteSource(
                  relays: 'AppCatalog',
                  stream: false,
                ),
                subscriptionPrefix: 'app-latest-releases-older',
              )
              .catchError((_) => const <Release>[]),
        );
      } else {
        // Nothing local. Try remote with a short timeout; fall back to empty.
        releases = await storage
            .query(
              req,
              source: const LocalAndRemoteSource(
                relays: 'AppCatalog',
                stream: false,
              ),
              subscriptionPrefix: 'app-latest-releases-older',
            )
            .timeout(
              const Duration(seconds: 5),
              onTimeout: () => const <Release>[],
            )
            .catchError((_) => const <Release>[]);
      }

      if (releases.isEmpty) {
        state = state.copyWith(isLoadingMore: false, hasMore: false);
        return;
      }

      final existingIds = all.map((r) => r.id).toSet();
      final unique = releases
          .where((r) => !existingIds.contains(r.id))
          .toList();

      // Fire relationship queries for the older page in the background.
      // The first-page subscription's general-update path will refresh
      // `appsByIdentifier` as apps/authors/assets land in storage.
      _hydrateOlderPageRelationships(unique);

      final keepIds = {
        ...state.firstPage.map((r) => r.appIdentifier),
        ...state.olderPages.map((r) => r.appIdentifier),
        ...unique.map((r) => r.appIdentifier),
      }..removeWhere((id) => id.isEmpty);

      state = state.copyWith(
        olderPages: [...state.olderPages, ...unique],
        appsByIdentifier: _resolveAppsFromLocal(storage, keepIds),
        isLoadingMore: false,
        hasMore: releases.length >= _kPageSize,
      );
    } catch (_) {
      state = state.copyWith(isLoadingMore: false);
    }
  }

  /// Synchronously map app identifiers → App using local storage only.
  /// Never awaits network; returns an empty entry for ids not yet in cache.
  Map<String, App> _resolveAppsFromLocal(
    StorageNotifier storage,
    Set<String> appIds,
  ) {
    final result = <String, App>{};
    for (final id in appIds) {
      final matches = storage.querySync(
        RequestFilter<App>(
          tags: {
            '#d': {id},
          },
          limit: 1,
        ).toRequest(),
      );
      if (matches.isNotEmpty) {
        result[id] = matches.first;
      }
    }
    return result;
  }

  /// Fire-and-forget relationship hydration for older-page Releases.
  /// Results land in local storage and trigger a refresh via the outer
  /// subscription's `handleStorageUpdate`.
  void _hydrateOlderPageRelationships(List<Release> releases) {
    if (releases.isEmpty) return;
    final storage = ref.read(storageNotifierProvider.notifier);

    final appIds = releases
        .map((r) => r.appIdentifier)
        .where((id) => id.isNotEmpty)
        .toSet();
    final assetIds = releases
        .expand((r) => r.event.getTagSetValues('e'))
        .toSet();

    if (appIds.isNotEmpty) {
      unawaited(
        storage.query(
          RequestFilter<App>(tags: {'#d': appIds}).toRequest(),
          source: const LocalAndRemoteSource(
            relays: 'AppCatalog',
            stream: false,
          ),
          subscriptionPrefix: 'app-latest-releases-older-apps',
        ),
      );
    }
    if (assetIds.isNotEmpty) {
      unawaited(
        storage.query(
          RequestFilter<SoftwareAsset>(ids: assetIds).toRequest(),
          source: const LocalAndRemoteSource(
            relays: 'AppCatalog',
            stream: false,
          ),
          subscriptionPrefix: 'app-latest-releases-older-assets',
        ),
      );
    }
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

      void checkInitialLoad() {
        if (!scrollController.hasClients) return;
        final position = scrollController.position;
        final s = ref.read(latestReleasesProvider);
        if (s.isLoadingMore || !s.hasMore) return;
        if (position.maxScrollExtent <= position.viewportDimension) {
          ref.read(latestReleasesProvider.notifier).loadMore();
        }
      }

      WidgetsBinding.instance.addPostFrameCallback((_) => checkInitialLoad());

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
              child: Center(
                child: SizedBox(
                  width: 24,
                  height: 24,
                  child: CircularProgressIndicator(strokeWidth: 5),
                ),
              ),
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
                color: Theme.of(
                  context,
                ).colorScheme.onSurface.withValues(alpha: 0.85),
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
