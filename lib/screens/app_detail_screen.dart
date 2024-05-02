// ignore_for_file: prefer_const_literals_to_create_immutables

import 'dart:convert';

import 'package:auto_size_text/auto_size_text.dart';
import 'package:carousel_slider/carousel_slider.dart';
import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/card.dart';
import 'package:zapstore/widgets/pill_widget.dart';

class AppDetailScreen extends HookConsumerWidget {
  final App app;
  const AppDetailScreen({
    required this.app,
    super.key,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Column(
      children: [
        Expanded(
          child: CustomScrollView(
            slivers: [
              // SliverAppBar(
              //   pinned: true,
              //   leading: IconButton(
              //     icon: Icon(Icons.arrow_back),
              //     onPressed: () {
              //       context.pop();
              //     },
              //   ),
              // ),
              SliverList(
                delegate: SliverChildListDelegate([
                  Row(
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
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            AutoSizeText(
                              app.name,
                              minFontSize: 16,
                              style: TextStyle(
                                  fontSize: 28, fontWeight: FontWeight.bold),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            Gap(8),
                            PillWidget(text: '0.5.9', color: Colors.grey[800]),
                          ],
                        ),
                      ),
                    ],
                  ),
                  Gap(16),
                  CarouselSlider(
                    options: CarouselOptions(
                      enableInfiniteScroll: false,
                    ),
                    items: (app.tagMap['image'] ?? [])
                        .map((i) => Padding(
                              padding: const EdgeInsets.only(right: 10),
                              child: Image.network(i,
                                  fit: BoxFit.cover, width: 1000),
                            ))
                        .toList(),
                  ),
                  Divider(height: 24),
                  MarkdownBody(
                    styleSheet: MarkdownStyleSheet(
                      h1: TextStyle(fontWeight: FontWeight.bold),
                      h2: TextStyle(fontWeight: FontWeight.bold),
                      p: TextStyle(fontSize: 16, fontWeight: FontWeight.w300),
                    ),
                    selectable: false,
                    data: app.content,
                  ),
                  Gap(10),
                  if (app.developer.isPresent)
                    AuthorContainer(
                        user: app.developer.value!,
                        text: 'Built by',
                        oneLine: false),
                  Gap(10),
                  Divider(height: 24),
                  Container(
                    padding: EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: Colors.grey[900],
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Column(
                      children: [
                        Padding(
                          padding: const EdgeInsets.all(8),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              Text('Source'),
                              Text(
                                app.repository,
                                style: TextStyle(fontSize: 11),
                              )
                            ],
                          ),
                        ),
                        Padding(
                          padding: const EdgeInsets.all(8),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              Text('Github stars'),
                              Text(app.githubStars)
                            ],
                          ),
                        ),
                        Padding(
                          padding: const EdgeInsets.all(8),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              Text('Github forks'),
                              Text(app.githubForks)
                            ],
                          ),
                        )
                      ],
                    ),
                  )
                ]),
              ),
            ],
          ),
        ),
        Container(
          height: 50.0,
          padding: EdgeInsets.all(8),
          // color: Colors.blue,
          child: Center(
            child: Expanded(
              child: ElevatedButton(
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.blue,
                  foregroundColor: Colors.white,
                  minimumSize: const Size.fromHeight(50),
                ),
                onPressed: () {},
                child: const Text('Install'),
              ),
            ),
          ),
        ),
      ],
    );
  }
}
