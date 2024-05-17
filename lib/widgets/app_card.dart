import 'package:auto_size_text/auto_size_text.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/widgets/pill_widget.dart';
import 'package:zapstore/widgets/rounded_image.dart';

class AppCard extends HookConsumerWidget {
  final App? app;

  const AppCard({super.key, required this.app});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (app == null) {
      return Skeletonizer.zone(
        child: Card(
          margin: EdgeInsets.only(top: 6, bottom: 6),
          elevation: 0,
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.start,
              children: [
                Column(
                  children: [
                    Bone.square(uniRadius: 10, size: 70),
                  ],
                ),
                Gap(16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Bone.text(fontSize: 20),
                      Padding(
                        padding: const EdgeInsets.only(top: 4),
                        child: Bone.multiText(lines: 2),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    }
    return Card(
      margin: EdgeInsets.only(top: 6, bottom: 6),
      elevation: 0,
      child: GestureDetector(
        onTap: () {
          context.go('/details', extra: app);
        },
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.start,
            children: [
              RoundedImage(
                url: app!.icons.firstOrNull,
                size: 60,
                radius: 15,
              ),
              Gap(16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Expanded(
                          child: AutoSizeText(
                            app!.name!,
                            minFontSize: 16,
                            style: TextStyle(
                                fontSize: 20, fontWeight: FontWeight.bold),
                            overflow: TextOverflow.ellipsis,
                            maxLines: 1,
                          ),
                        ),
                        if (app!.latestMetadata?.version != null)
                          PillWidget(
                            text: app!.latestMetadata!.version!,
                            size: 10,
                            color: Colors.grey[800]!,
                          ),
                      ],
                    ),
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Text(
                        // TODO fix markdown?
                        app!.content,
                        style: TextStyle(
                            fontSize: 14, fontWeight: FontWeight.w300),
                        overflow: TextOverflow.ellipsis,
                        maxLines: 1,
                        softWrap: true,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class TinyAppCard extends HookConsumerWidget {
  final App? app;

  const TinyAppCard({super.key, required this.app});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Card(
      elevation: 0,
      child: GestureDetector(
        onTap: () {
          context.go('/details', extra: app);
        },
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: app == null
              ? Skeletonizer.zone(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.start,
                    children: [
                      Bone.square(uniRadius: 10, size: 58),
                      Gap(8),
                      Bone.text(),
                    ],
                  ),
                )
              : Column(
                  mainAxisAlignment: MainAxisAlignment.start,
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    RoundedImage(
                      url: app!.icons.firstOrNull,
                      size: 60,
                      radius: 12,
                    ),
                    Gap(8),
                    Expanded(
                      child: Center(
                        child: AutoSizeText(
                          app!.name!,
                          textAlign: TextAlign.center,
                          minFontSize: 10,
                          style: TextStyle(
                              fontSize: 13, fontWeight: FontWeight.bold),
                          overflow: TextOverflow.clip,
                          maxLines: 1,
                        ),
                      ),
                    ),
                  ],
                ),
        ),
      ),
    );
  }
}

class TagsContainer extends StatelessWidget {
  final List<String> tags;
  const TagsContainer({
    super.key,
    required this.tags,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        for (final tag in tags)
          Padding(
            padding: const EdgeInsets.only(right: 6),
            child: PillWidget(text: tag),
          ),
      ],
    );
  }
}
