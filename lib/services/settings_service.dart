import 'dart:convert';

import 'package:amber_signer/amber_signer.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:zapstore/services/log_service.dart';

const _storage = FlutterSecureStorage(
  aOptions: AndroidOptions(encryptedSharedPreferences: true),
  iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock),
);

/// All local settings stored as a single JSON blob in secure storage.
class LocalSettings {
  final String? nwcConnectionString;
  final DateTime? lastAppOpened;
  final DateTime? seenUntil;
  final DateTime? deletionSyncedUntil;
  final bool installedAppsBackupEnabled;
  final bool backgroundAutoUpdatesEnabled;
  final LogLevel logLevel;

  const LocalSettings({
    this.nwcConnectionString,
    this.lastAppOpened,
    this.seenUntil,
    this.deletionSyncedUntil,
    this.installedAppsBackupEnabled = false,
    this.backgroundAutoUpdatesEnabled = false,
    this.logLevel = LogLevel.debug,
  });

  bool get hasNwcString => nwcConnectionString?.isNotEmpty == true;

  factory LocalSettings.fromJson(Map<String, dynamic> json) {
    return LocalSettings(
      nwcConnectionString: json['nwc'] as String?,
      lastAppOpened: _parseDateTime(json['lastAppOpened']),
      seenUntil: _parseDateTime(json['seenUntil']),
      deletionSyncedUntil: _parseDateTime(json['deletionSyncedUntil']),
      installedAppsBackupEnabled: json['backupEnabled'] as bool? ?? false,
      backgroundAutoUpdatesEnabled:
          json['backgroundAutoUpdates'] as bool? ?? false,
      logLevel: LogLevel.parse(json['logLevel'] as String?) ?? LogLevel.debug,
    );
  }

  Map<String, dynamic> toJson() => {
    if (nwcConnectionString != null) 'nwc': nwcConnectionString,
    if (lastAppOpened != null)
      'lastAppOpened': lastAppOpened!.millisecondsSinceEpoch,
    if (seenUntil != null) 'seenUntil': seenUntil!.millisecondsSinceEpoch,
    if (deletionSyncedUntil != null)
      'deletionSyncedUntil': deletionSyncedUntil!.millisecondsSinceEpoch,
    if (installedAppsBackupEnabled) 'backupEnabled': true,
    if (backgroundAutoUpdatesEnabled) 'backgroundAutoUpdates': true,
    // Only persist non-default value to keep blob small.
    if (logLevel != LogLevel.debug) 'logLevel': logLevel.name,
  };

  LocalSettings copyWith({
    String? nwcConnectionString,
    DateTime? lastAppOpened,
    DateTime? seenUntil,
    DateTime? deletionSyncedUntil,
    bool? installedAppsBackupEnabled,
    bool? backgroundAutoUpdatesEnabled,
    LogLevel? logLevel,
    bool clearNwc = false,
  }) {
    return LocalSettings(
      nwcConnectionString: clearNwc
          ? null
          : (nwcConnectionString ?? this.nwcConnectionString),
      lastAppOpened: lastAppOpened ?? this.lastAppOpened,
      seenUntil: seenUntil ?? this.seenUntil,
      deletionSyncedUntil: deletionSyncedUntil ?? this.deletionSyncedUntil,
      installedAppsBackupEnabled:
          installedAppsBackupEnabled ?? this.installedAppsBackupEnabled,
      backgroundAutoUpdatesEnabled:
          backgroundAutoUpdatesEnabled ?? this.backgroundAutoUpdatesEnabled,
      logLevel: logLevel ?? this.logLevel,
    );
  }

  static DateTime? _parseDateTime(dynamic value) =>
      value is int ? DateTime.fromMillisecondsSinceEpoch(value) : null;
}

/// Service for reading and writing local settings.
class SettingsService {
  static const _key = 'settings';
  static const _discardedLegacyRelaysKey = 'app_catalog_relays';

  // Legacy keys for migration
  static const _legacyNwcKey = 'nwc_connection_string';
  static const _legacyLastAppOpenedKey = 'last_app_opened';
  static const _legacySeenUntilKey = 'seen_until';
  static const _legacyDeletionSyncedUntilKey = 'deletion_synced_until';
  static const _legacyBackupKey = 'installed_apps_backup_enabled';

  Future<LocalSettings> load() async {
    final json = await _storage.read(key: _key);
    if (json != null && json.isNotEmpty) {
      try {
        final decoded = jsonDecode(json) as Map<String, dynamic>;
        final settings = LocalSettings.fromJson(decoded);
        try {
          if (decoded.containsKey('relays')) {
            await save(settings);
          }
          await _storage.delete(key: _discardedLegacyRelaysKey);
        } catch (error, stack) {
          LogService.I.warn(
            'legacy relay settings cleanup failed',
            tag: 'settings',
            err: error,
            stack: stack,
          );
        }
        return settings;
      } catch (_) {
        return const LocalSettings();
      }
    }

    // Migrate from legacy format if present
    return _migrateFromLegacy();
  }

  Future<LocalSettings> _migrateFromLegacy() async {
    final nwc = await _storage.read(key: _legacyNwcKey);
    final lastAppOpened = await _storage.read(key: _legacyLastAppOpenedKey);
    final seenUntil = await _storage.read(key: _legacySeenUntilKey);
    final deletionSynced = await _storage.read(
      key: _legacyDeletionSyncedUntilKey,
    );
    final backupEnabled = await _storage.read(key: _legacyBackupKey);

    // No legacy data found
    if ([
      nwc,
      lastAppOpened,
      seenUntil,
      deletionSynced,
      backupEnabled,
    ].every((v) => v == null || v.isEmpty)) {
      return const LocalSettings();
    }

    final settings = LocalSettings(
      nwcConnectionString: (nwc?.isNotEmpty == true) ? nwc : null,
      lastAppOpened: _parseLegacyDateTime(lastAppOpened),
      seenUntil: _parseLegacyDateTime(seenUntil),
      deletionSyncedUntil: _parseLegacyDateTime(deletionSynced),
      installedAppsBackupEnabled: backupEnabled == 'true',
    );

    // Save migrated settings and clean up legacy keys
    await save(settings);
    await Future.wait([
      _storage.delete(key: _legacyNwcKey),
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
    LocalSettings Function(LocalSettings) updater,
  ) async {
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
