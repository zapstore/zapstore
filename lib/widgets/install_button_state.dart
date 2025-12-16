import 'package:background_downloader/background_downloader.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/download/download_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';

/// Represents all possible button states in the install flow.
/// This state machine ensures UI consistency and prevents invalid states.
sealed class InstallButtonState {
  const InstallButtonState();
}

/// App is not installed, ready to install
class ReadyToInstall extends InstallButtonState {
  final bool hasRelease;
  const ReadyToInstall({required this.hasRelease});
}

/// App is installed and up to date, can be opened
class InstalledUpToDate extends InstallButtonState {
  const InstalledUpToDate();
}

/// App is installed, update available
class UpdateAvailable extends InstallButtonState {
  final bool hasRelease;
  const UpdateAvailable({required this.hasRelease});
}

/// App is installed, but relay has older version (downgrade not allowed)
class DowngradeBlocked extends InstallButtonState {
  const DowngradeBlocked();
}

/// Download is in progress
class Downloading extends InstallButtonState {
  final double progress;
  final String? totalSizeMb;
  const Downloading({required this.progress, this.totalSizeMb});
}

/// Download is paused
class DownloadPaused extends InstallButtonState {
  final double progress;
  final String? totalSizeMb;
  const DownloadPaused({required this.progress, this.totalSizeMb});
}

/// Download is enqueued or waiting to retry
class DownloadEnqueued extends InstallButtonState {
  final bool isUpdate;
  const DownloadEnqueued({required this.isUpdate});
}

/// Download completed, ready to install from file
class DownloadedReadyToInstall extends InstallButtonState {
  final bool isUpdate;
  const DownloadedReadyToInstall({required this.isUpdate});
}

/// Certificate mismatch - force update required (uninstall + install)
class ForceUpdateRequired extends InstallButtonState {
  const ForceUpdateRequired();
}

/// Installation is in progress
class Installing extends InstallButtonState {
  const Installing();
}

/// Download or installation failed
class Failed extends InstallButtonState {
  final String errorMessage;
  final bool canRetryReckless;
  final DownloadInfo downloadInfo;
  const Failed({
    required this.errorMessage,
    required this.canRetryReckless,
    required this.downloadInfo,
  });
}

/// Determines the current button state from app, download info, and release data.
/// This is the single source of truth for button state logic.
InstallButtonState determineInstallButtonState({
  required App app,
  required PackageInfo? installedPackage,
  required DownloadInfo? downloadInfo,
  required Release? release,
  required String? Function(Release?) formatTotalSizeMb,
}) {
  final isInstalled = installedPackage != null;
  final hasUpdate = app.hasUpdate;
  final hasDowngrade = app.hasDowngrade;
  final hasRelease = release != null;

  // PRIORITY 1: Check active download states first
  if (downloadInfo != null) {
    // Ready to install from downloaded file
    if (downloadInfo.isReadyToInstall) {
      // Check for certificate mismatch
      if (downloadInfo.errorDetails == 'CERTIFICATE_MISMATCH') {
        return const ForceUpdateRequired();
      }
      return DownloadedReadyToInstall(isUpdate: isInstalled && hasUpdate);
    }

    // Installation in progress
    if (downloadInfo.isInstalling) {
      return const Installing();
    }

    // Check task status
    switch (downloadInfo.status) {
      case TaskStatus.running:
        return Downloading(
          progress: downloadInfo.progress,
          totalSizeMb: formatTotalSizeMb(release),
        );

      case TaskStatus.paused:
        return DownloadPaused(
          progress: downloadInfo.progress,
          totalSizeMb: formatTotalSizeMb(release),
        );

      case TaskStatus.enqueued:
      case TaskStatus.waitingToRetry:
        return DownloadEnqueued(isUpdate: isInstalled && hasUpdate);

      case TaskStatus.complete:
        return DownloadedReadyToInstall(isUpdate: isInstalled && hasUpdate);

      case TaskStatus.failed:
        final errorMessage =
            downloadInfo.errorDetails ?? 'Download failed. Please try again.';
        final isHashError =
            errorMessage.contains('Hash verification failed') &&
                !errorMessage.contains('Invalid APK file');
        return Failed(
          errorMessage: errorMessage,
          canRetryReckless: isHashError,
          downloadInfo: downloadInfo,
        );

      case TaskStatus.canceled:
      case TaskStatus.notFound:
        break;
    }
  }

  // PRIORITY 2: Check installation state (no active download)
  if (isInstalled) {
    // Downgrade case
    if (hasDowngrade) {
      return const DowngradeBlocked();
    }

    // Update available
    if (hasUpdate) {
      return UpdateAvailable(hasRelease: hasRelease);
    }

    // Up to date
    return const InstalledUpToDate();
  }

  // PRIORITY 3: Not installed, no active download
  return ReadyToInstall(hasRelease: hasRelease);
}

