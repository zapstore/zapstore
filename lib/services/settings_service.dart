import 'dart:convert';

import 'package:amber_signer/amber_signer.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:zapstore/services/log_service.dart';

const _storage = FlutterSecureStorage(
  aOptions: AndroidOptions(encryptedSharedPreferences: true),
  iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock),
);

/// Portable preferences, mirrored by DeviceStateService.
class PortableSettings {
  final bool backgroundAutoUpdatesEnabled;
  final Set<String> trustedSigners;

  const PortableSettings({
    this.backgroundAutoUpdatesEnabled = false,
    this.trustedSigners = const {},
  });

  factory PortableSettings.fromJson(Map<String, dynamic> json) {
    return PortableSettings(
      backgroundAutoUpdatesEnabled:
          json['backgroundAutoUpdatesEnabled'] as bool? ?? false,
      trustedSigners:
          (json['trustedSigners'] as List?)?.whereType<String>().toSet() ??
          const {},
    );
  }

  Map<String, dynamic> toJson() => {
    'backgroundAutoUpdatesEnabled': backgroundAutoUpdatesEnabled,
    'trustedSigners': trustedSigners.toList()..sort(),
  };

  PortableSettings copyWith({
    bool? backgroundAutoUpdatesEnabled,
    Set<String>? trustedSigners,
  }) => PortableSettings(
    backgroundAutoUpdatesEnabled:
        backgroundAutoUpdatesEnabled ?? this.backgroundAutoUpdatesEnabled,
    trustedSigners: trustedSigners ?? this.trustedSigners,
  );
}

/// Per-install operational values. This data is deliberately never backed up.
class TempSettings {
  final DateTime? lastAppOpened;
  final DateTime? seenUntil;
  final DateTime? deletionSyncedUntil;
  final LogLevel logLevel;

  const TempSettings({
    this.lastAppOpened,
    this.seenUntil,
    this.deletionSyncedUntil,
    this.logLevel = LogLevel.debug,
  });

  factory TempSettings.fromJson(Map<String, dynamic> json) => TempSettings(
    lastAppOpened: _parseDateTime(json['lastAppOpened']),
    seenUntil: _parseDateTime(json['seenUntil']),
    deletionSyncedUntil: _parseDateTime(json['deletionSyncedUntil']),
    logLevel: LogLevel.parse(json['logLevel'] as String?) ?? LogLevel.debug,
  );

  Map<String, dynamic> toJson() => {
    if (lastAppOpened != null)
      'lastAppOpened': lastAppOpened!.millisecondsSinceEpoch,
    if (seenUntil != null) 'seenUntil': seenUntil!.millisecondsSinceEpoch,
    if (deletionSyncedUntil != null)
      'deletionSyncedUntil': deletionSyncedUntil!.millisecondsSinceEpoch,
    if (logLevel != LogLevel.debug) 'logLevel': logLevel.name,
  };

  TempSettings copyWith({
    DateTime? lastAppOpened,
    DateTime? seenUntil,
    DateTime? deletionSyncedUntil,
    LogLevel? logLevel,
  }) => TempSettings(
    lastAppOpened: lastAppOpened ?? this.lastAppOpened,
    seenUntil: seenUntil ?? this.seenUntil,
    deletionSyncedUntil: deletionSyncedUntil ?? this.deletionSyncedUntil,
    logLevel: logLevel ?? this.logLevel,
  );
}

/// Compatibility view over the three deliberate local storage entries.
class LocalSettings {
  final String? nwcConnectionString;
  final TempSettings temp;
  final PortableSettings portable;

  const LocalSettings({
    this.nwcConnectionString,
    this.temp = const TempSettings(),
    this.portable = const PortableSettings(),
  });

  DateTime? get lastAppOpened => temp.lastAppOpened;
  DateTime? get seenUntil => temp.seenUntil;
  DateTime? get deletionSyncedUntil => temp.deletionSyncedUntil;
  LogLevel get logLevel => temp.logLevel;
  bool get backgroundAutoUpdatesEnabled =>
      portable.backgroundAutoUpdatesEnabled;
  Set<String> get trustedSigners => portable.trustedSigners;
  bool get hasNwcString => nwcConnectionString?.isNotEmpty == true;

  LocalSettings copyWith({
    String? nwcConnectionString,
    DateTime? lastAppOpened,
    DateTime? seenUntil,
    DateTime? deletionSyncedUntil,
    bool? backgroundAutoUpdatesEnabled,
    Set<String>? trustedSigners,
    LogLevel? logLevel,
    bool clearNwc = false,
  }) => LocalSettings(
    nwcConnectionString: clearNwc
        ? null
        : (nwcConnectionString ?? this.nwcConnectionString),
    portable: portable.copyWith(
      backgroundAutoUpdatesEnabled: backgroundAutoUpdatesEnabled,
      trustedSigners: trustedSigners,
    ),
    temp: temp.copyWith(
      lastAppOpened: lastAppOpened,
      seenUntil: seenUntil,
      deletionSyncedUntil: deletionSyncedUntil,
      logLevel: logLevel,
    ),
  );
}

class SettingsService {
  static const settingsKey = 'settings';
  static const tempSettingsKey = 'temp_settings';
  static const nwcKey = 'nwc';

  Future<LocalSettings> load() async {
    final values = await Future.wait([
      _storage.read(key: settingsKey),
      _storage.read(key: tempSettingsKey),
      _storage.read(key: nwcKey),
    ]);
    return LocalSettings(
      portable: _decodePortable(values[0]),
      temp: _decodeTemp(values[1]),
      nwcConnectionString: values[2]?.isNotEmpty == true ? values[2] : null,
    );
  }

  Future<PortableSettings> loadPortable() async =>
      _decodePortable(await _storage.read(key: settingsKey));

  Future<TempSettings> loadTemp() async =>
      _decodeTemp(await _storage.read(key: tempSettingsKey));

  Future<void> save(LocalSettings settings) async {
    await Future.wait([
      _storage.write(
        key: settingsKey,
        value: jsonEncode(settings.portable.toJson()),
      ),
      _storage.write(
        key: tempSettingsKey,
        value: jsonEncode(settings.temp.toJson()),
      ),
      if (settings.nwcConnectionString?.isNotEmpty == true)
        _storage.write(key: nwcKey, value: settings.nwcConnectionString!)
      else
        _storage.delete(key: nwcKey),
    ]);
  }

  Future<void> savePortable(PortableSettings settings) =>
      _storage.write(key: settingsKey, value: jsonEncode(settings.toJson()));

  Future<void> saveTemp(TempSettings settings) => _storage.write(
    key: tempSettingsKey,
    value: jsonEncode(settings.toJson()),
  );

  Future<void> saveNwc(String? connectionString) =>
      connectionString?.isNotEmpty == true
      ? _storage.write(key: nwcKey, value: connectionString!)
      : _storage.delete(key: nwcKey);

  Future<LocalSettings> update(
    LocalSettings Function(LocalSettings) updater,
  ) async {
    final updated = updater(await load());
    await save(updated);
    return updated;
  }

  static PortableSettings _decodePortable(String? raw) {
    try {
      return raw == null || raw.isEmpty
          ? const PortableSettings()
          : PortableSettings.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (_) {
      return const PortableSettings();
    }
  }

  static TempSettings _decodeTemp(String? raw) {
    try {
      return raw == null || raw.isEmpty
          ? const TempSettings()
          : TempSettings.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (_) {
      return const TempSettings();
    }
  }
}

DateTime? _parseDateTime(dynamic value) =>
    value is int ? DateTime.fromMillisecondsSinceEpoch(value) : null;

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
