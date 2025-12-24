import 'package:auto_size_text/auto_size_text.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:models/models.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/download_text_container.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/theme.dart';

class AppInfoTable extends HookConsumerWidget {
  const AppInfoTable({super.key, required this.app, this.fileMetadata});

  final App app;
  final FileMetadata? fileMetadata;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface.withValues(alpha: 0.6),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: Theme.of(context).colorScheme.outline.withValues(alpha: 0.2),
          width: 1,
        ),
      ),
      child: Column(children: _buildInfoRows(context, ref)),
    );
  }

  List<Widget> _buildInfoRows(BuildContext context, WidgetRef ref) {
    final rows = <Widget>[];

    rows.add(
      _InfoRow(
        label: 'Source',
        value: app.repository ?? 'Not available',
        valueWidget: app.repository != null
            ? Flexible(
                child: GestureDetector(
                  onTap: () => launchUrl(Uri.parse(app.repository!)),
                  child: DownloadTextContainer(
                    url: app.repository!,
                    beforeText: '',
                    oneLine: true,
                    showFullUrl: false,
                    size: context.textTheme.bodyMedium?.fontSize,
                  ),
                ),
              )
            : Flexible(
                child: Text(
                  'Not available',
                  style: context.textTheme.bodyMedium?.copyWith(
                    color: Colors.red[300],
                    fontWeight: FontWeight.bold,
                  ),
                  textAlign: TextAlign.right,
                ),
              ),
      ),
    );

    if (app.license != null) {
      final licenseId = app.license!;
      final licenseName = licenseId == 'NOASSERTION'
          ? 'N/A'
          : (_spdxIdToName[licenseId] ?? licenseId);
      rows.add(_InfoRow(label: 'License', value: licenseName));
    }

    rows.add(_InfoRow(label: 'App ID', value: app.identifier));

    if (fileMetadata?.hash != null) {
      final full = fileMetadata!.hash;
      rows.add(
        _InfoRow(label: 'File hash', value: full.abbreviate(), copyValue: full),
      );
    }

    if (fileMetadata?.certificateHash != null) {
      final full = fileMetadata!.certificateHash!;
      rows.add(
        _InfoRow(
          label: 'Certificate hash',
          value: full.abbreviate(),
          copyValue: full,
        ),
      );
    }

    if (fileMetadata?.versionCode != null) {
      // Get installed package info for comparison
      final installedPackage = app.installedPackage;
      final installedVersionCode = installedPackage?.versionCode;
      final availableVersionCode = fileMetadata!.versionCode!;

      rows.add(
        _InfoRow(
          label: 'Version code',
          value: availableVersionCode.toString(),
          valueWidget: _buildVersionCodePills(
            context,
            ref,
            installedVersionCode,
            availableVersionCode,
          ),
        ),
      );
    }

    // Add release date as the last row
    final release = app.latestRelease.value;
    if (release?.createdAt != null) {
      rows.add(
        _InfoRow(
          label: 'Release date',
          value: DateFormat('MMM d, y').format(release!.createdAt),
        ),
      );
    }

    return rows;
  }

  Widget _buildVersionCodePills(
    BuildContext context,
    WidgetRef ref,
    int? installedVersionCode,
    int availableVersionCode,
  ) {
    // If no installed version or same version code, show single pill
    if (installedVersionCode == null ||
        installedVersionCode == availableVersionCode) {
      return Flexible(
        child: _buildVersionCodePill(
          context,
          availableVersionCode.toString(),
          AppColors.darkPillBackground,
          Colors.white,
        ),
      );
    }

    // Show dual pills for upgrade/downgrade
    final isDowngrade = availableVersionCode < installedVersionCode;

    return Flexible(
      child: Row(
        mainAxisAlignment: MainAxisAlignment.end,
        mainAxisSize: MainAxisSize.min,
        children: [
          // Installed version pill (muted)
          _buildVersionCodePill(
            context,
            installedVersionCode.toString(),
            Theme.of(context).colorScheme.outline.withValues(alpha: 0.3),
            Theme.of(context).colorScheme.onSurface,
          ),

          // Arrow
          Icon(
            Icons.arrow_right,
            size: 16,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),

          // Available version pill (highlighted for update, muted for downgrade)
          _buildVersionCodePill(
            context,
            availableVersionCode.toString(),
            isDowngrade
                ? Theme.of(context).colorScheme.outline.withValues(alpha: 0.3)
                : AppColors.darkPillBackground,
            isDowngrade
                ? Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.5)
                : Colors.white,
          ),
        ],
      ),
    );
  }

  Widget _buildVersionCodePill(
    BuildContext context,
    String versionCode,
    Color backgroundColor,
    Color textColor,
  ) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        versionCode,
        style: context.textTheme.labelSmall?.copyWith(
          fontWeight: FontWeight.bold,
          color: textColor,
        ),
      ),
    );
  }
}

// SPDX id -> human friendly name (compact)
const Map<String, String> _spdxIdToName = {
  '0BSD': 'BSD Zero Clause',
  'Apache-2.0': 'Apache 2.0',
  'MIT': 'MIT',
  'BSD-2-Clause': 'BSD 2-Clause "Simplified" License',
  'BSD-3-Clause': 'BSD 3-Clause "New" or "Revised" License',
  'GPL-2.0-only': 'GNU GPL v2.0 only',
  'GPL-2.0-or-later': 'GNU GPL v2.0 or later',
  'GPL-3.0-only': 'GNU GPL v3.0 only',
  'GPL-3.0-or-later': 'GNU GPL v3.0 or later',
  'LGPL-2.1-only': 'GNU LGPL v2.1 only',
  'LGPL-2.1-or-later': 'GNU LGPL v2.1 or later',
  'LGPL-3.0-only': 'GNU LGPL v3.0 only',
  'LGPL-3.0-or-later': 'GNU LGPL v3.0 or later',
  'AGPL-3.0-only': 'GNU AGPL v3.0 only',
  'AGPL-3.0-or-later': 'GNU AGPL v3.0 or later',
  'MPL-2.0': 'Mozilla Public License 2.0',
  'Unlicense': 'The Unlicense',
  'CC0-1.0': 'CC0 1.0',
  'CDDL-1.0': 'CDDL 1.0',
  'EUPL-1.2': 'EUPL 1.2',
  'BSL-1.0': 'Boost 1.0',
  'ISC': 'ISC',
  'Zlib': 'zlib',
  'Artistic-2.0': 'Artistic 2.0',
  'NCSA': 'NCSA',
  'WTFPL': 'WTFPL',
};

class _InfoRow extends StatelessWidget {
  const _InfoRow({
    required this.label,
    required this.value,
    this.copyValue,
    this.valueWidget,
  });
  final String label;
  final String value;
  final String? copyValue;
  final Widget? valueWidget;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Text(
            label,
            style: Theme.of(
              context,
            ).textTheme.bodyMedium?.copyWith(color: Colors.grey[600]),
            maxLines: 1,
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Row(
              mainAxisAlignment: MainAxisAlignment.end,
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                valueWidget != null
                    ? valueWidget!
                    : Flexible(
                        child: AutoSizeText(
                          value,
                          style: context.textTheme.bodyMedium,
                          maxLines: 1,
                          textAlign: TextAlign.right,
                          minFontSize: 12,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                if (copyValue != null) ...[
                  const SizedBox(width: 6),
                  IconButton(
                    icon: const Icon(Icons.copy, size: 16),
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints(),
                    style: IconButton.styleFrom(
                      tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                      padding: EdgeInsets.zero,
                      minimumSize: Size.zero,
                    ),
                    onPressed: () {
                      Clipboard.setData(ClipboardData(text: copyValue!));
                      context.showInfo('Copied to clipboard');
                    },
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}
