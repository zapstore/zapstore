import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/url_utils.dart';
import '../../theme.dart';

class ProfileAvatar extends StatelessWidget {
  final Profile? profile;
  final double radius;
  final List<Color>? borderColors;

  /// When provided, shows a signed-in placeholder (account_circle)
  /// instead of the generic person icon used for signed-out state.
  final String? pubkey;

  const ProfileAvatar({
    super.key,
    this.profile,
    this.radius = 24,
    this.borderColors,
    this.pubkey,
  });

  Widget _buildPlaceholder(BuildContext context) {
    // Use different icon for signed-in vs signed-out state
    final isSignedIn = pubkey != null;
    return Container(
      width: radius * 2,
      height: radius * 2,
      color: const Color(0xFF1A1A1A),
      child: Center(
        child: Icon(
          isSignedIn ? Icons.account_circle : Icons.person,
          color: isSignedIn
              ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.6)
              : Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.3),
          size: isSignedIn ? radius * 1.8 : radius * 0.9,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final pictureUrl = sanitizeHttpUrl(profile?.pictureUrl);

    Widget avatar = ClipOval(
      child: pictureUrl != null
          ? CachedNetworkImage(
              imageUrl: pictureUrl,
              fit: BoxFit.cover,
              width: radius * 2,
              height: radius * 2,
              fadeInDuration: const Duration(milliseconds: 500),
              fadeOutDuration: const Duration(milliseconds: 200),
              placeholder: (context, url) => _buildPlaceholder(context),
              errorWidget: (context, url, error) => _buildPlaceholder(context),
            )
          : _buildPlaceholder(context),
    );

    if (borderColors != null) {
      return Container(
        padding: const EdgeInsets.all(3),
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: LinearGradient(colors: borderColors!),
        ),
        child: Container(
          decoration: BoxDecoration(
            color: AppColors.darkSkeletonBase,
            shape: BoxShape.circle,
          ),
          child: avatar,
        ),
      );
    }

    return avatar;
  }
}
