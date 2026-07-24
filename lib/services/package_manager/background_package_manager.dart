import 'package:models/models.dart';
import 'package:flutter/services.dart';
import 'package:zapstore/services/package_manager/installed_packages_snapshot.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/log_service.dart';

/// Background-safe PackageManager that avoids EventChannel usage.
final class BackgroundPackageManager extends PackageManager {
  BackgroundPackageManager(super.ref);

  static const _methodChannel = MethodChannel('android_package_manager');

  // Resolved from the device's ABIs, same as the foreground manager. The
  // background entry point initializes the cache before this is read;
  // if that ever fails it falls back to [kDefaultPlatformTag].
  @override
  String get platform => DeviceCapabilitiesCache.capabilities.platformTag;

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
    required Installable target,
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
    try {
      final installedApps =
          await _methodChannel
              .invokeMethod<List<Object?>>('getInstalledApps', {
                'includeSystemApps': false,
              })
              .timeout(const Duration(seconds: 10)) ??
          [];

      final packages = <String, PackageInfo>{};
      for (final appObj in installedApps) {
        final app = Map<String, dynamic>.from(appObj as Map<Object?, Object?>);
        final appId =
            app['bundleId'] as String? ?? app['packageName'] as String? ?? '';
        if (appId.isEmpty) continue;

        final rawHashes = app['signatureHashes'];
        packages[appId] = PackageInfo(
          appId: appId,
          name: app['name'] as String?,
          version: app['versionName'] as String? ?? '0.0.0',
          versionCode: app['versionCode'] as int?,
          signatureHashes: rawHashes is List
              ? rawHashes.cast<String>().toList()
              : const [],
          installTime: null,
          canInstallSilently: app['canInstallSilently'] as bool? ?? false,
        );
      }

      state = state.copyWith(installed: packages);
      await InstalledPackagesSnapshot.save(packages);
    } catch (e, st) {
      LogService.I.warn(
        'native background package scan failed; using snapshot',
        tag: 'background_updates',
        err: e,
        stack: st,
      );
      final installed = await InstalledPackagesSnapshot.load();
      state = state.copyWith(installed: installed);
    }
  }
}
