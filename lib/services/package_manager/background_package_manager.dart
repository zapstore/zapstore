import 'package:models/models.dart';
import 'package:zapstore/services/package_manager/installed_packages_snapshot.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';

/// Background-safe PackageManager that avoids EventChannel usage.
final class BackgroundPackageManager extends PackageManager {
  BackgroundPackageManager(super.ref);

  // Zapstore currently targets arm64 APKs for background checks.
  @override
  String get platform => 'android-arm64-v8a';

  @override
  String get packageExtension => '.apk';

  @override
  bool get supportsSilentInstall => false;

  @override
  Future<void> install(
    String appId,
    String filePath, {
    required String expectedHash,
    required int expectedSize,
    required FileMetadata target,
  }) {
    throw UnsupportedError('Install not supported in background');
  }

  @override
  Future<void> uninstall(String appId) {
    throw UnsupportedError('Uninstall not supported in background');
  }

  @override
  Future<void> launchApp(String appId) {
    throw UnsupportedError('Launch not supported in background');
  }

  @override
  Future<void> requestPermission() {
    throw UnsupportedError('Permission not supported in background');
  }

  @override
  Future<bool> hasPermission() async => false;

  @override
  Future<void> syncInstalledPackages() async {
    final installed = await InstalledPackagesSnapshot.load();
    state = state.copyWith(installed: installed);
  }
}
