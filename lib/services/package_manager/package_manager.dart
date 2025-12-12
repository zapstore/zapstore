import 'dart:async';

import 'package:equatable/equatable.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/package_manager/dummy_package_manager.dart';
import 'package:zapstore/utils/version_utils.dart';

/// Information about an installed package
class PackageInfo extends Equatable {
  const PackageInfo({
    required this.appId,
    required this.version,
    required this.versionCode,
    required this.signatureHash,
    this.installTime,
  });

  /// The application identifier (bundle ID)
  final String appId;

  /// Version string (e.g., "1.2.3")
  final String version;

  /// Numeric version code (Android) or null for other platforms
  final int? versionCode;

  /// APK signature hash or certificate hash
  final String signatureHash;

  /// When the app was installed
  final DateTime? installTime;

  @override
  List<Object?> get props => [
    appId,
    version,
    versionCode,
    signatureHash,
    installTime,
  ];
}

/// Package management interface
abstract class PackageManager extends StateNotifier<List<PackageInfo>> {
  PackageManager(this.ref) : super([]);

  /// Riverpod ref for accessing storage and other dependencies.
  final Ref ref;

  /// Install an APK or package from the given file path.
  /// Returns when installation completes (success or failure).
  /// Throws on failure.
  Future<void> install(
    String appId,
    String filePath, {
    required String expectedHash,
    required int expectedSize,
    bool skipVerification = false,
  });

  /// Uninstall a package by its app identifier
  Future<void> uninstall(String appId);

  /// Launch an installed app by its app identifier
  Future<void> launchApp(String appId);

  /// Check if an app can be installed (version compatibility, etc.)
  ///
  /// Compares by versionCode first when available. If version codes are equal
  /// or unavailable, falls back to semantic version comparison.
  ///
  /// Uses the current state for installed packages check.
  bool canInstall(FileMetadata m, String version, {int? versionCode}) {
    final installed = state
        .where((p) => p.appId == m.appIdentifier)
        .firstOrNull;
    if (installed == null) return true;

    return canUpgrade(
      installed.versionCode?.toString() ?? installed.version,
      versionCode?.toString() ?? version,
    );
  }

  bool canUpdate(FileMetadata m) {
    final installed = state
        .where((p) => p.appId == m.appIdentifier)
        .firstOrNull;
    if (installed == null) return false;
    return canUpgrade(
      installed.versionCode?.toString() ?? installed.version,
      m.versionCode?.toString() ?? m.version,
    );
  }

  /// Request installation permissions from the user/system
  Future<void> requestPermission();

  /// Check if the app has installation permissions
  Future<bool> hasPermission();

  /// Whether the platform supports silent installation
  bool get supportsSilentInstall;

  /// Check if a specific package can be silently installed/updated
  /// Returns false if not supported or if the package requires user confirmation
  Future<bool> canInstallSilently(String appId);

  /// Target platform identifier used to filter file metadata (e.g., '#f' tag)
  /// Example: 'android-arm64-v8a' for Android arm64 builds
  String get platform;

  /// File extension for packages on this platform
  /// Example: '.apk' for Android, '.ipa' for iOS, '.dmg' for macOS
  String get packageExtension;

  /// Refresh the internal state of installed packages
  Future<void> syncInstalledPackages();

  /// Get all installed packages (returns current state)
  List<PackageInfo> getInstalledPackages() => state;

  /// Check if a package is installed
  bool isInstalled(String appId) {
    return state.any((p) => p.appId == appId);
  }

  /// Get package info for a specific app
  PackageInfo? getInfo(String appId) {
    try {
      return state.firstWhere((p) => p.appId == appId);
    } catch (e) {
      return null;
    }
  }
}

final packageManagerProvider =
    StateNotifierProvider<PackageManager, List<PackageInfo>>(
      DummyPackageManager.new,
    );

extension FileMetadataExt on FileMetadata {
  bool get canUpdate =>
      ref.read(packageManagerProvider.notifier).canUpdate(this);
}
