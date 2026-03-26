import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';

/// Reactive query provider: fetches SoftwareAsset (3063) events and resolves
/// their parent App (32267) via the direct relationship.
///
/// This is the primary query path for app listings. Each SoftwareAsset carries
/// version, versionCode, hash, urls, and platform — everything needed for
/// app cards and update detection — and links directly to its App.
AutoDisposeStateNotifierProvider<RequestNotifier<SoftwareAsset>,
    StorageState<SoftwareAsset>> appAssetsQuery({
  Set<String>? authors,
  Map<String, Set<String>>? tags,
  String? search,
  DateTime? since,
  DateTime? until,
  int? limit,
  Source? source,
  required String subscriptionPrefix,
}) {
  return query<SoftwareAsset>(
    authors: authors,
    tags: tags,
    search: search,
    since: since,
    until: until,
    limit: limit,
    and: (asset) => {asset.app.query(), asset.author.query()},
    source: source,
    subscriptionPrefix: subscriptionPrefix,
  );
}

/// Reactive query provider: fetches FileMetadata (1063) events and resolves
/// their parent App (32267) via the direct `#i` → `#d` relationship.
///
/// Same shape as [appAssetsQuery] but for legacy 1063-only apps.
/// Delete this function when legacy 1063 support is fully removed.
AutoDisposeStateNotifierProvider<RequestNotifier<FileMetadata>,
    StorageState<FileMetadata>> legacyAppQuery({
  Set<String>? authors,
  Map<String, Set<String>>? tags,
  String? search,
  DateTime? since,
  DateTime? until,
  int? limit,
  Source? source,
  required String subscriptionPrefix,
}) {
  return query<FileMetadata>(
    authors: authors,
    tags: tags,
    search: search,
    since: since,
    until: until,
    limit: limit,
    and: (fm) => {fm.app.query(), fm.author.query()},
    source: source,
    subscriptionPrefix: subscriptionPrefix,
  );
}

/// Result of a paginated asset-first fetch.
class AssetFetchResult {
  final List<App> apps;
  final int assetCount;
  const AssetFetchResult(this.apps, this.assetCount);
}

/// Imperative one-shot: fetches a page of SoftwareAsset (3063) events,
/// resolves their parent Apps, and pre-loads author profiles.
/// Returns the resolved Apps (deduplicated) and the raw asset count
/// (for accurate pagination — multiple assets may map to one app).
Future<AssetFetchResult> fetchAppsByAsset(
  StorageNotifier storage, {
  Map<String, Set<String>>? tags,
  DateTime? until,
  int? limit,
  Source? source,
  required String subscriptionPrefix,
}) async {
  final assets = await storage.query(
    RequestFilter<SoftwareAsset>(
      tags: tags,
      until: until,
      limit: limit,
    ).toRequest(),
    source: source,
    subscriptionPrefix: subscriptionPrefix,
  );

  if (assets.isEmpty) return const AssetFetchResult([], 0);

  final appFilters = assets
      .map((a) => a.app.req?.filters.firstOrNull)
      .nonNulls
      .toList();

  if (appFilters.isEmpty) return AssetFetchResult(const [], assets.length);

  final apps = await storage.query(
    Request<App>(appFilters),
    source: source,
    subscriptionPrefix: '$subscriptionPrefix-apps',
  );

  await _loadAuthors(storage, apps, source, '$subscriptionPrefix-authors');

  return AssetFetchResult(apps, assets.length);
}

/// Imperative one-shot: fetches a page of FileMetadata (1063) events,
/// resolves their parent Apps, and pre-loads author profiles.
/// Same shape as [fetchAppsByAsset] but for legacy 1063-only apps.
Future<AssetFetchResult> fetchLegacyAppsByMetadata(
  StorageNotifier storage, {
  Map<String, Set<String>>? tags,
  DateTime? until,
  int? limit,
  Source? source,
  required String subscriptionPrefix,
}) async {
  final metadatas = await storage.query(
    RequestFilter<FileMetadata>(
      tags: tags,
      until: until,
      limit: limit,
    ).toRequest(),
    source: source,
    subscriptionPrefix: subscriptionPrefix,
  );

  if (metadatas.isEmpty) return const AssetFetchResult([], 0);

  final appFilters = metadatas
      .map((fm) => fm.app.req?.filters.firstOrNull)
      .nonNulls
      .toList();

  if (appFilters.isEmpty) return AssetFetchResult(const [], metadatas.length);

  final apps = await storage.query(
    Request<App>(appFilters),
    source: source,
    subscriptionPrefix: '$subscriptionPrefix-apps',
  );

  await _loadAuthors(storage, apps, source, '$subscriptionPrefix-authors');

  return AssetFetchResult(apps, metadatas.length);
}

Future<void> _loadAuthors(
  StorageNotifier storage,
  List<App> apps,
  Source? source,
  String subscriptionPrefix,
) async {
  if (apps.isEmpty) return;
  final authorFilters = apps
      .map((a) => a.author.req?.filters.firstOrNull)
      .nonNulls
      .toList();
  if (authorFilters.isEmpty) return;
  await storage.query(
    Request<Profile>(authorFilters),
    source: source,
    subscriptionPrefix: subscriptionPrefix,
  );
}
