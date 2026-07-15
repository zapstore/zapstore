import 'dart:async';
import 'dart:convert';

import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/router.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/device_private_event_service.dart';
import 'package:zapstore/services/device_private_sync_service.dart';
import 'package:zapstore/services/device_state_service.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/services/settings_service.dart';
import 'package:zapstore/widgets/device_backup_dialog.dart';
import 'package:zapstore/widgets/device_restore_dialog.dart';
import 'package:zapstore/widgets/legacy_installed_apps_dialog.dart';

/// Backs up the device key to the active Amber identity and restores it again.
///
/// The relay record is authored by Amber, unlike portable device state which is
/// always authored by the recovered device key.
class DeviceBackupService {
  /// Retained as a lifecycle hook for callers from the previous recovery
  /// implementation. Device-key backup has no long-lived foreground request.
  void beginWork() {}

  /// Device-key backup publishes are short-lived and do not own a cancellable
  /// request. Bootstrap mining is owned by DeviceStateNotifier.
  void cancelCurrentWork(Ref ref) {}

  Future<void> backupDeviceKey({
    required Ref ref,
    required Signer amberSigner,
  }) async {
    final privateKeyHex = await ref
        .read(deviceKeyServiceProvider)
        .getOrCreatePrivateKey();
    final ciphertext = await amberSigner.nip44Encrypt(
      jsonEncode({'privateKeyHex': privateKeyHex}),
      amberSigner.pubkey,
    );
    final signed = await PartialCustomData(
      identifier: kDeviceKeyBackupIdentifier,
      content: ciphertext,
    ).signWith(amberSigner);
    if (!verifySignedEvent(ref, signed.event)) {
      throw const DeviceBackupException('Could not verify Amber key backup.');
    }
    final storage = ref.read(storageNotifierProvider.notifier);
    final saved = await storage.save({signed});
    if (!saved) {
      throw const DeviceBackupException(
        'Could not save Amber key backup locally.',
      );
    }
    final response = await storage.publish({signed}, relays: 'AppCatalog');
    final accepted =
        response.results[signed.event.id]?.any((result) => result.accepted) ??
        false;
    if (!accepted) {
      throw const DeviceBackupException(
        'Saved locally, but no AppCatalog relay accepted the Amber key backup.',
      );
    }
  }

  Future<String?> fetchAmberBackup({
    required Ref ref,
    required Signer amberSigner,
  }) async {
    final results = await ref
        .read(storageNotifierProvider.notifier)
        .query(
          RequestFilter<CustomData>(
            authors: {amberSigner.pubkey},
            tags: {
              '#d': {kDeviceKeyBackupIdentifier},
            },
            limit: 1,
          ).toRequest(),
          source: const LocalAndRemoteSource(
            relays: 'AppCatalog',
            stream: false,
          ),
          subscriptionPrefix: 'app-device-key-backup',
        );
    final backup = results.firstOrNull;
    if (backup == null ||
        backup.pubkey != amberSigner.pubkey ||
        !verifySignedEvent(ref, backup.event)) {
      return null;
    }
    try {
      final plaintext = await amberSigner.nip44Decrypt(
        backup.content,
        amberSigner.pubkey,
      );
      final decoded = jsonDecode(plaintext);
      if (decoded is! Map) return null;
      final privateKeyHex = decoded['privateKeyHex'];
      if (privateKeyHex is! String ||
          privateKeyHex.length != 64 ||
          Utils.derivePublicKey(privateKeyHex).isEmpty) {
        return null;
      }
      return privateKeyHex;
    } catch (_) {
      return null;
    }
  }

  Future<void> restoreDeviceKey({
    required Ref ref,
    required String privateKeyHex,
  }) async {
    if (privateKeyHex.length != 64) {
      throw const DeviceBackupException(
        'Device key must be 64 hexadecimal characters.',
      );
    }
    final pubkey = Utils.derivePublicKey(privateKeyHex);
    await ref.read(deviceKeyServiceProvider).replacePrivateKey(privateKeyHex);
    final signer = Bip340PrivateKeySigner(privateKeyHex, ref);
    await signer.signIn(setAsActive: false);
    ref.read(devicePubkeyProvider.notifier).state = pubkey;
  }

  Future<List<String>> fetchLegacyInstalledAppIds({
    required Ref ref,
    required Signer amberSigner,
  }) async {
    final results = await ref
        .read(storageNotifierProvider.notifier)
        .query(
          RequestFilter<AppStack>(
            authors: {amberSigner.pubkey},
            tags: {
              '#d': {kInstalledAppsIdentifier},
            },
            limit: 1,
          ).toRequest(),
          source: const LocalAndRemoteSource(
            relays: 'AppCatalog',
            stream: false,
          ),
          subscriptionPrefix: 'app-legacy-installed-recovery',
        );
    final stack = results.firstOrNull;
    if (stack == null || !verifySignedEvent(ref, stack.event)) return const [];
    try {
      final plaintext = await amberSigner.nip44Decrypt(
        stack.content,
        amberSigner.pubkey,
      );
      final decoded = jsonDecode(plaintext);
      return decoded is List
          ? decoded
                .whereType<String>()
                .where((id) => id.startsWith('32267:'))
                .toList()
          : const [];
    } catch (_) {
      return const [];
    }
  }
}

final deviceBackupServiceProvider = Provider<DeviceBackupService>(
  (ref) => DeviceBackupService(),
);

/// Runs the one-time recovery choice after the navigator overlay is available.
Future<void> maybeOfferInitialDeviceRestore(Ref ref) async {
  final settings = ref.read(settingsServiceProvider);
  final temp = await settings.loadTemp();
  if (temp.restoreOnboardingComplete) return;
  final context = rootNavigatorKey.currentState?.overlay?.context;
  if (context == null || !context.mounted) return;

  final result = await showDialog<DeviceRestoreResult>(
    context: context,
    barrierDismissible: false,
    builder: (_) => const DeviceRestoreDialog(),
  );
  if (result == null) return;

  switch (result.action) {
    case DeviceRestoreAction.startFresh:
      await settings.saveTemp(temp.copyWith(restoreOnboardingComplete: true));
      unawaited(ref.read(deviceStateProvider.notifier).bootstrap());
      break;
    case DeviceRestoreAction.pasteKey:
      final privateKeyHex = ref
          .read(deviceKeyServiceProvider)
          .parsePrivateKey(result.key ?? '');
      if (privateKeyHex == null) {
        LogService.I.warn('invalid pasted device key', tag: 'backup');
        return;
      }
      await ref
          .read(deviceBackupServiceProvider)
          .restoreDeviceKey(ref: ref, privateKeyHex: privateKeyHex);
      await ref.read(devicePrivateSyncProvider.notifier).syncRestoredKey();
      final restored = await ref
          .read(deviceStateProvider.notifier)
          .restoreFromLocalEvent();
      if (!restored) {
        unawaited(ref.read(deviceStateProvider.notifier).bootstrap());
      }
      await settings.saveTemp(temp.copyWith(restoreOnboardingComplete: true));
      break;
    case DeviceRestoreAction.amber:
      await ref.read(amberSignerProvider).signIn();
      break;
  }
}

/// Offers Amber recovery only once per fresh local installation.
Future<void> maybeOfferDeviceBackup(Ref ref) async {
  final amberSigner = ref.read(Signer.activeSignerProvider);
  if (amberSigner == null) return;

  final settings = ref.read(settingsServiceProvider);
  final temp = await settings.loadTemp();
  final service = ref.read(deviceBackupServiceProvider);
  try {
    if (!temp.restoreOnboardingComplete) {
      final privateKeyHex = await service.fetchAmberBackup(
        ref: ref,
        amberSigner: amberSigner,
      );
      if (privateKeyHex != null) {
        final context = rootNavigatorKey.currentState?.overlay?.context;
        if (context != null && context.mounted) {
          final restore = await showDialog<bool>(
            context: context,
            barrierDismissible: false,
            builder: (_) => DeviceBackupRestoreDialog(
              onRestore: () => Navigator.of(context).pop(true),
              onKeepCurrent: () => Navigator.of(context).pop(false),
            ),
          );
          if (restore == true) {
            await service.restoreDeviceKey(
              ref: ref,
              privateKeyHex: privateKeyHex,
            );
            await ref
                .read(devicePrivateSyncProvider.notifier)
                .syncRestoredKey();
            if (!ref.read(deviceStateProvider).isReady) {
              unawaited(ref.read(deviceStateProvider.notifier).bootstrap());
            }
          }
        }
      }
      final legacyInstalledApps = await service.fetchLegacyInstalledAppIds(
        ref: ref,
        amberSigner: amberSigner,
      );
      if (legacyInstalledApps.isNotEmpty) {
        final context = rootNavigatorKey.currentState?.overlay?.context;
        if (context != null && context.mounted) {
          await showDialog<void>(
            context: context,
            builder: (_) =>
                LegacyInstalledAppsDialog(appIds: legacyInstalledApps),
          );
        }
      }
      await settings.saveTemp(temp.copyWith(restoreOnboardingComplete: true));
    }
    await service.backupDeviceKey(ref: ref, amberSigner: amberSigner);
    if (!ref.read(deviceStateProvider).isReady) {
      unawaited(ref.read(deviceStateProvider.notifier).bootstrap());
    }
  } catch (error, stack) {
    LogService.I.warn(
      'device key backup or recovery failed',
      tag: 'backup',
      err: error,
      stack: stack,
    );
  }
}

final class DeviceBackupException implements Exception {
  const DeviceBackupException(this.message);

  final String message;

  @override
  String toString() => message;
}
