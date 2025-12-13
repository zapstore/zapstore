import 'dart:io';

import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';

/// Android implementation of PackageManager with integrated installation.
final class AndroidPackageManager extends PackageManager {
  AndroidPackageManager(super.ref);

  static const MethodChannel _channel = MethodChannel(
    'android_package_manager',
  );
  bool _supportsSilentInstall = false;

  @override
  String get platform => 'android-arm64-v8a';

  @override
  String get packageExtension => '.apk';

  @override
  Future<void> install(
    String appId,
    String filePath, {
    required String expectedHash,
    required int expectedSize,
    bool skipVerification = false,
  }) async {
    await _ensureInstallPermission();

    final file = File(filePath);
    if (!file.existsSync()) {
      throw Exception('APK file not found: $filePath');
    }

    final result = await _channel
        .invokeMethod<Map<Object?, Object?>>('install', {
          'filePath': filePath,
          'packageName': appId,
          'expectedHash': expectedHash,
          'expectedSize': expectedSize,
          'skipVerification': skipVerification,
        })
        .timeout(
          const Duration(minutes: 5),
          onTimeout: () => {
            'isSuccess': false,
            'errorMessage': 'Installation timed out - user did not respond',
          },
        );

    final resultMap = Map<String, dynamic>.from(result ?? {});

    if (!(resultMap['isSuccess'] == true)) {
      final error = resultMap['errorMessage'] ?? 'Installation failed';
      throw Exception(error);
    }

    // Refresh installed packages state
    await syncInstalledPackages();
  }

  @override
  Future<void> uninstall(String appId) async {
    final result = await _channel.invokeMethod<Map<Object?, Object?>>('uninstall', {
      'packageName': appId,
    });

    final resultMap = Map<String, dynamic>.from(result ?? {});

    if (!(resultMap['isSuccess'] == true)) {
      final wasCancelled = resultMap['cancelled'] == true;
      if (wasCancelled) {
        throw Exception('Uninstall was cancelled');
      }
      throw Exception('Uninstallation failed');
    }

    // Refresh installed packages state
    await syncInstalledPackages();
  }

  @override
  Future<void> requestPermission() async {
    try {
      final result = await _channel.invokeMethod<Map<Object?, Object?>>(
        'requestInstallPermission',
      );
      final resultMap = Map<String, dynamic>.from(result ?? {});

      if (!(resultMap['success'] == true)) {
        final error = resultMap['message'] ?? 'Failed to request permission';
        throw Exception(error);
      }
    } catch (e) {
      throw Exception(
        'CRITICAL: "Install unknown apps" permission is required.\n\n'
        'Please:\n'
        '1. Go to Android Settings\n'
        '2. Apps > ZapStore > Install unknown apps\n'
        '3. Enable "Allow from this source"\n'
        '4. Try installation again',
      );
    }
  }

  @override
  Future<bool> hasPermission() async {
    try {
      final hasUnknownSources =
          await _channel.invokeMethod<bool>('hasUnknownSourcesPermission') ??
          false;
      return hasUnknownSources;
    } catch (e) {
      return false;
    }
  }

  @override
  bool get supportsSilentInstall {
    // Cached capability determined asynchronously via plugin
    return _supportsSilentInstall;
  }

  /// Check if automatic, unattended updates are possible
  bool get supportsAutomaticUpdates {
    return _supportsSilentInstall;
  }

  /// Check if we can silently install/update a specific package
  @override
  Future<bool> canInstallSilently(String appId) async {
    try {
      final result =
          await _channel.invokeMethod<bool>('canInstallSilently', {
            'packageName': appId,
          }) ??
          false;
      return result;
    } catch (e) {
      return false;
    }
  }

  /// Install an update silently if possible, otherwise fall back to user confirmation
  Future<void> installUpdate(
    String appId,
    String filePath, {
    required String expectedHash,
    required int expectedSize,
  }) async {
    // Install regardless - the system will handle showing confirmation if needed
    await install(
      appId,
      filePath,
      expectedHash: expectedHash,
      expectedSize: expectedSize,
    );
  }

  /// Launch an installed app by its package identifier
  @override
  Future<void> launchApp(String appId) async {
    try {
      final result = await _channel
          .invokeMethod<Map<Object?, Object?>>('launchApp', {
            'packageName': appId,
          })
          .timeout(
            const Duration(seconds: 10),
            onTimeout: () => {
              'isSuccess': false,
              'errorMessage': 'App launch timed out after 10 seconds',
            },
          );

      final resultMap = Map<String, dynamic>.from(result ?? {});

      if (!(resultMap['isSuccess'] == true)) {
        final error = resultMap['errorMessage'] ?? 'Failed to launch app';
        throw Exception(error);
      }
    } catch (e) {
      throw Exception('Failed to launch app: $e');
    }
  }
  
  /// Re-launch a pending install prompt that was backgrounded.
  /// Returns true if there was a pending prompt and it was re-launched.
  Future<bool> retryPendingInstall(String appId) async {
    try {
      final result = await _channel
          .invokeMethod<Map<Object?, Object?>>('retryPendingInstall', {
            'packageName': appId,
          });

      final resultMap = Map<String, dynamic>.from(result ?? {});
      return resultMap['hasPending'] == true && resultMap['isSuccess'] == true;
    } catch (e) {
      return false;
    }
  }

  @override
  Future<void> syncInstalledPackages() async {
    try {
      // Check and cache silent install capability (general check)
      final canInstallSilently =
          await _channel.invokeMethod<bool>('canInstallSilently') ?? false;
      _supportsSilentInstall = canInstallSilently;

      // Get installed apps via method channel, excluding system apps
      final installedApps =
          await _channel.invokeMethod<List<Object?>>('getInstalledApps', {
            'includeSystemApps': false,
          }) ??
          [];

      final packages = <PackageInfo>[];

      for (final appObj in installedApps) {
        final app = Map<String, dynamic>.from(appObj as Map<Object?, Object?>);
        final appId =
            app['bundleId'] as String? ?? app['packageName'] as String? ?? '';
        final version = app['versionName'] as String? ?? '0.0.0';
        final versionCode = app['versionCode'] as int?;
        final signatureHash = app['signatureHash'] as String? ?? '';

        if (appId.isNotEmpty) {
          packages.add(
            PackageInfo(
              appId: appId,
              version: version,
              versionCode: versionCode,
              installTime: null, // Method channel doesn't provide install time
              signatureHash: signatureHash,
            ),
          );
        }
      }

      state = packages;
    } catch (e) {
      // Fallback to empty state on error
      state = [];
    }
  }

  Future<void> _ensureInstallPermission() async {
    if (!Platform.isAndroid) return;

    try {
      final status = await Permission.requestInstallPackages.status;
      if (status.isGranted) {
        return;
      }

      final newStatus = await Permission.requestInstallPackages.request();
      if (!newStatus.isGranted) {
        throw Exception(
          'Install permission required. Please enable "Install unknown apps" '
          'for Zapstore and try again.',
        );
      }
    } catch (e) {
      throw Exception('Unable to request install permission: $e');
    }
  }
}
