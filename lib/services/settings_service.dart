import 'dart:convert';

import 'package:amber_signer/amber_signer.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

const _storage = FlutterSecureStorage(
  aOptions: AndroidOptions(encryptedSharedPreferences: true),
  iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock),
);

/// All local settings stored as a single JSON blob in secure storage.
class LocalSettings {
  final String? nwcConnectionString;
  final Set<String>? appCatalogRelays;
  final DateTime? lastAppOpened;
  final DateTime? seenUntil;
  final DateTime? deletionSyncedUntil;
  final bool installedAppsBackupEnabled;

  const LocalSettings({
    this.nwcConnectionString,
    this.appCatalogRelays,
    this.lastAppOpened,
    this.seenUntil,
    this.deletionSyncedUntil,
    this.installedAppsBackupEnabled = false,
  });

  bool get hasNwcString => nwcConnectionString?.isNotEmpty == true;

  factory LocalSettings.fromJson(Map<String, dynamic> json) {
    return LocalSettings(
      nwcConnectionString: json['nwc'] as String?,
      appCatalogRelays: (json['relays'] as List?)?.cast<String>().toSet(),
      lastAppOpened: _parseDateTime(json['lastAppOpened']),
      seenUntil: _parseDateTime(json['seenUntil']),
      deletionSyncedUntil: _parseDateTime(json['deletionSyncedUntil']),
      installedAppsBackupEnabled: json['backupEnabled'] as bool? ?? false,
    );
  }

  Map<String, dynamic> toJson() => {
        if (nwcConnectionString != null) 'nwc': nwcConnectionString,
        if (appCatalogRelays != null) 'relays': appCatalogRelays!.toList(),
        if (lastAppOpened != null)
          'lastAppOpened': lastAppOpened!.millisecondsSinceEpoch,
        if (seenUntil != null) 'seenUntil': seenUntil!.millisecondsSinceEpoch,
        if (deletionSyncedUntil != null)
          'deletionSyncedUntil': deletionSyncedUntil!.millisecondsSinceEpoch,
        if (installedAppsBackupEnabled) 'backupEnabled': true,
      };

  LocalSettings copyWith({
    String? nwcConnectionString,
    Set<String>? appCatalogRelays,
    DateTime? lastAppOpened,
    DateTime? seenUntil,
    DateTime? deletionSyncedUntil,
    bool? installedAppsBackupEnabled,
    bool clearNwc = false,
  }) {
    return LocalSettings(
      nwcConnectionString:
          clearNwc ? null : (nwcConnectionString ?? this.nwcConnectionString),
      appCatalogRelays: appCatalogRelays ?? this.appCatalogRelays,
      lastAppOpened: lastAppOpened ?? this.lastAppOpened,
      seenUntil: seenUntil ?? this.seenUntil,
      deletionSyncedUntil: deletionSyncedUntil ?? this.deletionSyncedUntil,
      installedAppsBackupEnabled:
          installedAppsBackupEnabled ?? this.installedAppsBackupEnabled,
    );
  }

  static DateTime? _parseDateTime(dynamic value) =>
      value is int ? DateTime.fromMillisecondsSinceEpoch(value) : null;
}

/// Service for reading and writing local settings.
class SettingsService {
  static const _key = 'settings';

  // Legacy keys for migration
  static const _legacyNwcKey = 'nwc_connection_string';
  static const _legacyRelaysKey = 'app_catalog_relays';
  static const _legacyLastAppOpenedKey = 'last_app_opened';
  static const _legacySeenUntilKey = 'seen_until';
  static const _legacyDeletionSyncedUntilKey = 'deletion_synced_until';
  static const _legacyBackupKey = 'installed_apps_backup_enabled';

  Future<LocalSettings> load() async {
    final json = await _storage.read(key: _key);
    if (json != null && json.isNotEmpty) {
      try {
        return LocalSettings.fromJson(jsonDecode(json) as Map<String, dynamic>);
      } catch (_) {
        return const LocalSettings();
      }
    }

    // Migrate from legacy format if present
    return _migrateFromLegacy();
  }

  Future<LocalSettings> _migrateFromLegacy() async {
    final nwc = await _storage.read(key: _legacyNwcKey);
    final relaysJson = await _storage.read(key: _legacyRelaysKey);
    final lastAppOpened = await _storage.read(key: _legacyLastAppOpenedKey);
    final seenUntil = await _storage.read(key: _legacySeenUntilKey);
    final deletionSynced = await _storage.read(key: _legacyDeletionSyncedUntilKey);
    final backupEnabled = await _storage.read(key: _legacyBackupKey);

    // No legacy data found
    if ([nwc, relaysJson, lastAppOpened, seenUntil, deletionSynced, backupEnabled]
        .every((v) => v == null || v.isEmpty)) {
      return const LocalSettings();
    }

    // Parse legacy values
    Set<String>? relays;
    if (relaysJson != null && relaysJson.isNotEmpty) {
      try {
        relays = Set<String>.from((jsonDecode(relaysJson) as List).cast<String>());
      } catch (_) {}
    }

    final settings = LocalSettings(
      nwcConnectionString: (nwc?.isNotEmpty == true) ? nwc : null,
      appCatalogRelays: relays,
      lastAppOpened: _parseLegacyDateTime(lastAppOpened),
      seenUntil: _parseLegacyDateTime(seenUntil),
      deletionSyncedUntil: _parseLegacyDateTime(deletionSynced),
      installedAppsBackupEnabled: backupEnabled == 'true',
    );

    // Save migrated settings and clean up legacy keys
    await save(settings);
    await Future.wait([
      _storage.delete(key: _legacyNwcKey),
      _storage.delete(key: _legacyRelaysKey),
      _storage.delete(key: _legacyLastAppOpenedKey),
      _storage.delete(key: _legacySeenUntilKey),
      _storage.delete(key: _legacyDeletionSyncedUntilKey),
      _storage.delete(key: _legacyBackupKey),
    ]);

    return settings;
  }

  static DateTime? _parseLegacyDateTime(String? value) {
    if (value == null || value.isEmpty) return null;
    final ms = int.tryParse(value);
    return ms != null ? DateTime.fromMillisecondsSinceEpoch(ms) : null;
  }

  Future<void> save(LocalSettings settings) async {
    await _storage.write(key: _key, value: jsonEncode(settings.toJson()));
  }

  Future<LocalSettings> update(
      LocalSettings Function(LocalSettings) updater) async {
    final current = await load();
    final updated = updater(current);
    await save(updated);
    return updated;
  }
}

/// Persists the AmberSigner pubkey in secure storage.
class SecureStoragePubkeyPersistence implements AmberPubkeyPersistence {
  static const _key = 'amber_pubkey';

  @override
  Future<void> persistPubkey(String pubkey) =>
      _storage.write(key: _key, value: pubkey);

  @override
  Future<String?> loadPubkey() => _storage.read(key: _key);

  @override
  Future<void> clearPubkey() => _storage.delete(key: _key);
}

final settingsServiceProvider = Provider<SettingsService>(
  (ref) => SettingsService(),
);

/// Current local settings. Invalidate after updates to refresh UI.
final localSettingsProvider = FutureProvider<LocalSettings>((ref) async {
  return ref.watch(settingsServiceProvider).load();
});
