import 'dart:convert';

import 'package:amber_signer/amber_signer.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Service for securely storing sensitive data (NWC connection strings,
/// app catalog relays) using platform-native secure storage
/// (Keychain on iOS, KeyStore on Android).
///
/// This does NOT require user authentication - data is encrypted at rest
/// by the platform's secure storage mechanism.
class SecureStorageService {
  SecureStorageService();

  // Use explicit options for reliability across platforms
  static final _storage = FlutterSecureStorage(
    aOptions: const AndroidOptions(
      encryptedSharedPreferences: true,
    ),
    iOptions: const IOSOptions(
      accessibility: KeychainAccessibility.first_unlock,
    ),
  );

  static const _nwcKey = 'nwc_connection_string';
  static const _appCatalogRelaysKey = 'app_catalog_relays';

  /// Get the stored NWC connection string
  Future<String?> getNWCString() async {
    final value = await _storage.read(key: _nwcKey);
    return (value?.isNotEmpty == true) ? value : null;
  }

  /// Store an NWC connection string
  Future<void> setNWCString(String connectionString) async {
    await _storage.write(key: _nwcKey, value: connectionString);
  }

  /// Clear the stored NWC connection string
  Future<void> clearNWCString() async {
    await _storage.delete(key: _nwcKey);
  }

  /// Check if an NWC connection string is stored
  Future<bool> hasNWCString() async {
    final value = await _storage.read(key: _nwcKey);
    return value?.isNotEmpty == true;
  }

  // =========================================================================
  // Update Notification Throttling
  // =========================================================================

  static const _lastUpdateNotificationKey = 'last_update_notification';

  /// Get the last time an update notification was shown.
  Future<DateTime?> getLastUpdateNotificationTime() async {
    final value = await _storage.read(key: _lastUpdateNotificationKey);
    if (int.tryParse(value ?? '') case final ms?) {
      return DateTime.fromMillisecondsSinceEpoch(ms);
    }
    return null;
  }

  /// Store the last update notification time.
  Future<void> setLastUpdateNotificationTime(DateTime time) async {
    await _storage.write(
      key: _lastUpdateNotificationKey,
      value: '${time.millisecondsSinceEpoch}',
    );
  }

  // =========================================================================
  // App Catalog Relays
  // =========================================================================

  /// Get the stored app catalog relay URLs.
  ///
  /// Returns null if no relays have been stored (use defaults).
  /// Returns empty set if user explicitly cleared all relays (invalid state,
  /// but handled gracefully).
  Future<Set<String>?> getAppCatalogRelays() async {
    final json = await _storage.read(key: _appCatalogRelaysKey);
    if (json == null || json.isEmpty) return null;
    try {
      final list = jsonDecode(json) as List;
      return Set<String>.from(list.cast<String>());
    } catch (e) {
      // Corrupted data - treat as unset
      return null;
    }
  }

  /// Store app catalog relay URLs.
  ///
  /// This is the local source of truth for relay configuration,
  /// used to initialize the app before sign-in.
  Future<void> setAppCatalogRelays(Set<String> relays) async {
    await _storage.write(
      key: _appCatalogRelaysKey,
      value: jsonEncode(relays.toList()),
    );
  }
}

/// Persists the AmberSigner pubkey in flutter_secure_storage.
/// This survives app data clears (database deletion) and is encrypted.
class SecureStoragePubkeyPersistence implements AmberPubkeyPersistence {
  static const _key = 'amber_pubkey';

  @override
  Future<void> persistPubkey(String pubkey) async {
    await SecureStorageService._storage.write(key: _key, value: pubkey);
  }

  @override
  Future<String?> loadPubkey() async {
    return SecureStorageService._storage.read(key: _key);
  }

  @override
  Future<void> clearPubkey() async {
    await SecureStorageService._storage.delete(key: _key);
  }
}

final secureStorageServiceProvider = Provider<SecureStorageService>(
  (ref) => SecureStorageService(),
);

/// Whether an NWC connection string is currently stored.
///
/// Use `ref.invalidate(hasNwcStringProvider)` after updating or clearing the
/// stored string to refresh UI.
final hasNwcStringProvider = FutureProvider.autoDispose<bool>((ref) async {
  final secureStorage = ref.watch(secureStorageServiceProvider);
  return secureStorage.hasNWCString();
});
