import 'dart:io';

import 'package:hooks_riverpod/hooks_riverpod.dart';

import '../package_manager/android_package_manager.dart';
import '../package_manager/package_manager.dart';
import '../secure_storage_service.dart';
import 'download_info.dart';

/// Callback to update state in the main service
typedef StateUpdater = void Function(
  String appId,
  DownloadInfo Function(DownloadInfo) updater,
);

/// Callback to remove from state
typedef StateRemover = void Function(String appId);

/// Callback to get current state
typedef StateGetter = DownloadInfo? Function(String appId);

/// Manages sequential installation of downloaded apps
class InstallationQueue {
  InstallationQueue(this._ref);

  final Ref _ref;

  final List<String> _queue = [];
  final Set<String> _pendingInstallations = {};
  bool _isProcessing = false;
  bool _isAppInForeground = true;

  // Callbacks set by the service
  late StateUpdater _updateState;
  late StateRemover _removeFromState;
  late StateGetter _getState;

  void setCallbacks({
    required StateUpdater updateState,
    required StateRemover removeFromState,
    required StateGetter getState,
  }) {
    _updateState = updateState;
    _removeFromState = removeFromState;
    _getState = getState;
  }

  /// Set app foreground state
  Future<void> setAppForeground(bool inForeground) async {
    final wasBackground = !_isAppInForeground;
    _isAppInForeground = inForeground;

    final packageManager = _ref.read(packageManagerProvider.notifier);
    if (packageManager is AndroidPackageManager) {
      await packageManager.setAppForegroundState(inForeground);
    }

    if (inForeground && wasBackground) {
      await _handleReturnToForeground();
    }
  }

  /// Handle download completion - add to installation queue
  Future<void> handleDownloadComplete(String appId) async {
    final downloadInfo = _getState(appId);
    if (downloadInfo == null) return;

    final packageManager = _ref.read(packageManagerProvider.notifier);
    final canSilent = await packageManager.canInstallSilently(appId);

    // If backgrounded and not silent install, defer
    if (!_isAppInForeground && !canSilent) {
      _pendingInstallations.add(appId);
      _updateState(appId, (info) => info.copyWith(isReadyToInstall: true));
      return;
    }

    // Check if permission dialog needs to be shown (Android, non-silent, first time)
    if (Platform.isAndroid && !canSilent) {
      final secureStorage = _ref.read(secureStorageServiceProvider);
      final hasSeenDialog = await secureStorage.hasSeenInstallPermissionDialog();
      if (!hasSeenDialog) {
        _updateState(appId, (info) => info.copyWith(isReadyToInstall: true));
        return;
      }
    }

    // Add to queue and process
    if (!_queue.contains(appId)) {
      _queue.add(appId);
    }
    _processQueue();
  }

  /// Add to installation queue manually (e.g., from installFromDownloaded)
  void enqueue(String appId) {
    if (!_queue.contains(appId)) {
      _queue.add(appId);
    }
    _processQueue();
  }

  /// Handle returning to foreground
  Future<void> _handleReturnToForeground() async {
    // Process pending installations
    if (_pendingInstallations.isNotEmpty) {
      for (final appId in _pendingInstallations) {
        if (!_queue.contains(appId)) {
          _queue.add(appId);
        }
      }
      _pendingInstallations.clear();
      _processQueue();
    }
  }

  /// Process stalled apps (called by service with list of stalled appIds)
  Future<void> handleStalledApps(List<String> stalledAppIds) async {
    final packageManager = _ref.read(packageManagerProvider.notifier);

    for (final appId in stalledAppIds) {
      if (packageManager is AndroidPackageManager) {
        final retry = await packageManager.retryPendingInstall(appId);

        if (retry.hasPending && (retry.relaunched || retry.promptAlreadyShown)) {
          continue;
        }

        if (retry.hasPending && retry.sessionPending) {
          _updateState(appId, (info) => info.copyWith(
            isInstalling: false,
            isReadyToInstall: true,
          ));
          continue;
        }
      }

      // No pending prompt - mark ready to install
      _updateState(appId, (info) => info.copyWith(
        isInstalling: false,
        isReadyToInstall: true,
      ));
    }

    // Reset lock if there were stalled apps without prompts
    final stalledWithoutPrompt = stalledAppIds.where((appId) {
      final info = _getState(appId);
      return info != null && !info.isInstalling && info.isReadyToInstall;
    }).toList();

    if (stalledWithoutPrompt.isNotEmpty) {
      _isProcessing = false;
    }

    _processQueue();
  }

  /// Process installation queue sequentially
  Future<void> _processQueue() async {
    if (_isProcessing || _queue.isEmpty) return;

    _isProcessing = true;

    try {
      while (_queue.isNotEmpty) {
        final appId = _queue.removeAt(0);
        final downloadInfo = _getState(appId);

        if (downloadInfo == null) continue;

        try {
          final filePath = await downloadInfo.task.filePath();

          _updateState(appId, (info) => info.copyWith(
            isInstalling: true,
            isReadyToInstall: false,
          ));

          final packageManager = _ref.read(packageManagerProvider.notifier);
          await packageManager.install(
            appId,
            filePath,
            expectedHash: downloadInfo.fileMetadata.hash,
            expectedSize: downloadInfo.fileMetadata.size ?? 0,
            skipVerification: downloadInfo.skipVerificationOnInstall,
          );

          // Clean up file after successful install
          try {
            final file = File(filePath);
            if (await file.exists()) {
              await file.delete();
            }
          } catch (_) {}

          _removeFromState(appId);

          if (_queue.isNotEmpty) {
            await Future.delayed(const Duration(milliseconds: 500));
          }
        } catch (e) {
          _handleInstallError(appId, downloadInfo, e.toString());
        }
      }
    } finally {
      _isProcessing = false;
    }
  }

  void _handleInstallError(String appId, DownloadInfo downloadInfo, String errorMessage) {
    // Already in progress - put back and stop
    if (errorMessage.contains('INSTALL_ALREADY_IN_PROGRESS')) {
      _queue.insert(0, appId);
      _updateState(appId, (info) => info.copyWith(
        isInstalling: true,
        isReadyToInstall: false,
      ));
      return;
    }

    final isCertMismatch =
        errorMessage.contains('signatures do not match') ||
        errorMessage.contains('INSTALL_FAILED_UPDATE_INCOMPATIBLE') ||
        errorMessage.contains('UPDATE_INCOMPATIBLE');

    final wasCancelled = errorMessage.contains('cancelled');

    if (wasCancelled) {
      _removeFromState(appId);
    } else {
      _updateState(appId, (info) => info.copyWith(
        isInstalling: false,
        isReadyToInstall: true,
        errorDetails: isCertMismatch
            ? 'CERTIFICATE_MISMATCH'
            : errorMessage.replaceFirst('Exception: ', ''),
      ));
    }
  }
}

