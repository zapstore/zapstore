import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:zapstore/utils/extensions.dart';

/// Download text container widget showing "Direct download from (icon) (path)"
/// Mirrors the style and layout of `author_container.dart`
class DownloadTextContainer extends StatelessWidget {
  final String url;
  final String beforeText;
  final bool oneLine;
  final double? size;
  final VoidCallback? onTap;
  final bool showFullUrl;

  const DownloadTextContainer({
    super.key,
    required this.url,
    this.beforeText = 'Released at ',
    this.oneLine = true,
    this.size,
    this.onTap,
    this.showFullUrl = false,
  });

  @override
  Widget build(BuildContext context) {
    final Uri? uri = _tryParseUri(url);

    final iconWidget = _buildIcon(context, uri);
    final pathText = showFullUrl ? url : (_buildDisplayPath(uri) ?? url);

    final baseStyle = context.textTheme.bodyMedium?.copyWith(
      fontSize: size ?? context.textTheme.bodyMedium!.fontSize!,
      color: Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.7),
    );
    final boldStyle = baseStyle?.copyWith(fontWeight: FontWeight.w600);

    final richText = Text.rich(
      TextSpan(
        children: [
          TextSpan(text: beforeText, style: baseStyle),
          const TextSpan(text: ' '),
          if (iconWidget != null)
            WidgetSpan(
              alignment: PlaceholderAlignment.middle,
              child: Padding(
                padding: const EdgeInsets.only(right: 4),
                child: iconWidget,
              ),
            ),
          TextSpan(text: pathText, style: boldStyle),
        ],
      ),
      softWrap: !oneLine,
      overflow: oneLine ? TextOverflow.ellipsis : TextOverflow.visible,
      maxLines: oneLine ? 1 : null,
    );

    if (onTap != null) {
      return InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: Padding(padding: const EdgeInsets.all(4), child: richText),
      );
    }

    return richText;
  }

  Uri? _tryParseUri(String value) {
    try {
      return Uri.parse(value);
    } catch (_) {
      return null;
    }
  }

  /// Builds the icon widget according to domain rules, or attempts to fetch favicon.
  Widget? _buildIcon(BuildContext context, Uri? uri) {
    if (uri == null || uri.host.isEmpty) return null;
    final host = _normalizedHost(uri.host);
    final double effectiveFontSize =
        size ?? context.textTheme.bodySmall?.fontSize ?? 12;
    final double diameter =
        effectiveFontSize * 1.6; // Mirrors ProfileAvatar sizing

    // Known domains → asset icons
    if (host == 'github.com') {
      return _circleAsset(context, 'assets/images/github.png', diameter);
    }
    if (host == 'gitlab.com') {
      return _circleAsset(context, 'assets/images/gitlab.png', diameter);
    }
    if (host == 'fdroid.org' || host == 'f-droid.org') {
      return _circleAsset(context, 'assets/images/fdroid.png', diameter);
    }

    // Fallback → attempt to fetch favicon from site
    final scheme = (uri.scheme == 'http' || uri.scheme == 'https')
        ? uri.scheme
        : 'https';
    final faviconIcoUrl = '$scheme://${uri.host}/favicon.ico';
    final faviconPngUrl = '$scheme://${uri.host}/favicon.png';
    return CachedNetworkImage(
      imageUrl: faviconIcoUrl,
      fadeInDuration: const Duration(milliseconds: 250),
      fadeOutDuration: const Duration(milliseconds: 150),
      placeholder: (context, url) => const SizedBox.shrink(),
      errorWidget: (context, url, error) => CachedNetworkImage(
        imageUrl: faviconPngUrl,
        fadeInDuration: const Duration(milliseconds: 250),
        fadeOutDuration: const Duration(milliseconds: 150),
        placeholder: (context, url) => const SizedBox.shrink(),
        errorWidget: (context, url, error) => const SizedBox.shrink(),
        imageBuilder: (context, imageProvider) {
          return Container(
            width: diameter,
            height: diameter,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              image: DecorationImage(image: imageProvider, fit: BoxFit.cover),
            ),
          );
        },
      ),
      imageBuilder: (context, imageProvider) {
        return Container(
          width: diameter,
          height: diameter,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            image: DecorationImage(image: imageProvider, fit: BoxFit.cover),
          ),
        );
      },
    );
  }

  Widget _circleAsset(BuildContext context, String assetPath, double diameter) {
    return Container(
      width: diameter,
      height: diameter,
      decoration: BoxDecoration(shape: BoxShape.circle),
      clipBehavior: Clip.antiAlias,
      child: Image.asset(
        assetPath,
        width: diameter,
        height: diameter,
        fit: BoxFit.cover,
      ),
    );
  }

  /// Creates a display path according to domain-specific rules.
  String? _buildDisplayPath(Uri? uri) {
    if (uri == null) return null;
    final host = _normalizedHost(uri.host);
    final segments = uri.pathSegments.where((s) => s.isNotEmpty).toList();

    if (host == 'github.com' || host == 'gitlab.com') {
      if (segments.length >= 2) {
        return '${segments[0]}/${segments[1]}';
      }
      return host; // Fallback
    }

    if (host == 'fdroid.org' || host == 'f-droid.org') {
      // New style: Expecting /repo/<fileName>
      // Example: https://f-droid.org/repo/app.comaps.fdroid_25080802.apk
      final repoIndex = segments.indexOf('repo');
      if (repoIndex != -1 && repoIndex + 1 < segments.length) {
        final fileName = segments[repoIndex + 1];
        final appId = fileName.split('_').first; // before first underscore
        return appId;
      }

      // Legacy style: /<lang>/packages/<appId>
      final pkgIndex = segments.indexOf('packages');
      if (pkgIndex != -1 && pkgIndex + 1 < segments.length) {
        return segments[pkgIndex + 1];
      }

      // Fallback to last non-empty segment
      if (segments.isNotEmpty) return segments.last;
      return host;
    }

    // Default → just the domain
    return _normalizedHost(uri.host);
  }

  String _normalizedHost(String host) {
    final lower = host.toLowerCase();
    if (lower.startsWith('www.')) return lower.substring(4);
    return lower;
  }
}
