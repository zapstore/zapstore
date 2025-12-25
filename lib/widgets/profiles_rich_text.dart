import 'package:flutter/material.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/common/profile_avatar.dart';

/// Generic widget for displaying a list of user profiles in a readable sentence format
/// Handles multiple users with proper grammar ("John", "John and Jane")
/// Features:
/// - Configurable maximum display count (no automatic "others" text)
/// - Customizable leading/trailing text
/// - Separator mode: either commas-only or comma + "and" before the last item
/// - Inline avatars with names
/// Usage: Social features like "Liked by John and Jane" or follower lists
class ProfilesRichText extends StatelessWidget {
  const ProfilesRichText({
    super.key,
    this.leadingText,
    this.trailingText,
    required this.profiles,
    this.maxProfilesToDisplay = 6,
    this.avatarRadius = 8,
    this.textStyle,
    this.nameStyle,
    this.maxLines,
    this.commasOnly = false,
  });

  /// Text to display before the profiles list
  final String? leadingText;

  /// Text to display after the profiles list
  final String? trailingText;

  /// List of profiles to display
  final List<Profile> profiles;

  /// Maximum number of profiles to display
  final int maxProfilesToDisplay;

  /// Radius for profile avatars
  final double avatarRadius;

  /// Style for the main text
  final TextStyle? textStyle;

  /// Style for profile names (defaults to bold version of textStyle)
  final TextStyle? nameStyle;

  /// Maximum lines for the text (null = unlimited, no ellipsis)
  final int? maxLines;

  /// When true, separates all names with commas only (", "), including the last two.
  /// When false (default), uses a comma between items and " and " before the last item.
  final bool commasOnly;

  @override
  Widget build(BuildContext context) {
    if (profiles.isEmpty && leadingText == null && trailingText == null) {
      return const SizedBox.shrink();
    }

    // If no profiles but we have leading/trailing text, show just the text
    if (profiles.isEmpty) {
      return Text(
        '${leadingText ?? ''}${trailingText ?? ''}',
        style:
            textStyle ??
            context.textTheme.bodyMedium?.copyWith(
              color: Theme.of(
                context,
              ).colorScheme.onSurface.withValues(alpha: 0.7),
              fontWeight: FontWeight.w400,
            ),
        maxLines: maxLines,
        overflow: maxLines != null
            ? TextOverflow.ellipsis
            : TextOverflow.visible,
      );
    }

    final usersToDisplay = profiles.take(maxProfilesToDisplay).toList();
    final remainingCount = profiles.length - usersToDisplay.length;

    final defaultTextStyle =
        textStyle ??
        context.textTheme.bodyMedium?.copyWith(
          color: Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.7),
          fontWeight: FontWeight.w400,
        );

    final defaultNameStyle =
        nameStyle ?? defaultTextStyle?.copyWith(fontWeight: FontWeight.w600);

    final spans = <InlineSpan>[];

    // Add leading text
    if (leadingText != null) {
      spans.add(TextSpan(text: leadingText!, style: defaultTextStyle));
    }

    // Add user spans
    for (int i = 0; i < usersToDisplay.length; i++) {
      final profile = usersToDisplay[i];

      // Avatar as inline widget aligned to text baseline
      spans.add(
        WidgetSpan(
          alignment: PlaceholderAlignment.middle,
          child: ProfileAvatar(profile: profile, radius: avatarRadius),
        ),
      );
      // Small gap between avatar and name
      spans.add(
        const WidgetSpan(
          alignment: PlaceholderAlignment.middle,
          child: SizedBox(width: 4),
        ),
      );
      // Name as TextSpan to preserve baseline for following text
      spans.add(TextSpan(text: profile.nameOrNpub, style: defaultNameStyle));

      // Add separators between displayed users
      if (i < usersToDisplay.length - 1) {
        final bool useAnd =
            !commasOnly &&
            (i == usersToDisplay.length - 2) &&
            (remainingCount == 0);
        spans.add(
          TextSpan(text: useAnd ? ' and ' : ', ', style: defaultTextStyle),
        );
      }
    }

    // No automatic "others" text; callers can provide desired trailing text

    // Add trailing text
    if (trailingText != null) {
      spans.add(TextSpan(text: trailingText!, style: defaultTextStyle));
    }

    return Text.rich(
      TextSpan(children: spans),
      maxLines: maxLines,
      overflow: maxLines != null ? TextOverflow.ellipsis : TextOverflow.visible,
    );
  }
}
