import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/error_reporting_service.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/trusted_signers_service.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/author_container.dart';
import 'package:zapstore/widgets/common/base_dialog.dart';
import 'package:zapstore/widgets/install_alert_dialog.dart';
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
    final operation = ref.watch(installOperationProvider(app.identifier));
    final installedPkg = ref.watch(installedPackageProvider(app.identifier));

    // Derive what to display
    final isInstalled = installedPkg != null;
    // Note: PackageManager keeps terminal operations (e.g. Completed) in the
    // operations map for a while so batch UX (e.g. "All done") can derive counts.
    // For the detail screen action row, Completed should be treated as "not busy"
    // so the user can immediately Open/Delete after install.
    final canShowActionButtons =
        isInstalled && (operation == null || operation is Completed);
    final hasUpdate = app.hasUpdate;
    final hasDowngrade = app.hasDowngrade;
    final hasRelease = release != null;
    final fileMetadata = app.latestFileMetadata;

    // Listen for errors to show toasts
    ref.listen(installOperationProvider(app.identifier), (prev, next) {
      if (next is OperationFailed && prev is! OperationFailed) {
        _showErrorToast(context, ref, next);
      }
    });

    final fontSize = compact ? 13.0 : 16.0;

    // Build button based on operation state
    final button = _buildButton(
      context,
      ref,
      operation: operation,
      isInstalled: isInstalled,
      hasUpdate: hasUpdate,
      hasDowngrade: hasDowngrade,
      hasRelease: hasRelease,
      fileMetadata: fileMetadata,
      fontSize: fontSize,
    );

    if (compact) {
      return button;
    }

    // Full layout with action buttons
    return Positioned(
      bottom: 0,
      left: 0,
      right: 0,
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: Theme.of(context).scaffoldBackgroundColor,
        ),
        child: Row(
          children: [
            Expanded(child: SizedBox(height: 48, child: button)),
            // Show action buttons only for installed apps with no active operation
            if (canShowActionButtons) ...[
              if (hasUpdate) ...[
                const SizedBox(width: 8),
                _buildOpenIconButton(context, ref),
              ],
              const SizedBox(width: 8),
              _buildUninstallIconButton(context, ref),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildButton(
    BuildContext context,
    WidgetRef ref, {
    required InstallOperation? operation,
    required bool isInstalled,
    required bool hasUpdate,
    required bool hasDowngrade,
    required bool hasRelease,
    required FileMetadata? fileMetadata,
    required double fontSize,
  }) {
    // Completed is a terminal "result" state and may linger for batch progress UX.
    // If the app is no longer installed (e.g. user uninstalled right after install),
    // ignore a stale Completed op so we fall back to the normal "Install" UI.
    final effectiveOperation =
        (!isInstalled && operation is Completed) ? null : operation;

    // Handle operation states first
    if (effectiveOperation != null) {
      return switch (effectiveOperation) {
        DownloadQueued() => _buildSimpleButton(
          context,
          'Queued for download',
          null,
          fontSize: fontSize,
          showSpinner: true,
        ),

        Downloading(:final progress) => _buildProgressButton(
          context,
          ref,
          progress: progress,
          text: _formatProgress(progress),
          fontSize: fontSize,
          onTap: () => _pauseDownload(ref),
        ),

        DownloadPaused(:final progress) => _buildProgressButton(
          context,
          ref,
          progress: progress,
          text: '${_formatProgress(progress)} (paused)',
          fontSize: fontSize,
          onTap: () => _resumeDownload(ref),
        ),

        Verifying(:final progress) =>
          progress > 0
              ? _buildProgressButton(
                  context,
                  ref,
                  progress: progress,
                  text: 'Verifying ${(progress * 100).round()}%',
                  fontSize: fontSize,
                  onTap: null, // Cannot pause/cancel verification
                )
              : _buildSimpleButton(
                  context,
                  'Verifying...',
                  null,
                  fontSize: fontSize,
                  showSpinner: true,
                ),

        AwaitingPermission() => _buildSimpleButton(
          context,
          'Grant Permission',
          () => _requestPermission(ref),
          fontSize: fontSize,
          isWarning: true,
        ),

        ReadyToInstall() => _buildSimpleButton(
          context,
          'Queued for ${isInstalled ? 'update' : 'install'}',
          null, // Not tappable - system advances automatically
          fontSize: fontSize,
          showSpinner: true,
        ),

        Installing(:final isSilent) => _buildSimpleButton(
          context,
          isSilent
              ? (isInstalled ? 'Updating...' : 'Installing...')
              : (isInstalled ? 'Requesting update' : 'Requesting installation'),
          null, // Not tappable - auto-transitions to retry after 10s if no response
          fontSize: fontSize,
          showSpinner: true,
        ),

        InstallCancelled() => _buildSimpleButton(
          context,
          'Install (retry)',
          () => _retryInstall(ref),
          fontSize: fontSize,
          isWarning: true,
        ),

        SystemProcessing() => _buildSimpleButton(
          context,
          'System processing...',
          null,
          fontSize: fontSize,
          showSpinner: true,
        ),

        Uninstalling() => _buildSimpleButton(
          context,
          'Uninstalling...',
          null,
          fontSize: fontSize,
          showSpinner: true,
        ),

        OperationFailed(:final type, :final needsForceUpdate) =>
          _buildErrorButton(
            context,
            ref,
            type: type,
            needsForceUpdate: needsForceUpdate,
            fontSize: fontSize,
          ),

        Completed() => _buildAsyncButton(
          context,
          ref,
          text: 'Open',
          onPressed: () => _openApp(context, ref),
          fontSize: fontSize,
          needsTrustCheck: false,
        ),
      };
    }

    // No operation - show based on installed state
    if (isInstalled) {
      if (hasDowngrade) {
        return _buildSimpleButton(
          context,
          "Can't downgrade",
          null,
          fontSize: fontSize,
          isDisabled: true,
        );
      }

      if (hasUpdate) {
        return _buildAsyncButton(
          context,
          ref,
          text: 'Update',
          onPressed: hasRelease && fileMetadata != null
              ? () => _startDownload(context, ref, fileMetadata)
              : null,
          fontSize: fontSize,
        );
      }

      // Up to date
      return _buildAsyncButton(
        context,
        ref,
        text: 'Open',
        onPressed: () => _openApp(context, ref),
        fontSize: fontSize,
        needsTrustCheck: false,
      );
    }

    // Not installed
    return _buildAsyncButton(
      context,
      ref,
      text: 'Install',
      onPressed: hasRelease && fileMetadata != null
          ? () => _startDownload(context, ref, fileMetadata)
          : null,
      fontSize: fontSize,
      needsTrustCheck: true,
    );
  }

  String _formatProgress(double progress) {
    final percent = (progress * 100).round();
    final sizeMb = _formatTotalSizeMb();
    return sizeMb != null ? '$percent% of $sizeMb' : '$percent%';
  }

  String? _formatTotalSizeMb() {
    final sizeBytes = app.latestFileMetadata?.size;
    if (sizeBytes == null || sizeBytes <= 0) return null;
    final mb = sizeBytes / (1024 * 1024);
    return '${mb.toStringAsFixed(1)} MB';
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // BUTTON BUILDERS
  // ═══════════════════════════════════════════════════════════════════════════

  Widget _buildAsyncButton(
    BuildContext context,
    WidgetRef ref, {
    required String text,
    required Future<void> Function()? onPressed,
    required double fontSize,
    bool needsTrustCheck = false,
  }) {
    return AsyncButtonBuilder(
      onPressed: onPressed == null
          ? null
          : () async {
              if (needsTrustCheck) {
                final proceed = await _checkTrust(context, ref);
                if (!proceed) return;
              }
              await onPressed();
            },
      builder: (context, child, callback, buttonState) {
        return _buildSimpleButton(
          context,
          buttonState.maybeWhen(
            loading: () => text == 'Open' ? 'Launching...' : 'Starting...',
            orElse: () => text,
          ),
          buttonState.maybeWhen(loading: () => null, orElse: () => callback),
          fontSize: fontSize,
        );
      },
      child: Text(text),
      onError: () {
        if (context.mounted) {
          context.showError('Operation failed. Please try again.');
        }
      },
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

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Container(
          constraints: const BoxConstraints(minHeight: 38),
          decoration: BoxDecoration(
            color: actionColor,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Stack(
            children: [
              // Progress fill
              Positioned.fill(
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: FractionallySizedBox(
                    widthFactor: progress.clamp(0.0, 1.0),
                    heightFactor: 1.0,
                    child: Container(
                      decoration: BoxDecoration(
                        color: darkerAction,
                        borderRadius: BorderRadius.circular(12),
                      ),
                    ),
                  ),
                ),
              ),
              // Text
              Center(
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    vertical: 10,
                    horizontal: 16,
                  ),
                  child: Text(
                    text,
                    style: TextStyle(
                      fontSize: fontSize,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSimpleButton(
    BuildContext context,
    String text,
    VoidCallback? onPressed, {
    double fontSize = 16.0,
    bool showSpinner = false,
    bool isError = false,
    bool isWarning = false,
    bool isDisabled = false,
    IconData? icon,
  }) {
    final theme = Theme.of(context);

    Widget child = Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        if (icon != null) ...[Icon(icon, size: 18), const SizedBox(width: 6)],
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
          const SizedBox(
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

    Color backgroundColor;
    Color foregroundColor = Colors.white;

    if (isError) {
      backgroundColor = theme.colorScheme.error;
    } else if (isWarning) {
      backgroundColor = Colors.amber.shade700;
    } else if (isDisabled) {
      backgroundColor = theme.colorScheme.outline;
      foregroundColor = theme.colorScheme.onSurface.withValues(alpha: 0.5);
    } else {
      backgroundColor = AppColors.darkActionPrimary;
    }

    return FilledButton(
      onPressed: isDisabled ? null : onPressed,
      style: FilledButton.styleFrom(
        backgroundColor: backgroundColor,
        disabledBackgroundColor: backgroundColor,
        disabledForegroundColor: foregroundColor,
        foregroundColor: foregroundColor,
        padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 16),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
      child: child,
    );
  }

  Widget _buildErrorButton(
    BuildContext context,
    WidgetRef ref, {
    required FailureType type,
    required bool needsForceUpdate,
    required double fontSize,
  }) {
    if (needsForceUpdate) {
      return _buildSimpleButton(
        context,
        'Force update',
        () => _showForceUpdateDialog(ref, context),
        fontSize: fontSize,
        isError: true,
      );
    }

    return _buildSimpleButton(
      context,
      'Error (tap for details)',
      () => _handleErrorTap(ref, context),
      fontSize: fontSize,
      isError: true,
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

  // ═══════════════════════════════════════════════════════════════════════════
  // ACTIONS
  // ═══════════════════════════════════════════════════════════════════════════

  Future<bool> _checkTrust(BuildContext context, WidgetRef ref) async {
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
      if (!context.mounted) return false;
      final result = await showBaseDialog<({bool trustPermanently})>(
        context: context,
        dialog: InstallAlertDialog(app: app),
      );
      if (result == null) return false;
      if (result.trustPermanently && signerPubkey != null) {
        try {
          await ref.read(trustServiceProvider).addTrustedSigner(signerPubkey);
        } catch (_) {}
      }
    }
    return true;
  }

  Future<void> _startDownload(
    BuildContext context,
    WidgetRef ref,
    FileMetadata fileMetadata,
  ) async {
    final pm = ref.read(packageManagerProvider.notifier);
    await pm.startDownload(app.identifier, fileMetadata, displayName: app.name);
  }

  void _pauseDownload(WidgetRef ref) {
    final pm = ref.read(packageManagerProvider.notifier);
    pm.pauseDownload(app.identifier);
  }

  void _resumeDownload(WidgetRef ref) {
    final pm = ref.read(packageManagerProvider.notifier);
    pm.resumeDownload(app.identifier);
  }

  Future<void> _retryInstall(WidgetRef ref) async {
    final pm = ref.read(packageManagerProvider.notifier);
    await pm.retryInstall(app.identifier);
  }

  Future<void> _requestPermission(WidgetRef ref) async {
    final pm = ref.read(packageManagerProvider.notifier);

    // Check if permission is already granted (user may have granted it in settings)
    if (await pm.hasPermission()) {
      await pm.onPermissionGranted(app.identifier);
      return;
    }

    // Request permission (opens settings)
    await pm.requestPermission();

    // Check again after returning from settings
    if (await pm.hasPermission()) {
      await pm.onPermissionGranted(app.identifier);
    }
    // If still no permission, user will need to tap button again
  }

  Future<void> _openApp(BuildContext context, WidgetRef ref) async {
    try {
      final pm = ref.read(packageManagerProvider.notifier);
      await pm.launchApp(app.identifier);
    } catch (e) {
      if (!context.mounted) return;
      context.showError(
        'Failed to launch ${app.name ?? app.identifier}',
        description: 'The app may have been uninstalled or moved.\n\n$e',
      );
    }
  }

  Future<void> _uninstallApp(WidgetRef ref, BuildContext context) async {
    try {
      final pm = ref.read(packageManagerProvider.notifier);
      await pm.uninstall(app.identifier);
    } catch (e) {
      if (context.mounted) {
        final errorMessage = e.toString();
        if (!errorMessage.contains('cancelled')) {
          context.showError(
            'Uninstall failed. Please try again.',
            description: errorMessage,
          );
        }
      }
    }
  }

  void _handleErrorTap(WidgetRef ref, BuildContext context) {
    final operation = ref.read(installOperationProvider(app.identifier));
    if (operation is! OperationFailed) return;

    context.showError(
      operation.message,
      description: operation.description,
      actions: [
        (
          'Report issue',
          () async {
            final reporter = ref.read(errorReportingServiceProvider);
            final success = await reporter.reportUserError(
              title: operation.message,
              technicalDetails: operation.description,
            );
            if (context.mounted) {
              context.showInfo(
                success ? 'Report sent' : 'Failed to send report',
              );
            }
          },
        ),
      ],
    );

    // Always clear error after showing it (reckless mode removed).
    final pm = ref.read(packageManagerProvider.notifier);
    pm.dismissError(app.identifier);
  }

  void _showErrorToast(
    BuildContext context,
    WidgetRef ref,
    OperationFailed operation,
  ) {
    final reportAction = (
      'Report issue',
      () async {
        final reporter = ref.read(errorReportingServiceProvider);
        final success = await reporter.reportUserError(
          title: operation.message,
          technicalDetails: operation.description,
        );
        if (context.mounted) {
          context.showInfo(success ? 'Report sent' : 'Failed to send report');
        }
      },
    );

    if (operation.type == FailureType.certMismatch) {
      context.showError(
        'Update signed by different developer',
        description:
            'To install this update, you\'ll need to uninstall the current version first. This will remove app data.',
        actions: [reportAction],
      );
    } else if (operation.type == FailureType.incompatible) {
      context.showError(
        'Device incompatible',
        description:
            'This app is not compatible with your device. It may require a different architecture or Android version.',
        actions: [reportAction],
      );
    } else if (operation.description != null) {
      // Errors with descriptions (from Kotlin) are shown as toasts
      context.showError(
        operation.message,
        description: operation.description,
        actions: [reportAction],
      );
    }
    // Other errors are shown when user taps the error button
  }

  Future<void> _showForceUpdateDialog(
    WidgetRef ref,
    BuildContext context,
  ) async {
    final installedPkg = ref.read(installedPackageProvider(app.identifier));
    final operation = ref.read(installOperationProvider(app.identifier));
    if (operation is! OperationFailed) return;

    final updateVersion = operation.target.version;
    final currentVersion = installedPkg?.version ?? 'Unknown';
    final currentCertHash = installedPkg?.signatureHash ?? 'Unknown';
    final updateCertHash = operation.target.apkSignatureHash ?? 'Unknown';
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
              if (author != null)
                Row(
                  children: [
                    Expanded(
                      child: AuthorContainer(
                        profile: author,
                        beforeText: 'This update was published by',
                        afterText: ' but signed with a different certificate.',
                        oneLine: false,
                        size: 14,
                      ),
                    ),
                  ],
                ),
              const SizedBox(height: 12),
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
              const Text(
                'Android security prevents updating apps signed by different certificates. '
                'Contact the publisher for details.',
                style: TextStyle(fontSize: 14),
              ),
              const SizedBox(height: 8),
              const Text(
                'To proceed anyway, uninstall the current version and install the new one. '
                'ALL APP DATA WILL BE LOST.',
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
        final pm = ref.read(packageManagerProvider.notifier);
        await pm.forceUpdate(app.identifier);
      } catch (e) {
        final errorMessage = e.toString();
        if (context.mounted && !errorMessage.contains('cancelled')) {
          context.showError(
            'Update failed. Please try again.',
            description: errorMessage,
          );
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
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                TextSpan(
                  text: _abbr(hash),
                  style: const TextStyle(fontFamily: 'monospace'),
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
