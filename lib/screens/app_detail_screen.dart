import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zapstore/utils/debug_utils.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/widgets/app_detail_widgets.dart';
import 'package:zapstore/widgets/app_header.dart';
import 'package:zapstore/widgets/app_info_table.dart';
import 'package:zapstore/widgets/author_container.dart';
import 'package:zapstore/widgets/comments_section.dart';
import 'package:zapstore/widgets/download_text_container.dart';
import 'package:zapstore/widgets/expandable_markdown.dart';
import 'package:zapstore/widgets/floating_overflow_menu.dart';
import 'package:zapstore/widgets/install_button.dart';
import 'package:zapstore/widgets/screenshots_gallery.dart';

class AppDetailScreen extends HookConsumerWidget {
  const AppDetailScreen({super.key, required this.appId, this.authorPubkey});

  final String appId;
  final String? authorPubkey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final platform = ref.read(packageManagerProvider.notifier).platform;

    // Query app with relationships including author profile
    final appState = ref.watch(
      query<App>(
        authors: authorPubkey != null ? {authorPubkey!} : null,
        tags: {
          '#d': {appId},
          '#f': {platform},
        },
        limit: 1,
        and: (a) => {
          a.latestAsset.query(),
          a.latestRelease.query(
            and: (release) => {release.latestMetadata.query()},
          ),
        },
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'app-detail-$appId',
      ),
    );

    // Listen for query completion with no results - app removed from relay
    ref.listen(
      query<App>(
        authors: authorPubkey != null ? {authorPubkey!} : null,
        tags: {
          '#d': {appId},
          '#f': {platform},
        },
        limit: 1,
        and: (a) => {
          a.latestAsset.query(),
          a.latestRelease.query(
            and: (release) => {release.latestMetadata.query()},
          ),
        },
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'app-detail-$appId',
      ),
      (previous, next) async {
        // When query completes with no models (app not found on relay)
        if (next is StorageData<App> && next.models.isEmpty) {
          // Remove stale local data for this app
          await ref.storage.clear(
            RequestFilter<App>(
              tags: {
                '#d': {appId},
              },
            ).toRequest(),
          );
          // Navigate back
          if (context.mounted) {
            context.pop();
          }
        }
      },
    );

    final app = appState.models.firstOrNull;

    if (appState case StorageError(:final exception)) {
      return _ErrorScaffold(message: exception.toString());
    }

    if (app == null) {
      // StorageLoading with no models yet: show skeleton
      // StorageData with no models: app not found (handled by ref.listen pop)
      return const Scaffold(
        body: SafeArea(
          child: SingleChildScrollView(
            padding: EdgeInsets.all(16),
            child: AppDetailSkeleton(),
          ),
        ),
      );
    }

    return _AppDetailContent(app: app);
  }
}

class _ErrorScaffold extends StatelessWidget {
  final String message;
  const _ErrorScaffold({required this.message});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Open App')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.error_outline, size: 64),
            const SizedBox(height: 12),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24),
              child: Text(message, textAlign: TextAlign.center),
            ),
          ],
        ),
      ),
    );
  }
}

/// Internal widget that displays app details
class _AppDetailContent extends HookConsumerWidget {
  final App app;

  const _AppDetailContent({required this.app});

  @override
  Widget build(BuildContext context, WidgetRef ref) {

    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);
    final showDebugSections = isDebugMode(signedInPubkey);

    // Query author profile from social relays
    final authorState = ref.watch(
      query<Profile>(
        authors: {app.pubkey},
        source: const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          cachedFor: Duration(hours: 2),
        ),
        subscriptionPrefix: 'app-detail-profile',
      ),
    );
    final author = authorState.models.firstOrNull;

    final latestRelease = app.latestRelease.value;
    final latestMetadata = app.installable;

    return Scaffold(
      body: SafeArea(
        child: Stack(
          children: [
            SingleChildScrollView(
              padding: const EdgeInsets.only(top: 16, bottom: 80),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // App header
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: AppHeader(app: app),
                  ),

                  // Published by / Released at section
                  if (app.isRelaySigned && latestMetadata != null)
                    Padding(
                      padding: const EdgeInsets.only(
                        left: 16,
                        right: 16,
                        bottom: 8,
                      ),
                      child: DownloadTextContainer(
                        url: latestMetadata.urls.first,
                        size: 14,
                        onTap: app.repository != null
                            ? () => launchUrl(Uri.parse(app.repository!))
                            : null,
                      ),
                    )
                  else
                    Padding(
                      padding: const EdgeInsets.only(
                        left: 16,
                        right: 16,
                        bottom: 8,
                      ),
                      child: author != null
                          ? AuthorContainer(
                              profile: author,
                              beforeText: 'Published by',
                              oneLine: true,
                              size: 14,
                              app: app,
                              onTap: () {
                                final segments = GoRouterState.of(
                                  context,
                                ).uri.pathSegments;
                                final first = segments.isNotEmpty
                                    ? segments.first
                                    : 'search';
                                context.push('/$first/user/${author.pubkey}');
                              },
                            )
                          : const AuthorSkeleton(),
                    ),

                  // Screenshots gallery
                  if (app.images.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 8),
                      child: ScreenshotsGallery(app: app),
                    ),

                  // App description
                  if (app.description.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(
                        left: 20,
                        right: 20,
                        top: 8,
                      ),
                      child: ExpandableMarkdown(
                        data: app.description,
                        styleSheet:
                            MarkdownStyleSheet.fromTheme(
                              Theme.of(context),
                            ).copyWith(
                              p: context.textTheme.bodyLarge?.copyWith(
                                height: 1.6,
                              ),
                              blockquoteDecoration: BoxDecoration(
                                color: const Color(0xFF1E3A5F), // Dark blue
                                borderRadius: BorderRadius.circular(8),
                              ),
                            ),
                      ),
                    ),

                  // Social action buttons
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const SizedBox(height: 16),
                        SocialActionsRow(app: app, author: author),
                        const SizedBox(height: 16),
                      ],
                    ),
                  ),

                  // Latest release section — always shown; skeletons until metadata loads
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const SizedBox(height: 12),
                        Row(
                          children: [
                            Expanded(
                              child: Container(
                                height: 1,
                                color: Theme.of(
                                  context,
                                ).colorScheme.onSurface.withValues(alpha: 0.2),
                              ),
                            ),
                            Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 16,
                              ),
                              child: Text(
                                'LATEST RELEASE',
                                style: context.textTheme.labelLarge?.copyWith(
                                  color: Theme.of(context).colorScheme.onSurface
                                      .withValues(alpha: 0.85),
                                  letterSpacing: 1.5,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                            Expanded(
                              child: Container(
                                height: 1,
                                color: Theme.of(
                                  context,
                                ).colorScheme.onSurface.withValues(alpha: 0.2),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 16),
                        latestMetadata != null
                            ? Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 12,
                                  vertical: 8,
                                ),
                                decoration: BoxDecoration(
                                  color: Theme.of(
                                    context,
                                  ).colorScheme.onSurface.withValues(alpha: 0.05),
                                  borderRadius: BorderRadius.circular(8),
                                ),
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Text(
                                      'Version:',
                                      style: context.textTheme.bodyMedium,
                                    ),
                                    Gap(4),
                                    Text(
                                      latestMetadata.version,
                                      style: context.textTheme.bodyMedium
                                          ?.copyWith(fontWeight: FontWeight.bold),
                                    ),
                                    Gap(4),
                                    Text(
                                      '(${formatDate(latestMetadata.createdAt)})',
                                      style: context.textTheme.bodyMedium?.copyWith(
                                        color: Theme.of(context)
                                            .colorScheme
                                            .onSurface
                                            .withValues(alpha: 0.6),
                                      ),
                                    ),
                                  ],
                                ),
                              )
                            : ClipRRect(
                                borderRadius: BorderRadius.circular(8),
                                child: SizedBox(
                                  height: 32,
                                  width: 220,
                                  child: buildGradientLoader(context),
                                ),
                              ),
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 20),
                          child: latestRelease != null
                              ? ReleaseNotes(release: latestRelease)
                              : const ReleaseNotesSkeleton(),
                        ),
                      ],
                    ),
                  ),

                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: AppInfoTable(app: app, fileMetadata: latestMetadata),
                  ),

                  if (latestMetadata != null)
                    CommentsSection(app: app, fileMetadata: latestMetadata),

                  // Debug section
                  if (showDebugSections)
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      child: DebugVersionsSection(app: app),
                    ),

                  const SizedBox(height: 100),
                ],
              ),
            ),

            // Sticky install button
            InstallButton(app: app),

            // Floating three-dot menu
            FloatingOverflowMenu(
              shareUrl: getAppShareUrl(app),
              publisherPubkey: app.pubkey,
              app: app,
            ),

          ],
        ),
      ),
    );
  }

}
