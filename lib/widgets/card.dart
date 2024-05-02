import 'dart:convert';

import 'package:auto_size_text/auto_size_text.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/pill_widget.dart';

class CardWidget extends HookConsumerWidget {
  final App app;

  const CardWidget({super.key, required this.app});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Card(
      margin: EdgeInsets.only(top: 20),
      elevation: 0,
      child: GestureDetector(
        onTap: () => context.go('/details', extra: app),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.start,
            children: [
              CircularImage(
                url: app.icon,
                size: 80,
                radius: 25,
              ),
              Gap(16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    AutoSizeText(
                      app.releases.safeFirst?.identifier.split('@').last ??
                          'none',
                      minFontSize: 16,
                      style:
                          TextStyle(fontSize: 23, fontWeight: FontWeight.bold),
                      overflow: TextOverflow.ellipsis,
                      maxLines: 1,
                    ),
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Text(
                        app.content,
                        style: TextStyle(
                            fontSize: 14, fontWeight: FontWeight.w300),
                        overflow: TextOverflow.ellipsis,
                        maxLines: 2,
                        softWrap: true,
                      ),
                    ),
                    Gap(10),
                    if (app.developer.isPresent)
                      AuthorContainer(
                        user: app.developer.value!,
                        text: 'Built by',
                      ),
                    if (app.signer.isPresent)
                      AuthorContainer(user: app.signer.value!),
                    // Gap(16),
                    // TagsContainer(
                    //   tags: app.tagMap['t'] ?? [],
                    // ),
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

class AuthorContainer extends StatelessWidget {
  final User user;
  final String text;
  final bool oneLine;
  const AuthorContainer({
    super.key,
    required this.user,
    this.text = 'Signed by',
    this.oneLine = true,
  });

  String get name => user.name ?? user.npub;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(top: 4, bottom: 4),
      child: Row(
        children: [
          CircularImage(url: user.avatarUrl, size: oneLine ? 22 : 46),
          Gap(10),
          if (oneLine) Text('$text $name'),
          if (!oneLine)
            Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(text),
                Padding(
                  padding: const EdgeInsets.only(left: 1),
                  child:
                      Text(name, style: TextStyle(fontWeight: FontWeight.bold)),
                ),
              ],
            )
        ],
      ),
    );
  }
}

class CircularImage extends StatelessWidget {
  const CircularImage({
    super.key,
    this.url,
    this.size = 22,
    this.radius = 60,
  });

  final String? url;
  final int size;
  final int radius;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius.toDouble()),
      child: CachedNetworkImage(
        imageUrl: url ?? '',
        fit: BoxFit.cover,
        width: size.toDouble(),
        height: size.toDouble(),
      ),
    );
  }
}
