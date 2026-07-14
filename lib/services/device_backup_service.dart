import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:io';

import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/router.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/device_private_event_service.dart';
import 'package:zapstore/services/device_private_sync_service.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/trusted_signers_service.dart';
import 'package:zapstore/widgets/device_backup_dialog.dart';

const _kSettingsFormatVersion = 2;
const _kRecoveriesKey = 'recoveries';
const _kRecoveryAuthorization = 'zapstore-device-key-authorization-v1';
const _kLegacyInstalledAppsBackupIdentifier = 'zapstore-installed-backup';
const _kLegacyUnmanagedAppsIdentifier = 'zapstore-ignored-apps';

/// Manages device-signed settings recovery and legacy Amber stack migration.
class DeviceBackupService {
  final Set<Request<Model<dynamic>>> _activeRequests = {};
  bool _cancelled = false;

  void beginWork() => _cancelled = false;

  void _checkCancelled() {
    if (_cancelled) throw const DeviceBackupCancelled();
  }

  Future<CustomData?> fetchExistingSettings(
    Ref ref,
    String devicePubkey,
  ) async {
    final results = await _queryTracked(
      ref,
      RequestFilter<CustomData>(
        authors: {devicePubkey},
        tags: {
          '#d': {kSettingsIdentifier},
        },
        limit: 1,
      ).toRequest(),
      source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
      subscriptionPrefix: 'app-device-settings-upsert',
    );
    return results.firstOrNull;
  }

  /// Finds device-authored settings events recoverable by [amberSigner].
  Future<List<DeviceBackupInfo>> fetchRecoveryCandidates({
    required Ref ref,
    required Signer amberSigner,
  }) async {
    _checkCancelled();
    final request = RequestFilter<CustomData>(
      tags: {
        '#d': {kSettingsIdentifier},
        '#p': {amberSigner.pubkey},
      },
      limit: 50,
    ).toRequest();
    final settingsEvents = await _queryTracked(
      ref,
      request,
      source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
      subscriptionPrefix: 'app-device-recovery',
    );

    final candidates = <String, DeviceBackupInfo>{};
    for (final settings in settingsEvents) {
      _checkCancelled();
      if (!verifySignedEvent(ref, settings.event) ||
          !Nip13.isValid(
            settings.event,
            minimumDifficulty: kPrivateEventPowDifficulty,
          )) {
        continue;
      }
      final envelope = _decodeSettingsEnvelope(settings.content);
      if (envelope == null) continue;
      final recoveries = envelope[_kRecoveriesKey];
      if (recoveries is! List) continue;

      for (final raw in recoveries.whereType<Map>()) {
        final recovery = Map<String, dynamic>.from(raw);
        if (recovery['p'] != amberSigner.pubkey) continue;
        final ciphertext = recovery['content'];
        if (ciphertext is! String || ciphertext.isEmpty) continue;
        try {
          final plaintext = await amberSigner.nip44Decrypt(
            ciphertext,
            settings.pubkey,
          );
          _checkCancelled();
          final capsule = Map<String, dynamic>.from(
            jsonDecode(plaintext) as Map,
          );
          final privateKey = capsule['pk'];
          final authorization = capsule['authorization'];
          if (privateKey is! String ||
              privateKey.length != 64 ||
              Utils.derivePublicKey(privateKey) != settings.pubkey ||
              authorization is! Map ||
              !validateRecoveryAuthorization(
                ref,
                Map<String, dynamic>.from(authorization),
                amberPubkey: amberSigner.pubkey,
                devicePubkey: settings.pubkey,
              )) {
            continue;
          }
          candidates[privateKey] = DeviceBackupInfo(
            privateKeyHex: privateKey,
            deviceName: capsule['name'] as String? ?? 'Android device',
            backedUpAt: capsule['ts'] is int
                ? DateTime.fromMillisecondsSinceEpoch(capsule['ts'] as int)
                : null,
          );
        } catch (_) {
          // Invalid or unrelated recovery capsules degrade gracefully.
        }
      }
    }
    return candidates.values.toList(growable: false);
  }

  /// Upserts a recovery capsule for the active Amber identity.
  Future<void> backupDeviceKey({
    required Ref ref,
    required Signer amberSigner,
  }) async {
    _checkCancelled();
    final privateEvents = ref.read(devicePrivateEventServiceProvider);
    final keyService = ref.read(deviceKeyServiceProvider);
    final privateKeyHex = await keyService.getOrCreatePrivateKey();
    final devicePubkey = privateEvents.devicePubkey;
    final existing = await fetchExistingSettings(ref, devicePubkey);
    final envelope = existing != null
        ? _decodeSettingsEnvelope(existing.content) ?? _emptySettingsEnvelope()
        : _emptySettingsEnvelope();

    final deviceName = await _getDeviceName();
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    final authorizationPartial = PartialNote(_kRecoveryAuthorization);
    authorizationPartial.event.addTagValue('device', devicePubkey);
    authorizationPartial.event.addTagValue('p', amberSigner.pubkey);
    final authorization = await authorizationPartial.signWith(amberSigner);
    _checkCancelled();
    final capsule = await privateEvents.encryptFor(
      jsonEncode({
        'pk': privateKeyHex,
        'name': deviceName,
        'ts': timestamp,
        'authorization': authorization.event.toMap(),
      }),
      amberSigner.pubkey,
    );
    _checkCancelled();

    final recoveries =
        (envelope[_kRecoveriesKey] as List?)
            ?.whereType<Map>()
            .map((entry) => Map<String, dynamic>.from(entry))
            .toList() ??
        <Map<String, dynamic>>[];
    recoveries.removeWhere((entry) => entry['p'] == amberSigner.pubkey);
    recoveries.add({
      'p': amberSigner.pubkey,
      'content': capsule,
      'ts': timestamp,
    });
    envelope[_kRecoveriesKey] = recoveries;

    final partial = PartialCustomData(
      identifier: kSettingsIdentifier,
      content: jsonEncode(envelope),
    );
    for (final recovery in recoveries) {
      final pubkey = recovery['p'];
      if (pubkey is String) partial.event.addTagValue('p', pubkey);
    }
    partial.event.setTagValue('format', 'zapstore-device-settings-v2');
    partial.event.createdAt = privateEvents.nextReplaceableTimestamp(
      existing?.createdAt,
    );
    await privateEvents.signAndSave(partial);
  }

  /// Merges every Amber-authored encrypted AppStack into device ownership.
  Future<bool> migratePrivateStacksToDeviceKey({
    required Ref ref,
    required Signer amberSigner,
  }) async {
    _checkCancelled();
    final request = RequestFilter<AppStack>(
      authors: {amberSigner.pubkey},
    ).toRequest();
    final amberStacks = await _queryTracked(
      ref,
      request,
      source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
      subscriptionPrefix: 'app-private-stack-migration',
    );

    final privateEvents = ref.read(devicePrivateEventServiceProvider);
    final storage = ref.read(storageNotifierProvider.notifier);
    final platform = ref.read(packageManagerProvider.notifier).platform;
    var migratedAny = false;

    for (final stack in amberStacks.where(
      (stack) => stack.content.isNotEmpty,
    )) {
      _checkCancelled();
      if (!verifySignedEvent(ref, stack.event)) {
        LogService.I.warn(
          'ignored unverified Amber private stack',
          tag: 'backup',
          fields: {'identifier': stack.identifier},
        );
        continue;
      }
      final oldAppIds = await decryptAmberStackAppIds(amberSigner, stack);
      _checkCancelled();
      if (oldAppIds == null) {
        LogService.I.warn(
          'could not decrypt Amber private stack; migration remains retryable',
          tag: 'backup',
          fields: {'identifier': stack.identifier},
        );
        continue;
      }

      final identifier = _deviceIdentifierFor(stack.identifier);
      final existing = (await storage.query(
        RequestFilter<AppStack>(
          authors: {privateEvents.devicePubkey},
          tags: {
            '#d': {identifier},
          },
          limit: 1,
        ).toRequest(),
        source: const LocalSource(),
        subscriptionPrefix: 'app-private-stack-migration-device',
      )).firstOrNull;
      if (existing != null) {
        await existing.prepareAfterLoading(ref);
        _checkCancelled();
        if (!existing.isDecrypted) {
          LogService.I.warn(
            'could not decrypt device stack; migration remains retryable',
            tag: 'backup',
            fields: {'identifier': identifier},
          );
          continue;
        }
      }

      final merged = LinkedHashSet<String>.of(
        existing?.privateAppIds ?? const [],
      )..addAll(oldAppIds);
      if (existing != null &&
          merged.length == existing.privateAppIds.toSet().length) {
        continue;
      }

      final partial = PartialAppStack.withEncryptedApps(
        name: stack.name ?? identifier,
        identifier: identifier,
        description: stack.description,
        apps: merged.toList(growable: false),
        platform: stack.platform ?? platform,
      );
      partial.event.createdAt = privateEvents.nextReplaceableTimestamp(
        existing?.createdAt,
      );
      await privateEvents.signAndSave(partial);
      migratedAny = true;
    }

    if (migratedAny) {
      LogService.I.info(
        'migrated Amber private stacks to device key',
        tag: 'backup',
      );
    }
    return migratedAny;
  }

  Future<void> restoreDeviceKey({
    required Ref ref,
    required String privateKeyHex,
  }) async {
    await ref.read(deviceKeyServiceProvider).replacePrivateKey(privateKeyHex);
    final restoredSigner = Bip340PrivateKeySigner(privateKeyHex, ref);
    await restoredSigner.signIn(setAsActive: false);
    ref.read(devicePubkeyProvider.notifier).state = restoredSigner.pubkey;
  }

  Future<List<E>> _queryTracked<E extends Model<dynamic>>(
    Ref ref,
    Request<E> request, {
    required Source source,
    required String subscriptionPrefix,
  }) async {
    _checkCancelled();
    _activeRequests.add(request);
    try {
      final results = await ref
          .read(storageNotifierProvider.notifier)
          .query(
            request,
            source: source,
            subscriptionPrefix: subscriptionPrefix,
          );
      _checkCancelled();
      return results;
    } finally {
      _activeRequests.remove(request);
    }
  }

  void cancelCurrentWork(Ref ref) {
    _cancelled = true;
    final storage = ref.read(storageNotifierProvider.notifier);
    for (final request in _activeRequests.toList(growable: false)) {
      unawaited(storage.cancel(request));
    }
    _activeRequests.clear();
    ref.read(devicePrivateEventServiceProvider).cancelMining();
  }

  Future<String> _getDeviceName() async {
    try {
      if (Platform.isAndroid) {
        return (await DeviceInfoPlugin().androidInfo).model;
      }
    } catch (_) {}
    return 'Android device';
  }
}

/// Explicitly decrypts an imperatively queried Amber stack.
///
/// Imperative storage queries do not run EncryptableModel preparation, so
/// migration must not rely on [AppStack.privateAppIds] being populated.
Future<List<String>?> decryptAmberStackAppIds(
  Signer amberSigner,
  AppStack stack,
) async {
  try {
    final plaintext = await amberSigner.nip44Decrypt(
      stack.content,
      amberSigner.pubkey,
    );
    final decoded = jsonDecode(plaintext);
    return decoded is List ? decoded.whereType<String>().toList() : null;
  } catch (_) {
    return null;
  }
}

final deviceBackupServiceProvider = Provider<DeviceBackupService>(
  (ref) => DeviceBackupService(),
);

Map<String, dynamic> _emptySettingsEnvelope() => {
  'version': _kSettingsFormatVersion,
  _kRecoveriesKey: <Map<String, dynamic>>[],
};

Map<String, dynamic>? _decodeSettingsEnvelope(String content) {
  try {
    final decoded = jsonDecode(content);
    if (decoded is! Map) return null;
    final envelope = Map<String, dynamic>.from(decoded);
    if (envelope['version'] != _kSettingsFormatVersion) return null;
    return envelope;
  } catch (_) {
    return null;
  }
}

/// Verifies that Amber explicitly authorized the recovered device pubkey.
bool validateRecoveryAuthorization(
  Ref ref,
  Map<String, dynamic> authorization, {
  required String amberPubkey,
  required String devicePubkey,
}) {
  try {
    if (authorization['kind'] != 1 ||
        authorization['pubkey'] != amberPubkey ||
        authorization['content'] != _kRecoveryAuthorization) {
      return false;
    }
    final event = PartialEvent<Model<dynamic>>(authorization, 1);
    if (!event.getTagSetValues('device').contains(devicePubkey) ||
        !event.getTagSetValues('p').contains(amberPubkey) ||
        authorization['id'] != Utils.getEventId(event, amberPubkey)) {
      return false;
    }
    return ref.read(verifierProvider).verify(authorization);
  } catch (_) {
    return false;
  }
}

String _deviceIdentifierFor(String identifier) {
  return switch (identifier) {
    _kLegacyInstalledAppsBackupIdentifier => kInstalledAppsIdentifier,
    _kLegacyUnmanagedAppsIdentifier => kUnmanagedAppsIdentifier,
    _ => identifier,
  };
}

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

final class DeviceBackupCancelled implements Exception {
  const DeviceBackupCancelled();
}

/// Runs recovery, migration, and backup after every successful Amber sign-in.
Future<void> maybeOfferDeviceBackup(Ref ref) async {
  final amberPubkey = ref.read(Signer.activePubkeyProvider);
  final amberSigner = ref.read(Signer.activeSignerProvider);
  if (amberPubkey == null || amberSigner == null) return;

  final keyService = ref.read(deviceKeyServiceProvider);
  final service = ref.read(deviceBackupServiceProvider);

  try {
    final alreadyOffered = await keyService.hasBackupBeenOffered(amberPubkey);
    List<DeviceBackupInfo> candidates = const [];
    try {
      candidates = await service.fetchRecoveryCandidates(
        ref: ref,
        amberSigner: amberSigner,
      );
    } catch (error, stack) {
      LogService.I.warn(
        'device recovery query failed',
        tag: 'backup',
        err: error,
        stack: stack,
      );
    }

    final currentPrivateKey = await keyService.getOrCreatePrivateKey();
    final otherDevices = candidates
        .where((entry) => entry.privateKeyHex != currentPrivateKey)
        .toList(growable: false);

    if (!alreadyOffered && otherDevices.isNotEmpty) {
      final context = rootNavigatorKey.currentState?.overlay?.context;
      if (context != null && context.mounted) {
        final selected = await showDialog<DeviceBackupInfo>(
          context: context,
          barrierDismissible: false,
          builder: (_) => DeviceBackupRestoreDialog(backups: otherDevices),
        );
        if (selected != null) {
          await service.restoreDeviceKey(
            ref: ref,
            privateKeyHex: selected.privateKeyHex,
          );
          await ref.read(devicePrivateSyncProvider.notifier).syncRestoredKey();
        }
      }
    }

    await service.migratePrivateStacksToDeviceKey(
      ref: ref,
      amberSigner: amberSigner,
    );
    await ref.read(trustServiceProvider).migrateLegacyAmberRecord(amberSigner);
    await service.backupDeviceKey(ref: ref, amberSigner: amberSigner);
    if (!alreadyOffered) {
      await keyService.markBackupOffered(amberPubkey);
    }
  } catch (error, stack) {
    LogService.I.warn(
      'device recovery/migration/backup failed',
      tag: 'backup',
      err: error,
      stack: stack,
    );
  }
}
