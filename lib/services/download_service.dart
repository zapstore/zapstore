import 'dart:async';
import 'dart:io';

import 'package:background_downloader/background_downloader.dart';
import 'package:flutter/foundation.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/extensions.dart';

import '../services/package_manager/package_manager.dart';

/// Simplified Download Service leveraging background_downloader package
///
/// This service acts as a thin wrapper around background_downloader,
/// providing integration with our app models and package manager.
///
/// Key features:
/// - Platform-agnostic - works with any package manager implementation
/// - Uses FileMetadata.hash as filename for uniqueness and verification
/// - Automatic installation after download completion
/// - Leverages package's built-in retry, pause/resume, and progress tracking
/// - Minimal state management - relies on background_downloader's task tracking

/// Minimal wrapper around Task with app-specific metadata
class DownloadInfo {
  const DownloadInfo({
    required this.appId,
    required this.task,
    required this.fileMetadata,
    this.status = TaskStatus.enqueued,
    this.progress = 0.0,
    this.isInstalling = false,
    this.isReadyToInstall = false,
    this.errorDetails,
  });

  final String appId;
  final DownloadTask task;
  final FileMetadata fileMetadata; // Store the actual metadata being downloaded
  final TaskStatus status;
  final double progress;
  final bool isInstalling;
  final bool isReadyToInstall; // Downloaded but installation failed/canceled
  final String? errorDetails; // Store error details for display

  String get taskId => task.taskId;
  String get fileName => task.filename;

  String get formattedProgress {
    final percent = (progress * 100).round();
    return switch (status) {
      TaskStatus.enqueued => 'Queued',
      TaskStatus.running => '$percent%',
      TaskStatus.complete => 'Completed',
      TaskStatus.failed => 'Failed',
      TaskStatus.canceled => 'Canceled',
      TaskStatus.paused => 'Paused',
      TaskStatus.waitingToRetry => 'Retrying...',
      TaskStatus.notFound => 'Not found',
    };
  }

  DownloadInfo copyWith({
    TaskStatus? status,
    double? progress,
    bool? isInstalling,
    bool? isReadyToInstall,
    String? errorDetails,
  }) {
    return DownloadInfo(
      appId: appId,
      task: task,
      fileMetadata: fileMetadata,
      status: status ?? this.status,
      progress: progress ?? this.progress,
      isInstalling: isInstalling ?? this.isInstalling,
      isReadyToInstall: isReadyToInstall ?? this.isReadyToInstall,
      errorDetails: errorDetails ?? this.errorDetails,
    );
  }
}

/// Simplified download service
class DownloadService extends StateNotifier<Map<String, DownloadInfo>> {
  DownloadService(this.ref) : super({}) {
    _initialize();
  }

  final Ref ref;
  late final FileDownloader _downloader;
  
  // Installation queue to prevent concurrent installations
  final List<String> _installQueue = [];
  bool _isInstallingFromQueue = false;

  @override
  void dispose() {
    _downloader.unregisterCallbacks();
    ref.read(packageManagerProvider.notifier).onInstallResult = null;
    super.dispose();
  }

  Future<void> _initialize() async {
    _downloader = FileDownloader();

    // Configure downloader with optimized settings
    try {
      // Configure timeouts - the package uses iOS URL session timeout on iOS
      // and OkHttp timeouts on Android
      await _downloader.configure(
        globalConfig: [
          (
            Config.requestTimeout,
            const Duration(seconds: 20),
          ), // 20 second timeout
          (
            Config.resourceTimeout,
            const Duration(minutes: 30),
          ), // 30 minute total timeout
          (
            Config.checkAvailableSpace,
            Config.never,
          ), // Don't check space as requested
        ],
        androidConfig: [
          (Config.useCacheDir, false), // Use external storage for APKs
        ],
      );

      // Note: maxConcurrentTasks is controlled per-platform:
      // - iOS: Set FDMaximumConcurrentTasks in Info.plist
      // - Android: The library automatically manages concurrency
      // We'll manually track and limit to 3 downloads in our service
    } catch (e) {
      // Continue with defaults if configuration fails
    }

    // Configure notifications
    _downloader.configureNotificationForGroup(
      FileDownloader.defaultGroup,
      running: const TaskNotification(
        'Downloading {filename}',
        'File: {filename}',
      ),
      complete: const TaskNotification('Download complete', 'File: {filename}'),
      error: const TaskNotification('Download failed', 'File: {filename}'),
      paused: const TaskNotification('Download paused', 'File: {filename}'),
      progressBar: true,
    );

    // Set up global callbacks
    _downloader.registerCallbacks(
      taskStatusCallback: _handleTaskUpdate,
      taskProgressCallback: _handleTaskUpdate,
    );

    // Set up install result callback via the abstract interface
    ref.read(packageManagerProvider.notifier).onInstallResult =
        _handleInstallResult;

    // Clear any existing downloads on startup (no resume)
    await _clearExistingDownloads();
  }

  /// Clear any existing downloads on startup (no resume)
  Future<void> _clearExistingDownloads() async {
    try {
      // Remove any tracked tasks and their files (including completed ones)
      final trackedRecords = await _downloader.database
          .allRecords(group: FileDownloader.defaultGroup);

      for (final record in trackedRecords) {
        final task = record.task;
        if (task is DownloadTask) {
          try {
            final filePath = await task.filePath();
            final file = File(filePath);
            if (await file.exists()) {
              await file.delete();
            }
          } catch (e) {
            // Ignore cleanup failures
          }
        }
      }

      // Cancel all existing tasks
      final tasks = await _downloader.allTasks();

      for (final task in tasks) {
        if (task is DownloadTask && task.group == FileDownloader.defaultGroup) {
          await _downloader.cancelTaskWithId(task.taskId);

          // Try to clean up the file
          try {
            final filePath = await task.filePath();
            final file = File(filePath);
            if (await file.exists()) {
              await file.delete();
            }
          } catch (e) {
            // File might not exist yet, ignore
          }
        }
      }

      // Clear persisted task records (completed/failed and active)
      await _downloader.database
          .deleteAllRecords(group: FileDownloader.defaultGroup);

      // Reset downloader state
      await _downloader.reset();
    } catch (e) {
      // Failed to clear existing downloads
    }
  }

  /// Start downloading an app with specific file metadata
  Future<void> downloadAppWithMetadata(
    String appId,
    String appName,
    FileMetadata fileMetadata,
  ) async {
    // Get download URL for current platform
    final packageManager = ref.read(packageManagerProvider.notifier);
    final platform = packageManager.platform;

    String? downloadUrl;
    if (fileMetadata.platforms.contains(platform) ||
        fileMetadata.platforms.isEmpty) {
      downloadUrl = fileMetadata.urls.firstOrNull;
    }

    if (downloadUrl == null || downloadUrl.isEmpty) {
      throw Exception(
        'No download URL available for platform: $platform. '
        'Available platforms: ${fileMetadata.platforms.join(', ')}',
      );
    }

    // Check if already downloading
    if (state.containsKey(appId)) {
      final existing = state[appId]!;
      if (existing.status == TaskStatus.running ||
          existing.status == TaskStatus.enqueued) {
        return;
      }
    }

    // Use FileMetadata hash as filename with platform-specific extension
    final packageExtension = packageManager.packageExtension;
    final fileName = '${fileMetadata.hash}$packageExtension';

    // Create download task
    final taskId =
        '${appId}_${DateTime.now().millisecondsSinceEpoch}_${UniqueKey().toString()}';
    final task = DownloadTask(
      taskId: taskId,
      url: downloadUrl,
      filename: fileName,
      updates: Updates.statusAndProgress,
      requiresWiFi: false,
      retries: 3,
      allowPause: true,
      metaData: appId,
      displayName: appName,
    );

    // Update state
    state = {
      ...state,
      appId: DownloadInfo(
        appId: appId,
        task: task,
        fileMetadata: fileMetadata,
        progress: 0.0,
      ),
    };

    try {
      final result = await _downloader.enqueue(task);
      if (!result) {
        throw Exception('Failed to start download');
      }
    } catch (e) {
      state = Map.from(state)..remove(appId);
      rethrow;
    }
  }

  /// Start downloading an app
  Future<void> downloadApp(App app, Release release) async {
    final fileMetadata = app.latestFileMetadata;
    if (fileMetadata == null) {
      throw Exception('No file metadata available for this release');
    }

    // Get download URL for current platform
    final packageManager = ref.read(packageManagerProvider.notifier);
    final platform = packageManager.platform;

    String? downloadUrl;
    if (fileMetadata.platforms.contains(platform) ||
        fileMetadata.platforms.isEmpty) {
      downloadUrl = fileMetadata.urls.firstOrNull;
    }

    if (downloadUrl == null || downloadUrl.isEmpty) {
      throw Exception(
        'No download URL available for platform: $platform. '
        'Available platforms: ${fileMetadata.platforms.join(', ')}',
      );
    }

    // Check if already downloading
    if (state.containsKey(app.identifier)) {
      final existing = state[app.identifier]!;
      if (existing.status == TaskStatus.running ||
          existing.status == TaskStatus.enqueued) {
        return; // Already downloading
      }
    }

    // Enforce maximum 3 concurrent downloads
    final activeDownloads = state.values
        .where(
          (info) =>
              info.status == TaskStatus.running ||
              info.status == TaskStatus.enqueued ||
              info.status == TaskStatus.waitingToRetry,
        )
        .length;

    if (activeDownloads >= 3) {
      throw Exception(
        'Maximum concurrent downloads (3) reached. '
        'Please wait for current downloads to complete.',
      );
    }

    // Use FileMetadata hash as filename with platform-specific extension
    final packageExtension = packageManager.packageExtension;
    final fileName = '${fileMetadata.hash}$packageExtension';

    // Create download task with built-in retry
    // Use a more unique task ID to prevent collisions
    final taskId =
        '${app.identifier}_${DateTime.now().millisecondsSinceEpoch}_${UniqueKey().toString()}';
    final task = DownloadTask(
      taskId: taskId,
      url: downloadUrl,
      filename: fileName,
      updates: Updates.statusAndProgress,
      requiresWiFi: false,
      retries: 3, // Built-in retry logic
      allowPause: true,
      metaData: app.identifier, // Store app ID for easy lookup
      displayName: app.name ?? app.identifier, // Show app name in notifications
    );

    // Update state with initial progress of 0.0
    state = {
      ...state,
      app.identifier: DownloadInfo(
        appId: app.identifier,
        task: task,
        fileMetadata: fileMetadata,
        progress: 0.0, // Explicitly set initial progress
      ),
    };

    try {
      // Enqueue the download
      final result = await _downloader.enqueue(task);

      if (!result) {
        throw Exception('Failed to start download');
      }
    } catch (e) {
      // Remove from state on failure
      state = Map.from(state)..remove(app.identifier);
      rethrow;
    }
  }

  /// Handle task updates (both status and progress)
  void _handleTaskUpdate(TaskUpdate update) {
    // Find the app ID for this task
    String? appId;
    for (final entry in state.entries) {
      if (entry.value.taskId == update.task.taskId) {
        appId = entry.key;
        break;
      }
    }

    if (appId == null) {
      return;
    }

    final currentInfo = state[appId]!;

    // Update based on update type
    if (update is TaskStatusUpdate) {
      // Handle failure with detailed error
      if (update.status == TaskStatus.failed) {
        String errorDetails = 'Download failed';

        if (update.exception != null) {
          // Extract meaningful error message
          final exception = update.exception!;
          if (exception.runtimeType.toString().contains('TaskException')) {
            // TaskException contains httpResponseCode and description
            errorDetails = exception.toString();
          } else {
            errorDetails = exception.toString();
          }

          // Trim long stack traces
          if (errorDetails.length > 200) {
            errorDetails = '${errorDetails.substring(0, 197)}...';
          }
        }

        state = {
          ...state,
          appId: currentInfo.copyWith(
            status: update.status,
            errorDetails: errorDetails,
          ),
        };
      } else if (update.status == TaskStatus.paused) {
        state = {...state, appId: currentInfo.copyWith(status: update.status)};
      } else if (update.status == TaskStatus.canceled) {
        state = {...state, appId: currentInfo.copyWith(status: update.status)};
      } else {
        state = {...state, appId: currentInfo.copyWith(status: update.status)};

        // Handle completion
        if (update.status == TaskStatus.complete) {
          _handleDownloadComplete(appId);
        }
      }
    } else if (update is TaskProgressUpdate) {
      // Background_downloader sends bogus -5.0 progress when pausing
      // We need to preserve the last valid progress instead of using 0.0
      double safeProgress = currentInfo.progress;

      if (update.progress.isFinite &&
          !update.progress.isNaN &&
          update.progress >= 0.0) {
        safeProgress = update.progress.clamp(0.0, 1.0);
      }
      // Otherwise keep the previous valid progress

      state = {...state, appId: currentInfo.copyWith(progress: safeProgress)};
    }
  }

  /// Handle download completion - add to installation queue
  Future<void> _handleDownloadComplete(String appId) async {
    final downloadInfo = state[appId];
    // ignore: unnecessary_null_comparison
    if (downloadInfo == null) return; // Early exit if already removed
    
    // Add to installation queue
    if (!_installQueue.contains(appId)) {
      _installQueue.add(appId);
    }
    
    // Process queue if not already processing
    _processInstallQueue();
  }
  
  /// Process installation queue sequentially to prevent concurrent installations
  Future<void> _processInstallQueue() async {
    // Don't start processing if already processing
    if (_isInstallingFromQueue) return;
    
    // Don't process if queue is empty
    if (_installQueue.isEmpty) return;
    
    _isInstallingFromQueue = true;
    
    while (_installQueue.isNotEmpty) {
      final appId = _installQueue.first;
      final downloadInfo = state[appId];
      
      // Skip if download info no longer exists
      if (downloadInfo == null) {
        _installQueue.removeAt(0);
        continue;
      }
      
      try {
        // Get the downloaded file path
        final filePath = await downloadInfo.task.filePath();

        // Update state to show installing
        state = {...state, appId: downloadInfo.copyWith(isInstalling: true)};

        // Use the stored file metadata from the download info
        final fileMetadata = downloadInfo.fileMetadata;

        // Trigger installation with verification data
        final packageManager = ref.read(packageManagerProvider.notifier);
        await packageManager.install(
          appId,
          filePath,
          expectedHash: fileMetadata.hash,
          expectedSize: fileMetadata.size ?? 0,
        );
        
        // Remove from queue after successful installation initiation
        _installQueue.removeAt(0);
        
        // Add a small delay between installations to ensure Android processes them sequentially
        if (_installQueue.isNotEmpty) {
          await Future.delayed(const Duration(milliseconds: 500));
        }
      } catch (e) {
        // Remove from queue and state on error
        _installQueue.removeAt(0);
        state = Map.from(state)..remove(appId);
      }
    }
    
    _isInstallingFromQueue = false;
  }

  /// Handle installation result from BroadcastReceiver
  void _handleInstallResult(Map<String, dynamic> result) {
    if (!mounted) return; // Safety check - notifier may be disposed

    final packageName = result['packageName'] as String?;
    final success = result['success'] as bool? ?? false;

    if (packageName == null) return; // Safety check

    // Find and remove the download
    final downloadInfo = state[packageName];
    // ignore: unnecessary_null_comparison
    if (downloadInfo != null) {
      // Check if download exists
      if (success) {
        // Clean up downloaded file - ALWAYS delete after successful install
        // Fire-and-forget: no state modifications in callback
        downloadInfo.task.filePath().then((filePath) async {
          try {
            final file = File(filePath);
            if (await file.exists()) {
              await file.delete();
            }
          } catch (e) {
            // Continue even if deletion fails
          }
        });

        // Remove from state (synchronous, before any async gaps)
        state = Map.from(state)..remove(packageName);

        // Refresh package list to show installed app
        ref.read(packageManagerProvider.notifier).syncInstalledPackages();
      } else {
        // Keep download info but mark as ready to install (don't remove file)
        state = Map.from(state)
          ..[packageName] = downloadInfo.copyWith(
            isInstalling: false,
            isReadyToInstall: true,
          );
      }
    }
  }

  /// Pause a download
  ///
  /// The background_downloader package handles pause state persistence,
  /// so downloads can be resumed even after app restart.
  Future<void> pauseDownload(String appId) async {
    final downloadInfo = state[appId];
    if (downloadInfo == null) {
      return;
    }

    try {
      await _downloader.pause(downloadInfo.task);
      // State update happens automatically via task callbacks
    } catch (e) {
      // Exception while pausing download
    }
  }

  /// Resume a paused download
  ///
  /// The background_downloader package automatically handles resumption
  /// from the exact byte position where it was paused.
  Future<void> resumeDownload(String appId) async {
    final downloadInfo = state[appId];
    if (downloadInfo == null) {
      return;
    }

    try {
      await _downloader.resume(downloadInfo.task);
      // State update happens automatically via task callbacks
    } catch (e) {
      // Exception while resuming download
    }
  }

  /// Cancel a download
  Future<void> cancelDownload(String appId) async {
    final downloadInfo = state[appId];
    if (downloadInfo == null) return;

    try {
      await _downloader.cancel(downloadInfo.task);

      // Remove from state
      state = Map.from(state)..remove(appId);

      // Clean up any partial file
      final filePath = await downloadInfo.task.filePath();
      // ignore: unnecessary_null_comparison
      if (filePath != null) {
        // File might not exist yet
        await File(filePath).delete().catchError((_) => File(filePath));
      }
    } catch (e) {
      // Failed to cancel download
    }
  }

  /// Retry a failed download
  Future<void> retryDownload(String appId) async {
    final downloadInfo = state[appId];
    if (downloadInfo == null) return;

    try {
      // The package handles retry automatically with the retries parameter
      // For manual retry, we need to create a new task
      await _downloader.cancel(downloadInfo.task);
      state = Map.from(state)..remove(appId);

      // Re-download using the same logic
      // This requires us to have access to the app and release
      // For now, just remove it and let user re-initiate
    } catch (e) {
      // Failed to retry download
    }
  }

  /// Get download info for an app
  DownloadInfo? getDownloadInfo(String appId) => state[appId];

  /// Check if app is currently downloading
  bool isDownloading(String appId) {
    final info = state[appId];
    return info != null &&
        (info.status == TaskStatus.running ||
            info.status == TaskStatus.enqueued);
  }

  /// Mark download as installing (for reckless mode retry)
  void markInstalling(String appId) {
    final downloadInfo = state[appId];
    if (downloadInfo != null) {
      state = {
        ...state,
        appId: downloadInfo.copyWith(
          isInstalling: true,
          isReadyToInstall: false,
          errorDetails: null,
        ),
      };
    }
  }

  /// Install from existing downloaded file
  Future<void> installFromDownloaded(String appId) async {
    final downloadInfo = state[appId];
    if (downloadInfo == null || !downloadInfo.isReadyToInstall) return;

    try {
      final filePath = await downloadInfo.task.filePath();

      // Check if file still exists
      if (!await File(filePath).exists()) {
        // File was deleted, remove from state
        state = Map.from(state)..remove(appId);
        return;
      }

      // Update state to mark as no longer ready to install (about to install)
      state = {
        ...state,
        appId: downloadInfo.copyWith(
          isReadyToInstall: false,
        ),
      };

      // Add to installation queue instead of installing directly
      if (!_installQueue.contains(appId)) {
        _installQueue.add(appId);
      }
      
      // Process queue if not already processing
      _processInstallQueue();
    } catch (e) {
      // Reset to ready-to-install state on error
      state = Map.from(state)
        ..[appId] = downloadInfo.copyWith(
          isInstalling: false,
          isReadyToInstall: true,
        );
    }
  }

  /// Clean up all downloads
  Future<void> cleanupDownloads() async {
    try {
      // Cancel all active downloads
      for (final downloadInfo in state.values) {
        await _downloader.cancel(downloadInfo.task);
      }

      // Clear state
      state = {};

      // Clean up download directory
      // background_downloader manages its own directory internally
      // We rely on task.filePath() to get file locations
    } catch (e) {
      // Failed to cleanup downloads
    }
  }
}

/// Provider for the download service
final downloadServiceProvider =
    StateNotifierProvider<DownloadService, Map<String, DownloadInfo>>(
      DownloadService.new,
    );

/// Provider for getting download info for a specific app
final downloadInfoProvider = Provider.family<DownloadInfo?, String>((
  ref,
  appId,
) {
  final downloads = ref.watch(downloadServiceProvider);
  return downloads[appId];
});
