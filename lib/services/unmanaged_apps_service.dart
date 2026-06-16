import 'dart:async';

import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/log_service.dart';

/// Reactive set of package IDs the user has chosen as unmanaged.
///
/// Backed by an encrypted AppStack signed by the device key. Auto-decrypted
/// by EncryptableModel since the device signer is always registered.
final unmanagedAppsProvider = Provider<Set<String>>((ref) {
  final devicePubkey = ref.watch(devicePubkeyProvider);
  if (devicePubkey == null) return const {};

  final state = ref.watch(
    query<AppStack>(
      authors: {devicePubkey},
      tags: {
        '#d': {kUnmanagedAppsIdentifier},
      },
      source: const LocalAndRemoteSource(
        relays: 'AppCatalog',
        stream: true,
      ),
      subscriptionPrefix: 'app-unmanaged-apps',
    ),
  );

  final stack = switch (state) {
    StorageLoading() => null,
    StorageError() => null,
    StorageData(:final models) => models.firstOrNull,
  };

  if (stack == null) return const {};
  return stack.privateAppIds.toSet();
});

/// Toggles [appId] in the unmanaged encrypted stack.
/// Pass [unmanage: true] to add, [unmanage: false] to remove.
Future<void> toggleUnmanagedApp(
  WidgetRef ref,
  String appId, {
  required bool unmanage,
}) async {
  final devicePubkey = ref.read(devicePubkeyProvider);
  if (devicePubkey == null) return;

  final signer = ref.read(Signer.signerProvider(devicePubkey));
  if (signer == null) return;

  final storage = ref.read(storageNotifierProvider.notifier);

  List<String> current = [];
  try {
    final existing = await storage.query(
      RequestFilter<AppStack>(
        authors: {devicePubkey},
        tags: {'#d': {kUnmanagedAppsIdentifier}},
      ).toRequest(),
      source: const LocalSource(),
      subscriptionPrefix: 'app-unmanaged-apps-write',
    );
    final stack = existing.firstOrNull;
    if (stack != null) {
      current = List<String>.from(stack.privateAppIds);
    }
  } catch (e, st) {
    LogService.I.warn(
      'could not read existing unmanaged-apps stack',
      tag: 'unmanaged-apps',
      err: e,
      stack: st,
    );
  }

  final updated = unmanage
      ? [...current.where((id) => id != appId), appId]
      : current.where((id) => id != appId).toList();

  try {
    final partial = PartialAppStack.withEncryptedApps(
      name: 'Unmanaged Apps',
      identifier: kUnmanagedAppsIdentifier,
      apps: updated,
    );
    final signed = await partial.signWith(signer);
    await storage.save({signed});
    unawaited(storage.publish({signed}, relays: 'AppCatalog'));
  } catch (e, st) {
    LogService.I.warn(
      'failed to save unmanaged-apps stack',
      tag: 'unmanaged-apps',
      err: e,
      stack: st,
    );
  }
}
