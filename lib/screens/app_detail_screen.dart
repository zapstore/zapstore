import 'package:auto_size_text/auto_size_text.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:collection/collection.dart';
import 'package:external_app_launcher/external_app_launcher.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/utils/extensions.dart';
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
        alsoWatch: (_) =>
            {_.releases, _.releases.artifacts, _.signer, _.developer});
    // hack to refresh on install changes
    final _ = ref.watch(installedAppProvider);

    final app = state.model ?? model;

    return RefreshIndicator(
      onRefresh: () => ref.apps.findOne(model.id!),
      child: Column(
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
                                      child: CachedNetworkImage(
                                        imageUrl: i,
                                        errorWidget: (_, __, ___) =>
                                            Container(),
                                      ),
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
                          p: TextStyle(
                              fontSize: 18, fontWeight: FontWeight.w300),
                        ),
                        selectable: false,
                        data: app.content,
                      ),
                      Gap(10),
                      Padding(
                        padding: const EdgeInsets.only(right: 14),
                        child: SignerAndDeveloperRow(app: app),
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
                                mainAxisAlignment:
                                    MainAxisAlignment.spaceBetween,
                                children: [
                                  SizedBox(child: Text('Source ')),
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
                            if (app.githubStars != null)
                              Padding(
                                padding: const EdgeInsets.all(8),
                                child: Row(
                                  mainAxisAlignment:
                                      MainAxisAlignment.spaceBetween,
                                  children: [
                                    Text('Github stars'),
                                    Text(app.githubStars!)
                                  ],
                                ),
                              ),
                            if (app.githubForks != null)
                              Padding(
                                padding: const EdgeInsets.all(8),
                                child: Row(
                                  mainAxisAlignment:
                                      MainAxisAlignment.spaceBetween,
                                  children: [
                                    Text('Github forks'),
                                    Text(app.githubForks!)
                                  ],
                                ),
                              ),
                            Padding(
                              padding: const EdgeInsets.all(8),
                              child: Row(
                                mainAxisAlignment:
                                    MainAxisAlignment.spaceBetween,
                                children: [
                                  Text('License'),
                                  Text((app.license == null ||
                                          app.license == 'NOASSERTION')
                                      ? 'Unknown'
                                      : app.license!)
                                ],
                              ),
                            ),
                            Padding(
                              padding: const EdgeInsets.all(8),
                              child: Row(
                                mainAxisAlignment:
                                    MainAxisAlignment.spaceBetween,
                                children: [
                                  Text('SHA-256 hash '),
                                  Flexible(
                                    child: Text(
                                      '${app.latestMetadata!.hash!.substring(0, 26)}...',
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                    ),
                                  ),
                                ],
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
                      for (final release in app.releases.ordered)
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
      ),
    );
  }
}

class SignerAndDeveloperRow extends StatelessWidget {
  const SignerAndDeveloperRow({
    super.key,
    required this.app,
  });

  final App app;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        if (app.developer.isPresent)
          GestureDetector(
            onTap: () async {
              final url =
                  Uri.parse('https://njump.me/${app.developer.value!.npub}');
              if (!await launchUrl(url)) {
                throw Exception('Could not launch $url');
              }
            },
            child: AuthorContainer(
                user: app.developer.value!, text: 'Built by', oneLine: false),
          ),
        if (app.signer.isPresent)
          GestureDetector(
            onTap: () async {
              final url =
                  Uri.parse('https://njump.me/${app.signer.value!.npub}');
              if (!await launchUrl(url)) {
                throw Exception('Could not launch $url');
              }
            },
            child: AuthorContainer(
                user: app.signer.value!, text: 'Signed by', oneLine: false),
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
            release.content.length < 3000
                ? MarkdownBody(data: release.content)
                : Container(
                    constraints: BoxConstraints(
                      maxHeight: 300,
                    ),
                    child: Markdown(data: release.content),
                  ),
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
    final progress = ref.watch(installationProgressProvider(app.identifier));

    return SizedBox(
      width: double.infinity,
      child: ElevatedButton(
        onPressed: switch (app.status) {
          AppInstallStatus.noArch => null,
          AppInstallStatus.downgrade => null,
          AppInstallStatus.updated => () {
              LaunchApp.openApp(androidPackageName: app.id!.toString());
            },
          _ => switch (progress) {
              IdleInstallProgress() => () {
                  // show trust dialog only if first install
                  if (app.canInstall) {
                    showDialog(
                      context: context,
                      builder: (BuildContext context) {
                        return InstallAlertDialog(app: app);
                      },
                    );
                  } else if (app.canUpdate) {
                    app.install();
                  }
                },
              ErrorInstallProgress(e: final e) => () {
                  // show error and reset state to idle
                  context.showError(e.toString());
                  ref
                      .read(installationProgressProvider(app.id!.toString())
                          .notifier)
                      .state = IdleInstallProgress();
                },
              _ => null,
            }
        },
        style: ElevatedButton.styleFrom(
            disabledForegroundColor: Colors.white,
            disabledBackgroundColor: Colors.blue[700],
            foregroundColor: Colors.white,
            backgroundColor: switch (progress) {
              ErrorInstallProgress() => Colors.red,
              _ => Colors.blue[700],
            }),
        child: switch (app.status) {
          AppInstallStatus.noArch =>
            Text('Sorry, release does not support your device'),
          AppInstallStatus.downgrade => Text(
              'Installed version ${app.installedVersion ?? ''} is higher, can\'t downgrade'),
          AppInstallStatus.updated => Text('Open'),
          _ => switch (progress) {
              IdleInstallProgress() => app.canUpdate
                  ? AutoSizeText(
                      'Update ${compact ? '' : 'to ${app.latestMetadata!.version!}'}',
                      maxLines: 1)
                  : Text('Install'),
              DownloadingInstallProgress(progress: final p) => Text(
                  '${(p * 100).floor()}%',
                  style: TextStyle(fontWeight: FontWeight.bold),
                ),
              DeviceInstallProgress() => compact
                  ? SizedBox(
                      width: 14, height: 14, child: CircularProgressIndicator())
                  : Text(
                      '${app.canUpdate ? 'Updating' : 'Installing'} on device'),
              ErrorInstallProgress() => compact
                  ? SizedBox(width: 14, height: 14, child: Icon(Icons.error))
                  : Text('Error, tap to see message'),
            }
        },
      ),
    );
  }
}

class InstallAlertDialog extends ConsumerWidget {
  const InstallAlertDialog({
    super.key,
    required this.app,
  });

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.settings.watchOne('_').model!.user.value;
    return AlertDialog(
      elevation: 10,
      title: Text(
        'Are you sure you want to install ${app.name}?',
        style: TextStyle(fontWeight: FontWeight.bold),
      ),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
              'By installing this app you are trusting the signer now and for all future updates. Make sure you know who they are.'),
          Gap(20),
          // SignerAndDeveloperRow(app: app),
          AuthorContainer(
              user: app.signer.value!, text: 'Signed by', oneLine: true),
          Gap(20),
          if (user != null) WebOfTrustContainer(user: user, app: app),
          Gap(20),
          if (user != null) Text('The app will be downloaded from:\n'),
          if (user != null)
            Text(
              app.latestMetadata!.urls.firstOrNull ?? 'zap.store',
              style: TextStyle(fontWeight: FontWeight.bold),
            ),
          // AppDrawer(),
        ],
      ),
      actions: [
        if (user == null)
          TextButton(
            onPressed: () {
              Navigator.of(context).pop();
              scaffoldKey.currentState!.openDrawer();
            },
            child: Text('Log in to view web of trust',
                style: TextStyle(fontWeight: FontWeight.bold)),
          ),
        TextButton(
          onPressed: () {
            app.install();
            // NOTE: can't use context.pop()
            Navigator.of(context).pop();
          },
          child: user != null
              ? Text('Install', style: TextStyle(fontWeight: FontWeight.bold))
              : Text('I trust the signer, install anyway'),
        ),
        TextButton(
          onPressed: () {
            // NOTE: can't use context.pop()
            Navigator.of(context).pop();
          },
          child: Text('Go back'),
        ),
      ],
    );
  }
}

class WebOfTrustContainer extends HookConsumerWidget {
  const WebOfTrustContainer({
    super.key,
    required this.user,
    required this.app,
  });

  final User user;
  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final result = useFuture(useMemoized(
        () => ref.users.userAdapter.getTrusted(user, app.signer.value!)));
    if (result.connectionState == ConnectionState.waiting) {
      return Center(
          child: Column(
        children: [
          Text('Loading web of trust connections...'),
          SizedBox(width: 14, height: 14, child: CircularProgressIndicator()),
        ],
      ));
    } else if (result.hasError) {
      return Center(
          child: Text('Error checking web of trust: ${result.error}'));
    } else {
      final trustedUsers = result.data!;
      final hasUser = trustedUsers.contains(user);
      return Wrap(
        children: [
          if (hasUser) Text('You, '),
          for (final t in trustedUsers)
            if (t != user)
              Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Wrap(
                    children: [
                      CircularImage(url: t.avatarUrl, size: 22),
                      SizedBox(width: 4),
                      Text(
                        softWrap: true,
                        '${t.nameOrNpub}${trustedUsers.indexOf(t) == trustedUsers.length - 1 ? '' : ','}',
                      ),
                      SizedBox(width: 6),
                      if (trustedUsers.indexOf(t) == trustedUsers.length - 1)
                        Text('and others follow this signer', softWrap: true)
                    ],
                  )
                ],
              ),
        ],
      );
    }
  }
}
