import 'package:models/models.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/constants/app_constants.dart';
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

  const trustedAuthorities = {kZapstorePubkey, kZapstoreCommunityPubkey};
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
