import 'dart:convert';
import 'dart:io';

import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/router.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/widgets/device_backup_dialog.dart';

const kSettingsIdentifier = 'zapstore-settings';
const _kDeviceBackupsKey = 'deviceBackups';
const _kLegacyInstalledAppsBackupIdentifier = 'zapstore-installed-backup';
const _kLegacyIgnoredAppsIdentifier = 'zapstore-ignored-apps';

/// Manages device key backup/restore via the Amber-signed settings event.
///
/// The settings event is a single replaceable CustomData (kind 30078) event
/// with d-tag "zapstore-settings". Its content is always NIP-44 encrypted to
/// the Amber key and contains a JSON object. Device key backups live under
/// "deviceBackups": [{"pk": "<hex>", "name": "Pixel 7", "ts": 1715...}, ...].
class DeviceBackupService {
  /// Fetch the existing encrypted settings event for the given Amber pubkey.
  Future<CustomData?> fetchExistingSettings(Ref ref, String amberPubkey) async {
    final storage = ref.read(storageNotifierProvider.notifier);
    final results = await storage.query(
      RequestFilter<CustomData>(
        authors: {amberPubkey},
        tags: {
          '#d': {kSettingsIdentifier},
        },
      ).toRequest(),
      source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
      subscriptionPrefix: 'app-settings-check',
    );
    return results.firstOrNull;
  }

  /// Decrypt the settings content. Invalid or legacy list content degrades
  /// gracefully to an empty object.
  Future<Map<String, dynamic>> decryptSettings(
    Signer signer,
    CustomData settings,
  ) async {
    try {
      final decrypted = await signer.nip44Decrypt(
        settings.content,
        signer.pubkey,
      );
      final decoded = jsonDecode(decrypted);
      if (decoded is Map<String, dynamic>) return decoded;
      if (decoded is List) {
        return {_kDeviceBackupsKey: decoded};
      }
    } catch (_) {
      return {};
    }
    return {};
  }

  /// Decode device backup entries from an encrypted settings event.
  Future<List<Map<String, dynamic>>> decryptEntries(
    Signer signer,
    CustomData settings,
  ) async {
    final decodedSettings = await decryptSettings(signer, settings);
    final entries = decodedSettings[_kDeviceBackupsKey];
    if (entries is! List) return [];
    return entries.whereType<Map<String, dynamic>>().toList();
  }

  /// Back up the current device key. Reads existing entries, upserts the
  /// current device, then publishes the updated array.
  Future<void> backupDeviceKey({
    required Ref ref,
    required Signer amberSigner,
  }) async {
    final deviceKeyService = ref.read(deviceKeyServiceProvider);
    final privateKeyHex = await deviceKeyService.getOrCreatePrivateKey();
    final deviceName = await _getDeviceName();

    // Load and preserve existing encrypted settings fields.
    final existing = await fetchExistingSettings(ref, amberSigner.pubkey);
    final settings = existing != null
        ? await decryptSettings(amberSigner, existing)
        : <String, dynamic>{};
    final entries = (settings[_kDeviceBackupsKey] as List?)
            ?.whereType<Map<String, dynamic>>()
            .toList() ??
        <Map<String, dynamic>>[];

    // Upsert this device's entry (match by pk)
    entries.removeWhere((e) => e['pk'] == privateKeyHex);
    entries.add({
      'pk': privateKeyHex,
      'name': deviceName,
      'ts': DateTime.now().millisecondsSinceEpoch,
    });
    settings[_kDeviceBackupsKey] = entries;

    final encrypted = await amberSigner.nip44Encrypt(
      jsonEncode(settings),
      amberSigner.pubkey,
    );

    final partial = PartialCustomData(
      identifier: kSettingsIdentifier,
      content: encrypted,
    );

    final signed = await partial.signWith(amberSigner);
    final storage = ref.read(storageNotifierProvider.notifier);
    await storage.save({signed});
    storage.publish({signed}, relays: 'AppCatalog');
  }

  /// Move Amber-authored encrypted AppStacks to equivalent device-key stacks.
  ///
  /// Existing device-key stacks are merged, not overwritten. Legacy identifiers
  /// from the pre-device-key branch are normalized to the current d-tags.
  Future<bool> migratePrivateStacksToDeviceKey({
    required Ref ref,
    required Signer amberSigner,
  }) async {
    final keyService = ref.read(deviceKeyServiceProvider);
    final devicePubkey = ref.read(devicePubkeyProvider);
    if (devicePubkey == null) return false;

    if (await keyService.hasPrivateStacksMigrated(
      amberSigner.pubkey,
      devicePubkey,
    )) {
      return true;
    }

    final deviceSigner = ref.read(Signer.signerProvider(devicePubkey));
    if (deviceSigner == null) return false;

    final storage = ref.read(storageNotifierProvider.notifier);
    final platform = ref.read(packageManagerProvider.notifier).platform;

    final amberStacks = await storage.query(
      RequestFilter<AppStack>(authors: {amberSigner.pubkey}).toRequest(),
      source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
      subscriptionPrefix: 'app-private-stack-migration',
    );

    final encryptedAmberStacks = amberStacks
        .where((stack) => stack.content.isNotEmpty)
        .toList();
    if (encryptedAmberStacks.isEmpty) {
      return false;
    }

    var migratedAny = false;
    for (final stack in encryptedAmberStacks) {
      final identifier = _deviceIdentifierFor(stack.identifier);
      final oldAppIds = stack.privateAppIds;
      if (oldAppIds.isEmpty) continue;

      final existingDeviceStacks = await storage.query(
        RequestFilter<AppStack>(
          authors: {devicePubkey},
          tags: {
            '#d': {identifier},
          },
        ).toRequest(),
        source: const LocalSource(),
        subscriptionPrefix: 'app-private-stack-migration-device',
      );
      final existingAppIds =
          existingDeviceStacks.firstOrNull?.privateAppIds ?? const <String>[];
      final mergedAppIds = [
        ...existingAppIds,
        ...oldAppIds.where((id) => !existingAppIds.contains(id)),
      ];

      if (mergedAppIds.length == existingAppIds.length) continue;

      final partial = PartialAppStack.withEncryptedApps(
        name: stack.name ?? identifier,
        identifier: identifier,
        apps: mergedAppIds,
        platform: platform,
      );

      final signed = await partial.signWith(deviceSigner);
      await storage.save({signed});
      storage.publish({signed}, relays: 'AppCatalog');
      migratedAny = true;
    }

    await keyService.markPrivateStacksMigrated(
      amberSigner.pubkey,
      devicePubkey,
    );
    if (migratedAny) {
      LogService.I.info(
        'migrated Amber private stacks to device key',
        tag: 'backup',
      );
    }
    return true;
  }

  /// Replace the stored device key and register the restored signer immediately.
  Future<void> restoreDeviceKey({
    required Ref ref,
    required String privateKeyHex,
  }) async {
    await ref.read(deviceKeyServiceProvider).replacePrivateKey(privateKeyHex);
    final restoredSigner = Bip340PrivateKeySigner(privateKeyHex, ref);
    await restoredSigner.signIn(setAsActive: false);
    ref.read(devicePubkeyProvider.notifier).state = restoredSigner.pubkey;
  }

  Future<String> _getDeviceName() async {
    try {
      if (Platform.isAndroid) {
        final info = await DeviceInfoPlugin().androidInfo;
        return info.model;
      }
    } catch (_) {}
    return 'Android device';
  }
}

final deviceBackupServiceProvider = Provider<DeviceBackupService>(
  (ref) => DeviceBackupService(),
);

String _deviceIdentifierFor(String identifier) {
  return switch (identifier) {
    _kLegacyInstalledAppsBackupIdentifier => kInstalledAppsIdentifier,
    _kLegacyIgnoredAppsIdentifier => kUnmanagedAppsIdentifier,
    _ => identifier,
  };
}

/// Decoded device entry for display in the restore dialog.
class DeviceBackupInfo {
  DeviceBackupInfo({
    required this.privateKeyHex,
    required this.deviceName,
    this.backedUpAt,
  });

  final String privateKeyHex;
  final String deviceName;
  final DateTime? backedUpAt;
}

/// Called after successful Amber sign-in. Automatically backs up the device
/// key, then shows a restore dialog only if other device entries exist.
Future<void> maybeOfferDeviceBackup(Ref ref) async {
  final amberPubkey = ref.read(Signer.activePubkeyProvider);
  if (amberPubkey == null) return;

  final keyService = ref.read(deviceKeyServiceProvider);

  final service = ref.read(deviceBackupServiceProvider);
  final signer = ref.read(Signer.activeSignerProvider);
  if (signer == null) return;

  try {
    final hasBackupBeenOffered = await keyService.hasBackupBeenOffered(
      amberPubkey,
    );
    final backup = hasBackupBeenOffered
        ? null
        : await service.fetchExistingSettings(ref, amberPubkey);

    final entries = backup != null
        ? await service.decryptEntries(signer, backup)
        : const <Map<String, dynamic>>[];
    final currentPrivateKey = await keyService.getOrCreatePrivateKey();

    final otherDevices = entries
        .where((e) => e['pk'] != currentPrivateKey)
        .map(
          (e) => DeviceBackupInfo(
            privateKeyHex: e['pk'] as String,
            deviceName: e['name'] as String? ?? 'Unknown device',
            backedUpAt: e['ts'] != null
                ? DateTime.fromMillisecondsSinceEpoch(e['ts'] as int)
                : null,
          ),
        )
        .toList();

    if (otherDevices.isNotEmpty) {
      final context = rootNavigatorKey.currentState?.overlay?.context;
      if (context == null || !context.mounted) {
        return;
      }

      final selectedBackup = await showDialog<DeviceBackupInfo>(
        context: context,
        barrierDismissible: false,
        builder: (_) => DeviceBackupRestoreDialog(backups: otherDevices),
      );

      if (selectedBackup != null) {
        await service.restoreDeviceKey(
          ref: ref,
          privateKeyHex: selectedBackup.privateKeyHex,
        );
      }
    }

    await service.migratePrivateStacksToDeviceKey(
      ref: ref,
      amberSigner: signer,
    );

    if (hasBackupBeenOffered) return;

    // Always back up whichever device key is active after optional restore.
    await service.backupDeviceKey(ref: ref, amberSigner: signer);
    await keyService.markBackupOffered(amberPubkey);
  } catch (e, st) {
    LogService.I.warn(
      'device backup/restore check failed',
      tag: 'backup',
      err: e,
      stack: st,
    );
  }
}
