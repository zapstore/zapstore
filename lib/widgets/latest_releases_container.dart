import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/updates_service.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/paged_subscription_notifier.dart';
import 'app_card.dart';

const _kPageSize = 20;

/// Local-first app feed. Apps are the stable row identity and pagination
/// source; releases/assets may change without creating duplicate app cards.
class LatestReleasesNotifier extends PagedSubscriptionNotifier<App> {
  LatestReleasesNotifier(super.ref);

  ProviderSubscription<StorageState<App>>? _sub;

  @override
  int get pageSize => _kPageSize;

  @override
  void startSubscription() {
    _sub?.close();
    _sub = ref.listen(
      query<App>(
        limit: pageSize,
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: true),
        subscriptionPrefix: 'app-latest-releases',
        and: (app) => {
          app.latestAsset.query(
            source: const LocalAndRemoteSource(
              relays: 'AppCatalog',
              stream: false,
            ),
          ),
          app.latestRelease.query(
            source: const LocalAndRemoteSource(
              relays: 'AppCatalog',
              stream: false,
            ),
            and: (release) => {
              release.latestMetadata.query(
                source: const LocalAndRemoteSource(
                  relays: 'AppCatalog',
                  stream: false,
                ),
              ),
              release.latestAsset.query(
                source: const LocalAndRemoteSource(
                  relays: 'AppCatalog',
                  stream: false,
                ),
              ),
            },
          ),
        },
      ),
      (_, next) => updateFirstPage(next),
      fireImmediately: true,
    );
  }

  @override
  Future<({List<App> items, int count})> fetchOlderPage(DateTime until) async {
    final storage = ref.read(storageNotifierProvider.notifier);
    final request = RequestFilter<App>(
      until: until,
      limit: pageSize,
    ).toRequest();

    final local = storage.querySync(request);
    if (local.isNotEmpty) {
      unawaited(_hydrateRelationships(local));
      unawaited(
        storage
            .query(
              request,
              source: const LocalAndRemoteSource(
                relays: 'AppCatalog',
                stream: false,
              ),
              subscriptionPrefix: 'app-latest-releases-older',
            )
            .catchError((_) => const <App>[]),
      );
      return (items: local, count: local.length);
    }

    final remote = await storage
        .query(
          request,
          source: const LocalAndRemoteSource(
            relays: 'AppCatalog',
            stream: false,
          ),
          subscriptionPrefix: 'app-latest-releases-older',
        )
        .timeout(const Duration(seconds: 5), onTimeout: () => const <App>[])
        .catchError((_) => const <App>[]);
    unawaited(_hydrateRelationships(remote));
    return (items: remote, count: remote.length);
  }

  /// VersionPillWidget is local-only, so preload the data it displays here.
  Future<void> _hydrateRelationships(List<App> apps) async {
    if (apps.isEmpty) return;

    final storage = ref.read(storageNotifierProvider.notifier);
    const source = LocalAndRemoteSource(relays: 'AppCatalog', stream: false);
    final assetFilters = apps
        .map((app) => app.latestAsset.req?.filters.firstOrNull)
        .nonNulls
        .toList();
    final releaseFilters = apps
        .map((app) => app.latestRelease.req?.filters.firstOrNull)
        .nonNulls
        .toList();

    try {
      if (assetFilters.isNotEmpty) {
        await storage.query(
          Request<SoftwareAsset>(assetFilters),
          source: source,
          subscriptionPrefix: 'app-latest-releases-assets',
        );
      }
      if (releaseFilters.isEmpty) return;

      final releases = await storage.query(
        Request<Release>(releaseFilters),
        source: source,
        subscriptionPrefix: 'app-latest-releases-releases',
      );
      final metadataFilters = releases
          .map((release) => release.latestMetadata.req?.filters.firstOrNull)
          .nonNulls
          .toList();
      final releaseAssetFilters = releases
          .map((release) => release.latestAsset.req?.filters.firstOrNull)
          .nonNulls
          .toList();

      if (metadataFilters.isNotEmpty) {
        await storage.query(
          Request<FileMetadata>(metadataFilters),
          source: source,
          subscriptionPrefix: 'app-latest-releases-metadata',
        );
      }
      if (releaseAssetFilters.isNotEmpty) {
        await storage.query(
          Request<SoftwareAsset>(releaseAssetFilters),
          source: source,
          subscriptionPrefix: 'app-latest-releases-release-assets',
        );
      }
    } catch (_) {
      // The feed remains usable with locally-cached relationship data.
    }
  }

  @override
  String getId(App app) => app.id;

  @override
  DateTime getCreatedAt(App app) => app.event.createdAt;

  @override
  void dispose() {
    _sub?.close();
    super.dispose();
  }
}

final latestReleasesProvider =
    StateNotifierProvider<LatestReleasesNotifier, PagedState<App>>((ref) {
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
    final apps = state?.combined ?? const <App>[];

    final categorized = ref.watch(categorizedUpdatesProvider);
    final pinnedApps = [
      ...categorized.automaticUpdates,
      ...categorized.manualUpdates,
    ].where((a) => a.isZapstoreApp).toList();
    final pinnedIds = pinnedApps.map((a) => a.identifier).toSet();

    final seenAppIds = <String>{...pinnedIds};
    final dedupedApps = <App>[];
    for (final app in apps) {
      if (seenAppIds.add(app.identifier)) {
        dedupedApps.add(app);
      }
    }

    final combinedApps = [...pinnedApps, ...dedupedApps];
    final lastAutoFillLength = useRef<int?>(null);

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

      scrollController.addListener(onScroll);
      return () => scrollController.removeListener(onScroll);
    }, [scrollController, state != null]);

    useEffect(() {
      if (state == null ||
          state.firstPage is StorageLoading ||
          state.isLoadingMore ||
          !state.hasMore ||
          lastAutoFillLength.value == apps.length) {
        return null;
      }
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!scrollController.hasClients) {
          return;
        }
        final position = scrollController.position;
        if (position.pixels < position.maxScrollExtent - 300) {
          return;
        }
        lastAutoFillLength.value = apps.length;
        ref.read(latestReleasesProvider.notifier).loadMore();
      });
      return null;
    }, [apps.length, state?.isLoadingMore, state?.hasMore]);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _buildHeader(context),
        const SizedBox(height: 8),
        if (showSkeleton ||
            state == null ||
            (state.firstPage is StorageLoading && combinedApps.isEmpty))
          Column(
            children: List.generate(3, (_) => const AppCard(isLoading: true)),
          )
        else if (state.firstPage case StorageError(
          :final exception,
        ) when combinedApps.isEmpty)
          _buildError(context, exception.toString())
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
