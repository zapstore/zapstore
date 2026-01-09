import 'dart:async';
import 'dart:io';

import 'package:background_downloader/background_downloader.dart';
import 'package:equatable/equatable.dart';
import 'package:flutter/foundation.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/package_manager/dummy_package_manager.dart';
import 'package:zapstore/services/package_manager/install_operation.dart';
import 'package:zapstore/utils/version_utils.dart';

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
  });

  /// Map of appId → installed package info
  final Map<String, PackageInfo> installed;

  /// Map of appId → active install operation
  final Map<String, InstallOperation> operations;

  PackageManagerState copyWith({
    Map<String, PackageInfo>? installed,
    Map<String, InstallOperation>? operations,
  }) {
    return PackageManagerState(
      installed: installed ?? this.installed,
      operations: operations ?? this.operations,
    );
  }

  @override
  List<Object?> get props => [installed, operations];
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
abstract class PackageManager extends StateNotifier<PackageManagerState> {
  PackageManager(this.ref) : super(const PackageManagerState()) {
    _downloaderInit = _initializeDownloader();
  }

  final Ref ref;
  late final FileDownloader _downloader;
  late final Future<void> _downloaderInit;

  Future<void> _ensureDownloaderReady() => _downloaderInit;

  /// Hook called when an app transitions into [ReadyToInstall].
  ///
  /// Default behavior is to immediately start installation. Platforms that must
  /// serialize installs (Android PackageInstaller UI) should override this to
  /// queue/advance one install at a time.
  @protected
  void onInstallReady(String appId) {
    unawaited(triggerInstall(appId));
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
    } catch (_) {}

    _downloader.configureNotificationForGroup(
      FileDownloader.defaultGroup,
      running: const TaskNotification(
        'Downloading {displayName}',
        '{progress}',
      ),
      // No 'complete' notification - download completion immediately triggers install
      // so showing "Download complete" is redundant and creates notification clutter
      complete: null,
      error: const TaskNotification('Download failed', '{displayName}'),
      paused: const TaskNotification('Download paused', '{displayName}'),
      progressBar: true,
    );

    _downloader.registerCallbacks(
      taskStatusCallback: _handleDownloadUpdate,
      taskProgressCallback: _handleDownloadUpdate,
    );

    await _restoreOperations();
  }

  @override
  void dispose() {
    _downloader.unregisterCallbacks();
    super.dispose();
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

  List<String> getAwaitingUserAction() => state.operations.entries
      .where((e) => e.value is AwaitingUserAction)
      .map((e) => e.key)
      .toList();

  // ═══════════════════════════════════════════════════════════════════════════
  // STATE MANAGEMENT (Public for subclass use)
  // ═══════════════════════════════════════════════════════════════════════════

  void setOperation(String appId, InstallOperation op) {
    state = state.copyWith(operations: {...state.operations, appId: op});
  }

  void clearOperation(String appId) {
    state = state.copyWith(
      operations: Map.from(state.operations)..remove(appId),
    );
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
    if (hasOperation(appId)) return false;

    final downloadUrl = target.urls.firstOrNull;
    if (downloadUrl == null || downloadUrl.isEmpty) {
      setOperation(
        appId,
        OperationFailed(
          target: target,
          type: FailureType.downloadFailed,
          message: 'No download URL available',
        ),
      );
      return false;
    }

    // Check download slots - only count active downloads, not queued
    final activeDownloads = countOperations<Downloading>();

    if (activeDownloads >= maxConcurrentDownloads) {
      setOperation(
        appId,
        DownloadQueued(target: target, displayName: displayName),
      );
      return true;
    }

    await _startDownloadTask(
      appId,
      target,
      downloadUrl,
      displayName: displayName,
    );
    return true;
  }

  /// Queue multiple downloads at once - immediately marks all as queued,
  /// then starts up to [maxConcurrentDownloads] actual downloads.
  /// This prevents UI confusion when "Update All" is tapped.
  Future<void> queueDownloads(
    List<({String appId, FileMetadata target, String? displayName})> items,
  ) async {
    await _ensureDownloaderReady();

    // Filter out items that already have operations
    final toQueue = items.where((item) => !hasOperation(item.appId)).toList();
    if (toQueue.isEmpty) return;

    // First, mark ALL items as queued immediately for responsive UI
    for (final item in toQueue) {
      final downloadUrl = item.target.urls.firstOrNull;
      if (downloadUrl == null || downloadUrl.isEmpty) {
        setOperation(
          item.appId,
          OperationFailed(
            target: item.target,
            type: FailureType.downloadFailed,
            message: 'No download URL available',
          ),
        );
      } else {
        setOperation(
          item.appId,
          DownloadQueued(target: item.target, displayName: item.displayName),
        );
      }
    }

    // Now process the queue to start actual downloads
    _processQueuedDownload();
  }

  Future<void> pauseDownload(String appId) async {
    await _ensureDownloaderReady();
    final op = getOperation(appId);
    if (op is! Downloading) return;

    try {
      final task = await _downloader.taskForId(op.taskId);
      if (task is DownloadTask) {
        await _downloader.pause(task);
      }
    } catch (_) {}
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
      }
    } catch (_) {}
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

    clearOperation(appId);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // INSTALL OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  /// Trigger install from ReadyToInstall state
  Future<void> triggerInstall(String appId) async {
    final op = getOperation(appId);
    if (op is! ReadyToInstall) return;

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

    // Directly perform install - permission was already checked when
    // transitioning to ReadyToInstall state. Don't call _proceedToInstall
    // here as that would re-set ReadyToInstall and call onInstallReady again,
    // causing an infinite loop.
    await _performInstall(appId, op.target, op.filePath);
  }

  /// Retry install from AwaitingUserAction state
  Future<void> retryInstall(String appId) async {
    final op = getOperation(appId);
    if (op is! AwaitingUserAction) return;

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
      final message = e.toString();
      if (!message.contains('cancelled')) {
        setOperation(
          appId,
          OperationFailed(
            target: op.target,
            type: FailureType.installFailed,
            message: 'Force update failed: $message',
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
                'Certificate mismatch. Uninstall current version to update.',
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
      // Permission is granted now: transition to ReadyToInstall and let the
      // platform decide whether to auto-start or queue.
      setOperation(appId, ReadyToInstall(target: target, filePath: filePath));
      onInstallReady(appId);
    }

    // Advance remaining apps
    for (final entry in toAdvance.entries) {
      final (target, filePath) = entry.value;
      setOperation(
        entry.key,
        ReadyToInstall(target: target, filePath: filePath),
      );
      onInstallReady(entry.key);
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
      retries: 3,
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
          message: 'Failed to start download: $e',
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
        setOperation(
          appId,
          OperationFailed(
            target: target,
            type: FailureType.downloadFailed,
            message: 'File not found (404)',
          ),
        );
        _processQueuedDownload();
        break;

      case TaskStatus.failed:
        String error = 'Download failed';
        final exception = update.exception;
        if (exception != null) {
          error = exception.toString();
          if (error.length > 200) error = '${error.substring(0, 197)}...';
        }
        setOperation(
          appId,
          OperationFailed(
            target: target,
            type: FailureType.downloadFailed,
            message: error,
          ),
        );
        _processQueuedDownload();
        break;

      case TaskStatus.canceled:
        clearOperation(appId);
        _processQueuedDownload();
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
    try {
      final filePath = await task.filePath();
      _processQueuedDownload();
      await _proceedToInstall(appId, target, filePath);
    } catch (e) {
      setOperation(
        appId,
        OperationFailed(
          target: target,
          type: FailureType.downloadFailed,
          message: 'Failed to access downloaded file: $e',
        ),
      );
      _processQueuedDownload();
    }
  }

  void _processQueuedDownload() {
    var activeDownloads = countOperations<Downloading>();
    if (activeDownloads >= maxConcurrentDownloads) return;

    // Start downloads until we fill all available slots
    for (final entry in state.operations.entries) {
      if (activeDownloads >= maxConcurrentDownloads) break;

      if (entry.value is DownloadQueued) {
        final queued = entry.value as DownloadQueued;
        final downloadUrl = queued.target.urls.firstOrNull;
        if (downloadUrl != null) {
          _startDownloadTask(
            entry.key,
            queued.target,
            downloadUrl,
            displayName: queued.displayName,
          );
          activeDownloads++;
        }
      }
    }
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

    // Permission was already granted before we checked - just advance this app
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
          AwaitingUserAction(target: target, filePath: filePath),
        );
        return;
      }

      final isCertMismatch =
          message.contains('signatures do not match') ||
          message.contains('INSTALL_FAILED_UPDATE_INCOMPATIBLE') ||
          message.contains('UPDATE_INCOMPATIBLE');

      final isHashMismatch = message.contains('Hash verification failed');
      final isInvalidFile = message.contains('Invalid APK file');

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
          message: isCertMismatch
              ? 'Certificate mismatch. Uninstall current version to update.'
              : message,
          filePath: filePath,
        ),
      );
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
        } catch (_) {}
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

  bool canInstall(FileMetadata m, String version, {int? versionCode}) {
    final installed = state.installed[m.appIdentifier];
    if (installed == null) return true;

    return canUpgrade(
      installed.versionCode?.toString() ?? installed.version,
      versionCode?.toString() ?? version,
    );
  }

  bool canUpdate(FileMetadata m) {
    final installed = state.installed[m.appIdentifier];
    if (installed == null) return false;
    return canUpgrade(
      installed.versionCode?.toString() ?? installed.version,
      m.versionCode?.toString() ?? m.version,
    );
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

final awaitingUserActionCountProvider = Provider<int>((ref) {
  return ref.watch(
    packageManagerProvider.select(
      (s) => s.operations.values.whereType<AwaitingUserAction>().length,
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
