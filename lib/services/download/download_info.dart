import 'package:background_downloader/background_downloader.dart';
import 'package:models/models.dart';

/// Stale download threshold - downloads older than this will be cleaned up
const staleDownloadThreshold = Duration(days: 7);

/// Maximum concurrent downloads allowed
const maxConcurrentDownloads = 3;

/// Wrapper around DownloadTask with app-specific metadata
class DownloadInfo {
  const DownloadInfo({
    required this.appId,
    required this.task,
    required this.fileMetadata,
    this.status = TaskStatus.enqueued,
    this.progress = 0.0,
    this.isInstalling = false,
    this.isReadyToInstall = false,
    this.skipVerificationOnInstall = false,
    this.errorDetails,
  });

  final String appId;
  final DownloadTask task;
  final FileMetadata fileMetadata;
  final TaskStatus status;
  final double progress;
  final bool isInstalling;
  final bool isReadyToInstall;
  final bool skipVerificationOnInstall;
  final String? errorDetails;

  String get taskId => task.taskId;
  String get fileName => task.filename;

  DownloadInfo copyWith({
    TaskStatus? status,
    double? progress,
    bool? isInstalling,
    bool? isReadyToInstall,
    bool? skipVerificationOnInstall,
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
      skipVerificationOnInstall:
          skipVerificationOnInstall ?? this.skipVerificationOnInstall,
      errorDetails: errorDetails ?? this.errorDetails,
    );
  }
}

/// Queued download waiting for a slot
class QueuedDownload {
  final String appId;
  final String appName;
  final FileMetadata fileMetadata;

  const QueuedDownload({
    required this.appId,
    required this.appName,
    required this.fileMetadata,
  });
}

