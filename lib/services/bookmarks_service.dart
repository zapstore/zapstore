import 'dart:convert';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/extensions.dart';

/// Provider that watches the user's saved apps pack and provides decrypted saved apps state
/// Fetches from remote once on boot, then uses local storage only
final bookmarksProvider = FutureProvider<Set<String>>((ref) async {
  final signer = ref.watch(Signer.activeSignerProvider);
  final signedInPubkey = ref.watch(Signer.activePubkeyProvider);

  if (signedInPubkey == null || signer == null) {
    return {};
  }

  // Query saved apps pack - stream enabled to auto-update when saved apps change locally
  // Initial fetch from remote, then watches local storage for changes
  final packState = ref.watch(
    query<AppPack>(
      authors: {signedInPubkey},
      tags: {
        '#d': {kAppBookmarksIdentifier},
      },
      source: const LocalAndRemoteSource(
        relays: 'social',
        stream: false,
      ),
      subscriptionPrefix: 'user-saved-apps',
    ),
  );

  // Handle different storage states
  final pack = switch (packState) {
    StorageLoading() => null,
    StorageError() => null,
    StorageData(:final models) => models.firstOrNull,
  };

  if (pack == null || pack.content.isEmpty) {
    return {};
  }

  try {
    // Content is always encrypted after signing - must explicitly decrypt
    final decryptedContent = await signer.nip44Decrypt(
      pack.content,
      signedInPubkey,
    );

    // Parse the JSON array of app IDs
    final appIds = (jsonDecode(decryptedContent) as List)
        .cast<String>()
        .toSet();

    return appIds;
  } catch (e) {
    return {};
  }
});

/// Extension to check if an app is saved
extension SavedAppsChecker on WidgetRef {
  /// Check if the given app is saved
  bool isAppSaved(App app) {
    final savedAppsState = watch(bookmarksProvider);
    final appAddressableId =
        '${app.event.kind}:${app.pubkey}:${app.identifier}';

    return savedAppsState.when(
      data: (savedApps) => savedApps.contains(appAddressableId),
      loading: () => false,
      error: (_, __) => false,
    );
  }

  /// Get all saved app addressable IDs
  Set<String> getSavedAppIds() {
    final savedAppsState = watch(bookmarksProvider);

    return savedAppsState.when(
      data: (savedApps) => savedApps,
      loading: () => {},
      error: (_, __) => {},
    );
  }
}
