import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:models/models.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/services/settings_service.dart';

/// Deletes events by their coordinates (a tags: kind:pubkey:d-tag).
///
/// For authority deletions, ignores the pubkey in the coordinate and deletes
/// ANY event matching the kind and d-tag, regardless of author.
/// Queries for events matching the coordinates and deletes them by ID.
Future<void> _deleteByCoordinates(
  PurplebaseStorageNotifier storage,
  Set<String> coordinates,
) async {
  if (coordinates.isEmpty) return;

  // Parse coordinates and build filters
  final filters = <RequestFilter<Model>>[];
  for (final coord in coordinates) {
    final parts = coord.split(':');
    if (parts.length < 2) continue;

    final kind = int.tryParse(parts[0]);
    if (kind == null) continue;

    // For authority deletions, we ignore the pubkey in the coordinate
    // and delete ANY event with matching kind and d-tag

    if (parts.length >= 3 && parts[2].isNotEmpty) {
      // Parameterized replaceable event with d-tag
      // NOTE: No authors filter - delete regardless of who published it
      filters.add(RequestFilter<Model>(
        kinds: {kind},
        tags: {'#d': {parts[2]}},
      ));
    } else {
      // Replaceable event without d-tag
      // NOTE: No authors filter - delete regardless of who published it
      filters.add(RequestFilter<Model>(
        kinds: {kind},
      ));
    }
  }

  if (filters.isEmpty) return;

  // Query for matching events
  final events = await storage.query(
    Request(filters),
    source: const LocalSource(),
  );

  // Delete by event IDs
  final eventIds = events.map((e) => e.id).toSet();
  if (eventIds.isNotEmpty) {
    await storage.delete(eventIds);
  }
}

/// Fetches NIP-09 kind-5 deletion events from the AppCatalog relay since the
/// last sync cursor, applies them to local storage, and advances the cursor.
///
/// The AppCatalog relay is curated, so no author filter is needed. The `since`
/// cursor (persisted in secure storage) keeps incremental runs cheap.
///
/// Purplebase auto-processes each kind-5 event on save (removes referenced
/// events whose pubkey matches the kind-5 author). However, relay-authority
/// deletions (blacklists signed by zapstore/community keys) won't match the
/// original author — those targets are deleted explicitly here.
Future<void> processDeletions({
  required PurplebaseStorageNotifier storage,
  required SettingsService settingsService,
  required String subscriptionPrefix,
}) async {
  final settings = await settingsService.load();
  final lastSync = settings.deletionSyncedUntil;

  // First run: no local data to delete, just seed the cursor.
  if (lastSync == null) {
    await settingsService.update(
        (s) => s.copyWith(deletionSyncedUntil: DateTime.now()));
    return;
  }

  final deletionRequests = await storage.query(
    RequestFilter<EventDeletionRequest>(
      since: lastSync,
      limit: 99,
    ).toRequest(),
    source: const RemoteSource(relays: 'AppCatalog', stream: false),
    subscriptionPrefix: subscriptionPrefix,
  );

  await settingsService.update(
      (s) => s.copyWith(deletionSyncedUntil: DateTime.now()));

  if (deletionRequests.isEmpty) return;

  const trustedAuthorities = {
    kZapstorePubkey,
    kCatalogPubkey,
    kZapstoreCommunityPubkey,
  };
  final authorityDeletions = deletionRequests
      .where((dr) => trustedAuthorities.contains(dr.event.pubkey))
      .toList();

  final authorityTargetIds = authorityDeletions
      .expand((dr) => dr.deletedEventIds)
      .toSet();

  final authorityTargetCoordinates = authorityDeletions
      .expand((dr) => dr.event.getTagSetValues('a'))
      .toSet();

  try {
    await storage.delete(deletionRequests.map((d) => d.id).toSet());
    if (authorityTargetIds.isNotEmpty) {
      await storage.delete(authorityTargetIds);
    }
    if (authorityTargetCoordinates.isNotEmpty) {
      await _deleteByCoordinates(storage, authorityTargetCoordinates);
    }
  } catch (_) {
    // Orphaned kind-5 rows are harmless; referenced events are
    // already removed by the DB layer during save.
  }
}

/// Deletes the local [kZapstorePubkey] listings for those [appIds] whose
/// [kCatalogPubkey] listing and asset are both stored locally, and returns
/// them. Catalog rotation removes the old listings from the relay without a
/// kind 5, so the device must drop them itself, but not before the
/// replacement can be installed offline.
Future<Set<String>> dropSupersededCatalogEvents(
  PurplebaseStorageNotifier storage,
  Set<String> appIds,
) async {
  if (appIds.isEmpty) return const {};

  final replacements = await storage.query(
    Request([
      RequestFilter<Model>(
        kinds: {32267},
        authors: {kCatalogPubkey},
        tags: {'#d': appIds},
      ),
      RequestFilter<Model>(
        kinds: {3063},
        authors: {kCatalogPubkey},
        tags: {'#i': appIds},
      ),
    ]),
    source: const LocalSource(),
  );
  final listed = <String>{};
  final withAsset = <String>{};
  for (final model in replacements) {
    final event = model.event;
    final target = event.kind == 32267 ? listed : withAsset;
    final appId = event.getFirstTagValue(event.kind == 32267 ? 'd' : 'i');
    if (appId != null) target.add(appId);
  }
  final ready = listed.intersection(withAsset);
  if (ready.isEmpty) return const {};

  final superseded = await storage.query(
    RequestFilter<Model>(
      kinds: {32267},
      authors: {kZapstorePubkey},
      tags: {'#d': ready},
    ).toRequest(),
    source: const LocalSource(),
  );
  await storage.delete(superseded.map((model) => model.id).toSet());
  return ready;
}

/// Drops superseded [kZapstorePubkey] listings as [kCatalogPubkey]
/// listings and assets land in local storage. Read once at startup to keep
/// it alive.
final supersededCatalogCleanupProvider = Provider<void>((ref) {
  final handled = <String>{};

  void check(Iterable<String?> candidates) {
    final appIds = candidates.nonNulls.toSet()..removeAll(handled);
    if (appIds.isEmpty) return;

    final storage =
        ref.read(storageNotifierProvider.notifier) as PurplebaseStorageNotifier;
    unawaited(
      dropSupersededCatalogEvents(storage, appIds).then(
        handled.addAll,
        onError: (Object e, StackTrace st) {
          LogService.I.warn(
            'superseded catalog cleanup failed',
            tag: 'catalog',
            err: e,
            stack: st,
          );
        },
      ),
    );
  }

  ref.listen(
    query<App>(
      authors: {kCatalogPubkey},
      source: const LocalSource(),
      subscriptionPrefix: 'app-superseded-catalog',
    ),
    (_, next) => check(next.models.map((app) => app.identifier)),
    fireImmediately: true,
  );
  ref.listen(
    query<SoftwareAsset>(
      authors: {kCatalogPubkey},
      source: const LocalSource(),
      subscriptionPrefix: 'app-superseded-catalog-assets',
    ),
    (_, next) =>
        check(next.models.map((asset) => asset.event.getFirstTagValue('i'))),
    fireImmediately: true,
  );
});
