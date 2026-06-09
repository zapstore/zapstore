import 'package:flutter/services.dart';
import 'package:zapstore/services/log_service.dart';

/// Native install helpers usable from the WorkManager background isolate.
class BackgroundNativeInstaller {
  static const _channel = MethodChannel('android_package_manager');

  /// Verify APK hash and signing metadata without installing.
  static Future<bool> verifyApk({
    required String filePath,
    required String expectedHash,
    required List<String> expectedCertHashes,
  }) async {
    try {
      final result = await _channel.invokeMethod<Map<Object?, Object?>>(
        'verifyApk',
        {
          'filePath': filePath,
          'expectedHash': expectedHash,
          'expectedCertHashes': expectedCertHashes,
        },
      );
      final map = Map<String, dynamic>.from(result ?? {});
      return map['valid'] == true;
    } catch (e, st) {
      LogService.I.warn(
        'background APK verify failed',
        tag: 'background_updates',
        fields: {'filePath': filePath},
        err: e,
        stack: st,
      );
      return false;
    }
  }

  /// Install silently and block until a terminal result is known.
  static Future<BackgroundInstallResult> installAndAwait({
    required String appId,
    required String filePath,
    required String expectedHash,
    required int expectedSize,
    required List<String> expectedCertHashes,
  }) async {
    try {
      final result = await _channel.invokeMethod<Map<Object?, Object?>>(
        'installAndAwait',
        {
          'filePath': filePath,
          'packageName': appId,
          'expectedHash': expectedHash,
          'expectedSize': expectedSize,
          'expectedCertHashes': expectedCertHashes,
        },
      );
      final map = Map<String, dynamic>.from(result ?? {});
      return BackgroundInstallResult(
        success: map['success'] == true,
        cancelled: map['cancelled'] == true,
        needsUserAction: map['needsUserAction'] == true,
        error: map['error'] as String?,
      );
    } catch (e, st) {
      LogService.I.warn(
        'background installAndAwait failed',
        tag: 'background_updates',
        fields: {'appId': appId},
        err: e,
        stack: st,
      );
      return BackgroundInstallResult(success: false, error: e.toString());
    }
  }

  /// Launch the Android install confirmation dialog for a prepared manual update.
  static Future<bool> launchPreparedInstall({
    required String appId,
    required String filePath,
    required String expectedHash,
    required int expectedSize,
    required List<String> expectedCertHashes,
  }) async {
    try {
      final result = await _channel.invokeMethod<Map<Object?, Object?>>(
        'install',
        {
          'filePath': filePath,
          'packageName': appId,
          'expectedHash': expectedHash,
          'expectedSize': expectedSize,
          'expectedCertHashes': expectedCertHashes,
        },
      );
      final map = Map<String, dynamic>.from(result ?? {});
      return map['started'] == true || map['alreadyInProgress'] == true;
    } catch (e, st) {
      LogService.I.warn(
        'launch prepared install failed',
        tag: 'background_updates',
        fields: {'appId': appId},
        err: e,
        stack: st,
      );
      return false;
    }
  }
}

class BackgroundInstallResult {
  const BackgroundInstallResult({
    required this.success,
    this.cancelled = false,
    this.needsUserAction = false,
    this.error,
  });

  final bool success;
  final bool cancelled;
  final bool needsUserAction;
  final String? error;
}
