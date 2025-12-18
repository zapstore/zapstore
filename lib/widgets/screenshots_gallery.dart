import 'package:cached_network_image/cached_network_image.dart';
import 'package:easy_image_viewer/easy_image_viewer.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/url_utils.dart';
// Local shimmer duplicated to avoid private import

class ScreenshotsGallery extends HookWidget {
  const ScreenshotsGallery({super.key, required this.app});

  final App app;

  @override
  Widget build(BuildContext context) {
    final imageUrls = filterValidHttpUrls(app.images);
    if (imageUrls.isEmpty) return const SizedBox.shrink();

    final scrollController = useScrollController();
    useListenable(scrollController);

    return SizedBox(
      height: 200,
      child: ListView.builder(
        controller: scrollController,
        padding: EdgeInsets.zero,
        scrollDirection: Axis.horizontal,
        itemCount: imageUrls.length,
        itemBuilder: (context, index) {
          final imageUrl = imageUrls[index];
          return GestureDetector(
            onTap: () => _showImageViewer(context, imageUrls, index),
            child: Container(
              width: 120,
              margin: EdgeInsets.only(left: index == 0 ? 16 : 0, right: 6),
              decoration: BoxDecoration(borderRadius: BorderRadius.circular(8)),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: CachedNetworkImage(
                  imageUrl: imageUrl,
                  fit: BoxFit.cover,
                  fadeInDuration: const Duration(milliseconds: 300),
                  fadeOutDuration: const Duration(milliseconds: 150),
                  errorWidget: (context, url, error) => Center(
                    child: Icon(
                      Icons.broken_image_outlined,
                      size: 24,
                      color: Colors.grey[400],
                    ),
                  ),
                  placeholder: (context, url) => _buildGradientLoader(context),
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildGradientLoader(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF2A2A2A), Color(0xFF3A3A3A), Color(0xFF2A2A2A)],
          stops: [0.0, 0.5, 1.0],
        ),
      ),
      child: _LocalShimmerEffect(context: context),
    );
  }

  void _showImageViewer(
    BuildContext context,
    List<String> imageUrls,
    int initialIndex,
  ) {
    final imageProviders = imageUrls
        .map((url) => CachedNetworkImageProvider(url) as ImageProvider)
        .toList();

    showImageViewerPager(
      context,
      MultiImageProvider(imageProviders, initialIndex: initialIndex),
      onPageChanged: (page) {},
      onViewerDismissed: (page) {},
      swipeDismissible: false,
      doubleTapZoomable: true,
      immersive: false,
      useSafeArea: true,
    );
  }
}

class _LocalShimmerEffect extends HookWidget {
  final BuildContext context;
  const _LocalShimmerEffect({required this.context});

  @override
  Widget build(BuildContext context) {
    final controller = useAnimationController(
      duration: const Duration(milliseconds: 1500),
    )..repeat();
    final animation = useMemoized(
      () => Tween<double>(
        begin: -1.0,
        end: 2.0,
      ).animate(CurvedAnimation(parent: controller, curve: Curves.easeInOut)),
      [controller],
    );

    const shimmerColors = [
      Color(0xFF2A2A2A),
      Color(0xFF3A3A3A),
      Color(0xFF2A2A2A),
    ];

    return AnimatedBuilder(
      animation: animation,
      builder: (context, child) {
        return Container(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: shimmerColors,
              stops: [
                (animation.value - 0.3).clamp(0.0, 1.0),
                animation.value.clamp(0.0, 1.0),
                (animation.value + 0.3).clamp(0.0, 1.0),
              ],
            ),
          ),
        );
      },
    );
  }
}
