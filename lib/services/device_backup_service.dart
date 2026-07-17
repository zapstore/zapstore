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
import 'package:zapstore/widgets/device_backup_dialog.dart';

/// Backs up the device key to the active Amber identity and restores it again.
///
/// The relay record is authored by Amber, unlike portable device state which is
/// always authored by the recovered device key.
class DeviceBackupService {
  Future<void>? _amberRestore;

  bool get isRestoringFromAmber => _amberRestore != null;

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
    final backup = await _findAmberBackup(ref: ref, amberSigner: amberSigner);
    if (backup == null) return null;
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

  /// Returns whether Amber already holds a signed recovery record.
  ///
  /// A normal sign-in must not replace an existing recovery key. In
  /// particular, a fresh install has already generated a new device key before
  /// Amber becomes available; publishing that key would orphan the user's
  /// existing private state.
  Future<bool> hasAmberBackup({
    required Ref ref,
    required Signer amberSigner,
  }) async =>
      await _findAmberBackup(ref: ref, amberSigner: amberSigner) != null;

  static bool isValidAmberBackup({
    required Ref ref,
    required CustomData? backup,
    required Signer amberSigner,
  }) =>
      backup != null &&
      backup.pubkey == amberSigner.pubkey &&
      verifySignedEvent(ref, backup.event);

  Future<CustomData?> _findAmberBackup({
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
    return isValidAmberBackup(
          ref: ref,
          backup: backup,
          amberSigner: amberSigner,
        )
        ? backup
        : null;
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

  /// Signs in to Amber, verifies its backup, and offers to replace the key.
  ///
  /// This is explicitly user-initiated from Device key management. It prevents
  /// the regular sign-in backup from overwriting the remote backup first.
  Future<void> restoreFromAmber({required Ref ref}) {
    return _amberRestore ??= _restoreFromAmber(ref).whenComplete(() {
      _amberRestore = null;
    });
  }

  Future<void> _restoreFromAmber(Ref ref) async {
    await ref.read(amberSignerProvider).signIn();
    final amberSigner = ref.read(Signer.activeSignerProvider);
    if (amberSigner == null) {
      throw const DeviceBackupException('Could not sign in with Amber.');
    }

    final privateKeyHex = await fetchAmberBackup(
      ref: ref,
      amberSigner: amberSigner,
    );
    if (privateKeyHex == null) {
      throw const DeviceBackupException(
        'No device key backup was found for this Amber identity.',
      );
    }

    final context = rootNavigatorKey.currentState?.overlay?.context;
    if (context == null || !context.mounted) {
      throw const DeviceBackupException('Restore screen is unavailable.');
    }
    final restore = await showDialog<bool>(
      context: context,
      builder: (_) => DeviceBackupRestoreDialog(
        onRestore: () => Navigator.of(context).pop(true),
        onKeepCurrent: () => Navigator.of(context).pop(false),
      ),
    );
    if (restore != true) return;

    await restoreDeviceKey(ref: ref, privateKeyHex: privateKeyHex);
    await ref.read(devicePrivateSyncProvider.notifier).syncRestoredKey();
    if (!ref.read(deviceStateProvider).isReady) {
      unawaited(ref.read(deviceStateProvider.notifier).bootstrap());
    }
  }
}

final deviceBackupServiceProvider = Provider<DeviceBackupService>(
  (ref) => DeviceBackupService(),
);

/// Backs up the current device key after a normal Amber sign-in.
Future<void> maybeOfferDeviceBackup(Ref ref) async {
  final amberSigner = ref.read(Signer.activeSignerProvider);
  if (amberSigner == null) return;

  final service = ref.read(deviceBackupServiceProvider);
  if (service.isRestoringFromAmber) return;
  try {
    if (await service.hasAmberBackup(ref: ref, amberSigner: amberSigner)) {
      return;
    }
    await service.backupDeviceKey(ref: ref, amberSigner: amberSigner);
    if (!ref.read(deviceStateProvider).isReady) {
      unawaited(ref.read(deviceStateProvider.notifier).bootstrap());
    }
  } catch (error, stack) {
    LogService.I.warn(
      'device key backup failed',
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
