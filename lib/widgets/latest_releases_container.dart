import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/updates_service.dart';
import 'package:zapstore/utils/extensions.dart';
import 'app_card.dart';

const _kPageSize = 5;

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

class LatestReleasesState {
  final List<SoftwareAsset> firstPage;
  final List<SoftwareAsset> olderPages;
  final Map<String, App> appsByAssetId;
  final bool isLoadingMore;
  final bool hasMore;
  final Object? error;

  const LatestReleasesState({
    required this.firstPage,
    required this.olderPages,
    required this.appsByAssetId,
    required this.isLoadingMore,
    required this.hasMore,
    this.error,
  });

  factory LatestReleasesState.loading() => const LatestReleasesState(
    firstPage: [],
    olderPages: [],
    appsByAssetId: {},
    isLoadingMore: false,
    hasMore: true,
  );

  bool get isLoading =>
      firstPage.isEmpty && olderPages.isEmpty && error == null;

  List<SoftwareAsset> get allAssets => [...firstPage, ...olderPages];

  LatestReleasesState copyWith({
    List<SoftwareAsset>? firstPage,
    List<SoftwareAsset>? olderPages,
    Map<String, App>? appsByAssetId,
    bool? isLoadingMore,
    bool? hasMore,
    Object? error,
  }) => LatestReleasesState(
    firstPage: firstPage ?? this.firstPage,
    olderPages: olderPages ?? this.olderPages,
    appsByAssetId: appsByAssetId ?? this.appsByAssetId,
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
/// - The outer `query<SoftwareAsset>(...)` subscription carries an `and:` chain
///   that loads `asset.app`
///   in the background via `NestedQueryManager`. Relationship queries are
///   fire-and-forget; they NEVER block the state update.
/// - The listener reacts to every emission — `StorageLoading(localAssets)`
///   during `awaitingRemote` and `StorageData` once EOSE lands. Local assets
///   are displayed within one frame regardless of network state.
/// - `appsByAssetId` is computed synchronously from local storage
///   (`storage.querySync`) on every emission. As the `and:` relationship
///   queries populate storage in the background, subsequent emissions pick
///   up the newly-available Apps.
///
/// INVARIANTS (see spec/guidelines/INVARIANTS.md):
/// - Local data renders immediately; no await on any network path.
/// - Remote failures degrade gracefully; rows without a resolved App are
///   simply skipped by the consumer widget.
class LatestReleasesNotifier extends StateNotifier<LatestReleasesState> {
  LatestReleasesNotifier(this.ref, {required this.platform})
    : super(LatestReleasesState.loading()) {
    _subscribe();
  }

  final Ref ref;
  final String platform;
  ProviderSubscription<StorageState<SoftwareAsset>>? _sub;

  void _subscribe() {
    _sub?.close();
    _sub = ref.listen(
      query<SoftwareAsset>(
        tags: {
          '#f': {platform},
        },
        limit: _kPageSize,
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: true),
        subscriptionPrefix: 'app-latest-releases',
        and: (asset) => {
          asset.app.query(
            source: const LocalAndRemoteSource(
              relays: 'AppCatalog',
              stream: false,
            ),
          ),
        },
      ),
      (_, next) {
        if (next is StorageError<SoftwareAsset>) {
          state = state.copyWith(error: next.exception);
          return;
        }
        _applyFirstPage(next.models);
      },
      fireImmediately: true,
    );
  }

  void _applyFirstPage(List<SoftwareAsset> assets) {
    final storage = ref.read(storageNotifierProvider.notifier);
    final liveIds = assets.map((asset) => asset.id).toSet();
    final filteredOlder = state.olderPages
        .where((asset) => !liveIds.contains(asset.id))
        .toList();

    final keepAssets = [...assets, ...filteredOlder];

    final appsByAssetId = _resolveAppsFromLocal(storage, keepAssets);

    state = state.copyWith(
      firstPage: assets,
      olderPages: filteredOlder,
      appsByAssetId: appsByAssetId,
      error: null,
    );
  }

  Future<void> loadMore() async {
    final all = state.allAssets;
    if (state.isLoadingMore || !state.hasMore || all.isEmpty) return;

    final oldest = all
        .map((asset) => asset.event.createdAt)
        .reduce((a, b) => a.isBefore(b) ? a : b)
        .subtract(const Duration(milliseconds: 1));

    state = state.copyWith(isLoadingMore: true);

    try {
      final storage = ref.read(storageNotifierProvider.notifier);
      final req = RequestFilter<SoftwareAsset>(
        tags: {
          '#f': {platform},
        },
        until: oldest,
        limit: _kPageSize,
      ).toRequest();

      // Local-first: read whatever is already cached, so offline scroll
      // shows older items immediately without waiting on the relay.
      final localAssets = storage.querySync(req);

      List<SoftwareAsset> assets;
      if (localAssets.isNotEmpty) {
        assets = localAssets;
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
              .catchError((_) => const <SoftwareAsset>[]),
        );
      } else {
        // Nothing local. Try remote with a short timeout; fall back to empty.
        assets = await storage
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
              onTimeout: () => const <SoftwareAsset>[],
            )
            .catchError((_) => const <SoftwareAsset>[]);
      }

      if (assets.isEmpty) {
        state = state.copyWith(isLoadingMore: false, hasMore: false);
        return;
      }

      final existingIds = all.map((asset) => asset.id).toSet();
      final unique = assets
          .where((asset) => !existingIds.contains(asset.id))
          .toList();

      // Fire relationship queries for the older page in the background.
      // The first-page subscription's general-update path will refresh
      // `appsByAssetId` as apps land in storage.
      _hydrateOlderPageRelationships(unique);

      final keepAssets = [...state.firstPage, ...state.olderPages, ...unique];

      state = state.copyWith(
        olderPages: [...state.olderPages, ...unique],
        appsByAssetId: _resolveAppsFromLocal(storage, keepAssets),
        isLoadingMore: false,
        hasMore: assets.length >= _kPageSize,
      );
    } catch (_) {
      state = state.copyWith(isLoadingMore: false);
    }
  }

  /// Synchronously map asset IDs -> parent App using local storage only.
  /// Never awaits network; returns an empty entry for ids not yet in cache.
  Map<String, App> _resolveAppsFromLocal(
    StorageNotifier storage,
    List<SoftwareAsset> assets,
  ) {
    final result = <String, App>{};
    for (final asset in assets) {
      if (asset.appIdentifier.isEmpty) continue;
      final matches = storage.querySync(
        RequestFilter<App>(
          authors: {asset.event.pubkey},
          tags: {
            '#d': {asset.appIdentifier},
            '#f': {platform},
          },
          limit: 1,
        ).toRequest(),
      );
      if (matches.isNotEmpty) {
        result[asset.id] = matches.first;
      }
    }
    return result;
  }

  /// Fire-and-forget relationship hydration for older-page SoftwareAssets.
  /// Results land in local storage and trigger a refresh via the outer
  /// subscription's `handleStorageUpdate`.
  void _hydrateOlderPageRelationships(List<SoftwareAsset> assets) {
    if (assets.isEmpty) return;
    final storage = ref.read(storageNotifierProvider.notifier);

    final appFilters = <RequestFilter<App>>[];
    for (final asset in assets) {
      final filters = asset.app.req?.filters;
      if (filters != null && filters.isNotEmpty) {
        appFilters.add(filters.first);
      }
    }

    if (appFilters.isNotEmpty) {
      unawaited(
        storage.query(
          Request<App>(appFilters),
          source: const LocalAndRemoteSource(
            relays: 'AppCatalog',
            stream: false,
          ),
          subscriptionPrefix: 'app-latest-releases-older-apps',
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
      final platform = ref.read(packageManagerProvider.notifier).platform;
      return LatestReleasesNotifier(ref, platform: platform);
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
    final assets = state?.allAssets ?? [];
    final appsByAssetId = state?.appsByAssetId ?? const {};

    final categorized = ref.watch(categorizedUpdatesProvider);
    final pinnedApps = [
      ...categorized.automaticUpdates,
      ...categorized.manualUpdates,
    ].where((a) => a.isZapstoreApp).toList();
    final pinnedIds = pinnedApps.map((a) => a.identifier).toSet();

    final seenAppIds = <String>{...pinnedIds};
    final dedupedApps = <App>[];
    for (final asset in assets) {
      final app = appsByAssetId[asset.id];
      if (app != null && seenAppIds.add(app.identifier)) {
        dedupedApps.add(app);
      }
    }

    final combinedApps = [...pinnedApps, ...dedupedApps];

    useEffect(() {
      if (state == null) return null;
      void onScroll() {
        if (!scrollController.hasClients) return;
        final s = ref.read(latestReleasesProvider);
        if (s.isLoadingMore || !s.hasMore) return;
        if (scrollController.position.pixels >=
            scrollController.position.maxScrollExtent - 300) {
          ref.read(latestReleasesProvider.notifier).loadMore();
        }
      }

      void checkContentExtent() {
        if (!scrollController.hasClients) return;
        final position = scrollController.position;
        final s = ref.read(latestReleasesProvider);
        if (s.isLoadingMore || !s.hasMore) return;
        if (position.maxScrollExtent <= 0 ||
            position.pixels >= position.maxScrollExtent - 300) {
          ref.read(latestReleasesProvider.notifier).loadMore();
        }
      }

      WidgetsBinding.instance.addPostFrameCallback((_) => checkContentExtent());

      scrollController.addListener(onScroll);
      return () => scrollController.removeListener(onScroll);
    }, [
      scrollController,
      combinedApps.length,
      state?.isLoadingMore,
      state?.hasMore,
    ]);

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
