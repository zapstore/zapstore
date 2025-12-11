import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/url_utils.dart';
import '../../theme.dart';

class ProfileAvatar extends StatelessWidget {
  final Profile? profile;
  final double radius;
  final List<Color>? borderColors;

  const ProfileAvatar({
    super.key,
    this.profile,
    this.radius = 24,
    this.borderColors,
  });

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
              placeholder: (context, url) => const SizedBox.shrink(),
              errorWidget: (context, url, error) => Center(
                child: Icon(
                  Icons.person_outline,
                  color: AppColors.darkSkeletonHighlight,
                  size: radius * 1.3,
                ),
              ),
            )
          : SizedBox(
              width: radius * 2,
              height: radius * 2,
              child: Center(
                child: Icon(
                  Icons.person_outline,
                  color: AppColors.darkSkeletonHighlight,
                  size: radius * 1.3,
                ),
              ),
            ),
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
