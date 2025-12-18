import 'dart:io';

import 'package:background_downloader/background_downloader.dart';
import 'package:flutter/foundation.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';

import '../../utils/extensions.dart';
import '../package_manager/package_manager.dart';
import 'download_info.dart';
import 'download_persistence.dart';
import 'installation_queue.dart';

export 'download_info.dart';

/// Download service - orchestrates downloads and installations
class DownloadService extends StateNotifier<Map<String, DownloadInfo>> {
  DownloadService(this.ref) : super({}) {
    _installQueue = InstallationQueue(ref);
    _initialize();
  }

  final Ref ref;
  late final FileDownloader _downloader;
  late final DownloadPersistence _persistence;
  late final InstallationQueue _installQueue;

  // Download queue for overflow (>3 concurrent)
  final List<QueuedDownload> _downloadQueue = [];

  @override
  void dispose() {
    _downloader.unregisterCallbacks();
    super.dispose();
  }

  Future<void> _initialize() async {
    _downloader = FileDownloader();

    // Configure downloader
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

    // Configure notifications
    _downloader.configureNotificationForGroup(
      FileDownloader.defaultGroup,
      running: const TaskNotification(
        'Downloading {displayName}',
        '{progress}%',
      ),
      complete: const TaskNotification('Download complete', '{displayName}'),
      error: const TaskNotification('Download failed', '{displayName}'),
      paused: const TaskNotification('Download paused', '{displayName}'),
      progressBar: true,
    );

    // Set up callbacks
    _downloader.registerCallbacks(
      taskStatusCallback: _handleTaskUpdate,
      taskProgressCallback: _handleTaskUpdate,
    );

    // Set up installation queue callbacks
    _installQueue.setCallbacks(
      updateState: (appId, updater) {
        final current = state[appId];
        if (current != null) {
          state = {...state, appId: updater(current)};
        }
      },
      removeFromState: (appId) {
        state = Map.from(state)..remove(appId);
      },
      getState: (appId) => state[appId],
    );

    // Restore persisted downloads
    _persistence = DownloadPersistence(
      _downloader,
      ref.read(storageNotifierProvider.notifier),
    );
    await _restoreDownloads();
  }

  Future<void> _restoreDownloads() async {
    final restored = await _persistence.restoreState();
    state = restored;

    // Resume incomplete downloads
    for (final entry in restored.entries) {
      final info = entry.value;
      if (_shouldResume(info.status)) {
        try {
          final isActive = await _downloader.taskForId(info.taskId) != null;
          if (!isActive) {
            await _downloader.resume(info.task);
          }
        } catch (e) {
          state = {
            ...state,
            entry.key: info.copyWith(
              status: TaskStatus.failed,
              errorDetails: 'Could not resume download. Please retry.',
            ),
          };
        }
      }
    }
  }

  bool _shouldResume(TaskStatus status) {
    return status == TaskStatus.paused ||
        status == TaskStatus.running ||
        status == TaskStatus.enqueued ||
        status == TaskStatus.waitingToRetry;
  }

  // ============ Public API ============

  /// Set app foreground state (called from lifecycle observer)
  Future<void> setAppForeground(bool inForeground) async {
    final stalledApps = state.entries
        .where((e) => e.value.isInstalling)
        .map((e) => e.key)
        .toList();

    await _installQueue.setAppForeground(inForeground);

    if (inForeground && stalledApps.isNotEmpty) {
      await _installQueue.handleStalledApps(stalledApps);
    }
  }

  /// Start downloading an app
  Future<void> downloadApp(App app, Release release) async {
    final fileMetadata = app.latestFileMetadata;
    if (fileMetadata == null) {
      throw Exception('No file metadata available for this release');
    }

    final appId = app.identifier;

    // Check if already downloading or queued
    if (state.containsKey(appId)) {
      final existing = state[appId]!;
      if (existing.status == TaskStatus.running ||
          existing.status == TaskStatus.enqueued) {
        return;
      }
    }
    if (_downloadQueue.any((q) => q.appId == appId)) {
      return;
    }

    // Check concurrent limit
    final activeCount = _countActiveDownloads();
    if (activeCount >= maxConcurrentDownloads) {
      _downloadQueue.add(
        QueuedDownload(
          appId: appId,
          appName: app.name ?? appId,
          fileMetadata: fileMetadata,
        ),
      );
      return;
    }

    await _startDownload(appId, app.name ?? appId, fileMetadata);
  }

  /// Pause a download
  Future<void> pauseDownload(String appId) async {
    final info = state[appId];
    if (info == null) return;

    try {
      await _downloader.pause(info.task);
    } catch (_) {}
  }

  /// Resume a paused download
  Future<void> resumeDownload(String appId) async {
    final info = state[appId];
    if (info == null) return;

    try {
      await _downloader.resume(info.task);
    } catch (_) {}
  }

  /// Cancel a download
  Future<void> cancelDownload(String appId) async {
    final info = state[appId];
    if (info == null) return;

    try {
      await _downloader.cancel(info.task);
      state = Map.from(state)..remove(appId);

      final filePath = await info.task.filePath();
      await File(filePath).delete().catchError((_) => File(filePath));
    } catch (_) {}
  }

  /// Install from downloaded file
  Future<void> installFromDownloaded(String appId) async {
    final info = state[appId];
    if (info == null || !info.isReadyToInstall) return;

    try {
      final filePath = await info.task.filePath();
      if (!await File(filePath).exists()) {
        state = Map.from(state)..remove(appId);
        return;
      }

      state = {
        ...state,
        appId: info.copyWith(isReadyToInstall: false, errorDetails: null),
      };

      _installQueue.enqueue(appId);
    } catch (_) {
      state = {
        ...state,
        appId: info.copyWith(isInstalling: false, isReadyToInstall: true),
      };
    }
  }

  /// Mark a download as ready to install (for reckless mode)
  void markReadyToInstall(
    String appId, {
    bool skipVerificationOnInstall = false,
  }) {
    final info = state[appId];
    if (info == null) return;

    state = {
      ...state,
      appId: info.copyWith(
        isInstalling: false,
        isReadyToInstall: true,
        skipVerificationOnInstall: skipVerificationOnInstall,
        errorDetails: null,
      ),
    };
  }

  /// Clear error for retry
  void clearError(String appId) {
    final info = state[appId];
    if (info?.errorDetails != null) {
      state = {...state, appId: info!.copyWith(errorDetails: null)};
    }
  }

  /// Get download info (prefer using downloadInfoProvider)
  DownloadInfo? getDownloadInfo(String appId) => state[appId];

  /// Download with explicit metadata (used for alternative artifact selection)
  Future<void> downloadAppWithMetadata(
    String appId,
    String appName,
    FileMetadata fileMetadata,
  ) async {
    // Check if already downloading or queued
    if (state.containsKey(appId)) {
      final existing = state[appId]!;
      if (existing.status == TaskStatus.running ||
          existing.status == TaskStatus.enqueued) {
        return;
      }
    }
    if (_downloadQueue.any((q) => q.appId == appId)) {
      return;
    }

    // Check concurrent limit
    final activeCount = _countActiveDownloads();
    if (activeCount >= maxConcurrentDownloads) {
      _downloadQueue.add(
        QueuedDownload(
          appId: appId,
          appName: appName,
          fileMetadata: fileMetadata,
        ),
      );
      return;
    }

    await _startDownload(appId, appName, fileMetadata);
  }

  // ============ Internal ============

  int _countActiveDownloads() {
    return state.values
        .where(
          (info) =>
              info.status == TaskStatus.running ||
              info.status == TaskStatus.enqueued ||
              info.status == TaskStatus.waitingToRetry,
        )
        .length;
  }

  Future<void> _startDownload(
    String appId,
    String appName,
    FileMetadata fileMetadata,
  ) async {
    final packageManager = ref.read(packageManagerProvider.notifier);
    final platform = packageManager.platform;

    String? downloadUrl;
    if (fileMetadata.platforms.contains(platform) ||
        fileMetadata.platforms.isEmpty) {
      downloadUrl = fileMetadata.urls.firstOrNull;
    }

    if (downloadUrl == null || downloadUrl.isEmpty) return;

    final fileName = '${fileMetadata.hash}${packageManager.packageExtension}';
    final metaData = DownloadPersistence.encodeMetadata(appId, fileMetadata.id);

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
      displayName: appName,
    );

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
        state = Map.from(state)..remove(appId);
      }
    } catch (_) {
      state = Map.from(state)..remove(appId);
    }
  }

  void _startNextQueuedDownload() {
    if (_downloadQueue.isEmpty) return;

    if (_countActiveDownloads() < maxConcurrentDownloads) {
      final next = _downloadQueue.removeAt(0);
      _startDownload(next.appId, next.appName, next.fileMetadata);
    }
  }

  void _handleTaskUpdate(TaskUpdate update) {
    String? appId;
    for (final entry in state.entries) {
      if (entry.value.taskId == update.task.taskId) {
        appId = entry.key;
        break;
      }
    }
    if (appId == null) return;

    final current = state[appId]!;

    if (update is TaskStatusUpdate) {
      _handleStatusUpdate(appId, current, update);
    } else if (update is TaskProgressUpdate) {
      _handleProgressUpdate(appId, current, update);
    }
  }

  void _handleStatusUpdate(
    String appId,
    DownloadInfo current,
    TaskStatusUpdate update,
  ) {
    switch (update.status) {
      case TaskStatus.failed:
        String error = 'Download failed';
        if (update.exception != null) {
          error = update.exception.toString();
          if (error.length > 200) error = '${error.substring(0, 197)}...';
        }
        state = {
          ...state,
          appId: current.copyWith(status: update.status, errorDetails: error),
        };
        _startNextQueuedDownload();

      case TaskStatus.canceled:
        state = {...state, appId: current.copyWith(status: update.status)};
        _startNextQueuedDownload();

      case TaskStatus.complete:
        state = {...state, appId: current.copyWith(status: update.status)};
        _installQueue.handleDownloadComplete(appId);
        _startNextQueuedDownload();

      default:
        state = {...state, appId: current.copyWith(status: update.status)};
    }
  }

  void _handleProgressUpdate(
    String appId,
    DownloadInfo current,
    TaskProgressUpdate update,
  ) {
    double progress = current.progress;
    if (update.progress.isFinite &&
        !update.progress.isNaN &&
        update.progress >= 0.0) {
      progress = update.progress.clamp(0.0, 1.0);
    }
    state = {...state, appId: current.copyWith(progress: progress)};
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
  return ref.watch(downloadServiceProvider)[appId];
});
