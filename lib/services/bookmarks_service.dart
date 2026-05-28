import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/device_key_service.dart';

/// Reactive set of bookmarked app addressable IDs.
///
/// Backed by an encrypted AppStack signed by the device key. Auto-decrypted
/// by EncryptableModel since the device signer is always registered.
final bookmarksProvider = Provider<Set<String>>((ref) {
  final devicePubkey = ref.watch(devicePubkeyProvider);
  if (devicePubkey == null) return const {};

  final stackState = ref.watch(
    query<AppStack>(
      authors: {devicePubkey},
      tags: {
        '#d': {kAppBookmarksIdentifier},
      },
      source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: true),
      subscriptionPrefix: 'app-user-saved-apps',
    ),
  );

  final stack = switch (stackState) {
    StorageLoading() => null,
    StorageError() => null,
    StorageData(:final models) => models.firstOrNull,
  };

  if (stack == null) return const {};
  return stack.privateAppIds.toSet();
});

/// Extension to check if an app is saved
extension SavedAppsChecker on WidgetRef {
  bool isAppSaved(App app) {
    final savedApps = watch(bookmarksProvider);
    final appAddressableId =
        '${app.event.kind}:${app.pubkey}:${app.identifier}';
    return savedApps.contains(appAddressableId);
  }

  Set<String> getSavedAppIds() {
    return watch(bookmarksProvider);
  }
}
