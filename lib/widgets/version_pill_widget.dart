import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/extensions.dart';
import '../services/package_manager/package_manager.dart';
import '../theme.dart';

/// Version pill widget showing the current release version
/// Displays version in a colored pill format similar to the old design
/// Can also show dual versions (installed vs available) for updates
class VersionPillWidget extends HookConsumerWidget {
  final App app;
  final bool showUpdateArrow;
  final String? forceVersion;

  const VersionPillWidget({
    super.key,
    required this.app,
    this.showUpdateArrow = false,
    this.forceVersion,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Watch package manager state for reactivity
    ref.watch(
      packageManagerProvider.select((s) => s.installed[app.identifier]),
    );

    // Resolve installed and available versions using AppExt
    final installedVersion = app.installedPackage?.version;
    final availableVersion = app.latestFileMetadata?.version;
    final updateAvailable = app.hasUpdate;
    final downgradeAvailable = app.hasDowngrade;

    // Dual version mode (when an update OR downgrade is available)
    if (showUpdateArrow &&
        (updateAvailable || downgradeAvailable) &&
        installedVersion != null &&
        availableVersion != null) {
      return _buildDualVersionPills(
        context,
        installedVersion,
        availableVersion,
        isDowngrade: downgradeAvailable,
      );
    }

    // Single version mode: use forced version if provided, otherwise show installed version if installed, otherwise available version
    final version = forceVersion ?? installedVersion ?? availableVersion;

    if (version == null || version.isEmpty) {
      return const SizedBox.shrink();
    }

    return _buildVersionPill(
      context,
      version,
      AppColors.darkPillBackground,
      Colors.white,
    );
  }

  Widget _buildDualVersionPills(
    BuildContext context,
    String installedVersion,
    String availableVersion, {
    bool isDowngrade = false,
  }) {
    // Get version codes
    final installedVersionCode = app.installedPackage?.versionCode;
    final availableVersionCode = app.latestFileMetadata?.versionCode;

    // When version strings are equal but version codes differ, show version codes in parentheses
    final showVersionCodes =
        installedVersion == availableVersion &&
        installedVersionCode != null &&
        availableVersionCode != null &&
        installedVersionCode != availableVersionCode;

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        // Current version pill (muted colors for installed version)
        Flexible(
          child: _buildVersionPill(
            context,
            installedVersion,
            Theme.of(context).colorScheme.outline.withValues(alpha: 0.3),
            Theme.of(context).colorScheme.onSurface,
            isInstalledVersion: true,
            versionCode: showVersionCodes ? installedVersionCode : null,
          ),
        ),

        // Arrow icon
        Icon(
          Icons.arrow_right,
          size: 16,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),

        // Available version pill (highlighted for update, greyed for downgrade)
        Flexible(
          child: _buildVersionPill(
            context,
            availableVersion,
            isDowngrade
                ? Theme.of(context).colorScheme.outline.withValues(alpha: 0.3)
                : AppColors.darkPillBackground,
            isDowngrade
                ? Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.5)
                : Colors.white,
            isDowngrade: isDowngrade,
            versionCode: showVersionCodes ? availableVersionCode : null,
          ),
        ),
      ],
    );
  }

  Widget _buildVersionPill(
    BuildContext context,
    String version,
    Color backgroundColor,
    Color textColor, {
    bool isInstalledVersion = false,
    bool isDowngrade = false,
    int? versionCode,
  }) {
    // Build display version with optional version code in parentheses
    String displayVersion = _displayVersion(version, versionCode: versionCode);

    // Determine app status for icon
    Widget? statusIcon;
    if (!isInstalledVersion) {
      if (isDowngrade) {
        // Downgrade forbidden
        statusIcon = Icon(Icons.block, size: 12, color: textColor);
      } else if (!app.isInstalled) {
        // Can install
        statusIcon = const Icon(
          Icons.download_rounded,
          size: 12,
          color: Colors.white,
        );
      } else {
        if (app.hasUpdate) {
          // Can update
          statusIcon = const Icon(
            Icons.update_outlined,
            size: 12,
            color: Colors.white,
          );
        } else {
          // Is updated
          statusIcon = const Icon(Icons.check, size: 12, color: Colors.white);
        }
      }
    }

    final double verticalPadding = isInstalledVersion ? 5.0 * 1.05 : 5.0;

    return Container(
      padding: EdgeInsets.symmetric(horizontal: 9, vertical: verticalPadding),
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Flexible(
            child: Text(
              displayVersion,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: context.textTheme.labelSmall?.copyWith(
                fontWeight: FontWeight.bold,
                color: textColor,
              ),
            ),
          ),
          if (statusIcon != null) ...[
            const SizedBox(width: 4),
            IconTheme.merge(
              data: const IconThemeData(size: 12),
              child: statusIcon,
            ),
          ],
        ],
      ),
    );
  }

  String _displayVersion(String version, {int? versionCode}) {
    if (versionCode != null) {
      return '$version ($versionCode)';
    }
    return version;
  }
}
