import 'package:models/models.dart';

/// Stale download threshold - operations older than this will be cleaned up
const staleOperationThreshold = Duration(days: 7);

/// Maximum concurrent downloads allowed
const maxConcurrentDownloads = 3;

// ═══════════════════════════════════════════════════════════════════════════════
// INSTALL OPERATION STATE MACHINE
// ═══════════════════════════════════════════════════════════════════════════════

/// Represents an active install operation for an app.
/// When there's no operation, the app simply has no entry in the operations map.
sealed class InstallOperation {
  /// The target file metadata being installed (works for both FileMetadata and SoftwareAsset)
  final FileMetadata target;

  const InstallOperation({required this.target});
}

// ═══════════════════════════════════════════════════════════════════════════════
// DOWNLOAD PHASE (Cancel allowed)
// ═══════════════════════════════════════════════════════════════════════════════

/// Waiting for download slot (max concurrent reached)
class DownloadQueued extends InstallOperation {
  final String? displayName;

  const DownloadQueued({required super.target, this.displayName});
}

/// Actively downloading
class Downloading extends InstallOperation {
  final double progress;
  final String taskId;

  const Downloading({
    required super.target,
    required this.progress,
    required this.taskId,
  });

  Downloading copyWith({double? progress}) {
    return Downloading(
      target: target,
      progress: progress ?? this.progress,
      taskId: taskId,
    );
  }
}

/// Download paused by user
class DownloadPaused extends InstallOperation {
  final double progress;
  final String taskId;

  const DownloadPaused({
    required super.target,
    required this.progress,
    required this.taskId,
  });
}

// ═══════════════════════════════════════════════════════════════════════════════
// VERIFICATION PHASE (Kotlin is verifying hash)
// ═══════════════════════════════════════════════════════════════════════════════

/// Verifying downloaded file hash (happens in Kotlin, visible to UI)
class Verifying extends InstallOperation {
  final String filePath;

  const Verifying({required super.target, required this.filePath});
}

// ═══════════════════════════════════════════════════════════════════════════════
// PERMISSION PHASE (Explicit for UX feedback)
// ═══════════════════════════════════════════════════════════════════════════════

/// Waiting for user to grant install permission
class AwaitingPermission extends InstallOperation {
  final String filePath;

  const AwaitingPermission({required super.target, required this.filePath});
}

// ═══════════════════════════════════════════════════════════════════════════════
// INSTALL PHASE (No cancel - Android controls)
// ═══════════════════════════════════════════════════════════════════════════════

/// File verified, waiting for install to be triggered
class ReadyToInstall extends InstallOperation {
  final String filePath;

  const ReadyToInstall({required super.target, required this.filePath});
}

/// Native installation in progress
class Installing extends InstallOperation {
  final String filePath;
  final bool isSilent;
  final DateTime startedAt;

  Installing({
    required super.target,
    required this.filePath,
    this.isSilent = false,
    DateTime? startedAt,
  }) : startedAt = startedAt ?? DateTime.now();
}

/// System dialog was dismissed/backgrounded - user can retry
/// Different from Failed: this is recoverable with a tap
class AwaitingUserAction extends InstallOperation {
  final String filePath;

  const AwaitingUserAction({required super.target, required this.filePath});
}

/// Uninstalling app (for force update: uninstall → install)
class Uninstalling extends InstallOperation {
  final String filePath;

  const Uninstalling({required super.target, required this.filePath});
}

// ═══════════════════════════════════════════════════════════════════════════════
// FAILURE STATE (Dismiss available)
// ═══════════════════════════════════════════════════════════════════════════════

/// Operation failed - may be retryable depending on type
class OperationFailed extends InstallOperation {
  final FailureType type;
  final String message;
  final String? description;
  final String? filePath;

  const OperationFailed({
    required super.target,
    required this.type,
    required this.message,
    this.description,
    this.filePath,
  });

  /// Whether this requires force update (uninstall + install)
  bool get needsForceUpdate => type == FailureType.certMismatch;
}

/// Types of failures that can occur during install operations
enum FailureType {
  /// Network error, timeout, server error during download
  downloadFailed,

  /// Hash doesn't match expected - can retry with reckless mode
  hashMismatch,

  /// File is corrupted or not a valid APK
  invalidFile,

  /// Generic installation error
  installFailed,

  /// Certificate/signature mismatch - needs force update (uninstall + install)
  certMismatch,

  /// User doesn't have install permission
  permissionDenied,

  /// Not enough storage space
  insufficientStorage,
}

// ═══════════════════════════════════════════════════════════════════════════════
// HELPER EXTENSIONS
// ═══════════════════════════════════════════════════════════════════════════════

extension InstallOperationX on InstallOperation {
  /// Whether this operation is in a download state (cancel allowed)
  bool get isDownloading =>
      this is DownloadQueued || this is Downloading || this is DownloadPaused;

  /// Whether this operation is actively processing (not waiting for user)
  bool get isActive =>
      this is Downloading ||
      this is Verifying ||
      this is Installing ||
      this is Uninstalling;

  /// Whether this operation is in the verification phase
  bool get isVerifying => this is Verifying;

  /// Get file path if available
  String? get filePath => switch (this) {
    Verifying(:final filePath) => filePath,
    AwaitingPermission(:final filePath) => filePath,
    ReadyToInstall(:final filePath) => filePath,
    Installing(:final filePath) => filePath,
    AwaitingUserAction(:final filePath) => filePath,
    Uninstalling(:final filePath) => filePath,
    OperationFailed(:final filePath) => filePath,
    _ => null,
  };
}
