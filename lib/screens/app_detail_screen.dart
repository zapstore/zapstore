import 'dart:async';

import 'package:async_button_builder/async_button_builder.dart';
import 'package:auto_size_text/auto_size_text.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/screens/search_screen.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/card.dart';
import 'package:zapstore/widgets/pill_widget.dart';

class AppDetailScreen extends HookConsumerWidget {
  final App app;
  AppDetailScreen({
    required this.app,
    super.key,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scrollController = ScrollController();

    useFuture(useMemoized(() async {
      final releases = await ref.releases.findAll(params: {'#a': app.aTag});
      final metadataIds = releases.map((r) => r.tagMap['e']!).expand((_) => _);
      await ref.fileMetadata.findAll(params: {
        'ids': metadataIds,
        '#m': [kAndroidMimeType]
      });
    }));

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
                delegate: SliverChildListDelegate(
                  [
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.start,
                      children: [
                        CircularImage(
                          url: app.icons.first,
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
                                app.name!,
                                minFontSize: 16,
                                style: TextStyle(
                                    fontSize: 28, fontWeight: FontWeight.bold),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                              Gap(8),
                              if (app.releases.isNotEmpty)
                                PillWidget(
                                    text: app.releases.first.version,
                                    color: Colors.grey[800]),
                            ],
                          ),
                        ),
                      ],
                    ),
                    Gap(16),
                    if (app.images.isNotEmpty)
                      Scrollbar(
                        controller: scrollController,
                        interactive: true,
                        trackVisibility: true,
                        child: SingleChildScrollView(
                          controller: scrollController,
                          scrollDirection: Axis.horizontal,
                          child: SizedBox(
                            height: 320,
                            child: Row(
                              children: [
                                for (final i in app.images)
                                  Padding(
                                    padding: const EdgeInsets.only(right: 12),
                                    child: CachedNetworkImage(imageUrl: i),
                                  ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    Divider(height: 24),
                    MarkdownBody(
                      styleSheet: MarkdownStyleSheet(
                        h1: TextStyle(fontWeight: FontWeight.bold),
                        h2: TextStyle(fontWeight: FontWeight.bold),
                        p: TextStyle(fontSize: 18, fontWeight: FontWeight.w300),
                      ),
                      selectable: false,
                      data: app.content,
                    ),
                    Gap(10),
                    Padding(
                      padding: const EdgeInsets.only(right: 14),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          if (app.signer.isPresent)
                            GestureDetector(
                              onTap: () async {
                                final url = Uri.parse(
                                    'https://primal.net/p/${app.signer.value!.npub}');
                                if (!await launchUrl(url)) {
                                  throw Exception('Could not launch $url');
                                }
                              },
                              child: AuthorContainer(
                                  user: app.signer.value!,
                                  text: 'Curated by',
                                  oneLine: false),
                            ),
                          if (app.developer.isPresent)
                            GestureDetector(
                              onTap: () async {
                                final url = Uri.parse(
                                    'https://primal.net/p/${app.developer.value!.npub}');
                                if (!await launchUrl(url)) {
                                  throw Exception('Could not launch $url');
                                }
                              },
                              child: AuthorContainer(
                                  user: app.developer.value!,
                                  text: 'Built by',
                                  oneLine: false),
                            ),
                        ],
                      ),
                    ),
                    Gap(30),
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
                                SizedBox(child: Text('Source')),
                                Flexible(
                                  child: AutoSizeText(
                                    app.repository!,
                                    minFontSize: 11,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ),
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
                          ),
                          Padding(
                            padding: const EdgeInsets.all(8),
                            child: Row(
                              mainAxisAlignment: MainAxisAlignment.spaceBetween,
                              children: [Text('License'), Text(app.license)],
                            ),
                          ),
                        ],
                      ),
                    ),
                    Divider(height: 50),
                    Text(
                      'Releases'.toUpperCase(),
                      style: TextStyle(
                        fontSize: 16,
                        letterSpacing: 3,
                        fontWeight: FontWeight.w300,
                      ),
                    ),
                    for (final release in app.releases.toList())
                      ReleaseCard(release: release),
                  ],
                ),
              ),
            ],
          ),
        ),
        Container(
          height: 50,
          padding: EdgeInsets.all(8),
          child: Center(
            child: InstallButton(app: app),
          ),
        ),
      ],
    );
  }
}

class ReleaseCard extends StatelessWidget {
  const ReleaseCard({
    super.key,
    required this.release,
  });

  final Release release;

  @override
  Widget build(BuildContext context) {
    final metadata = release.androidArtifacts.first;
    return Card(
      margin: EdgeInsets.only(top: 8, bottom: 8),
      elevation: 0,
      child: Container(
        padding: EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: Colors.grey[900],
          borderRadius: BorderRadius.circular(10),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(release.version,
                style: TextStyle(fontSize: 26, fontWeight: FontWeight.bold)),
            Gap(10),
            MarkdownBody(data: release.content),
            Gap(30),
            Padding(
              padding: const EdgeInsets.all(8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [Text('Version'), Text(release.version)],
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text('Date'),
                  Text(release.createdAt.toIso8601String())
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text('Size'),
                  Text('${int.parse(metadata.size!) ~/ 1024 ~/ 1024} MB')
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class InstallButton extends HookConsumerWidget {
  InstallButton({
    super.key,
    required this.app,
  });

  final App app;

  final installLabelProvider =
      StateProvider<(String, String)>((_) => ('Install', 'Installing...'));

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    useFuture(useMemoized(() async {
      final apps =
          await ref.apps.appAdapter.getInstalledApps(only: app.id.toString());
      ref.read(installLabelProvider.notifier).state = apps.first.isUpdated
          ? ('Install', 'Installing...')
          : ('Update', 'Updating...');
    }));

    return AsyncButtonBuilder(
      loadingWidget: Text(ref.watch(installLabelProvider).$2),
      onPressed: () async {
        final a = app.releases.latest!.androidArtifacts.firstOrNull;
        if (a != null) {
          final completer = Completer();
          a.install().then(completer.complete).catchError((e) {
            context.showError(e);
            completer.completeError(e);
          });
          return completer.future;
        }
      },
      builder: (context, child, callback, buttonState) {
        final buttonColor = buttonState.when(
          idle: () => Colors.blue,
          loading: () => Colors.blue[800],
          success: () => Colors.orangeAccent,
          error: (err, stack) => Colors.orange,
        );

        return OutlinedButton(
          onPressed: callback,
          style: OutlinedButton.styleFrom(
            foregroundColor: Colors.white,
            backgroundColor: buttonColor,
            minimumSize: const Size.fromHeight(50),
          ),
          child: child,
        );
      },
      child: Text(ref.watch(installLabelProvider).$1),
    );
  }
}
