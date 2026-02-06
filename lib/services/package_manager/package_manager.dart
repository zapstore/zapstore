import 'dart:async';
import 'dart:io';

import 'package:background_downloader/background_downloader.dart';
import 'package:equatable/equatable.dart';
import 'package:flutter/foundation.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/package_manager/device_capabilities.dart';
import 'package:zapstore/services/package_manager/dummy_package_manager.dart';
import 'package:zapstore/services/package_manager/install_operation.dart';
export 'device_capabilities.dart';
export 'install_operation.dart';

// ═══════════════════════════════════════════════════════════════════════════════
// PACKAGE INFO
// ═══════════════════════════════════════════════════════════════════════════════

/// Information about an installed package
class PackageInfo extends Equatable {
  const PackageInfo({
    required this.appId,
    required this.version,
    required this.versionCode,
    required this.signatureHash,
    this.name,
    this.installTime,
    this.canInstallSilently = false,
  });

  final String appId;
  final String? name;
  final String version;
  final int? versionCode;
  final String signatureHash;
  final DateTime? installTime;
  final bool canInstallSilently;

  @override
  List<Object?> get props => [
    appId,
    name,
    version,
    versionCode,
    signatureHash,
    installTime,
    canInstallSilently,
  ];
}

// ═══════════════════════════════════════════════════════════════════════════════
// PACKAGE MANAGER STATE
// ═══════════════════════════════════════════════════════════════════════════════

/// Combined state for all package-related data
class PackageManagerState extends Equatable {
  const PackageManagerState({
    this.installed = const {},
    this.operations = const {},
    this.isScanning = false,
  });

  /// Map of appId → installed package info
  final Map<String, PackageInfo> installed;

  /// Map of appId → active install operation
  final Map<String, InstallOperation> operations;

  /// Whether installed packages are currently being scanned
  final bool isScanning;

  PackageManagerState copyWith({
    Map<String, PackageInfo>? installed,
    Map<String, InstallOperation>? operations,
    bool? isScanning,
  }) {
    return PackageManagerState(
      installed: installed ?? this.installed,
      operations: operations ?? this.operations,
      isScanning: isScanning ?? this.isScanning,
    );
  }

  @override
  List<Object?> get props => [installed, operations, isScanning];
}

// ═══════════════════════════════════════════════════════════════════════════════
// PACKAGE MANAGER BASE CLASS
// ═══════════════════════════════════════════════════════════════════════════════

/// Package management interface - the single source of truth for:
/// 1. Installed packages
/// 2. Active install operations (download/verify/install)
///
/// Architecture:
/// - Download phase: Managed by background_downloader (can pause/resume/cancel)
/// - Install phase: Platform-specific, event-driven (no hanging awaits)
/// - Explicit queues: Ordered lists for downloads and installs (not derived from state)
abstract class PackageManager extends StateNotifier<PackageManagerState> {
  PackageManager(this.ref) : super(const PackageManagerState()) {
    _downloaderInit = _initializeDownloader();
  }

  final Ref ref;
  late final FileDownloader _downloader;
  late final Future<void> _downloaderInit;

  /// Watchdog timer for detecting stale operations (Dart-side fallback)
  Timer? _watchdogTimer;

  // ═══════════════════════════════════════════════════════════════════════════
  // EXPLICIT QUEUE TRACKING
  // Queues are the source of truth for order; operations map is for UI state.
  // Protected for subclass access (e.g., AndroidPackageManager).
  // ═══════════════════════════════════════════════════════════════════════════

  /// Ordered download queue (appIds waiting for download slot)
  @protected
  final List<String> downloadQueue = [];

  /// Ordered install queue (appIds waiting for install slot)
  @protected
  final List<String> installQueue = [];

  /// Currently active downloads (appIds)
  @protected
  final Set<String> activeDownloads = {};

  /// Currently active install (only 1 allowed due to Android PackageInstaller)
  @protected
  String? activeInstall;

  /// Lock to prevent concurrent queue processing
  bool _processingQueue = false;

  /// Dynamic max concurrent downloads based on device capability
  int get maxConcurrentDownloads =>
      DeviceCapabilitiesCache.capabilities.maxConcurrentDownloads;

  Future<void> _ensureDownloaderReady() => _downloaderInit;

  /// Hook called when an app transitions into [ReadyToInstall].
  ///
  /// Default behavior is to process the queue, which will start the install
  /// if no other install is active. Subclasses can override for custom behavior.
  @protected
  void onInstallReady(String appId) {
    unawaited(processQueue());
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // INITIALIZATION
  // ═══════════════════════════════════════════════════════════════════════════

  Future<void> _initializeDownloader() async {
    _downloader = FileDownloader();

    try {
      await _downloader.configure(
        globalConfig: [
          (Config.requestTimeout, const Duration(seconds: 20)),
          (Config.resourceTimeout, const Duration(minutes: 30)),
          (Config.checkAvailableSpace, Config.never),
        ],
        androidConfig: [(Config.useCacheDir, false)],
      );
    } catch (e) {
      debugPrint('FileDownloader configure failed: $e');
    }

    // Note: We don't call configureNotificationForGroup because we handle
    // our own UI for download progress. The package defaults to no notifications.

    try {
      _downloader.registerCallbacks(
        taskStatusCallback: _handleDownloadUpdate,
        taskProgressCallback: _handleDownloadUpdate,
      );
    } catch (e) {
      debugPrint('FileDownloader registerCallbacks failed: $e');
    }

    await _restoreOperations();
  }

  @override
  void dispose() {
    _watchdogTimer?.cancel();
    _downloader.unregisterCallbacks();
    super.dispose();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // WATCHDOG TIMER (Dart-side fallback for stale operations)
  // ═══════════════════════════════════════════════════════════════════════════

  /// Start or stop watchdog timer based on whether there are operations to monitor.
  void _updateWatchdogTimer() {
    final needsWatchdog = state.operations.values.any((op) => op.needsWatchdog);

    if (needsWatchdog && _watchdogTimer == null) {
      _watchdogTimer = Timer.periodic(watchdogCheckInterval, (_) {
        _checkForStaleOperations();
      });
    } else if (!needsWatchdog && _watchdogTimer != null) {
      _watchdogTimer?.cancel();
      _watchdogTimer = null;
    }
  }

  /// Check for operations stuck too long (Dart-side fallback if native events stop).
  void _checkForStaleOperations() {
    final now = DateTime.now();
    var needsQueueProcessing = false;

    for (final entry in state.operations.entries) {
      final appId = entry.key;
      final op = entry.value;
      final timestamp = op.watchdogTimestamp;

      if (timestamp == null || now.difference(timestamp) <= watchdogTimeout) {
        continue;
      }

      debugPrint(
        '[PackageManager] Watchdog: $appId stuck in ${op.runtimeType}, '
        'transitioning to error',
      );

      // Use appropriate failure type and cleanup based on operation type
      if (op is Downloading) {
        activeDownloads.remove(appId);
        // Try to cancel the stuck task
        unawaited(
          _downloader.cancelTaskWithId(op.taskId).catchError((_) => false),
        );
        setOperation(
          appId,
          OperationFailed(
            target: op.target,
            type: FailureType.downloadFailed,
            message: 'Download timed out. Please check your internet connection and try again.',
          ),
        );
        needsQueueProcessing = true;
      } else {
        setOperation(
          appId,
          OperationFailed(
            target: op.target,
            type: FailureType.installFailed,
            message: 'Installation timed out. Please try again.',
            filePath: op.filePath,
          ),
        );
        if (op is Installing || op is SystemProcessing) {
          clearInstallSlot(appId);
          needsQueueProcessing = true;
        }
      }
    }

    if (needsQueueProcessing) {
      scheduleProcessQueue();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // QUERIES
  // ═══════════════════════════════════════════════════════════════════════════

  bool isInstalled(String appId) => state.installed.containsKey(appId);

  PackageInfo? getInstalled(String appId) => state.installed[appId];

  InstallOperation? getOperation(String appId) => state.operations[appId];

  bool hasOperation(String appId) => state.operations.containsKey(appId);

  int countOperations<T extends InstallOperation>() =>
      state.operations.values.whereType<T>().length;

  List<String> getReadyToInstall() => state.operations.entries
      .where((e) => e.value is ReadyToInstall)
      .map((e) => e.key)
      .toList();

  List<String> getInstallCancelled() => state.operations.entries
      .where((e) => e.value is InstallCancelled)
      .map((e) => e.key)
      .toList();

  // ═══════════════════════════════════════════════════════════════════════════
  // STATE MANAGEMENT (Public for subclass use)
  // ═══════════════════════════════════════════════════════════════════════════

  void setOperation(String appId, InstallOperation op) {
    state = state.copyWith(operations: {...state.operations, appId: op});
    _updateWatchdogTimer();
  }

  void clearOperation(String appId) {
    state = state.copyWith(
      operations: Map.from(state.operations)..remove(appId),
    );
    _updateWatchdogTimer();
  }

  /// Clear all completed and failed operations from the map.
  /// Called after the batch completion display timeout.
  void clearCompletedOperations() {
    final remaining = Map.of(state.operations)
      ..removeWhere((_, op) => op is Completed || op is OperationFailed);
    state = state.copyWith(operations: remaining);
    _updateWatchdogTimer();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DOWNLOAD OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  /// Start download - returns false if operation already exists for this app
  /// [displayName] is shown in system notification (defaults to appId if null)
  Future<bool> startDownload(
    String appId,
    FileMetadata target, {
    String? displayName,
  }) async {
    await _ensureDownloaderReady();
    final existing = getOperation(appId);
    if (existing != null) {
      // We keep terminal states (Completed/Failed) in the operations map briefly so
      // batch progress UI can derive totals. However, starting a new download for the
      // same app should not be blocked by a stale terminal op (e.g. install -> uninstall
      // -> install again). Only in-flight operations should block.
      if (existing is Completed || existing is OperationFailed) {
        clearOperation(appId);
      } else {
        return false;
      }
    }

    final downloadUrl = target.urls.firstOrNull;
    if (downloadUrl == null || downloadUrl.isEmpty) {
      setOperation(
        appId,
        OperationFailed(
          target: target,
          type: FailureType.downloadFailed,
          message: 'Download link unavailable.',
        ),
      );
      return false;
    }

    // Add to explicit queue and set UI state
    downloadQueue.add(appId);
    setOperation(
      appId,
      DownloadQueued(target: target, displayName: displayName),
    );

    // Process queue to potentially start this download immediately
    unawaited(processQueue());
    return true;
  }

  /// Queue multiple downloads at once - staggered to prevent UI flood.
  /// This is the primary method for "Update All" functionality.
  Future<void> queueDownloads(
    List<({String appId, FileMetadata target, String? displayName})> items,
  ) async {
    await _ensureDownloaderReady();

    // Filter out items that already have operations
    final toQueue = items.where((item) => !hasOperation(item.appId)).toList();
    if (toQueue.isEmpty) return;

    // Queue items with staggered delays to prevent UI flood
    for (var i = 0; i < toQueue.length; i++) {
      final item = toQueue[i];
      final downloadUrl = item.target.urls.firstOrNull;

      if (downloadUrl == null || downloadUrl.isEmpty) {
        setOperation(
          item.appId,
          OperationFailed(
            target: item.target,
            type: FailureType.downloadFailed,
            message: 'Download link unavailable. Please try again later.',
          ),
        );
      } else {
        // Add to explicit queue
        downloadQueue.add(item.appId);
        setOperation(
          item.appId,
          DownloadQueued(target: item.target, displayName: item.displayName),
        );
      }

      // Stagger state updates to prevent Riverpod rebuild flood
      if (i < toQueue.length - 1) {
        await Future.delayed(const Duration(milliseconds: batchQueueDelayMs));
      }
    }

    // Process queue to start actual downloads
    unawaited(processQueue());
  }

  Future<void> pauseDownload(String appId) async {
    await _ensureDownloaderReady();
    final op = getOperation(appId);
    if (op is! Downloading) return;

    try {
      final task = await _downloader.taskForId(op.taskId);
      if (task is DownloadTask) {
        final paused = await _downloader.pause(task);
        if (!paused) {
          // Pause failed - task may be stuck, transition to failed state
          debugPrint(
            '[PackageManager] Pause returned false for $appId, marking as failed',
          );
          activeDownloads.remove(appId);
          setOperation(
            appId,
            OperationFailed(
              target: op.target,
              type: FailureType.downloadFailed,
              message: 'Download stopped. Please try again.',
            ),
          );
          // Cancel the stuck task to clean up
          try {
            await _downloader.cancelTaskWithId(op.taskId);
          } catch (_) {}
          scheduleProcessQueue();
        }
      } else {
        // Task not found - download is in zombie state
        debugPrint(
          '[PackageManager] Task not found for $appId, marking as failed',
        );
        activeDownloads.remove(appId);
        setOperation(
          appId,
          OperationFailed(
            target: op.target,
            type: FailureType.downloadFailed,
            message: 'Download was interrupted. Please try again.',
          ),
        );
        scheduleProcessQueue();
      }
    } catch (e) {
      debugPrint('[PackageManager] Failed to pause download for $appId: $e');
      activeDownloads.remove(appId);
      setOperation(
        appId,
        OperationFailed(
          target: op.target,
          type: FailureType.downloadFailed,
          message: 'Download failed. Please try again.',
          description: '$e',
        ),
      );
      scheduleProcessQueue();
    }
  }

  Future<void> resumeDownload(String appId) async {
    await _ensureDownloaderReady();
    final op = getOperation(appId);
    if (op is! DownloadPaused) return;

    try {
      final task = await _downloader.taskForId(op.taskId);
      if (task is DownloadTask) {
        await _downloader.resume(task);
        setOperation(
          appId,
          Downloading(
            target: op.target,
            progress: op.progress,
            taskId: op.taskId,
          ),
        );
      } else {
        // Task not found - transition to error
        debugPrint('[PackageManager] Resume failed: task not found for $appId');
        setOperation(
          appId,
          OperationFailed(
            target: op.target,
            type: FailureType.downloadFailed,
            message: 'Download was interrupted. Please try again.',
          ),
        );
      }
    } catch (e) {
      debugPrint('[PackageManager] Failed to resume download for $appId: $e');
      setOperation(
        appId,
        OperationFailed(
          target: op.target,
          type: FailureType.downloadFailed,
          message: 'Failed to resume download. Please start again.',
          description: '$e',
        ),
      );
    }
  }

  Future<void> cancelDownload(String appId) async {
    await _ensureDownloaderReady();
    final op = getOperation(appId);
    if (op == null || !op.isDownloading) return;

    if (op is Downloading) {
      try {
        await _downloader.cancelTaskWithId(op.taskId);
      } catch (_) {}
    } else if (op is DownloadPaused) {
      try {
        await _downloader.cancelTaskWithId(op.taskId);
      } catch (_) {}
    }

    // Remove from queues
    downloadQueue.remove(appId);
    activeDownloads.remove(appId);
    clearOperation(appId);

    // Advance queue
    scheduleProcessQueue();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // INSTALL OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  /// Trigger install from ReadyToInstall state.
  /// CRITICAL: Must clear install slot on any early return to prevent queue stall.
  Future<void> triggerInstall(String appId) async {
    final op = getOperation(appId);
    if (op is! ReadyToInstall) {
      // Operation changed (e.g., cancelled) - clear slot and advance queue
      clearInstallSlot(appId);
      return;
    }

    if (!await File(op.filePath).exists()) {
      setOperation(
        appId,
        OperationFailed(
          target: op.target,
          type: FailureType.downloadFailed,
          message: 'Downloaded file not found. Please download again.',
        ),
      );
      // CRITICAL: Clear install slot so queue can advance to next app
      clearInstallSlot(appId);
      return;
    }

    // Directly perform install - permission was already checked when
    // transitioning to ReadyToInstall state. Don't call _proceedToInstall
    // here as that would re-set ReadyToInstall and call onInstallReady again,
    // causing an infinite loop.
    await _performInstall(appId, op.target, op.filePath);
  }

  /// Retry install from InstallCancelled state
  Future<void> retryInstall(String appId) async {
    final op = getOperation(appId);
    if (op is! InstallCancelled) return;

    if (!await File(op.filePath).exists()) {
      setOperation(
        appId,
        OperationFailed(
          target: op.target,
          type: FailureType.downloadFailed,
          message: 'Downloaded file not found. Please download again.',
        ),
      );
      return;
    }

    await _performInstall(appId, op.target, op.filePath);
  }

  /// Force update (uninstall + install) from OperationFailed with certMismatch
  Future<void> forceUpdate(String appId) async {
    final op = getOperation(appId);
    if (op is! OperationFailed || !op.needsForceUpdate) return;

    final filePath = op.filePath;
    if (filePath == null || !await File(filePath).exists()) {
      setOperation(
        appId,
        OperationFailed(
          target: op.target,
          type: FailureType.downloadFailed,
          message: 'Downloaded file not found. Please download again.',
        ),
      );
      return;
    }

    setOperation(appId, Uninstalling(target: op.target, filePath: filePath));

    try {
      await uninstall(appId);
      await _performInstall(appId, op.target, filePath);
    } catch (e) {
      final errorMessage = e.toString();
      if (!errorMessage.contains('cancelled')) {
        setOperation(
          appId,
          OperationFailed(
            target: op.target,
            type: FailureType.installFailed,
            message: 'Update failed.',
            description: errorMessage,
            filePath: filePath,
          ),
        );
      } else {
        setOperation(
          appId,
          OperationFailed(
            target: op.target,
            type: FailureType.certMismatch,
            message:
                'Update signed by different developer. Uninstall current version to update.',
            filePath: filePath,
          ),
        );
      }
    }
  }

  /// Dismiss error and clean up
  void dismissError(String appId) {
    final op = getOperation(appId);
    if (op is! OperationFailed) return;

    final filePath = op.filePath;
    if (filePath != null) {
      _deleteFile(filePath);
    }
    clearOperation(appId);
  }

  /// Called when permission is granted.
  /// Advances the specified app AND all other apps awaiting permission.
  Future<void> onPermissionGranted(String appId) async {
    // Collect all apps that need to advance (AwaitingPermission or permissionDenied failures)
    final toAdvance = <String, (FileMetadata target, String filePath)>{};

    for (final entry in state.operations.entries) {
      final id = entry.key;
      final op = entry.value;
      switch (op) {
        case AwaitingPermission(:final target, :final filePath):
          toAdvance[id] = (target, filePath);
        case OperationFailed(:final target, :final type, :final filePath)
            when type == FailureType.permissionDenied && filePath != null:
          toAdvance[id] = (target, filePath);
        default:
          continue;
      }
    }

    if (toAdvance.isEmpty) return;

    // Advance the requested app first (for responsive UX)
    if (toAdvance.containsKey(appId)) {
      final (target, filePath) = toAdvance.remove(appId)!;
      _addToInstallQueue(appId, target, filePath);
    }

    // Advance remaining apps
    for (final entry in toAdvance.entries) {
      final (target, filePath) = entry.value;
      _addToInstallQueue(entry.key, target, filePath);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DOWNLOAD INTERNALS
  // ═══════════════════════════════════════════════════════════════════════════

  Future<void> _startDownloadTask(
    String appId,
    FileMetadata target,
    String downloadUrl, {
    String? displayName,
    bool isCdnRetry = false,
  }) async {
    final fileName = '${target.hash}$packageExtension';
    final metaData = _encodeTaskMetadata(
      appId,
      target.id,
      isCdnRetry: isCdnRetry,
    );

    final task = DownloadTask(
      taskId:
          '${appId}_${DateTime.now().millisecondsSinceEpoch}_${UniqueKey()}',
      url: downloadUrl,
      filename: fileName,
      updates: Updates.statusAndProgress,
      requiresWiFi: false,
      retries: 10,
      allowPause: true,
      metaData: metaData,
      displayName: displayName ?? appId,
    );

    setOperation(
      appId,
      Downloading(target: target, progress: 0.0, taskId: task.taskId),
    );

    try {
      final result = await _downloader.enqueue(task);
      if (!result) {
        setOperation(
          appId,
          OperationFailed(
            target: target,
            type: FailureType.downloadFailed,
            message: 'Failed to start download',
          ),
        );
      }
    } catch (e) {
      setOperation(
        appId,
        OperationFailed(
          target: target,
          type: FailureType.downloadFailed,
          message: 'Failed to start download. Please try again.',
          description: '$e',
        ),
      );
    }
  }

  void _handleDownloadUpdate(TaskUpdate update) {
    // Fast path: decode appId from task metadata (no O(n) scan on each tick).
    String? appId;
    InstallOperation? operation;

    final metaData = update.task.metaData;
    bool isCdnRetry = false;
    if (metaData.isNotEmpty) {
      final (decodedAppId, _, cdnRetry) = _parseTaskMetadata(metaData);
      isCdnRetry = cdnRetry;
      if (decodedAppId != null) {
        final op = getOperation(decodedAppId);
        // Ensure the operation actually matches this taskId (metadata could be stale).
        if (op is Downloading && op.taskId == update.task.taskId) {
          appId = decodedAppId;
          operation = op;
        } else if (op is DownloadPaused && op.taskId == update.task.taskId) {
          appId = decodedAppId;
          operation = op;
        }
      }
    }

    // Fallback: old tasks without metadata, or mismatched metadata.
    if (appId == null || operation == null) {
      for (final entry in state.operations.entries) {
        final op = entry.value;
        if (op is Downloading && op.taskId == update.task.taskId) {
          appId = entry.key;
          operation = op;
          break;
        } else if (op is DownloadPaused && op.taskId == update.task.taskId) {
          appId = entry.key;
          operation = op;
          break;
        }
      }
    }
    if (appId == null || operation == null) return;

    if (update is TaskStatusUpdate) {
      _handleDownloadStatusUpdate(appId, operation, update, isCdnRetry);
    } else if (update is TaskProgressUpdate) {
      _handleDownloadProgressUpdate(appId, operation, update);
    }
  }

  void _handleDownloadStatusUpdate(
    String appId,
    InstallOperation operation,
    TaskStatusUpdate update,
    bool isCdnRetry,
  ) {
    final target = operation.target;

    switch (update.status) {
      case TaskStatus.running:
        if (operation is DownloadPaused) {
          setOperation(
            appId,
            Downloading(
              target: target,
              progress: operation.progress,
              taskId: operation.taskId,
            ),
          );
        }
        break;

      case TaskStatus.paused:
        if (operation is Downloading) {
          setOperation(
            appId,
            DownloadPaused(
              target: target,
              progress: operation.progress,
              taskId: operation.taskId,
            ),
          );
        }
        break;

      case TaskStatus.complete:
        unawaited(
          _handleDownloadComplete(appId, target, update.task as DownloadTask),
        );
        break;

      case TaskStatus.notFound:
        // 404 error - retry with CDN fallback if not already tried
        if (!isCdnRetry) {
          final cdnUrl = 'https://cdn.zapstore.dev/${target.hash}';
          unawaited(
            _startDownloadTask(appId, target, cdnUrl, isCdnRetry: true),
          );
          return;
        }
        // CDN also returned 404 - fail the operation
        activeDownloads.remove(appId);
        setOperation(
          appId,
          OperationFailed(
            target: target,
            type: FailureType.downloadFailed,
            message: 'File no longer available (404). Please check for a newer version.',
          ),
        );
        scheduleProcessQueue();
        break;

      case TaskStatus.failed:
        String? errorDetails;
        final exception = update.exception;
        if (exception != null) {
          errorDetails = exception.toString();
          if (errorDetails.length > 500) {
            errorDetails = '${errorDetails.substring(0, 497)}...';
          }
        }
        activeDownloads.remove(appId);
        setOperation(
          appId,
          OperationFailed(
            target: target,
            type: FailureType.downloadFailed,
            message: 'Download failed. Please try again.',
            description: errorDetails,
          ),
        );
        scheduleProcessQueue();
        break;

      case TaskStatus.canceled:
        activeDownloads.remove(appId);
        downloadQueue.remove(appId);
        clearOperation(appId);
        scheduleProcessQueue();
        break;

      default:
        break;
    }
  }

  void _handleDownloadProgressUpdate(
    String appId,
    InstallOperation operation,
    TaskProgressUpdate update,
  ) {
    if (operation is! Downloading) return;

    double progress = operation.progress;
    if (update.progress.isFinite &&
        !update.progress.isNaN &&
        update.progress >= 0.0) {
      progress = update.progress.clamp(0.0, 1.0);
    }

    // Throttle: update state only when the displayed percentage changes.
    final oldPercent = (operation.progress * 100).floor();
    final newPercent = (progress * 100).floor();
    if (newPercent == oldPercent) return;

    setOperation(appId, operation.copyWith(progress: progress));
  }

  Future<void> _handleDownloadComplete(
    String appId,
    FileMetadata target,
    DownloadTask task,
  ) async {
    // Remove from active downloads
    activeDownloads.remove(appId);

    try {
      final filePath = await task.filePath();
      // Proceed to install (this will add to install queue when ready)
      await _proceedToInstall(appId, target, filePath);
    } catch (e) {
      setOperation(
        appId,
        OperationFailed(
          target: target,
          type: FailureType.downloadFailed,
          message: 'Failed to access downloaded file.',
          description: '$e',
        ),
      );
    }

    // Advance download queue
    scheduleProcessQueue();
  }

  /// Unified queue processor with lock to prevent race conditions.
  /// Handles both download and install queues.
  @protected
  Future<void> processQueue() async {
    // Lock to prevent concurrent processing
    if (_processingQueue) return;
    _processingQueue = true;

    try {
      // Process download queue: fill available slots
      while (activeDownloads.length < maxConcurrentDownloads &&
          downloadQueue.isNotEmpty) {
        final appId = downloadQueue.removeAt(0);
        final op = getOperation(appId);

        if (op is! DownloadQueued) {
          // Operation was cancelled or changed, skip
          continue;
        }

        final downloadUrl = op.target.urls.firstOrNull;
        if (downloadUrl == null) {
          setOperation(
            appId,
            OperationFailed(
              target: op.target,
              type: FailureType.downloadFailed,
              message: 'Download link unavailable.',
            ),
          );
          continue;
        }

        activeDownloads.add(appId);
        await _startDownloadTask(
          appId,
          op.target,
          downloadUrl,
          displayName: op.displayName,
        );
      }

      // Process install queue: only 1 at a time (Android PackageInstaller limit)
      if (activeInstall == null && installQueue.isNotEmpty) {
        final appId = installQueue.removeAt(0);
        final op = getOperation(appId);

        if (op is ReadyToInstall) {
          activeInstall = appId;
          debugPrint('[PackageManager] Starting install for $appId');
          unawaited(triggerInstall(appId));
        }
        // If operation changed, it will be picked up on next process cycle
      }
    } finally {
      _processingQueue = false;
    }
  }

  /// Schedule queue processing on the next microtask.
  @protected
  void scheduleProcessQueue() {
    Future.microtask(processQueue);
  }

  /// Clear the active install slot and advance the queue.
  /// Call this when an install fails to start or completes.
  @protected
  void clearInstallSlot(String appId) {
    if (activeInstall == appId) {
      activeInstall = null;
    }
    installQueue.remove(appId);
    scheduleProcessQueue();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // INSTALL FLOW
  // ═══════════════════════════════════════════════════════════════════════════

  /// Check permission and proceed to install
  Future<void> _proceedToInstall(
    String appId,
    FileMetadata target,
    String filePath,
  ) async {
    if (!await hasPermission()) {
      setOperation(
        appId,
        AwaitingPermission(target: target, filePath: filePath),
      );
      try {
        await requestPermission();
      } catch (e) {
        setOperation(
          appId,
          OperationFailed(
            target: target,
            type: FailureType.permissionDenied,
            message: e.toString().replaceFirst('Exception: ', ''),
            filePath: filePath,
          ),
        );
        return;
      }

      if (!await hasPermission()) return;

      // Permission was just granted - advance ALL waiting apps, not just this one
      await onPermissionGranted(appId);
      return;
    }

    // Permission was already granted - add to install queue
    _addToInstallQueue(appId, target, filePath);
  }

  /// Add app to install queue and trigger processing.
  void _addToInstallQueue(String appId, FileMetadata target, String filePath) {
    if (!installQueue.contains(appId)) {
      installQueue.add(appId);
    }
    setOperation(appId, ReadyToInstall(target: target, filePath: filePath));
    onInstallReady(appId);
  }

  /// Perform the actual installation
  Future<void> _performInstall(
    String appId,
    FileMetadata target,
    String filePath,
  ) async {
    try {
      await install(
        appId,
        filePath,
        expectedHash: target.hash,
        expectedSize: target.size ?? 0,
        target: target,
      );
      // For event-driven platforms, install() returns immediately
      // and results come via events. For sync platforms, it completes here.
    } catch (e) {
      final message = e.toString().replaceFirst('Exception: ', '');

      if (message.contains('cancelled') || message.contains('ABORTED')) {
        setOperation(
          appId,
          InstallCancelled(target: target, filePath: filePath),
        );
        // CRITICAL: Clear install slot so queue can advance to next app.
        // This handles exceptions that escape install()'s internal error handling.
        clearInstallSlot(appId);
        return;
      }

      final isCertMismatch =
          message.contains('signatures do not match') ||
          message.contains('INSTALL_FAILED_UPDATE_INCOMPATIBLE') ||
          message.contains('UPDATE_INCOMPATIBLE');

      final isHashMismatch = message.contains('Hash verification failed');
      final isInvalidFile = message.contains('Invalid APK file');

      final userMessage = isCertMismatch
          ? 'Update signed by different developer. Uninstall current version to update.'
          : isHashMismatch
              ? 'Hash mismatch. Possibly a malicious file, aborting installation.'
              : isInvalidFile
                  ? 'Invalid app file. The download may be corrupt.'
                  : 'Installation failed.';

      setOperation(
        appId,
        OperationFailed(
          target: target,
          type: isInvalidFile
              ? FailureType.invalidFile
              : isHashMismatch
              ? FailureType.hashMismatch
              : isCertMismatch
              ? FailureType.certMismatch
              : FailureType.installFailed,
          message: userMessage,
          description: message,
          filePath: filePath,
        ),
      );
      // CRITICAL: Clear install slot so queue can advance to next app.
      // This handles exceptions that escape install()'s internal error handling.
      clearInstallSlot(appId);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // RESTORATION
  // ═══════════════════════════════════════════════════════════════════════════

  Future<void> _restoreOperations() async {
    try {
      final records = await _downloader.database.allRecords(
        group: FileDownloader.defaultGroup,
      );

      for (final record in records) {
        final task = record.task;
        if (task is! DownloadTask) continue;

        final metaData = task.metaData;
        if (metaData.isEmpty) {
          await _cleanupTask(task);
          continue;
        }

        final (appId, metadataId, _) = _parseTaskMetadata(metaData);
        if (appId == null) {
          await _cleanupTask(task);
          continue;
        }

        final taskAge = DateTime.now().difference(task.creationTime);
        if (taskAge > staleOperationThreshold) {
          await _cleanupTask(task);
          continue;
        }

        final fileMetadata = await _loadFileMetadata(metadataId, task.filename);
        if (fileMetadata == null) {
          await _cleanupTask(task);
          continue;
        }

        await _restoreOperation(appId, record, task, fileMetadata);
      }
    } catch (e) {
      debugPrint('Failed to restore operations: $e');
    }
  }

  Future<void> _restoreOperation(
    String appId,
    TaskRecord record,
    DownloadTask task,
    FileMetadata fileMetadata,
  ) async {
    switch (record.status) {
      case TaskStatus.complete:
        final filePath = await task.filePath();
        if (await File(filePath).exists()) {
          await syncInstalledPackages();
          if (state.installed.containsKey(appId)) {
            _deleteFile(filePath);
          } else {
            await _proceedToInstall(appId, fileMetadata, filePath);
          }
        }
        break;

      case TaskStatus.running:
      case TaskStatus.enqueued:
      case TaskStatus.waitingToRetry:
        // Track as active download to respect maxConcurrentDownloads limit
        activeDownloads.add(appId);
        setOperation(
          appId,
          Downloading(
            target: fileMetadata,
            progress: record.progress,
            taskId: task.taskId,
          ),
        );
        try {
          await _downloader.resume(task);
        } catch (e) {
          // Resume failed - transition to error state to avoid hang
          activeDownloads.remove(appId);
          setOperation(
            appId,
            OperationFailed(
              target: fileMetadata,
              type: FailureType.downloadFailed,
              message: 'Failed to resume download. Please start again.',
              description: '$e',
            ),
          );
        }
        break;

      case TaskStatus.paused:
        setOperation(
          appId,
          DownloadPaused(
            target: fileMetadata,
            progress: record.progress,
            taskId: task.taskId,
          ),
        );
        break;

      default:
        await _cleanupTask(task);
        break;
    }
  }

  Future<void> _cleanupTask(DownloadTask task) async {
    try {
      await _downloader.cancelTaskWithId(task.taskId);
    } catch (_) {}

    try {
      final filePath = await task.filePath();
      _deleteFile(filePath);
    } catch (_) {}

    try {
      await _downloader.database.deleteRecordWithId(task.taskId);
    } catch (_) {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // HELPERS
  // ═══════════════════════════════════════════════════════════════════════════

  void _deleteFile(String filePath) {
    try {
      final file = File(filePath);
      if (file.existsSync()) {
        file.deleteSync();
      }
    } catch (_) {}
  }

  String _encodeTaskMetadata(
    String appId,
    String metadataId, {
    bool isCdnRetry = false,
  }) {
    return '$appId|$metadataId|${isCdnRetry ? '1' : '0'}';
  }

  (String? appId, String? metadataId, bool isCdnRetry) _parseTaskMetadata(
    String metaData,
  ) {
    final parts = metaData.split('|');
    if (parts.length >= 2) {
      final isCdnRetry = parts.length >= 3 && parts[2] == '1';
      return (parts[0], parts[1], isCdnRetry);
    }
    return (metaData.isNotEmpty ? metaData : null, null, false);
  }

  Future<FileMetadata?> _loadFileMetadata(
    String? metadataId,
    String filename,
  ) async {
    final storage = ref.read(storageNotifierProvider.notifier);

    if (metadataId != null) {
      try {
        final results = storage.querySync(
          RequestFilter<FileMetadata>(ids: {metadataId}).toRequest(),
        );
        if (results.isNotEmpty) return results.first;
      } catch (_) {}
    }

    final dotIndex = filename.lastIndexOf('.');
    final hash = dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
    if (hash.isNotEmpty) {
      try {
        final results = storage.querySync(
          RequestFilter<FileMetadata>(search: hash).toRequest(),
        );
        if (results.isNotEmpty) return results.first;
      } catch (_) {}
    }

    return null;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PLATFORM ABSTRACT METHODS
  // ═══════════════════════════════════════════════════════════════════════════

  /// Install a package from file path.
  /// For event-driven platforms (Android), this returns immediately and
  /// results come via EventChannel. The `target` parameter is passed through
  /// for state management.
  Future<void> install(
    String appId,
    String filePath, {
    required String expectedHash,
    required int expectedSize,
    required FileMetadata target,
  });

  Future<void> uninstall(String appId);

  Future<void> launchApp(String appId);

  Future<void> requestPermission();

  Future<bool> hasPermission();

  bool get supportsSilentInstall;

  String get platform;

  String get packageExtension;

  Future<void> syncInstalledPackages();

  /// Whether [latest] is an update over the installed version of the same app.
  ///
  /// Comparison uses Android versionCode only. Returns false when either
  /// versionCode is unavailable or the app is not installed.
  bool hasUpdate(String appId, FileMetadata latest) {
    final installed = state.installed[appId];
    if (installed == null) return false;
    final installedCode = installed.versionCode;
    final latestCode = latest.versionCode;
    if (installedCode == null || latestCode == null) return false;
    return latestCode > installedCode;
  }

  /// Whether [latest] would be a downgrade from the installed version.
  ///
  /// Comparison uses Android versionCode only. Returns false when either
  /// versionCode is unavailable or the app is not installed.
  bool hasDowngrade(String appId, FileMetadata latest) {
    final installed = state.installed[appId];
    if (installed == null) return false;
    final installedCode = installed.versionCode;
    final latestCode = latest.versionCode;
    if (installedCode == null || latestCode == null) return false;
    return latestCode < installedCode;
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PROVIDERS
// ═══════════════════════════════════════════════════════════════════════════════

final packageManagerProvider =
    StateNotifierProvider<PackageManager, PackageManagerState>(
      DummyPackageManager.new,
    );

final installedPackageProvider = Provider.family<PackageInfo?, String>((
  ref,
  appId,
) {
  return ref.watch(packageManagerProvider.select((s) => s.installed[appId]));
});

final installOperationProvider = Provider.family<InstallOperation?, String>((
  ref,
  appId,
) {
  return ref.watch(packageManagerProvider.select((s) => s.operations[appId]));
});

final activeOperationsCountProvider = Provider<int>((ref) {
  return ref.watch(packageManagerProvider.select((s) => s.operations.length));
});

final readyToInstallCountProvider = Provider<int>((ref) {
  return ref.watch(
    packageManagerProvider.select(
      (s) => s.operations.values.whereType<ReadyToInstall>().length,
    ),
  );
});

final installCancelledCountProvider = Provider<int>((ref) {
  return ref.watch(
    packageManagerProvider.select(
      (s) => s.operations.values.whereType<InstallCancelled>().length,
    ),
  );
});

/// Returns all installed packages as a list
final allInstalledPackagesProvider = Provider<List<PackageInfo>>((ref) {
  return ref.watch(
    packageManagerProvider.select((s) => s.installed.values.toList()),
  );
});

/// Returns packages installed on device but not tracked in relay data.
/// [knownAppIds] should be the set of app identifiers from relay storage.
final systemOnlyPackagesProvider =
    Provider.family<List<PackageInfo>, Set<String>>((ref, knownAppIds) {
      return ref.watch(
        packageManagerProvider.select(
          (s) => s.installed.values
              .where((pkg) => !knownAppIds.contains(pkg.appId))
              .toList(),
        ),
      );
    });

// ═══════════════════════════════════════════════════════════════════════════════
// BATCH PROGRESS (Fully Derived State)
// ═══════════════════════════════════════════════════════════════════════════════

/// Current phase of batch operations
enum BatchPhase { downloading, verifying, installing, completed, idle }

/// Batch progress summary - ALL state derived from operations map.
///
/// Key insight: total = operations.length (includes Completed state).
/// When an operation succeeds, it transitions to Completed instead of being removed.
/// This allows us to derive completed count without separate tracking.
class BatchProgress {
  const BatchProgress({
    required this.total,
    required this.completed,
    required this.downloading,
    required this.verifying,
    required this.installing,
    required this.queued,
    required this.failed,
    required this.cancelled,
    required this.phase,
  });

  /// Total operations (everything in the map, including completed)
  final int total;

  /// Operations that completed successfully
  final int completed;

  /// Operations currently downloading
  final int downloading;

  /// Operations currently verifying
  final int verifying;

  /// Operations currently installing
  final int installing;

  /// Operations waiting in queue
  final int queued;

  /// Operations that failed
  final int failed;

  /// Operations cancelled by user (InstallCancelled - can retry individually)
  final int cancelled;

  /// Current dominant phase
  final BatchPhase phase;

  /// Whether any operations are in progress (not terminal)
  /// Terminal states: Completed, OperationFailed, InstallCancelled
  bool get hasInProgress =>
      downloading > 0 || verifying > 0 || installing > 0 || queued > 0;

  /// Whether all operations are complete (all terminal)
  bool get isAllComplete => !hasInProgress && total > 0;

  /// Status text for display - simple "X of Y completed" format
  String get statusText {
    if (total == 0) return '';
    return '$completed of $total completed';
  }
}

/// Provider for batch progress - ALL state derived from operations map.
///
/// No parameters needed - derives everything from PackageManagerState.operations.
final batchProgressProvider = Provider<BatchProgress?>((ref) {
  final ops = ref.watch(packageManagerProvider.select((s) => s.operations));

  // No operations = no banner
  if (ops.isEmpty) return null;

  // Count operations by type
  int completed = 0,
      downloading = 0,
      verifying = 0,
      installing = 0,
      queued = 0,
      failed = 0,
      cancelled = 0;

  for (final op in ops.values) {
    switch (op) {
      case Completed():
        completed++;
      case DownloadQueued() || ReadyToInstall():
        queued++;
      case Downloading() || DownloadPaused():
        downloading++;
      case Verifying():
        verifying++;
      case Installing() || SystemProcessing():
        installing++;
      case OperationFailed():
        failed++;
      case InstallCancelled():
        cancelled++; // Terminal for batch - user can retry individually
      case AwaitingPermission():
        queued++; // Waiting for permission
      case Uninstalling():
        installing++; // Count as install phase
    }
  }

  final total = ops.length;

  // Determine current phase (priority: installing > verifying > downloading > completed)
  final phase = installing > 0
      ? BatchPhase.installing
      : verifying > 0
      ? BatchPhase.verifying
      : downloading > 0
      ? BatchPhase.downloading
      : (completed > 0 && queued == 0)
      ? BatchPhase.completed
      : BatchPhase.idle;

  return BatchProgress(
    total: total,
    completed: completed,
    downloading: downloading,
    verifying: verifying,
    installing: installing,
    queued: queued,
    failed: failed,
    cancelled: cancelled,
    phase: phase,
  );
});
