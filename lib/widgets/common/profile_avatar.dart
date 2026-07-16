import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/image_url.dart';
import 'package:zapstore/utils/url_utils.dart';
import '../../theme.dart';

class ProfileAvatar extends StatefulWidget {
  final Profile? profile;
  final double radius;
  final List<Color>? borderColors;

  /// When provided, shows a signed-in placeholder (account_circle)
  /// instead of the generic person icon used for signed-out state.
  /// Also used for the CDN profile picture path when [profile] is null.
  final String? pubkey;

  const ProfileAvatar({
    super.key,
    this.profile,
    this.radius = 24,
    this.borderColors,
    this.pubkey,
  });

  @override
  State<ProfileAvatar> createState() => _ProfileAvatarState();
}

class _ProfileAvatarState extends State<ProfileAvatar> {
  /// When true, skip the CDN pubkey URL and use kind-0 picture instead.
  bool _useKind0Fallback = false;

  bool get _tiny => widget.radius < 24;

  String? get _resolvedPubkey => widget.profile?.pubkey ?? widget.pubkey;

  String? get _cdnUrl => getProfileCdnUrl(_resolvedPubkey, tiny: _tiny);

  String? get _kind0Url => getCdnImageUrl(
        sanitizeHttpUrl(widget.profile?.pictureUrl),
        _tiny ? CdnImageVariant.iconsm : CdnImageVariant.icon,
      );

  String? get _imageUrl {
    if (!_useKind0Fallback) {
      final cdn = _cdnUrl;
      if (cdn != null) return cdn;
    }
    return _kind0Url;
  }

  @override
  void didUpdateWidget(covariant ProfileAvatar oldWidget) {
    super.didUpdateWidget(oldWidget);
    final pubkeyChanged =
        (oldWidget.profile?.pubkey ?? oldWidget.pubkey) != _resolvedPubkey;
    final pictureChanged =
        oldWidget.profile?.pictureUrl != widget.profile?.pictureUrl;
    final radiusCrossedTiny =
        (oldWidget.radius < 24) != _tiny;
    if (pubkeyChanged || pictureChanged || radiusCrossedTiny) {
      _useKind0Fallback = false;
    }
  }

  Widget _buildPlaceholder(BuildContext context) {
    // Use different icon for signed-in vs signed-out state
    final isSignedIn = widget.pubkey != null || widget.profile != null;
    return Container(
      width: widget.radius * 2,
      height: widget.radius * 2,
      color: const Color(0xFF1A1A1A),
      child: Center(
        child: Icon(
          isSignedIn ? Icons.account_circle : Icons.person,
          color: isSignedIn
              ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.6)
              : Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.3),
          size: isSignedIn ? widget.radius * 1.8 : widget.radius * 0.9,
        ),
      ),
    );
  }

  void _onImageError(String failedUrl) {
    if (_useKind0Fallback) return;
    final kind0 = _kind0Url;
    if (kind0 == null || kind0 == failedUrl) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) setState(() => _useKind0Fallback = true);
    });
  }

  @override
  Widget build(BuildContext context) {
    final pictureUrl = _imageUrl;

    Widget avatar = ClipOval(
      child: pictureUrl != null
          ? CachedNetworkImage(
              key: ValueKey(pictureUrl),
              imageUrl: pictureUrl,
              fit: BoxFit.cover,
              width: widget.radius * 2,
              height: widget.radius * 2,
              fadeInDuration: const Duration(milliseconds: 500),
              fadeOutDuration: const Duration(milliseconds: 200),
              placeholder: (context, url) => _buildPlaceholder(context),
              errorWidget: (context, url, error) {
                _onImageError(url);
                return _buildPlaceholder(context);
              },
            )
          : _buildPlaceholder(context),
    );

    if (widget.borderColors != null) {
      return Container(
        padding: const EdgeInsets.all(3),
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: LinearGradient(colors: widget.borderColors!),
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
