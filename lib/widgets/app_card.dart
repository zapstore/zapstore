import 'package:auto_size_text/auto_size_text.dart';
import 'package:dart_emoji/dart_emoji.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:remove_markdown/remove_markdown.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/local_app.dart';
import 'package:zapstore/widgets/author_container.dart';
import 'package:zapstore/widgets/pill_widget.dart';
import 'package:zapstore/widgets/rounded_image.dart';

class AppCard extends HookConsumerWidget {
  final App? app;
  final bool showDate;

  const AppCard({super.key, required this.app, this.showDate = false});

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
                        child: Bone.multiText(lines: 4),
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
    return GestureDetector(
      onTap: () {
        context.go('/details', extra: app);
      },
      child: Card(
        margin: EdgeInsets.only(top: 6, bottom: 6),
        elevation: 0,
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
                    Gap(2),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Expanded(
                          child: AutoSizeText(
                            app!.name!,
                            minFontSize: 16,
                            style: TextStyle(
                                fontSize: 19, fontWeight: FontWeight.bold),
                            overflow: TextOverflow.ellipsis,
                            maxLines: 1,
                          ),
                        ),
                        if (app!.latestMetadata?.version != null)
                          PillWidget(
                            text: WidgetSpan(
                              alignment: PlaceholderAlignment.middle,
                              child: Wrap(
                                crossAxisAlignment: WrapCrossAlignment.center,
                                children: [
                                  Text(
                                    app!.latestMetadata!.version!,
                                    style: TextStyle(
                                        fontSize: 13,
                                        fontWeight: FontWeight.bold),
                                  ),
                                  if (app!.localApp.value?.status ==
                                      AppInstallStatus.updatable)
                                    Row(children: [
                                      Gap(5),
                                      Icon(Icons.update_outlined, size: 15)
                                    ]),
                                ],
                              ),
                            ),
                            size: 10,
                            color: app!.localApp.value?.status ==
                                    AppInstallStatus.updatable
                                ? Color.fromARGB(255, 101, 104, 89)
                                : Colors.grey[700]!,
                          ),
                      ],
                    ),
                    Gap(6),
                    Text(
                      app!.content.removeMarkdown().parseEmojis(),
                      style:
                          TextStyle(fontSize: 14, fontWeight: FontWeight.w300),
                      overflow: TextOverflow.ellipsis,
                      maxLines: 3,
                      softWrap: true,
                    ),
                    Gap(6),
                    if (app!.signer.isPresent)
                      AuthorContainer(
                        user: app!.signer.value!,
                        text: 'Signed by',
                        oneLine: true,
                        size: 12,
                      ),
                    if (showDate)
                      Text(app!.latestRelease!.createdAt!.toIso8601String()),
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
    return GestureDetector(
      onTap: () {
        context.go('/details', extra: app);
      },
      child: Card(
        margin: EdgeInsets.all(0),
        elevation: 0,
        child: Padding(
          padding: const EdgeInsets.all(9),
          child: app == null
              ? Skeletonizer.zone(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.start,
                    children: [
                      Bone.square(uniRadius: 12, size: 46),
                      Gap(8),
                      Bone.multiText(lines: 2, fontSize: 8),
                    ],
                  ),
                )
              : Column(
                  mainAxisAlignment: MainAxisAlignment.start,
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    RoundedImage(
                      url: app!.icons.firstOrNull,
                      size: 46,
                      radius: 12,
                    ),
                    Gap(8),
                    Expanded(
                      child: Center(
                        child: AutoSizeText(
                          app!.name!,
                          textAlign: TextAlign.center,
                          minFontSize: 10,
                          style: TextStyle(fontSize: 11),
                          overflow: TextOverflow.clip,
                          maxLines: 2,
                          wrapWords: false,
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
            child: PillWidget(text: TextSpan(text: tag)),
          ),
      ],
    );
  }
}

extension StringWidget on String {
  static final _emojiParser = EmojiParser();
  String parseEmojis() {
    return replaceAllMapped(RegExp(':([a-z]*):'), (m) {
      return _emojiParser.hasName(m[1]!) ? _emojiParser.get(m[1]!).code : m[0]!;
    });
  }
}
