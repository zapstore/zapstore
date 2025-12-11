import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';

/// A rounded image widget with fallback to person icon
/// Used for user avatars and app icons with consistent styling
class RoundedImage extends StatelessWidget {
  const RoundedImage({
    super.key,
    this.url,
    this.size = 22,
    this.radius = 60,
  });

  final String? url;
  final double size;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final fallbackContainer = Container(
      height: size,
      width: size,
      color: Colors.grey[800],
      child: Icon(
        Icons.person,
        color: Colors.blueGrey,
        size: size * 0.7,
      ),
    );
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius.toDouble()),
      child: url == null
          ? fallbackContainer
          : (url!.endsWith('svg') || url!.endsWith('xml')
              ? fallbackContainer
              : CachedNetworkImage(
                  imageUrl: url!,
                  errorWidget: (_, __, ___) => fallbackContainer,
                  useOldImageOnUrlChange: false,
                  fit: BoxFit.cover,
                  width: size,
                  height: size,
                )),
    );
  }
}
