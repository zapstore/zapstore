import 'package:flutter/material.dart';
import 'package:models/models.dart';
import 'common/profile_avatar.dart';
import '../utils/extensions.dart';

/// Author container widget showing "Published by [profile]" text with avatar
/// Based on the old AuthorContainer design pattern
/// Can be hidden for Zapstore-published apps (except main Zapstore app)
/// Supports nullable profile with pubkey fallback for cases where profile isn't loaded
class AuthorContainer extends StatelessWidget {
  final Profile? profile;
  final String? pubkey; // Fallback pubkey when profile is null
  final String? beforeText;
  final String? afterText;
  final bool oneLine;
  final double? size;
  final App? app; // Optional app to check for Zapstore hiding logic
  final VoidCallback? onTap; // Optional tap handler

  const AuthorContainer({
    super.key,
    this.profile,
    this.pubkey,
    this.beforeText,
    this.afterText,
    this.oneLine = true,
    this.size,
    this.app,
    this.onTap,
  }) : assert(profile != null || pubkey != null,
            'Either profile or pubkey must be provided');

  @override
  Widget build(BuildContext context) {
    // ignore: no_leading_underscores_for_local_identifiers
    final _size = size ?? context.textTheme.bodyMedium!.fontSize!;

    // Hide "Published by Zapstore" for Zapstore-published apps (except main Zapstore app)
    if (app != null && app!.isRelaySigned) {
      return const SizedBox.shrink();
    }

    final baseStyle = context.textTheme.bodyMedium?.copyWith(
      fontSize: _size,
      color: Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.7),
    );
    final boldStyle = baseStyle?.copyWith(fontWeight: FontWeight.w600);

    // Get display name: use profile.nameOrNpub if available, otherwise encode pubkey as npub
    final displayName = profile?.nameOrNpub ??
        (pubkey != null
            ? Utils.encodeShareableFromString(pubkey!, type: 'npub')
            : 'Unknown');

    final rowWidget = Text.rich(
      TextSpan(
        children: [
          if (beforeText != null)
            TextSpan(text: '$beforeText ', style: baseStyle),
          WidgetSpan(
            alignment: PlaceholderAlignment.middle,
            child: Padding(
              padding: const EdgeInsets.only(right: 6),
              child: SizedBox(
                width: _size * 1.4,
                height: _size * 1.4,
                child: ProfileAvatar(profile: profile, radius: _size * 0.7),
              ),
            ),
          ),
          TextSpan(text: displayName, style: boldStyle),
          if (afterText != null) TextSpan(text: afterText, style: baseStyle),
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
        child: Padding(padding: const EdgeInsets.all(4), child: rowWidget),
      );
    }

    return rowWidget;
  }
}
