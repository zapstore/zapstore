import 'dart:io';

import 'package:async_button_builder/async_button_builder.dart';
import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/download/download_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/secure_storage_service.dart';
import 'package:zapstore/services/trust_service.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/author_container.dart';
import 'package:zapstore/widgets/common/base_dialog.dart';
import 'package:zapstore/widgets/install_alert_dialog.dart';
import 'package:zapstore/widgets/install_button_state.dart';
import 'package:zapstore/widgets/install_permission_dialog.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/theme.dart';

class InstallButton extends ConsumerWidget {
  const InstallButton({
    super.key,
    required this.app,
    this.release,
    this.compact = false,
  });

  final App app;
  final Release? release;
  final bool compact;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final downloadInfo = ref.watch(downloadInfoProvider(app.identifier));
    final installedPackage = ref
        .watch(packageManagerProvider)
        .where((p) => p.appId == app.identifier)
        .firstOrNull;

    // Listen for download completion to show permission dialog and auto-install
    ref.listen(downloadInfoProvider(app.identifier), (previous, next) {
      // Installation just failed - show error
      if (next != null &&
          next.isReadyToInstall &&
          next.errorDetails != null &&
          previous?.errorDetails != next.errorDetails) {
        if (context.mounted) {
          if (next.errorDetails == 'CERTIFICATE_MISMATCH') {
            context.showError(
              'Installation failed',
              description:
                  'Certificate mismatch detected. The app signature does not match the expected developer.',
            );
          } else {
            context.showError(
              'Installation failed',
              description: next.errorDetails,
            );
          }
        }
        return;
      }

      // Download just completed and ready to install (no error) - show permission dialog if needed
      if (next != null &&
          next.isReadyToInstall &&
          next.errorDetails == null &&
          previous?.isReadyToInstall != true) {
        _showPermissionDialogAndInstall(context, ref);
      }
    });

    // Determine current state from inputs using the extracted function
    final state = determineInstallButtonState(
      app: app,
      installedPackage: installedPackage,
      downloadInfo: downloadInfo,
      release: release,
      formatTotalSizeMb: _formatTotalSizeMb,
    );

    // Compact mode - just the button without positioning or extra actions
    if (compact) {
      return _buildButtonForState(context, ref, state);
    }

    // Regular mode - positioned at bottom with extra action buttons
    return Positioned(
      bottom: 0,
      left: 0,
      right: 0,
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: Theme.of(context).scaffoldBackgroundColor,
        ),
        child: SafeArea(
          child: Row(
            children: [
              Expanded(
                child: SizedBox(
                  height: 48,
                  child: _buildButtonForState(context, ref, state),
                ),
              ),
              // Show action buttons only for installed apps
              if (state is InstalledUpToDate ||
                  state is UpdateAvailable ||
                  state is DowngradeBlocked) ...[
                if (state is UpdateAvailable) ...[
                  const SizedBox(width: 8),
                  _buildOpenIconButton(context, ref),
                ],
                const SizedBox(width: 8),
                _buildUninstallIconButton(context, ref),
              ],
            ],
          ),
        ),
      ),
    );
  }

  /// Builds the appropriate button widget based on the current state
  /// Uses pattern matching on sealed class for exhaustive handling
  Widget _buildButtonForState(
    BuildContext context,
    WidgetRef ref,
    InstallButtonState state,
  ) {
    final fontSize = compact ? 13.0 : 16.0;

    return switch (state) {
      // Not installed, ready to install
      ReadyToInstall(:final hasRelease) => _buildAsyncButton(
        context,
        ref,
        text: 'Install',
        onPressed: hasRelease ? () => _startDownload(ref) : null,
        isPrimary: true,
        fontSize: fontSize,
        needsTrustCheck: true,
      ),

      // Installed and up to date
      InstalledUpToDate() => _buildAsyncButton(
        context,
        ref,
        text: 'Open',
        onPressed: () => _openApp(context, ref),
        isPrimary: true,
        fontSize: fontSize,
        needsTrustCheck: false,
      ),

      // Update available
      UpdateAvailable(:final hasRelease) => _buildAsyncButton(
        context,
        ref,
        text: 'Update',
        onPressed: hasRelease ? () => _startDownload(ref) : null,
        isPrimary: true,
        fontSize: fontSize,
        needsTrustCheck: false,
      ),

      // Downgrade blocked
      DowngradeBlocked() => _buildSimpleButton(
        context,
        "Can't downgrade",
        null,
        isPrimary: false,
        showSpinner: false,
        fontSize: fontSize,
        isDowngrade: true,
      ),

      // Download in progress
      Downloading(:final progress, :final totalSizeMb) => _buildProgressButton(
        context,
        ref,
        progress: progress,
        text: _formatDownloadProgress(progress, totalSizeMb),
        fontSize: fontSize,
        onTap: () => _pauseDownload(context, ref),
      ),

      // Download paused
      DownloadPaused(:final progress, :final totalSizeMb) =>
        _buildProgressButton(
          context,
          ref,
          progress: progress,
          text: _formatDownloadProgress(progress, totalSizeMb, paused: true),
          fontSize: fontSize,
          onTap: () => _resumeDownload(context, ref),
        ),

      // Download enqueued
      DownloadEnqueued(:final isUpdate) => _buildSimpleButton(
        context,
        isUpdate ? 'Update' : 'Install',
        () => _cancelAndRestart(ref),
        isPrimary: true,
        fontSize: fontSize,
      ),

      // Downloaded, ready to install
      DownloadedReadyToInstall(:final isUpdate) => AsyncButtonBuilder(
        onPressed: () => _showPermissionDialogAndInstall(context, ref),
        builder: (context, child, callback, buttonState) {
          return _buildSimpleButton(
            context,
            buttonState.maybeWhen(
              loading: () => 'Installing...',
              orElse: () => isUpdate ? 'Update' : 'Install',
            ),
            buttonState.maybeWhen(loading: () => null, orElse: () => callback),
            isPrimary: true,
            showSpinner: buttonState.maybeWhen(
              loading: () => true,
              orElse: () => false,
            ),
            fontSize: fontSize,
          );
        },
        child: Text(
          isUpdate ? 'Update' : 'Install',
          style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
        ),
        onError: () {
          if (context.mounted) {
            context.showError(
              'Installation failed',
              description:
                  'The package could not be installed. Check storage space and try again.',
            );
          }
        },
      ),

      // Certificate mismatch - requires force update
      ForceUpdateRequired() => _buildSimpleButton(
        context,
        'Force update',
        () => _showForceUpdateDialog(ref, context),
        isPrimary: false,
        fontSize: fontSize,
        isError: true,
      ),

      // Installing
      Installing() => _buildSimpleButton(
        context,
        'Requesting installation',
        null,
        isPrimary: true,
        showSpinner: true,
        fontSize: fontSize,
      ),

      // Failed
      Failed(:final canRetryReckless, :final downloadInfo) =>
        _buildSimpleButton(
          context,
          'Error (tap for details)',
          () => _handleErrorTap(ref, context, downloadInfo, canRetryReckless),
          isPrimary: false,
          isError: true,
          fontSize: fontSize,
        ),
    };
  }

  /// Helper to format download progress text
  String _formatDownloadProgress(
    double progress,
    String? totalSizeMb, {
    bool paused = false,
  }) {
    final percent = (progress * 100).round();
    final pausedSuffix = paused ? ' (paused)' : '';
    return totalSizeMb != null
        ? '$percent% of $totalSizeMb$pausedSuffix'
        : '$percent%$pausedSuffix';
  }

  /// Builds async button with trust check for fresh installs
  Widget _buildAsyncButton(
    BuildContext context,
    WidgetRef ref, {
    required String text,
    required Future<void> Function()? onPressed,
    required bool isPrimary,
    required double fontSize,
    required bool needsTrustCheck,
  }) {
    // Store error message for display
    String? lastError;

    return AsyncButtonBuilder(
      onPressed: onPressed == null
          ? null
          : () async {
              // Trust check only for fresh installs
              if (needsTrustCheck) {
                final signerPubkey = app.author.value?.pubkey;
                bool shouldShowDialog = true;
                if (signerPubkey != null) {
                  try {
                    final isTrusted = await ref
                        .read(trustServiceProvider)
                        .isSignerTrusted(signerPubkey);
                    shouldShowDialog = !isTrusted;
                  } catch (_) {
                    shouldShowDialog = true;
                  }
                }

                if (shouldShowDialog) {
                  if (!context.mounted) return;
                  final result =
                      await showBaseDialog<({bool trustPermanently})>(
                        context: context,
                        dialog: InstallAlertDialog(app: app),
                      );
                  if (result == null) return;
                  if (result.trustPermanently && signerPubkey != null) {
                    try {
                      await ref
                          .read(trustServiceProvider)
                          .addTrustedSigner(signerPubkey);
                    } catch (_) {
                      // ignore persistence errors
                    }
                  }
                }
              }

              try {
                await onPressed();
              } catch (e) {
                // Store the error message for display
                lastError = e.toString();
                rethrow;
              }
            },
      builder: (context, child, callback, buttonState) {
        return _buildSimpleButton(
          context,
          buttonState.maybeWhen(
            loading: () => text == 'Open' ? 'Launching...' : 'Starting...',
            orElse: () => text,
          ),
          buttonState.maybeWhen(loading: () => null, orElse: () => callback),
          isPrimary: isPrimary,
          showSpinner: false,
          fontSize: fontSize,
        );
      },
      child: Text(
        text,
        style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
      ),
      onError: () {
        if (context.mounted) {
          // Show the actual error message if available
          final message = lastError != null
              ? lastError!.replaceFirst('Exception: ', '')
              : 'Operation failed. Please try again.';
          context.showError(message);
        }
      },
    );
  }

  Widget _buildUninstallIconButton(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    return IconButton.filled(
      onPressed: () => _uninstallApp(ref, context),
      icon: const Icon(Icons.delete_outline),
      style: IconButton.styleFrom(
        backgroundColor: theme.colorScheme.errorContainer,
        foregroundColor: theme.colorScheme.onErrorContainer,
        padding: const EdgeInsets.all(12),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
      tooltip: 'Uninstall',
    );
  }

  // Removed: update logic moved to AppExt

  Widget _buildOpenIconButton(BuildContext context, WidgetRef ref) {
    const actionColor = AppColors.darkActionPrimary;
    return IconButton.filled(
      onPressed: () => _openApp(context, ref),
      icon: const Icon(Icons.open_in_new),
      style: IconButton.styleFrom(
        backgroundColor: actionColor,
        foregroundColor: Colors.white,
        padding: const EdgeInsets.all(12),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
      tooltip: 'Open',
    );
  }

  Widget _buildProgressButton(
    BuildContext context,
    WidgetRef ref, {
    required double progress,
    required String text,
    required double fontSize,
    VoidCallback? onTap,
  }) {
    const actionColor = AppColors.darkActionPrimary;
    final darkerAction = Color.alphaBlend(
      Colors.black.withValues(alpha: 0.22),
      actionColor,
    );

    return FilledButton(
      onPressed: onTap ?? () => _cancelDownload(context, ref),
      style: FilledButton.styleFrom(
        backgroundColor: Colors.transparent,
        disabledBackgroundColor: Colors.transparent,
        disabledForegroundColor: Colors.white,
        foregroundColor: Colors.white,
        elevation: 0,
        shadowColor: Colors.transparent,
        padding: EdgeInsets.zero,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(12),
        child: Stack(
          children: [
            Positioned.fill(child: Container(color: actionColor)),
            Positioned.fill(
              child: Align(
                alignment: Alignment.centerLeft,
                child: FractionallySizedBox(
                  widthFactor: progress,
                  heightFactor: 1.0,
                  child: Container(color: darkerAction),
                ),
              ),
            ),
            Center(
              child: Text(
                text,
                style: TextStyle(
                  fontSize: fontSize,
                  fontWeight: FontWeight.bold,
                  color: Colors.white,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSimpleButton(
    BuildContext context,
    String text,
    VoidCallback? onPressed, {
    bool isPrimary = true,
    bool isError = false,
    bool showSpinner = false,
    double fontSize = 16.0,
    bool isDowngrade = false,
  }) {
    final theme = Theme.of(context);

    Widget child = Row(
      mainAxisAlignment: MainAxisAlignment.center,
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        Flexible(
          child: Text(
            text,
            style: TextStyle(fontSize: fontSize, fontWeight: FontWeight.bold),
            overflow: TextOverflow.ellipsis,
            maxLines: 1,
          ),
        ),
        if (showSpinner) ...[
          const SizedBox(width: 8),
          SizedBox(
            width: 16,
            height: 16,
            child: CircularProgressIndicator(
              strokeWidth: 3,
              valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
            ),
          ),
        ],
      ],
    );

    if (isError) {
      return FilledButton(
        onPressed: onPressed,
        style: FilledButton.styleFrom(
          backgroundColor: theme.colorScheme.error,
          disabledBackgroundColor: theme.colorScheme.error,
          disabledForegroundColor: Colors.white,
          foregroundColor: Colors.white,
          padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
        child: child,
      );
    } else if (isDowngrade) {
      // Greyed out disabled button for downgrades (opaque)
      final greyColor = theme.colorScheme.outline;
      return FilledButton(
        onPressed: null, // disabled
        style: FilledButton.styleFrom(
          backgroundColor: greyColor,
          disabledBackgroundColor: greyColor,
          disabledForegroundColor: theme.colorScheme.onSurface.withValues(
            alpha: 0.5,
          ),
          padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
        child: child,
      );
    } else {
      const actionColor = AppColors.darkActionPrimary;
      final backgroundColor = isPrimary
          ? actionColor
          : actionColor.withValues(alpha: 0.8);

      return FilledButton(
        onPressed: onPressed,
        style: FilledButton.styleFrom(
          backgroundColor: backgroundColor,
          disabledBackgroundColor: backgroundColor,
          disabledForegroundColor: Colors.white,
          foregroundColor: Colors.white,
          padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
        child: child,
      );
    }
  }

  Future<void> _startDownload(WidgetRef ref) async {
    if (release == null) return;

    final downloadService = ref.read(downloadServiceProvider.notifier);
    await downloadService.downloadApp(app, release!);
  }

  /// Shows permission dialog (if needed) and triggers installation
  Future<void> _showPermissionDialogAndInstall(
    BuildContext context,
    WidgetRef ref,
  ) async {
    // Show permission explainer for first-time install (Android only)
    if (Platform.isAndroid) {
      final secureStorage = ref.read(secureStorageServiceProvider);
      final hasSeenDialog = await secureStorage.hasSeenInstallPermissionDialog();

      if (!hasSeenDialog) {
        if (!context.mounted) return;
        final shouldContinue = await showBaseDialog<bool>(
          context: context,
          dialog: const InstallPermissionDialog(),
        );
        if (shouldContinue != true) return;
        await secureStorage.setHasSeenInstallPermissionDialog();
      }
    }

    if (!context.mounted) return;
    
    try {
      final downloadService = ref.read(downloadServiceProvider.notifier);
      await downloadService.installFromDownloaded(app.identifier);
    } catch (e) {
      if (context.mounted) {
        context.showError(
          'Installation failed',
          description: 'The package could not be installed. Check storage space and try again.',
        );
      }
    }
  }

  Future<void> _resumeDownload(BuildContext context, WidgetRef ref) async {
    try {
      final downloadService = ref.read(downloadServiceProvider.notifier);
      await downloadService.resumeDownload(app.identifier);
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to resume download', description: '$e');
      }
    }
  }

  Future<void> _pauseDownload(BuildContext context, WidgetRef ref) async {
    try {
      final downloadService = ref.read(downloadServiceProvider.notifier);
      await downloadService.pauseDownload(app.identifier);
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to pause download', description: '$e');
      }
    }
  }

  Future<void> _cancelDownload(BuildContext context, WidgetRef ref) async {
    try {
      final downloadService = ref.read(downloadServiceProvider.notifier);
      await downloadService.cancelDownload(app.identifier);
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to cancel download', description: '$e');
      }
    }
  }

  Future<void> _openApp(BuildContext context, WidgetRef ref) async {
    try {
      final packageManager = ref.read(packageManagerProvider.notifier);
      context.showInfo('Launching ${app.name ?? app.identifier}...');
      await packageManager.launchApp(app.identifier);
    } catch (e) {
      if (!context.mounted) return;
      context.showError(
        'Failed to launch ${app.name ?? app.identifier}',
        description:
            'The app may have been uninstalled or moved. Try reinstalling.\n\n$e',
      );
    }
  }

  Future<void> _cancelAndRestart(WidgetRef ref) async {
    final downloadService = ref.read(downloadServiceProvider.notifier);
    await downloadService.cancelDownload(app.identifier);
  }

  String? _formatTotalSizeMb(Release? release) {
    try {
      final sizeBytes = app.latestFileMetadata?.size;
      if (sizeBytes == null || sizeBytes <= 0) return null;
      final mb = sizeBytes / (1024 * 1024);
      return '${mb.toStringAsFixed(1)} MB';
    } catch (_) {
      return null;
    }
  }

  void _handleErrorTap(
    WidgetRef ref,
    BuildContext context,
    DownloadInfo downloadInfo,
    bool canRetryReckless,
  ) {
    final errorMessage =
        downloadInfo.errorDetails ?? 'Download failed. Please try again.';

    context.showError(
      errorMessage,
      actions: canRetryReckless
          ? [
              (
                '⚠️ Proceed anyway (RECKLESS)',
                () async {
                  // Retry installation with skipVerification=true
                  // Update download state to show installing
                  final downloadService = ref.read(
                    downloadServiceProvider.notifier,
                  );
                  // Explicitly allow install for this override flow and install via the queue.
                  downloadService.markReadyToInstall(
                    app.identifier,
                    skipVerificationOnInstall: true,
                  );
                  await downloadService.installFromDownloaded(app.identifier);
                },
              ),
            ]
          : [],
    );

    // Only cancel if user doesn't have the option to proceed
    // For hash errors, keep the download so user can proceed
    if (!canRetryReckless) {
      final downloadService = ref.read(downloadServiceProvider.notifier);
      downloadService.cancelDownload(app.identifier);
    }
  }

  Future<void> _uninstallApp(WidgetRef ref, BuildContext context) async {
    try {
      final packageManager = ref.read(packageManagerProvider.notifier);
      await packageManager.uninstall(app.identifier);
      // Only reaches here after successful uninstall
      if (context.mounted) {
        context.showInfo('${app.name ?? app.identifier} has been uninstalled');
      }
    } catch (e) {
      if (context.mounted) {
        // Don't show error for user cancellation
        final message = e.toString();
        if (!message.contains('cancelled')) {
          context.showError('Uninstall failed', description: '$e');
        }
      }
    }
  }

  Future<void> _showForceUpdateDialog(
    WidgetRef ref,
    BuildContext context,
  ) async {
    // Get version and certificate info
    final installedPackage = ref
        .read(packageManagerProvider)
        .where((p) => p.appId == app.identifier)
        .firstOrNull;
    final updateVersion = app.latestFileMetadata?.version ?? 'Unknown';
    final currentVersion = installedPackage?.version ?? 'Unknown';
    final currentCertHash = installedPackage?.signatureHash ?? 'Unknown';
    final updateCertHash =
        app.latestFileMetadata?.apkSignatureHash ?? 'Unknown';

    // Get author profile
    final author = app.author.value;

    final shouldProceed = await showBaseDialog<bool>(
      context: context,
      dialog: Builder(
        builder: (dialogContext) => BaseDialog(
          titleIcon: Icon(
            Icons.security,
            color: Theme.of(dialogContext).colorScheme.error,
          ),
          title: const BaseDialogTitle('Certificate Mismatch'),
          content: BaseDialogContent(
            children: [
              Row(
                children: [
                  Expanded(
                    child: AuthorContainer(
                      profile: author!,
                      beforeText: 'This update was published by',
                      afterText: ' but signed with a different certificate.',
                      oneLine: false,
                      size: 14,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),

              // Compact version and certificate comparison
              _buildCompactHashRow(
                dialogContext,
                'Current ($currentVersion)',
                currentCertHash,
                'Installed version certificate',
              ),
              const SizedBox(height: 8),
              _buildCompactHashRow(
                dialogContext,
                'Update ($updateVersion)',
                updateCertHash,
                'New version certificate',
              ),
              const SizedBox(height: 12),
              Text(
                'Android security prevents updating apps signed by different certificates. Contact the publisher for details.',
                style: TextStyle(fontSize: 14),
              ),
              const SizedBox(height: 8),
              Text(
                'To proceed anyway, uninstall the current version and install the new one. ALL APP DATA WILL BE LOST.',
                style: TextStyle(fontSize: 14),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              style: FilledButton.styleFrom(
                backgroundColor: Theme.of(dialogContext).colorScheme.error,
                foregroundColor: Colors.white,
              ),
              child: const Text('Uninstall & Install'),
            ),
          ],
        ),
      ),
    );

    if (shouldProceed == true && context.mounted) {
      try {
        final downloadService = ref.read(downloadServiceProvider.notifier);
        final packageManager = ref.read(packageManagerProvider.notifier);

        final downloadInfo = downloadService.getDownloadInfo(app.identifier);
        if (downloadInfo == null) {
          throw Exception('Download not found');
        }

        downloadService.clearError(app.identifier);

        await packageManager.uninstall(app.identifier);

        // Queue install through DownloadService (single source of truth for installs).
        downloadService.markReadyToInstall(app.identifier);
        await downloadService.installFromDownloaded(app.identifier);
      } catch (e) {
        final message = e.toString();
        if (context.mounted && !message.contains('cancelled')) {
          context.showError('Force update failed', description: '$e');
        }
      }
    }
  }

  String _abbr(String v) {
    final t = v.trim();
    if (t.length <= 12) return t;
    return '${t.substring(0, 6)}...${t.substring(t.length - 6)}';
  }

  Widget _buildCompactHashRow(
    BuildContext context,
    String versionLabel,
    String hash,
    String tooltipText,
  ) {
    return Row(
      children: [
        Expanded(
          child: RichText(
            text: TextSpan(
              style: Theme.of(context).textTheme.bodyMedium,
              children: [
                TextSpan(
                  text: '$versionLabel → ',
                  style: TextStyle(fontWeight: FontWeight.w600),
                ),
                TextSpan(
                  text: _abbr(hash),
                  style: TextStyle(fontFamily: 'monospace'),
                ),
              ],
            ),
          ),
        ),
        IconButton(
          icon: const Icon(Icons.copy, size: 16),
          padding: EdgeInsets.zero,
          constraints: const BoxConstraints(),
          style: IconButton.styleFrom(
            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            minimumSize: Size.zero,
          ),
          tooltip: 'Copy certificate hash',
          onPressed: () {
            Clipboard.setData(ClipboardData(text: hash));
            context.showInfo('Copied $tooltipText');
          },
        ),
      ],
    );
  }
}
