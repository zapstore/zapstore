import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/updates_service.dart';
import 'package:zapstore/utils/app_query.dart';
import 'package:zapstore/utils/extensions.dart';
import 'app_card.dart';

const _pageSize = 5;

final latestReleasesProvider = appAssetsQuery(
  tags: {'#f': {'android-arm64-v8a'}},
  limit: _pageSize,
  source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: true),
  subscriptionPrefix: 'app-latest',
);

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
    // Don't query storage until initialization completes
    final state = showSkeleton ? null : ref.watch(latestReleasesProvider);

    final olderAssets = useState(<SoftwareAsset>[]);
    final isLoadingMore = useState(false);
    final hasMore = useState(true);

    final categorized = ref.watch(categorizedUpdatesProvider);
    final pinnedApps = [
      ...categorized.automaticUpdates,
      ...categorized.manualUpdates,
    ].where((a) => a.isZapstoreApp).toList();
    final pinnedIds = pinnedApps.map((a) => a.id).toSet();

    final seenIds = <String>{};
    final firstPageApps = state?.models
        .map((asset) => asset.app.value)
        .nonNulls
        .where((app) => !pinnedIds.contains(app.id) && seenIds.add(app.id))
        .toList() ?? [];

    final olderApps = olderAssets.value
        .map((asset) => asset.app.value)
        .nonNulls
        .where((app) => !pinnedIds.contains(app.id) && seenIds.add(app.id))
        .toList();

    final combinedApps = [...pinnedApps, ...firstPageApps, ...olderApps];

    useEffect(() {
      if (state == null) return null;
      void onScroll() {
        if (isLoadingMore.value || !hasMore.value) return;
        if (scrollController.position.pixels >=
            scrollController.position.maxScrollExtent - 300) {
          _loadMore(ref, state, olderAssets, isLoadingMore, hasMore);
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
        if (showSkeleton || state == null ||
            (state is StorageLoading<SoftwareAsset> && combinedApps.isEmpty) ||
            (state.models.isNotEmpty && combinedApps.isEmpty))
          Column(children: List.generate(3, (_) => const AppCard(isLoading: true)))
        else if (state is StorageError<SoftwareAsset>)
          _buildError(context, state.exception.toString())
        else ...[
          ...combinedApps.map((app) => AppCard(app: app, showUpdateArrow: app.hasUpdate)),
          if (isLoadingMore.value)
            Padding(
              padding: const EdgeInsets.all(16.0),
              child: Center(
                child: CircularProgressIndicator(),
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
                color: Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.85),
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
            Text(error, style: context.textTheme.bodySmall, textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }
}

Future<void> _loadMore(
  WidgetRef ref,
  StorageState<SoftwareAsset> firstPageState,
  ValueNotifier<List<SoftwareAsset>> olderAssets,
  ValueNotifier<bool> isLoadingMore,
  ValueNotifier<bool> hasMore,
) async {
  isLoadingMore.value = true;

  final allAssets = [...firstPageState.models, ...olderAssets.value];
  if (allAssets.isEmpty) {
    isLoadingMore.value = false;
    return;
  }

  final oldest = allAssets
      .map((a) => a.event.createdAt)
      .reduce((a, b) => a.isBefore(b) ? a : b)
      .subtract(const Duration(milliseconds: 1));

  try {
    final storage = ref.read(storageNotifierProvider.notifier);
    final items = await storage.query(
      RequestFilter<SoftwareAsset>(
        tags: {'#f': {'android-arm64-v8a'}},
        until: oldest,
        limit: _pageSize,
      ).toRequest(),
      source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
      subscriptionPrefix: 'app-latest-older',
    );

    final identifiers = items.map((a) => a.appIdentifier).toSet();
    if (identifiers.isNotEmpty) {
      await storage.query(
        RequestFilter<App>(tags: {'#d': identifiers}).toRequest(),
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'app-latest-older-apps',
      );
    }

    final existingIds = allAssets.map((a) => a.id).toSet();
    final unique = items.where((a) => !existingIds.contains(a.id)).toList();

    olderAssets.value = [...olderAssets.value, ...unique];
    hasMore.value = items.length >= _pageSize;
  } catch (_) {
    // Degrade gracefully — stop paging but don't crash
    hasMore.value = false;
  }
  isLoadingMore.value = false;
}
