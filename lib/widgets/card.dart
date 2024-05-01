import 'dart:convert';

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
    final user = User.fromMap({
      'kind': 0,
      'created_at': 112899202,
      'pubkey':
          'a9e95a4eb32b55441b222ae5674f063949bfd0759b82deb03d7cd262e82d5626',
      'content': jsonEncode(
          {'name': 'test', 'picture': 'https://picsum.photos/200/300'}),
      'tags': [],
    });

    return Card(
      margin: EdgeInsets.only(top: 20),
      child: GestureDetector(
        onTap: () => context.go('/details', extra: app),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.start,
            children: [
              CircularImage(
                url: 'https://picsum.photos/200/200',
                size: 80,
                radius: 25,
              ),
              Gap(16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Mutiny Wallet - Mutiny Wallet - Muti',
                      // '${app.name} - ${app.name}',
                      style:
                          TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                      overflow: TextOverflow.ellipsis,
                      maxLines: 1,
                      softWrap: true,
                    ),
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Text(
                        'Best web-based Lightning and Ecash wallet web-based Lightning and Ecash wallet',
                        style: TextStyle(
                            fontSize: 14, fontWeight: FontWeight.w300),
                        overflow: TextOverflow.ellipsis,
                        maxLines: 2,
                        softWrap: true,
                      ),
                    ),
                    Gap(10),
                    AuthorContainer(user: user),
                    AuthorContainer(user: user),
                    Gap(16),
                    TagsContainer(),
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
  const TagsContainer({
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: const [
        PillWidget(text: 'nostr'),
        Gap(6),
        PillWidget(text: 'wallet'),
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
      child: Container(
        width: size.toDouble(),
        height: size.toDouble(),
        color: Colors.grey[850],
        child: CachedNetworkImage(
          imageUrl: url ?? '',
          fit: BoxFit.cover,
        ),
      ),
    );
  }
}
