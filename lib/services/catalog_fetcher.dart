import 'dart:async';

import 'package:models/models.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';

/// Relay hard limit for filters in a single REQ message.
const kMaxFiltersPerReq = 50;

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
/// 1. Queries local DB for all known 3063/1063 events of [installedIds].
/// 2. Builds one incremental filter per app (`since` = most recent local
///    timestamp + 1 s) and sends them in batches of [kMaxFiltersPerReq].
/// 3. Loads related Apps and Profiles in the background after new events arrive.
///
/// Pass [localOnly] = true to skip the remote phase (cheap local re-derivation).
///
/// [installed] carries the packages currently on the device. Their signing
/// certificates decide which candidate wins when several publishers claim the
/// same app identifier — see [selectBetterInstallable].
Future<CatalogResult> fetchCatalog({
  required StorageNotifier storage,
  required Set<String> installedIds,
  required String platform,
  required String subscriptionPrefix,
  Map<String, PackageInfo> installed = const {},
  bool localOnly = false,
}) async {
  if (installedIds.isEmpty) return CatalogResult.empty;

  final installedCertificateHashes = certificateHashesByApp(installed);

  // ── Phase 1: local baseline ──────────────────────────────────────────
  final localAssets = await storage.query(
    RequestFilter<SoftwareAsset>(
      tags: {'#i': installedIds, '#f': {platform}},
    ).toRequest(),
    source: const LocalSource(),
    subscriptionPrefix: '$subscriptionPrefix-local-assets',
  );

  final installableByApp = <String, Installable>{};
  final newestTimestamp = <String, DateTime>{};

  for (final a in localAssets) {
    final id = a.appIdentifier;
    if (id.isEmpty) continue;
    _mergeInstallable(installableByApp, id, a, installedCertificateHashes);
    final prev = newestTimestamp[id];
    if (prev == null || a.createdAt.isAfter(prev)) {
      newestTimestamp[id] = a.createdAt;
    }
  }

  final assetCoveredIds = <String>{...installableByApp.keys};

  // Legacy 1063: local baseline for apps not covered by 3063
  final uncoveredIds = installedIds.difference(assetCoveredIds);
  if (uncoveredIds.isNotEmpty) {
    final legacyResult = await _localLegacyBaseline(
      storage: storage,
      uncoveredIds: uncoveredIds,
      platform: platform,
      subscriptionPrefix: '$subscriptionPrefix-local-legacy',
      installedCertificateHashes: installedCertificateHashes,
    );
    installableByApp.addAll(legacyResult.installableByApp);
    newestTimestamp.addAll(legacyResult.timestamps);
  }

  if (localOnly) {
    return _buildResult(
      storage: storage,
      installableByApp: installableByApp,
      platform: platform,
      subscriptionPrefix: subscriptionPrefix,
      localOnly: true,
    );
  }

  // ── Phase 2: incremental remote fetch (3063) ──────────────────────────
  final newAssets = await _incrementalRemoteFetch<SoftwareAsset>(
    storage: storage,
    installedIds: installedIds,
    newestTimestamp: newestTimestamp,
    platform: platform,
    subscriptionPrefix: '$subscriptionPrefix-assets',
  );

  for (final a in newAssets) {
    final id = a.appIdentifier;
    if (id.isEmpty) continue;
    _mergeInstallable(installableByApp, id, a, installedCertificateHashes);
    // Track 3063 coverage so we know which apps are asset-covered
    assetCoveredIds.add(id);
  }

  // Legacy 1063: remote App→Release→FileMetadata chain for apps without
  // any 3063 coverage. Uses the proper relationship chain because old 1063
  // events may lack an #i tag.
  final legacyIds = installedIds.difference(assetCoveredIds);
  if (legacyIds.isNotEmpty) {
    final legacyResult = await _remoteLegacyChain(
      storage: storage,
      legacyIds: legacyIds,
      platform: platform,
      subscriptionPrefix: '$subscriptionPrefix-legacy',
      installedCertificateHashes: installedCertificateHashes,
    );
    legacyResult.installableByApp.forEach((id, candidate) {
      _mergeInstallable(installableByApp, id, candidate, installedCertificateHashes);
    });
  }

  // ── Phase 3: load App events (awaited) + profiles (background) ───────
  final result = await _buildResult(
    storage: storage,
    installableByApp: installableByApp,
    platform: platform,
    subscriptionPrefix: subscriptionPrefix,
  );

  final pubkeys = result.apps.map((a) => a.event.pubkey).toSet();
  if (pubkeys.isNotEmpty) {
    unawaited(
      storage.query(
        RequestFilter<Profile>(authors: pubkeys).toRequest(),
        source: const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          cachedFor: Duration(hours: 2),
          stream: false,
        ),
        subscriptionPrefix: '$subscriptionPrefix-profiles-bg',
      ),
    );
  }

  return result;
}

// ═══════════════════════════════════════════════════════════════════════════════
// INTERNALS
// ═══════════════════════════════════════════════════════════════════════════════

/// Build one filter per app with `since` = newest local + 1 s, batched into
/// groups of [kMaxFiltersPerReq]. Returns only genuinely new events.
Future<List<T>> _incrementalRemoteFetch<T extends Model<T>>({
  required StorageNotifier storage,
  required Set<String> installedIds,
  required Map<String, DateTime> newestTimestamp,
  required String platform,
  required String subscriptionPrefix,
}) async {
  final filters = <RequestFilter<T>>[];
  for (final appId in installedIds) {
    final local = newestTimestamp[appId];
    filters.add(RequestFilter<T>(
      tags: {'#i': {appId}, '#f': {platform}},
      since: local?.add(const Duration(seconds: 1)),
      limit: 1,
    ));
  }

  final results = <T>[];
  for (var i = 0; i < filters.length; i += kMaxFiltersPerReq) {
    final batch = filters.sublist(
      i,
      (i + kMaxFiltersPerReq).clamp(0, filters.length),
    );
    final batchResults = await storage.query(
      Request<T>(batch),
      source: const RemoteSource(relays: 'AppCatalog', stream: false),
      subscriptionPrefix: '$subscriptionPrefix-$i',
    );
    results.addAll(batchResults);
  }
  return results;
}

/// Local-only legacy pass: App → Release → FileMetadata chain for apps
/// without any 3063 coverage.
Future<_LegacyBaseline> _localLegacyBaseline({
  required StorageNotifier storage,
  required Set<String> uncoveredIds,
  required String platform,
  required String subscriptionPrefix,
  required Map<String, Set<String>> installedCertificateHashes,
}) async {
  final installableByApp = <String, Installable>{};
  final timestamps = <String, DateTime>{};

  final legacyApps = await storage.query(
    RequestFilter<App>(
      tags: {'#d': uncoveredIds, '#f': {platform}},
    ).toRequest(),
    source: const LocalSource(),
    subscriptionPrefix: '$subscriptionPrefix-apps',
  );
  if (legacyApps.isEmpty) return _LegacyBaseline(installableByApp, timestamps);

  final releaseFilters = legacyApps
      .map((a) => a.latestRelease.req?.filters.firstOrNull)
      .nonNulls
      .toList();
  if (releaseFilters.isEmpty) {
    return _LegacyBaseline(installableByApp, timestamps);
  }

  final releases = await storage.query(
    Request<Release>(releaseFilters),
    source: const LocalSource(),
    subscriptionPrefix: '$subscriptionPrefix-releases',
  );
  if (releases.isEmpty) return _LegacyBaseline(installableByApp, timestamps);

  final metadataFilters = releases
      .map((r) => r.latestMetadata.req?.filters.firstOrNull)
      .nonNulls
      .toList();
  if (metadataFilters.isEmpty) {
    return _LegacyBaseline(installableByApp, timestamps);
  }

  final metadatas = await storage.query(
    Request<FileMetadata>(metadataFilters),
    source: const LocalSource(),
    subscriptionPrefix: '$subscriptionPrefix-meta',
  );

  for (final m in metadatas) {
    final id = m.appIdentifier;
    if (id.isEmpty) continue;
    _mergeInstallable(installableByApp, id, m, installedCertificateHashes);
    final prev = timestamps[id];
    if (prev == null || m.createdAt.isAfter(prev)) {
      timestamps[id] = m.createdAt;
    }
  }

  return _LegacyBaseline(installableByApp, timestamps);
}

/// Remote legacy pass: App → Release → FileMetadata chain for apps without
/// 3063 coverage. Old 1063 events may lack #i tags, so we must traverse
/// the relationship chain rather than filtering by tag directly.
Future<_LegacyBaseline> _remoteLegacyChain({
  required StorageNotifier storage,
  required Set<String> legacyIds,
  required String platform,
  required String subscriptionPrefix,
  required Map<String, Set<String>> installedCertificateHashes,
}) async {
  const source = RemoteSource(relays: 'AppCatalog', stream: false);
  final installableByApp = <String, Installable>{};

  final legacyApps = await storage.query(
    RequestFilter<App>(
      tags: {'#d': legacyIds, '#f': {platform}},
    ).toRequest(),
    source: source,
    subscriptionPrefix: '$subscriptionPrefix-apps',
  );
  if (legacyApps.isEmpty) return _LegacyBaseline(installableByApp, const {});

  final releaseFilters = legacyApps
      .map((a) => a.latestRelease.req?.filters.firstOrNull)
      .nonNulls
      .toList();
  if (releaseFilters.isEmpty) {
    return _LegacyBaseline(installableByApp, const {});
  }

  final releases = await storage.query(
    Request<Release>(releaseFilters),
    source: source,
    subscriptionPrefix: '$subscriptionPrefix-releases',
  );
  if (releases.isEmpty) return _LegacyBaseline(installableByApp, const {});

  final metadataFilters = releases
      .map((r) => r.latestMetadata.req?.filters.firstOrNull)
      .nonNulls
      .toList();
  if (metadataFilters.isEmpty) {
    return _LegacyBaseline(installableByApp, const {});
  }

  final metadatas = await storage.query(
    Request<FileMetadata>(metadataFilters),
    source: source,
    subscriptionPrefix: '$subscriptionPrefix-meta',
  );

  for (final m in metadatas) {
    final id = m.appIdentifier;
    if (id.isEmpty) continue;
    _mergeInstallable(installableByApp, id, m, installedCertificateHashes);
  }

  return _LegacyBaseline(installableByApp, const {});
}

/// Resolve App objects for all cataloged identifiers.
///
/// Uses [LocalAndRemoteSource] so Apps not yet in the local DB are fetched
/// from the relay. Pass [localOnly] to skip the network round-trip.
Future<CatalogResult> _buildResult({
  required StorageNotifier storage,
  required Map<String, Installable> installableByApp,
  required String platform,
  required String subscriptionPrefix,
  bool localOnly = false,
}) async {
  if (installableByApp.isEmpty) return CatalogResult.empty;

  final source = localOnly
      ? const LocalSource() as Source
      : const LocalAndRemoteSource(relays: 'AppCatalog', stream: false);

  final apps = await storage.query(
    RequestFilter<App>(
      tags: {'#d': installableByApp.keys.toSet(), '#f': {platform}},
    ).toRequest(),
    source: source,
    subscriptionPrefix: '$subscriptionPrefix-resolve-apps',
  );

  return CatalogResult(
    apps: apps,
    installableByApp: installableByApp,
    catalogedIds: installableByApp.keys.toSet(),
  );
}

/// Signing certificate hashes of each installed package, keyed by app id.
///
/// Packages whose certificates could not be read are omitted, so they fall back
/// to version-code ordering instead of being treated as "declares nothing".
Map<String, Set<String>> certificateHashesByApp(
  Map<String, PackageInfo> installed,
) => {
  for (final entry in installed.entries)
    if (entry.value.signatureHashes.isNotEmpty)
      entry.key: entry.value.signatureHashes.toSet(),
};

/// How a candidate's declared signing certificate relates to the certificate
/// the installed app is signed with.
///
/// Declaration order is the preference order: [matching] beats [undeclared],
/// which beats [mismatching].
enum CandidateCertificateTrust { matching, undeclared, mismatching }

/// Ranks [candidate] against the certificate hashes of the installed app.
///
/// Returns [CandidateCertificateTrust.undeclared] when either side declares
/// nothing, so legacy metadata without `apk_certificate_hash` keeps working.
CandidateCertificateTrust certificateTrustFor(
  Installable candidate,
  Set<String> installedCertificateHashes,
) {
  if (installedCertificateHashes.isEmpty) {
    return CandidateCertificateTrust.undeclared;
  }
  final declared = candidate.certificateHashes;
  if (declared.isEmpty) return CandidateCertificateTrust.undeclared;

  final installed = installedCertificateHashes
      .map((hash) => hash.toLowerCase())
      .toSet();
  final matches = declared.any(
    (hash) => installed.contains(hash.toLowerCase()),
  );
  return matches
      ? CandidateCertificateTrust.matching
      : CandidateCertificateTrust.mismatching;
}

/// Picks the better of two candidates for the same installed app.
///
/// Version code alone is not a safe ordering: the catalogue is permissionless,
/// so anyone can publish an asset carrying another app's identifier with an
/// arbitrarily high version code and displace the publisher's release. Android
/// refuses to update an installed app with an APK signed by a different
/// certificate, so such a candidate can never install — it must not hide one
/// that can.
///
/// Certificate trust is therefore compared first, and the highest version code
/// only decides within the same tier, which is the previous behaviour.
Installable selectBetterInstallable(
  Installable existing,
  Installable candidate,
  Set<String> installedCertificateHashes,
) {
  final existingTrust = certificateTrustFor(
    existing,
    installedCertificateHashes,
  );
  final candidateTrust = certificateTrustFor(
    candidate,
    installedCertificateHashes,
  );

  if (candidateTrust != existingTrust) {
    return candidateTrust.index < existingTrust.index ? candidate : existing;
  }

  return (candidate.versionCode ?? 0) > (existing.versionCode ?? 0)
      ? candidate
      : existing;
}

void _mergeInstallable(
  Map<String, Installable> map,
  String id,
  Installable candidate,
  Map<String, Set<String>> installedCertificateHashes,
) {
  final existing = map[id];
  if (existing == null) {
    map[id] = candidate;
    return;
  }
  map[id] = selectBetterInstallable(
    existing,
    candidate,
    installedCertificateHashes[id] ?? const {},
  );
}

class _LegacyBaseline {
  const _LegacyBaseline(this.installableByApp, this.timestamps);
  final Map<String, Installable> installableByApp;
  final Map<String, DateTime> timestamps;
}
