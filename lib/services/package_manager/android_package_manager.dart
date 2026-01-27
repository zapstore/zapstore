import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/package_manager/installed_packages_snapshot.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';

/// Install status values from native side.
///
/// IMPORTANT: The native side sends these as strings via EventChannel, so we
/// parse them into this enum for type-safe switching.
enum InstallStatus {
  started,
  verifying,
  pendingUserAction,
  installing, // User accepted, system is now installing
  alreadyInProgress,
  success,
  failed,
  cancelled,
}

extension InstallStatusX on InstallStatus {
  static InstallStatus? tryParse(String? raw) {
    return switch (raw) {
      'started' => InstallStatus.started,
      'verifying' => InstallStatus.verifying,
      'pendingUserAction' => InstallStatus.pendingUserAction,
      'installing' => InstallStatus.installing,
      'alreadyInProgress' => InstallStatus.alreadyInProgress,
      'success' => InstallStatus.success,
      'failed' => InstallStatus.failed,
      'cancelled' => InstallStatus.cancelled,
      _ => null,
    };
  }
}

/// Error codes from native side (structured, reliable)
class NativeErrorCode {
  static const downloadFailed = 'downloadFailed';
  static const hashMismatch = 'hashMismatch';
  static const invalidFile = 'invalidFile';
  static const installFailed = 'installFailed';
  static const certMismatch = 'certMismatch';
  static const permissionDenied = 'permissionDenied';
  static const insufficientStorage = 'insufficientStorage';
  static const incompatible = 'incompatible';
  static const blocked = 'blocked';
  static const alreadyInProgress = 'alreadyInProgress';
  static const installTimeout = 'installTimeout';
}

/// Android implementation of PackageManager using event-driven architecture.
///
/// The native side streams install status events via EventChannel.
/// Foreground state is auto-detected by ProcessLifecycleOwner on the native side.
/// No polling, no probing, no hanging awaits.
final class AndroidPackageManager extends PackageManager {
  AndroidPackageManager(super.ref) {
    _setupEventStream();
  }

  static const _methodChannel = MethodChannel('android_package_manager');
  static const _eventChannel = EventChannel('android_package_manager/events');

  bool _supportsSilentInstall = false;
  int _syncGeneration = 0;
  StreamSubscription<dynamic>? _eventSubscription;

  @override
  String get platform => 'android-arm64-v8a';

  @override
  String get packageExtension => '.apk';

  @override
  bool get supportsSilentInstall => _supportsSilentInstall;

  // ═══════════════════════════════════════════════════════════════════════════
  // EVENT STREAM HANDLING
  // ═══════════════════════════════════════════════════════════════════════════

  void _setupEventStream() {
    _eventSubscription = _eventChannel.receiveBroadcastStream().listen(
      _handleInstallEvent,
      onError: (_) {}, // Events will resume when stream reconnects
    );
  }

  /// Handle install status events from native side.
  /// Events arrive sequentially (Dart is single-threaded), no lock needed.
  void _handleInstallEvent(dynamic event) {
    if (event is! Map) {
      debugPrint('[PackageManager] Ignoring non-map event: $event');
      return;
    }

    final appId = event['appId'] as String?;
    final statusRaw = event['status'] as String?;
    final message = event['message'] as String?;
    final errorCode = event['errorCode'] as String?;
    final description = event['description'] as String?;
    final status = InstallStatusX.tryParse(statusRaw);

    debugPrint(
      '[PackageManager] Received event: appId=$appId, status=$status, msg=$message, errorCode=$errorCode, desc=$description',
    );

    if (appId == null || statusRaw == null) {
      debugPrint('[PackageManager] Ignoring event with null appId or status');
      return;
    }

    if (status == null) {
      debugPrint(
        '[PackageManager] Ignoring event with unknown status: $statusRaw',
      );
      return;
    }

    // Get target from existing operation state - no separate tracking needed
    final existingOp = getOperation(appId);
    if (existingOp == null) {
      debugPrint(
        '[PackageManager] WARNING: No tracked operation for appId=$appId, ignoring $status event',
      );
      debugPrint(
        '[PackageManager] Current operations: ${state.operations.keys.toList()}',
      );
      return;
    }

    debugPrint(
      '[PackageManager] Processing $status for $appId (current state: ${existingOp.runtimeType})',
    );

    final target = existingOp.target;
    final filePath = existingOp.filePath;

    switch (status) {
      case InstallStatus.verifying:
        // Kotlin started hash verification - show Verifying state
        if (filePath != null) {
          setOperation(appId, Verifying(target: target, filePath: filePath));
        }
        break;

      case InstallStatus.started:
        // Install session started - transition to Installing
        if (filePath != null) {
          final pkg = state.installed[appId];
          final isSilent = pkg?.canInstallSilently ?? false;
          setOperation(
            appId,
            Installing(target: target, filePath: filePath, isSilent: isSilent),
          );
        }
        break;

      case InstallStatus.pendingUserAction:
        // User action required. Ensure we don't get stuck in Verifying if the
        // STARTED event was missed; show Installing state.
        if (filePath != null && existingOp is! Installing) {
          final pkg = state.installed[appId];
          final isSilent = pkg?.canInstallSilently ?? false;
          setOperation(
            appId,
            Installing(target: target, filePath: filePath, isSilent: isSilent),
          );
        }
        break;

      case InstallStatus.installing:
        // User accepted the install dialog, system is now installing.
        // Transition to Installing with isSilent=true to show "Installing..."
        if (filePath != null) {
          setOperation(
            appId,
            Installing(target: target, filePath: filePath, isSilent: true),
          );
        }
        break;

      case InstallStatus.alreadyInProgress:
        // Real pending dialog exists - Kotlin will also send pendingUserAction event
        // which transitions to Installing. Nothing to do here.
        break;

      case InstallStatus.success:
        _deleteFile(filePath);
        // CRITICAL: Update installed package info DIRECTLY from target metadata.
        // We cannot rely on syncInstalledPackages() here because Android's package
        // database may not have committed yet, causing a race condition where
        // we get stale data and show "Update" instead of "Open".
        _updateInstalledPackage(appId, target);
        clearOperation(appId);
        // Sync in background to get accurate info (signature hash, etc.)
        // but our state machine doesn't depend on it.
        unawaited(syncInstalledPackages());
        // Advance to next queued install
        _advanceAfterDelay();
        break;

      case InstallStatus.failed:
        setOperation(
          appId,
          OperationFailed(
            target: target,
            type: _errorCodeToFailureType(errorCode, message),
            message: message ?? 'Installation failed',
            description: description,
            filePath: filePath,
          ),
        );
        // Advance to next queued install
        _advanceAfterDelay();
        break;

      case InstallStatus.cancelled:
        if (filePath != null) {
          setOperation(
            appId,
            AwaitingUserAction(target: target, filePath: filePath),
          );
        } else {
          clearOperation(appId);
        }
        // Advance to next queued install
        _advanceAfterDelay();
        break;
    }
  }

  @override
  void onInstallReady(String appId) {
    _tryAdvanceNextInstall();
  }

  /// Advance to next install after a delay, giving Android time to clean up.
  void _advanceAfterDelay() {
    Future.delayed(const Duration(seconds: 1), _tryAdvanceNextInstall);
  }

  /// Try to start the next app in ReadyToInstall state.
  /// Only advances if no other app is currently installing (one dialog at a time).
  void _tryAdvanceNextInstall() {
    // Check if any app is currently in an active install state
    final hasActiveInstall = state.operations.values.any(
      (op) => op is Verifying || op is Installing || op is Uninstalling,
    );

    if (hasActiveInstall) {
      debugPrint('[PackageManager] Not advancing - install already active');
      return;
    }

    // Get next app ready to install
    final readyToInstall = getReadyToInstall();
    if (readyToInstall.isEmpty) {
      debugPrint('[PackageManager] No apps waiting in ReadyToInstall');
      return;
    }

    final nextAppId = readyToInstall.first;
    debugPrint('[PackageManager] Advancing to next install: $nextAppId');

    // Trigger install (fire-and-forget, events will drive state)
    unawaited(triggerInstall(nextAppId));
  }

  /// Convert native error code to FailureType.
  /// Uses structured error code when available, falls back to message parsing.
  FailureType _errorCodeToFailureType(String? errorCode, String? message) {
    // Use structured error code when available (reliable)
    if (errorCode != null) {
      return switch (errorCode) {
        NativeErrorCode.downloadFailed => FailureType.downloadFailed,
        NativeErrorCode.hashMismatch => FailureType.hashMismatch,
        NativeErrorCode.invalidFile => FailureType.invalidFile,
        NativeErrorCode.installFailed => FailureType.installFailed,
        NativeErrorCode.certMismatch => FailureType.certMismatch,
        NativeErrorCode.permissionDenied => FailureType.permissionDenied,
        NativeErrorCode.insufficientStorage => FailureType.insufficientStorage,
        NativeErrorCode.incompatible => FailureType.installFailed,
        NativeErrorCode.blocked => FailureType.permissionDenied,
        NativeErrorCode.installTimeout => FailureType.installFailed,
        _ => FailureType.installFailed,
      };
    }

    // Fallback: categorize by message content (legacy, less reliable)
    if (message == null) return FailureType.installFailed;
    final lower = message.toLowerCase();
    if (lower.contains('signature') ||
        lower.contains('certificate') ||
        lower.contains('update_incompatible')) {
      return FailureType.certMismatch;
    }
    if (lower.contains('storage') || lower.contains('space')) {
      return FailureType.insufficientStorage;
    }
    if (lower.contains('hash') || lower.contains('verification')) {
      return FailureType.hashMismatch;
    }
    if (lower.contains('invalid') || lower.contains('corrupt')) {
      return FailureType.invalidFile;
    }
    if (lower.contains('permission') || lower.contains('denied')) {
      return FailureType.permissionDenied;
    }
    return FailureType.installFailed;
  }

  void _deleteFile(String? path) {
    if (path == null) return;
    try {
      final file = File(path);
      if (file.existsSync()) file.deleteSync();
    } catch (_) {}
  }

  /// Directly update installed package from target metadata.
  /// This ensures we don't depend on Android's package DB timing.
  void _updateInstalledPackage(String appId, FileMetadata target) {
    final existingPkg = state.installed[appId];
    final newPkg = PackageInfo(
      appId: appId,
      name: existingPkg?.name,
      version: target.version,
      versionCode: target.versionCode,
      // Keep existing signature hash if available, will be updated by sync
      signatureHash: existingPkg?.signatureHash ?? '',
      installTime: DateTime.now(),
      canInstallSilently: existingPkg?.canInstallSilently ?? false,
    );
    state = state.copyWith(installed: {...state.installed, appId: newPkg});
  }

  @override
  void dispose() {
    _eventSubscription?.cancel();
    super.dispose();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // INSTALLATION (Fire-and-forget - results come via events)
  // ═══════════════════════════════════════════════════════════════════════════

  @override
  Future<void> install(
    String appId,
    String filePath, {
    required String expectedHash,
    required int expectedSize,
    required FileMetadata target,
  }) async {
    // Permission is already checked by _proceedToInstall in base class
    // No need for redundant _ensureInstallPermission() call here

    final file = File(filePath);
    if (!file.existsSync()) {
      throw Exception('APK file not found: $filePath');
    }

    // Native side is the source of truth for install state; it will emit
    // VERIFYING/STARTED/PENDING_USER_ACTION/SUCCESS/FAILED/CANCELLED events.

    try {
      final result = await _methodChannel
          .invokeMethod<Map<Object?, Object?>>('install', {
            'filePath': filePath,
            'packageName': appId,
            'expectedHash': expectedHash,
            'expectedSize': expectedSize,
          })
          .timeout(const Duration(seconds: 30), onTimeout: () => null);

      final resultMap = Map<String, dynamic>.from(result ?? {});

      if (resultMap['alreadyInProgress'] == true) {
        // Real pending dialog exists - Kotlin re-launched it and sent pendingUserAction
        // event which transitions to Installing. Nothing to do here.
        return;
      }

      if (resultMap['started'] != true) {
        final error =
            resultMap['error'] as String? ?? 'Failed to start install';
        final errorCode = resultMap['errorCode'] as String?;
        setOperation(
          appId,
          OperationFailed(
            target: target,
            type: _errorCodeToFailureType(errorCode, error),
            message: error,
            filePath: filePath,
          ),
        );
        // Don't auto-advance after failure
      }
      // If started, wait for events via EventChannel
    } catch (e) {
      setOperation(
        appId,
        OperationFailed(
          target: target,
          type: FailureType.installFailed,
          message: e.toString(),
          filePath: filePath,
        ),
      );
      // Don't auto-advance after failure
    }
  }

  @override
  Future<void> uninstall(String appId) async {
    final result = await _methodChannel
        .invokeMethod<Map<Object?, Object?>>('uninstall', {
          'packageName': appId,
        })
        .timeout(
          const Duration(seconds: 60), // Uninstall needs user confirmation
          onTimeout: () => <Object?, Object?>{
            'isSuccess': false,
            'errorMessage': 'Uninstall timed out',
          },
        );

    final resultMap = Map<String, dynamic>.from(result ?? {});

    if (resultMap['isSuccess'] != true) {
      if (resultMap['cancelled'] == true) {
        throw Exception('Uninstall was cancelled');
      }
      throw Exception(resultMap['errorMessage'] ?? 'Uninstallation failed');
    }

    // Remove from installed immediately (don't wait for sync)
    state = state.copyWith(installed: Map.from(state.installed)..remove(appId));

    // Background sync for consistency
    syncInstalledPackages();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PERMISSIONS
  // ═══════════════════════════════════════════════════════════════════════════

  @override
  Future<void> requestPermission() async {
    try {
      final result = await _methodChannel
          .invokeMethod<Map<Object?, Object?>>('requestInstallPermission')
          .timeout(
            const Duration(seconds: 5),
            onTimeout: () => <Object?, Object?>{
              'success': false,
              'message': 'Request timed out',
            },
          );
      final resultMap = Map<String, dynamic>.from(result ?? {});

      if (resultMap['success'] != true) {
        throw Exception(resultMap['message'] ?? 'Failed to request permission');
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
      return await _methodChannel
              .invokeMethod<bool>('hasUnknownSourcesPermission')
              .timeout(const Duration(seconds: 5), onTimeout: () => false) ??
          false;
    } catch (_) {
      return false;
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // APP LAUNCH
  // ═══════════════════════════════════════════════════════════════════════════

  @override
  Future<void> launchApp(String appId) async {
    try {
      final result = await _methodChannel
          .invokeMethod<Map<Object?, Object?>>('launchApp', {
            'packageName': appId,
          })
          .timeout(
            const Duration(seconds: 10),
            onTimeout: () => {
              'isSuccess': false,
              'errorMessage': 'App launch timed out',
            },
          );

      final resultMap = Map<String, dynamic>.from(result ?? {});

      if (resultMap['isSuccess'] != true) {
        throw Exception(resultMap['errorMessage'] ?? 'Failed to launch app');
      }
    } catch (e) {
      throw Exception('Failed to launch app: $e');
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SYNC INSTALLED PACKAGES
  // ═══════════════════════════════════════════════════════════════════════════

  @override
  Future<void> syncInstalledPackages() async {
    final syncGen = ++_syncGeneration;
    state = state.copyWith(isScanning: true);
    try {
      // Single native call - getInstalledApps already returns canInstallSilently per-app
      final installedApps =
          await _methodChannel
              .invokeMethod<List<Object?>>('getInstalledApps', {
                'includeSystemApps': false,
              })
              .timeout(
                const Duration(seconds: 10),
                onTimeout: () => <Object?>[],
              ) ??
          [];

      if (syncGen != _syncGeneration) return;

      final packages = <String, PackageInfo>{};
      var anyCanInstallSilently = false;

      for (final appObj in installedApps) {
        final app = Map<String, dynamic>.from(appObj as Map<Object?, Object?>);
        final appId =
            app['bundleId'] as String? ?? app['packageName'] as String? ?? '';
        final name = app['name'] as String?;
        final version = app['versionName'] as String? ?? '0.0.0';
        final versionCode = app['versionCode'] as int?;
        final signatureHash = app['signatureHash'] as String? ?? '';
        final canInstallSilently = app['canInstallSilently'] as bool? ?? false;

        if (canInstallSilently) anyCanInstallSilently = true;

        if (appId.isNotEmpty) {
          packages[appId] = PackageInfo(
            appId: appId,
            name: name,
            version: version,
            versionCode: versionCode,
            signatureHash: signatureHash,
            installTime: null,
            canInstallSilently: canInstallSilently,
          );
        }
      }

      if (syncGen != _syncGeneration) return;

      // Derive general silent install capability from per-app data
      _supportsSilentInstall = anyCanInstallSilently;

      // Preserve any packages we've directly updated (they have installTime set)
      // This prevents race conditions where sync returns stale data
      final preserved = <String, PackageInfo>{};
      for (final entry in state.installed.entries) {
        if (entry.value.installTime != null &&
            !packages.containsKey(entry.key)) {
          // We directly updated this but sync doesn't have it yet - keep ours
          preserved[entry.key] = entry.value;
        } else if (entry.value.installTime != null &&
            packages.containsKey(entry.key)) {
          // Both have it - use sync data but preserve installTime marker
          final syncPkg = packages[entry.key]!;
          preserved[entry.key] = PackageInfo(
            appId: syncPkg.appId,
            name: syncPkg.name,
            version: syncPkg.version,
            versionCode: syncPkg.versionCode,
            signatureHash: syncPkg.signatureHash,
            installTime: entry.value.installTime,
            canInstallSilently: syncPkg.canInstallSilently,
          );
        }
      }

      state = state.copyWith(installed: {...packages, ...preserved});
      await InstalledPackagesSnapshot.save(state.installed);

      // Clear operations for apps where the installed version matches the target version
      // This catches installs that succeeded but we missed the event
      // IMPORTANT: Don't clear operations where we're updating to a NEWER version
      for (final appId in packages.keys) {
        final op = getOperation(appId);
        if (op == null) continue;
        if (op is! Installing &&
            op is! Verifying &&
            op is! AwaitingUserAction) {
          continue;
        }

        final installedPkg = packages[appId];
        final targetVc = op.target.versionCode;
        final installedVc = installedPkg?.versionCode;
        final targetV = op.target.version;
        final installedV = installedPkg?.version;

        final completed =
            (targetVc != null &&
                installedVc != null &&
                installedVc >= targetVc) ||
            (installedV != null && installedV == targetV);

        // Only clear if we can establish completion reliably.
        if (completed) {
          debugPrint(
            '[PackageManager] Sync: clearing completed operation for $appId '
            '(installedVc=$installedVc, targetVc=$targetVc, installedV=$installedV, targetV=$targetV)',
          );
          clearOperation(appId);
        } else {
          debugPrint(
            '[PackageManager] Sync: keeping operation for $appId '
            '(installedVc=$installedVc, targetVc=$targetVc, installedV=$installedV, targetV=$targetV)',
          );
        }
      }
    } catch (_) {
      // Don't clobber state on transient errors
    } finally {
      state = state.copyWith(isScanning: false);
    }
  }
}
