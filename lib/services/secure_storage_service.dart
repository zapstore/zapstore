import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Service for securely storing sensitive data (NWC connection strings)
/// using platform-native secure storage (Keychain on iOS, KeyStore on Android).
///
/// This does NOT require user authentication - data is encrypted at rest
/// by the platform's secure storage mechanism.
class SecureStorageService {
  SecureStorageService();

  static const _storage = FlutterSecureStorage();

  static const _nwcKey = 'nwc_connection_string';
  static const _installPermissionDialogKey = 'has_seen_install_permission_dialog';

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

  /// Check if the install permission dialog has been shown
  Future<bool> hasSeenInstallPermissionDialog() async {
    final value = await _storage.read(key: _installPermissionDialogKey);
    return value == 'true';
  }

  /// Mark the install permission dialog as shown
  Future<void> setHasSeenInstallPermissionDialog() async {
    await _storage.write(key: _installPermissionDialogKey, value: 'true');
  }
}

final secureStorageServiceProvider = Provider<SecureStorageService>(
  (ref) => SecureStorageService(),
);

