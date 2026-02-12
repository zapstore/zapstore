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
  verifyingProgress, // Verification progress update
  pendingUserAction,
  installing, // User accepted, system is now installing
  systemProcessing, // Committed, cannot cancel
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
      'verifyingProgress' => InstallStatus.verifyingProgress,
      'pendingUserAction' => InstallStatus.pendingUserAction,
      'installing' => InstallStatus.installing,
      'systemProcessing' => InstallStatus.systemProcessing,
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

  /// Tracks appIds where we've already attempted to abort orphaned sessions.
  /// Prevents spamming abort calls when native keeps sending events.
  final Set<String> _abortedOrphans = {};

  @override
  String get platform => 'android-arm64-v8a';

  @override
  String get packageExtension => '.apk';

  @override
  bool get supportsSilentInstall => _supportsSilentInstall;

  // ═══════════════════════════════════════════════════════════════════════════
  // EVENT STREAM HANDLING
  // ═══════════════════════════════════════════════════════════════════════════

  /// Whether we're currently attempting to reconnect the event stream
  bool _isReconnecting = false;

  void _setupEventStream() {
    _eventSubscription?.cancel();
    _eventSubscription = _eventChannel.receiveBroadcastStream().listen(
      _handleInstallEvent,
      onError: (e) {
        debugPrint('[PackageManager] EventChannel error: $e');
        _attemptEventStreamReconnect();
      },
      onDone: () {
        debugPrint('[PackageManager] EventChannel closed unexpectedly');
        _attemptEventStreamReconnect();
      },
    );
  }

  /// Attempt to reconnect the event stream after a failure.
  /// Uses exponential backoff to avoid hammering the system.
  void _attemptEventStreamReconnect() {
    if (_isReconnecting) return;
    _isReconnecting = true;

    // Reconnect after a short delay
    Future.delayed(const Duration(seconds: 2), () {
      _isReconnecting = false;
      if (mounted) {
        debugPrint('[PackageManager] Attempting EventChannel reconnect');
        _setupEventStream();
      }
    });
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
    final progress = event['progress'] as double?;
    final status = InstallStatusX.tryParse(statusRaw);

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
      // INVARIANT: No hanging states (FEAT-001).
      // Orphaned native sessions can occur when the app is killed during install.
      // Abort the native session to clean up and allow user to retry from clean state.
      // Only attempt abort once per appId to prevent spam when native keeps sending events.
      if (_abortedOrphans.add(appId)) {
        debugPrint(
          '[PackageManager] No tracked operation for appId=$appId, aborting orphaned native session',
        );
        // Release the install slot in case this app was the active install
        // (e.g., sync cleared the operation before the native event arrived).
        clearInstallSlot(appId);
        unawaited(abortInstall(appId));
      }
      return;
    }

    // INVARIANT: Don't process terminal events for already terminal operations.
    // This prevents stale or duplicate events from corrupting state.
    // For example, a stale 'cancelled' event arriving after 'success' would
    // clear the Completed operation (since Completed has no filePath).
    if (existingOp.isTerminal &&
        (status == InstallStatus.success ||
            status == InstallStatus.failed ||
            status == InstallStatus.cancelled)) {
      debugPrint(
        '[PackageManager] Ignoring terminal event $status for already terminal operation ${existingOp.runtimeType}',
      );
      return;
    }

    // INVARIANT: Once the user cancels (or the system dismisses the dialog),
    // the operation is InstallCancelled. The native side may retry the install
    // session (sending verifying/started/pendingUserAction), but we must NOT
    // let those events overwrite InstallCancelled. The only way out of
    // InstallCancelled is the user tapping "Install (retry)" (FEAT-001 spec).
    if (existingOp is InstallCancelled) {
      return;
    }

    final target = existingOp.target;
    final filePath = existingOp.filePath;

    switch (status) {
      case InstallStatus.verifying:
        // Kotlin started hash verification - show Verifying state
        if (filePath != null) {
          setOperation(appId, Verifying(target: target, filePath: filePath));
        }
        break;

      case InstallStatus.verifyingProgress:
        // Update verification progress
        final existingVerify = existingOp;
        if (existingVerify is Verifying && progress != null) {
          setOperation(appId, existingVerify.copyWith(progress: progress));
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
        // Skip if already in Installing or SystemProcessing to preserve the
        // original startedAt timestamp — otherwise the watchdog timeout resets
        // on every pendingUserAction/systemProcessing bounce and never fires.
        if (filePath != null &&
            existingOp is! Installing &&
            existingOp is! SystemProcessing) {
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

      case InstallStatus.systemProcessing:
        // Install session is committed and taking longer than expected.
        // Cannot be cancelled - system will eventually complete or fail.
        // Only create a new SystemProcessing if not already in that state,
        // to preserve the original startedAt timestamp for the watchdog.
        // CRITICAL: When transitioning from Installing, preserve the original
        // startedAt so the Dart watchdog doesn't reset its countdown.
        if (filePath != null && existingOp is! SystemProcessing) {
          final preservedStart =
              existingOp is Installing ? existingOp.startedAt : null;
          setOperation(
            appId,
            SystemProcessing(
              target: target,
              filePath: filePath,
              startedAt: preservedStart,
            ),
          );
        }
        break;

      case InstallStatus.alreadyInProgress:
        // Real pending dialog exists - Kotlin will also send pendingUserAction event
        // which transitions to Installing. Nothing to do here.
        break;

      case InstallStatus.success:
        _deleteFile(filePath);
        // Check BEFORE updating installed package info — after update the app
        // will always appear as installed, losing the update/new-install distinction.
        final wasInstalled = isInstalled(appId);
        // CRITICAL: Update installed package info DIRECTLY from target metadata.
        // We cannot rely on syncInstalledPackages() here because Android's package
        // database may not have committed yet, causing a race condition where
        // we get stale data and show "Update" instead of "Open".
        _updateInstalledPackage(appId, target);
        // Transition to Completed state (stays in map for batch progress tracking)
        setOperation(appId, Completed(target: target, isUpdate: wasInstalled));
        // Sync in background to get accurate info (signature hash, etc.)
        // but our state machine doesn't depend on it.
        unawaited(syncInstalledPackages());
        // Advance to next queued install
        clearInstallSlot(appId);
        break;

      case InstallStatus.failed:
        final failureType = _errorCodeToFailureType(errorCode, message);
        final userMessage = _getUserFriendlyMessage(failureType);
        // Preserve technical details: combine original message and description
        final technicalDetails = [
          if (message != null) message,
          if (description != null) description,
        ].join('\n\n');

        setOperation(
          appId,
          OperationFailed(
            target: target,
            type: failureType,
            message: userMessage,
            description: technicalDetails.isNotEmpty ? technicalDetails : null,
            filePath: filePath,
          ),
        );
        // Advance to next queued install
        clearInstallSlot(appId);
        break;

      case InstallStatus.cancelled:
        if (filePath != null) {
          setOperation(
            appId,
            InstallCancelled(target: target, filePath: filePath),
          );
        } else {
          clearOperation(appId);
        }
        // Advance to next queued install
        clearInstallSlot(appId);
        break;
    }
  }

  @override
  void setOperation(String appId, InstallOperation op) {
    // Clear from aborted orphans when a new operation is set,
    // so we can abort again if it becomes orphaned in a future session.
    _abortedOrphans.remove(appId);
    super.setOperation(appId, op);
  }

  @override
  void onInstallReady(String appId) {
    // Use base class queue processing
    super.onInstallReady(appId);
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
        NativeErrorCode.incompatible => FailureType.incompatible,
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
    if (lower.contains('incompatible') ||
        lower.contains('architecture') ||
        lower.contains('api level') ||
        lower.contains('sdk version')) {
      return FailureType.incompatible;
    }
    return FailureType.installFailed;
  }

  /// Convert failure type to user-friendly message.
  /// Technical details are preserved in the description field.
  String _getUserFriendlyMessage(FailureType type) {
    return switch (type) {
      FailureType.downloadFailed => 'Download failed.',
      FailureType.hashMismatch =>
        'File integrity check failed. Possibly a malicious file, aborting installation.',
      FailureType.invalidFile =>
        'Invalid app file. The download may be corrupt.',
      FailureType.certMismatch =>
        'Update signed by different developer. Uninstall current version to update.',
      FailureType.permissionDenied =>
        'Permission required. Please grant install permission and try again.',
      FailureType.insufficientStorage =>
        'Not enough storage space. Free up space to continue and try again.',
      FailureType.incompatible =>
        'This app is not compatible with your device.',
      FailureType.installFailed => 'Installation failed.',
    };
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
        // IMPORTANT: Clear activeInstall and advance queue on fail-to-start.
        // Otherwise the install queue is stuck indefinitely (hanging state).
        clearInstallSlot(appId);
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
      // IMPORTANT: Clear activeInstall and advance queue on exception.
      // Otherwise the install queue is stuck indefinitely (hanging state).
      clearInstallSlot(appId);
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

  /// Explicitly abort an install operation.
  /// This is best-effort - if the session is already committed, Android may still complete it.
  Future<void> abortInstall(String appId) async {
    try {
      final result = await _methodChannel
          .invokeMethod<Map<Object?, Object?>>('abortInstall', {
            'packageName': appId,
          })
          .timeout(
            const Duration(seconds: 5),
            onTimeout: () => <Object?, Object?>{'success': false},
          );

      final resultMap = Map<String, dynamic>.from(result ?? {});
      final wasCommitted = resultMap['wasCommitted'] as bool? ?? false;

      if (wasCommitted) {
        debugPrint(
          '[PackageManager] abortInstall: Session was committed, Android may still complete install for $appId',
        );
      }
    } catch (e) {
      debugPrint('[PackageManager] abortInstall failed for $appId: $e');
    }

    // Clear operation on Dart side regardless of native result
    clearOperation(appId);
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

      // Trust the native source as the single source of truth.
      // Android's SUCCESS broadcast only fires after the package is committed,
      // so there's no race condition with queryable state.
      state = state.copyWith(installed: packages);
      await InstalledPackagesSnapshot.save(state.installed);

      // Clear operations for apps where the installed version matches the target version
      // This catches installs that succeeded but we missed the event
      // IMPORTANT: Don't clear operations where we're updating to a NEWER version
      for (final appId in packages.keys) {
        final op = getOperation(appId);
        if (op == null) continue;
        if (op is! Installing && op is! Verifying && op is! InstallCancelled) {
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
        // Transition to Completed (not clearOperation) so batchProgressProvider
        // counts this app. The Completed op stays until the user dismisses the
        // banner or clearCompletedOperations runs.
        if (completed) {
          debugPrint(
            '[PackageManager] Sync: completing operation for $appId '
            '(installedVc=$installedVc, targetVc=$targetVc, installedV=$installedV, targetV=$targetV)',
          );
          // Sync fallback: state.installed was already overwritten with native
          // data above (line 723), so we must NOT call _updateInstalledPackage
          // here — that would replace accurate native info with target metadata.
          // isUpdate defaults to true: sync mostly catches silent updates where
          // the app was already installed. The impact of a wrong label is cosmetic.
          setOperation(appId, Completed(target: op.target, isUpdate: true));
          clearInstallSlot(appId);
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
