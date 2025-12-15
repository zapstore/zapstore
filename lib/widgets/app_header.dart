import 'package:auto_size_text/auto_size_text.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:models/models.dart';
import 'package:zapstore/widgets/version_pill_widget.dart';
import 'package:zapstore/utils/url_utils.dart';

class AppHeader extends StatelessWidget {
  const AppHeader({super.key, required this.app});

  final App app;

  @override
  Widget build(BuildContext context) {
    final iconUrl = firstValidHttpUrl(app.icons);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 80,
              height: 80,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(16),
              ),
              child: iconUrl != null
                  ? ClipRRect(
                      borderRadius: BorderRadius.circular(16),
                      child: CachedNetworkImage(
                        imageUrl: iconUrl,
                        fit: BoxFit.cover,
                        fadeInDuration: const Duration(milliseconds: 500),
                        fadeOutDuration: const Duration(milliseconds: 200),
                        errorWidget: (context, url, error) => Center(
                          child: Icon(
                            Icons.broken_image_outlined,
                            size: 32,
                            color: Colors.grey[400],
                          ),
                        ),
                        placeholder: (context, url) => const SizedBox.shrink(),
                      ),
                    )
                  : Center(
                      child: Icon(
                        Icons.apps_outlined,
                        size: 32,
                        color: Colors.grey[400],
                      ),
                    ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  AutoSizeText(
                    app.name ?? app.identifier,
                    style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                      fontWeight: FontWeight.w900,
                      letterSpacing: 0.1,
                      fontSize:
                          (Theme.of(
                                context,
                              ).textTheme.headlineSmall?.fontSize ??
                              24) *
                          1.08,
                    ),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    minFontSize: 16,
                  ),
                  const SizedBox(height: 10),
                  VersionPillWidget(app: app, showUpdateArrow: true),
                ],
              ),
            ),
          ],
        ),
        Gap(16),
      ],
    );
  }
}
