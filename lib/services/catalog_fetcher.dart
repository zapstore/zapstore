import 'package:models/models.dart';

/// Max identifiers per relay subscription to stay under the relay's 100-event
/// hard cap. Each app may have several versions on the relay, so a small batch
/// keeps every app represented in the response.
const kCatalogBatchSize = 60;

/// Raw catalog data returned by [fetchCatalog]. Callers decide what to do with
/// it (update UI state, compare for updates, show notifications, etc.).
class CatalogResult {
  const CatalogResult({
    required this.apps,
    required this.installableByApp,
    required this.catalogedIds,
  });

  final List<App> apps;
  final Map<String, Installable> installableByApp;
  final Set<String> catalogedIds;

  static const empty = CatalogResult(
    apps: [],
    installableByApp: {},
    catalogedIds: {},
  );
}

/// Fetch the latest catalog data for a set of installed app identifiers.
///
/// Used by both the foreground update poller and the background WorkManager
/// task. Pass [source] to override the default [RemoteSource] (e.g. use
/// [LocalSource] for a cheap local-only re-derivation).
Future<CatalogResult> fetchCatalog({
  required StorageNotifier storage,
  required Set<String> installedIds,
  required String platform,
  required String subscriptionPrefix,
  Source source = const RemoteSource(relays: 'AppCatalog', stream: false),
}) async {
  if (installedIds.isEmpty) return CatalogResult.empty;

  final installableByApp = <String, Installable>{};
  final allApps = <String, App>{};

  // ── Phase 1: Asset-first (3063 → 32267) ────────────────────────────
  final assets = await batchedQuery<SoftwareAsset>(
    storage: storage,
    allIds: installedIds,
    tagKey: '#i',
    extraTags: {'#f': {platform}},
    subscriptionPrefix: '$subscriptionPrefix-assets',
    source: source,
  );

  for (final a in assets) {
    final id = a.appIdentifier;
    if (id.isEmpty) continue;
    final existing = installableByApp[id];
    if (existing == null ||
        (a.versionCode ?? 0) > (existing.versionCode ?? 0)) {
      installableByApp[id] = a;
    }
  }

  final assetCoveredIds = installableByApp.keys.toSet();

  if (assetCoveredIds.isNotEmpty) {
    final apps = await storage.query(
      RequestFilter<App>(
        tags: {'#d': assetCoveredIds, '#f': {platform}},
      ).toRequest(),
      source: source,
      subscriptionPrefix: '$subscriptionPrefix-apps',
    );
    for (final app in apps) {
      allApps[app.identifier] = app;
    }
  }

  // ── Phase 2: Legacy (32267 → 30063 → 1063) ────────────────────────
  // TODO(cleanup): Remove when all apps are migrated to 3063.
  final uncoveredIds = installedIds.difference(assetCoveredIds);
  if (uncoveredIds.isNotEmpty) {
    final legacyApps = await storage.query(
      RequestFilter<App>(
        tags: {'#d': uncoveredIds, '#f': {platform}},
      ).toRequest(),
      source: source,
      subscriptionPrefix: '$subscriptionPrefix-legacy-apps',
    );

    if (legacyApps.isNotEmpty) {
      final releaseFilters = legacyApps
          .map((a) => a.latestRelease.req?.filters.firstOrNull)
          .nonNulls
          .toList();
      var releases = const <Release>[];
      if (releaseFilters.isNotEmpty) {
        releases = await storage.query(
          Request<Release>(releaseFilters),
          source: source,
          subscriptionPrefix: '$subscriptionPrefix-legacy-releases',
        );
      }

      if (releases.isNotEmpty) {
        final metadataFilters = releases
            .map((r) => r.latestMetadata.req?.filters.firstOrNull)
            .nonNulls
            .toList();
        if (metadataFilters.isNotEmpty) {
          final metadatas = await storage.query(
            Request<FileMetadata>(metadataFilters),
            source: source,
            subscriptionPrefix: '$subscriptionPrefix-legacy-meta',
          );
          for (final m in metadatas) {
            final id = m.appIdentifier;
            if (id.isEmpty) continue;
            final existing = installableByApp[id];
            if (existing == null ||
                (m.versionCode ?? 0) > (existing.versionCode ?? 0)) {
              installableByApp[id] = m;
            }
          }
        }
      }

      for (final app in legacyApps) {
        allApps.putIfAbsent(app.identifier, () => app);
      }
    }
  }

  return CatalogResult(
    apps: allApps.values.toList(),
    installableByApp: installableByApp,
    catalogedIds: installableByApp.keys.toSet(),
  );
}

/// Query a model type in batches of [kCatalogBatchSize] identifiers to avoid
/// hitting the relay's per-subscription event cap.
Future<List<T>> batchedQuery<T extends Model<T>>({
  required StorageNotifier storage,
  required Set<String> allIds,
  required String tagKey,
  Map<String, Set<String>> extraTags = const {},
  required String subscriptionPrefix,
  Source source = const RemoteSource(relays: 'AppCatalog', stream: false),
}) async {
  if (allIds.isEmpty) return const [];
  final idList = allIds.toList();
  final results = <T>[];
  for (var i = 0; i < idList.length; i += kCatalogBatchSize) {
    final batch = idList.sublist(
      i,
      (i + kCatalogBatchSize).clamp(0, idList.length),
    );
    final batchResults = await storage.query(
      RequestFilter<T>(
        tags: {tagKey: batch.toSet(), ...extraTags},
      ).toRequest(),
      source: source,
      subscriptionPrefix: '$subscriptionPrefix-$i',
    );
    results.addAll(batchResults);
  }
  return results;
}
