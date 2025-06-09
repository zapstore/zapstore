import 'package:auto_size_text/auto_size_text.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:easy_image_viewer/easy_image_viewer.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/install_button.dart';
import 'package:zapstore/widgets/release_card.dart';
import 'package:zapstore/widgets/signer_container.dart';
import 'package:zapstore/widgets/users_rich_text.dart';
import 'package:zapstore/widgets/versioned_app_header.dart';
import 'package:zapstore/widgets/zap_button.dart';
import 'package:zapstore/widgets/zap_receipts.dart';

class AppDetailScreen extends HookConsumerWidget {
  final App model;
  AppDetailScreen({
    required this.model,
    super.key,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scrollController = ScrollController();

    final snapshot = useFuture(useMemoized(() async {
      // Skip cache to actually hit the remote to refresh
      return await ref.apps
          .findOne(model.identifier, remote: true, params: {'skipCache': true});
    }));

    final state = ref.apps.watchOne(model.id!,
        alsoWatch: (_) => {
              _.localApp,
              _.releases,
              _.releases.artifacts,
              _.signer,
            });

    final app = state.model ?? model;

    final curatedBy = ref.appCurationSets
        .findAllLocal()
        .where((s) => s.appIds.contains(app.identifier))
        .map((s) => s.signer.value)
        .nonNulls
        .toSet();

    // If request returned and result was null, go back
    if (snapshot.connectionState == ConnectionState.done &&
        snapshot.data == null) {
      return Center(
        child: ElevatedButton(
          onPressed: () {
            Navigator.of(context).pop();
          },
          child: Text(
            'This app is no longer available.\nPress to go back.',
          ),
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: () => ref.apps.findOne(model.identifier, remote: true),
      child: Column(
        children: [
          Expanded(
            child: CustomScrollView(
              slivers: [
                SliverList(
                  delegate: SliverChildListDelegate(
                    [
                      VersionedAppHeader(app: app),
                      Gap(16),
                      if (app.hasCertificateMismatch)
                        Container(
                          decoration: BoxDecoration(
                            color: Colors.orange[900],
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: Padding(
                            padding: const EdgeInsets.all(10),
                            child: Text(
                              '${app.name} ${app.latestMetadata!.version} has a different Android certificate than version ${app.localApp.value!.installedVersion} installed on your device.\n\nHave you used another app store to install ${app.name}? If so, you can choose to remove it and re-install coming back to this screen.\n\nIt otherwise could be a malicious update, contact the developer for details.\n\nNew certificate hash: ${app.latestMetadata!.apkSignatureHash}',
                              style: TextStyle(fontWeight: FontWeight.bold),
                            ),
                          ),
                        ),
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
                                    GestureDetector(
                                      onTap: () {
                                        final imageProvider =
                                            CachedNetworkImageProvider(i,
                                                scale: 0.6);
                                        showImageViewer(
                                          context,
                                          imageProvider,
                                          doubleTapZoomable: true,
                                        );
                                      },
                                      child: Padding(
                                        padding:
                                            const EdgeInsets.only(right: 12),
                                        child: CachedNetworkImage(
                                          imageUrl: i,
                                          errorWidget: (_, __, ___) =>
                                              Container(),
                                        ),
                                      ),
                                    ),
                                ],
                              ),
                            ),
                          ),
                        ),
                      Divider(height: 24),
                      if (curatedBy.isNotEmpty)
                        Container(
                          decoration: BoxDecoration(
                            color: Colors.grey[900],
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: Padding(
                            padding: const EdgeInsets.all(10),
                            child: UsersRichText(
                              trailingText: ' picked this app',
                              users: curatedBy.toList(),
                            ),
                          ),
                        ),
                      if (curatedBy.isNotEmpty) Gap(20),
                      MarkdownBody(
                        styleSheet: MarkdownStyleSheet(
                          h1: TextStyle(fontWeight: FontWeight.bold),
                          h2: TextStyle(fontWeight: FontWeight.bold),
                          p: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.w300,
                          ),
                        ),
                        selectable: false,
                        data: app.event.content.parseEmojis(),
                      ),
                      Gap(10),
                      Padding(
                        padding: const EdgeInsets.only(right: 14),
                        child: SignerContainer(app: app),
                      ),
                      if (app.latestMetadata != null &&
                          app.signer.isPresent &&
                          app.isSelfSigned)
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Gap(10),
                            SizedBox(
                                width: double.infinity,
                                child: ZapButton(app: app)),
                            Gap(10),
                            ZapReceipts(app: app),
                          ],
                        ),
                      Gap(20),
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
                                  Text('Source'),
                                  Gap(10),
                                  Flexible(
                                    child: GestureDetector(
                                      onTap: () {
                                        if (app.repository != null) {
                                          launchUrl(Uri.parse(app.repository!));
                                        }
                                      },
                                      child: app.repository != null
                                          ? AutoSizeText(
                                              app.repository!,
                                              minFontSize: 12,
                                              maxLines: 1,
                                              overflow: TextOverflow.ellipsis,
                                            )
                                          : Text('Source code not available',
                                              style: TextStyle(
                                                  color: Colors.red[300],
                                                  fontWeight: FontWeight.bold)),
                                    ),
                                  ),
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
                                  Text('App ID'),
                                  Gap(10),
                                  Flexible(
                                    child: AutoSizeText(
                                      app.identifier,
                                      minFontSize: 12,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                    ),
                                  )
                                ],
                              ),
                            ),
                            if (app.latestMetadata != null)
                              Padding(
                                padding: const EdgeInsets.all(8),
                                child: Row(
                                  mainAxisAlignment:
                                      MainAxisAlignment.spaceBetween,
                                  children: [
                                    Text('APK package SHA-256'),
                                    Flexible(
                                      child: GestureDetector(
                                        onTap: () {
                                          Clipboard.setData(ClipboardData(
                                              text: app.latestMetadata!.hash!));
                                          context.showInfo(
                                              'Copied APK package SHA-256 to the clipboard');
                                        },
                                        child: Row(
                                          mainAxisSize: MainAxisSize.min,
                                          children: [
                                            Text(
                                              app.latestMetadata!.hash!.shorten,
                                              maxLines: 1,
                                            ),
                                            Gap(6),
                                            Icon(Icons.copy_rounded, size: 18)
                                          ],
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            if (app.latestMetadata?.apkSignatureHash != null)
                              Padding(
                                padding: const EdgeInsets.all(8),
                                child: Row(
                                  mainAxisAlignment:
                                      MainAxisAlignment.spaceBetween,
                                  children: [
                                    Text('APK certificate SHA-256'),
                                    Flexible(
                                      child: GestureDetector(
                                        onTap: () {
                                          Clipboard.setData(ClipboardData(
                                              text: app.latestMetadata!
                                                  .apkSignatureHash!));
                                          context.showInfo(
                                              'Copied APK certificate SHA-256 to the clipboard');
                                        },
                                        child: Row(
                                          mainAxisSize: MainAxisSize.min,
                                          children: [
                                            Text(
                                              app.latestMetadata!
                                                  .apkSignatureHash!.shorten,
                                              maxLines: 1,
                                            ),
                                            Gap(6),
                                            Icon(Icons.copy_rounded, size: 18)
                                          ],
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                          ],
                        ),
                      ),
                      Divider(height: 60),
                      Text(
                        'Latest release'.toUpperCase(),
                        style: TextStyle(
                          fontSize: 16,
                          letterSpacing: 3,
                          fontWeight: FontWeight.w300,
                        ),
                      ),
                      Gap(10),
                      if (app.releases.isEmpty) Text('No available releases'),
                      if (app.releases.isNotEmpty)
                        ReleaseCard(
                            release:
                                app.releases.toList().sortedByLatest.first),
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
