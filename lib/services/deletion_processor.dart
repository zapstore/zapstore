import 'package:models/models.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/secure_storage_service.dart';

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
  required SecureStorageService secureStorage,
  required String subscriptionPrefix,
}) async {
  final lastSync = await secureStorage.getDeletionsSyncedUntil();

  // First run: no local data to delete, just seed the cursor.
  if (lastSync == null) {
    await secureStorage.setDeletionsSyncedUntil(DateTime.now());
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

  await secureStorage.setDeletionsSyncedUntil(DateTime.now());

  if (deletionRequests.isEmpty) return;

  const trustedAuthorities = {kZapstorePubkey, kZapstoreCommunityPubkey};
  final authorityTargetIds = deletionRequests
      .where((dr) => trustedAuthorities.contains(dr.event.pubkey))
      .expand((dr) => dr.deletedEventIds)
      .toSet();

  try {
    await storage.delete(deletionRequests.map((d) => d.id).toSet());
    if (authorityTargetIds.isNotEmpty) {
      await storage.delete(authorityTargetIds);
    }
  } catch (_) {
    // Orphaned kind-5 rows are harmless; referenced events are
    // already removed by the DB layer during save.
  }
}
