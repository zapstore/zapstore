// ignore_for_file: prefer_const_literals_to_create_immutables

import 'dart:convert';

import 'package:auto_size_text/auto_size_text.dart';
import 'package:carousel_slider/carousel_slider.dart';
import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/card.dart';
import 'package:zapstore/widgets/pill_widget.dart';

final List<String> imgList = [
  'https://images.unsplash.com/photo-1520342868574-5fa3804e551c?ixlib=rb-0.3.5&ixid=eyJhcHBfaWQiOjEyMDd9&s=6ff92caffcdd63681a35134a6770ed3b&auto=format&fit=crop&w=1951&q=80',
  'https://images.unsplash.com/photo-1522205408450-add114ad53fe?ixlib=rb-0.3.5&ixid=eyJhcHBfaWQiOjEyMDd9&s=368f45b0888aeb0b7b08e3a1084d3ede&auto=format&fit=crop&w=1950&q=80',
  'https://images.unsplash.com/photo-1519125323398-675f0ddb6308?ixlib=rb-0.3.5&ixid=eyJhcHBfaWQiOjEyMDd9&s=94a1e718d89ca60a6337a6008341ca50&auto=format&fit=crop&w=1950&q=80',
  'https://images.unsplash.com/photo-1523205771623-e0faa4d2813d?ixlib=rb-0.3.5&ixid=eyJhcHBfaWQiOjEyMDd9&s=89719a0d55dd05e2deae4120227e6efc&auto=format&fit=crop&w=1953&q=80',
  'https://images.unsplash.com/photo-1508704019882-f9cf40e475b4?ixlib=rb-0.3.5&ixid=eyJhcHBfaWQiOjEyMDd9&s=8c6e5e3aba713b17aa1fe71ab4f0ae5b&auto=format&fit=crop&w=1352&q=80',
  'https://images.unsplash.com/photo-1519985176271-adb1088fa94c?ixlib=rb-0.3.5&ixid=eyJhcHBfaWQiOjEyMDd9&s=a0c8d632e977f94e5d312d9893258f59&auto=format&fit=crop&w=1355&q=80'
];

class AppDetailScreen extends HookConsumerWidget {
  final App app;
  const AppDetailScreen({
    required this.app,
    super.key,
  });

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
                        url: 'https://picsum.photos/200/200',
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
                              'Mutiny Wallet',
                              minFontSize: 16,
                              style: TextStyle(
                                  fontSize: 28, fontWeight: FontWeight.bold),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            Gap(8),
                            PillWidget(text: '0.5.6'),
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
                    items: imgList
                        .map((item) => Padding(
                              padding: const EdgeInsets.only(right: 10),
                              child: Image.network(item,
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
                    data:
                        'Lorem ipsum dolor sit amet\n - consectetur adipisicing elit\n - sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, ',
                  ),
                  Gap(10),
                  AuthorContainer(user: user, text: 'Built by', oneLine: false),
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
                            children: [Text('Source'), Text('github link')],
                          ),
                        ),
                        Padding(
                          padding: const EdgeInsets.all(8),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [Text('Github stars'), Text('456')],
                          ),
                        ),
                        Padding(
                          padding: const EdgeInsets.all(8),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [Text('Github forks'), Text('3')],
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
