import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:models/models.dart';

const _storage = FlutterSecureStorage(
  aOptions: AndroidOptions(encryptedSharedPreferences: true),
  iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock),
);

const _kSecurePrefsKey = 'zapstore_secure_prefs';

/// Manages all device-local secrets in a single secure storage entry.
///
/// Stored as a JSON object with keys:
///   - `nsec`: device private key (hex)
///   - `backup_offered`: list of Amber pubkeys already offered backup dialog
///   - `private_stacks_migrated`: amber/device pubkey pairs already migrated
class DeviceKeyService {
  Map<String, dynamic>? _cache;

  Future<Map<String, dynamic>> _load() async {
    if (_cache != null) return _cache!;
    final raw = await _storage.read(key: _kSecurePrefsKey);
    _cache = (raw != null && raw.isNotEmpty)
        ? jsonDecode(raw) as Map<String, dynamic>
        : <String, dynamic>{};
    return _cache!;
  }

  Future<void> _persist() async {
    await _storage.write(key: _kSecurePrefsKey, value: jsonEncode(_cache));
  }

  /// Load existing device key or generate a new one. Returns hex private key.
  Future<String> getOrCreatePrivateKey() async {
    final prefs = await _load();
    final existing = prefs['nsec'] as String?;
    if (existing != null && existing.isNotEmpty) return existing;

    final privateKeyHex = Utils.generateRandomHex64();
    prefs['nsec'] = privateKeyHex;
    await _persist();
    return privateKeyHex;
  }

  /// Returns the bech32-encoded private key for display/copy.
  Future<String> getNsec() async {
    final hex = await getOrCreatePrivateKey();
    return bech32Encode('nsec', hex);
  }

  /// Replace the current device key (used during restore from backup).
  Future<void> replacePrivateKey(String privateKeyHex) async {
    final prefs = await _load();
    prefs['nsec'] = privateKeyHex;
    await _persist();
  }

  /// Whether the backup/restore dialog has been offered for [pubkey].
  Future<bool> hasBackupBeenOffered(String pubkey) async {
    final prefs = await _load();
    final list = (prefs['backup_offered'] as List?)?.cast<String>() ?? [];
    return list.contains(pubkey);
  }

  /// Mark that the backup dialog was shown for [pubkey].
  Future<void> markBackupOffered(String pubkey) async {
    final prefs = await _load();
    final list = (prefs['backup_offered'] as List?)?.cast<String>() ?? [];
    if (!list.contains(pubkey)) {
      list.add(pubkey);
      prefs['backup_offered'] = list;
      await _persist();
    }
  }

  /// Whether Amber-authored private stacks have been migrated to [devicePubkey].
  Future<bool> hasPrivateStacksMigrated(
    String amberPubkey,
    String devicePubkey,
  ) async {
    final prefs = await _load();
    final list =
        (prefs['private_stacks_migrated'] as List?)?.cast<String>() ?? [];
    return list.contains(_migrationKey(amberPubkey, devicePubkey));
  }

  /// Mark private stack migration complete for [devicePubkey].
  Future<void> markPrivateStacksMigrated(
    String amberPubkey,
    String devicePubkey,
  ) async {
    final prefs = await _load();
    final list =
        (prefs['private_stacks_migrated'] as List?)?.cast<String>() ?? [];
    final key = _migrationKey(amberPubkey, devicePubkey);
    if (!list.contains(key)) {
      list.add(key);
      prefs['private_stacks_migrated'] = list;
      await _persist();
    }
  }

  String _migrationKey(String amberPubkey, String devicePubkey) =>
      '$amberPubkey:$devicePubkey';
}

final deviceKeyServiceProvider = Provider<DeviceKeyService>(
  (ref) => DeviceKeyService(),
);

/// The device pubkey (hex). Available after storageReadyProvider resolves.
final devicePubkeyProvider = StateProvider<String?>((_) => null);
