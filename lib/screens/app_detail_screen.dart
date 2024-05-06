import 'package:auto_size_text/auto_size_text.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:external_app_launcher/external_app_launcher.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/screens/search_screen.dart';
import 'package:zapstore/widgets/card.dart';
import 'package:zapstore/widgets/pill_widget.dart';

class AppDetailScreen extends HookConsumerWidget {
  final App model;
  AppDetailScreen({
    required this.model,
    super.key,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scrollController = ScrollController();

    final state = ref.apps.watchOne(model.id!,
        alsoWatch: (_) => {_.releases, _.releases.artifacts});

    useFuture(useMemoized(() async {
      final releases = await ref.releases.findAll(params: {'#a': model.aTag});
      final metadataIds = releases.map((r) => r.tagMap['e']!).expand((_) => _);
      await ref.fileMetadata.findAll(params: {
        'ids': metadataIds,
        '#m': [kAndroidMimeType]
      });
    }));

    final app = state.model ?? model;

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
                    VersionedAppHeader(app: app),
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
        SizedBox(
          height: 50,
          child: Center(
            child: InstallButton(app: app),
          ),
        ),
      ],
    );
  }
}

class VersionedAppHeader extends StatelessWidget {
  const VersionedAppHeader({
    super.key,
    required this.app,
    this.showUpdate = false,
  });

  final bool showUpdate;
  final App app;

  @override
  Widget build(BuildContext context) {
    final isUpdate = app.canUpdate && showUpdate;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisAlignment: MainAxisAlignment.start,
      children: [
        CircularImage(
          url: app.icons.firstOrNull,
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
                style: TextStyle(fontSize: 28, fontWeight: FontWeight.bold),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              Gap(8),
              Wrap(
                children: [
                  if (isUpdate)
                    PillWidget(
                        text: app.installedVersion!, color: Colors.grey[800]),
                  if (isUpdate) Icon(Icons.arrow_right),
                  if (app.latestMetadata != null)
                    PillWidget(
                      text: app.latestMetadata!.version!,
                      color: Colors.grey[800],
                    ),
                ],
              ),
            ],
          ),
        ),
        if (isUpdate)
          SizedBox(
            width: 90,
            height: 40,
            child: InstallButton(
              app: app,
              compact: true,
            ),
          ),
      ],
    );
  }
}

class ReleaseCard extends StatelessWidget {
  ReleaseCard({
    super.key,
    required this.release,
  });

  final Release release;
  final formatter = DateFormat('dd MMM yyyy');

  @override
  Widget build(BuildContext context) {
    final metadata = release.app.value!.latestMetadata;
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
            if (metadata != null)
              Padding(
                padding: const EdgeInsets.all(8),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [Text('Version'), Text(metadata.version!)],
                ),
              ),
            Padding(
              padding: const EdgeInsets.all(8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text('Date'),
                  Text(formatter.format(release.createdAt)),
                ],
              ),
            ),
            if (metadata != null)
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

class InstallButton extends ConsumerWidget {
  InstallButton({
    super.key,
    required this.app,
    this.compact = false,
  });

  final bool compact;
  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final progress = ref.watch(installationProgressProvider(app.identifier!));

    return SizedBox(
      width: double.infinity,
      child: ElevatedButton(
        onPressed: switch (app.status) {
          AppInstallStatus.notInstallable => null,
          AppInstallStatus.updated => () async {
              await LaunchApp.openApp(androidPackageName: app.id!.toString());
            },
          _ => () => switch (progress) {
                IdleInstallProgress() => app.install(),
                _ => null,
              }
        },
        style: ElevatedButton.styleFrom(
          disabledForegroundColor: Colors.white,
          disabledBackgroundColor: Colors.blueGrey,
          foregroundColor: Colors.white,
          backgroundColor: switch (progress) {
            DownloadingInstallProgress() => Colors.blue[900],
            ErrorInstallProgress() => Colors.red,
            _ => Colors.blue,
          },
        ),
        child: switch (app.status) {
          AppInstallStatus.notInstallable => Text('Sorry, can\'t install'),
          AppInstallStatus.updated => Text('Open'),
          _ => switch (progress) {
              IdleInstallProgress() => app.canUpdate
                  ? AutoSizeText(
                      'Update ${compact ? '' : 'to ${app.latestMetadata!.version!}'}',
                      maxLines: 1)
                  : Text('Install'),
              DownloadingInstallProgress(progress: final p, host: final h) =>
                Text(
                    '${compact ? '' : 'Downloading from $h '}${(p * 100).floor()}%'),
              DeviceInstallProgress() => compact
                  ? SizedBox(
                      width: 14, height: 14, child: CircularProgressIndicator())
                  : Text(
                      '${app.canUpdate ? 'Updating' : 'Installing'} on device'),
              ErrorInstallProgress(e: final e) =>
                compact ? Icon(Icons.error) : Text(e.toString()),
            }
        },
      ),
    );
  }
}
