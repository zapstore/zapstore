import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
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
import 'package:zapstore/widgets/install_button.dart';
import 'package:zapstore/widgets/screenshots_gallery.dart';
import 'package:zapstore/widgets/version_pill_widget.dart';

class AppDetailScreen extends HookConsumerWidget {
  const AppDetailScreen({super.key, required this.appId, this.authorPubkey});

  final String appId;
  final String? authorPubkey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final platform = ref.read(packageManagerProvider.notifier).platform;

    // Query app with relationships
    final appState = ref.watch(
      query<App>(
        authors: authorPubkey != null ? {authorPubkey!} : null,
        tags: {
          '#d': {appId},
          '#f': {platform},
        },
        limit: 1,
        and: (a) => {a.latestRelease, a.latestRelease.value?.latestMetadata},
        // stream=true ensures cached/local results render immediately; remote
        // results will merge in as they arrive.
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: authorPubkey != null
            ? 'app-detail-${authorPubkey!}-$appId'
            : 'app-detail-$appId',
      ),
    );

    // Handle loading/error states when app not yet available
    return switch (appState) {
      StorageError(:final exception) => _ErrorScaffold(
        message: exception.toString(),
      ),
      StorageData() => _AppDetailContent(
        app: appState.models.firstOrNull,
        appState: appState,
      ),
      StorageLoading() => const Scaffold(
        body: SafeArea(
          child: SingleChildScrollView(
            padding: EdgeInsets.all(16),
            child: AppDetailSkeleton(),
          ),
        ),
      ),
    };
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
  final App? app;
  final StorageState<App> appState;

  const _AppDetailContent({required this.app, required this.appState});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final app = this.app;
    if (app == null) {
      return const _ErrorScaffold(message: 'App not found');
    }

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
      ),
    );
    final author = switch (authorState) {
      StorageData(:final models) => models.firstOrNull,
      _ => null,
    };

    final latestRelease = app.latestRelease.value;
    final latestMetadata = app.latestFileMetadata;

    // Show skeleton while relationships are loading
    if (latestRelease == null || latestMetadata == null) {
      return Scaffold(
        body: SafeArea(
          child: Stack(
            children: [
              const SingleChildScrollView(
                padding: EdgeInsets.only(top: 16, bottom: 80),
                child: Padding(
                  padding: EdgeInsets.symmetric(horizontal: 16),
                  child: AppDetailSkeleton(),
                ),
              ),
              InstallButton(app: app, release: latestRelease),
            ],
          ),
        ),
      );
    }

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
                  if (app.isRelaySigned)
                    Padding(
                      padding: const EdgeInsets.only(
                        left: 16,
                        right: 16,
                        bottom: 8,
                      ),
                      child: DownloadTextContainer(
                        url: latestMetadata.urls.first,
                        size: 14,
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

                  // Latest release section
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const SizedBox(height: 12),
                        Text(
                          'Latest release',
                          style: context.textTheme.headlineMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Row(
                          children: [
                            VersionPillWidget(
                              app: app,
                              forceVersion: latestMetadata.version,
                            ),
                            const SizedBox(width: 12),
                            Text(
                              formatDate(latestMetadata.createdAt),
                              style: context.textTheme.bodyMedium?.copyWith(
                                color: Theme.of(
                                  context,
                                ).colorScheme.onSurface.withValues(alpha: 0.6),
                              ),
                            ),
                          ],
                        ),
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 20),
                          child: ReleaseNotes(release: latestRelease),
                        ),
                      ],
                    ),
                  ),

                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: AppInfoTable(app: app, fileMetadata: latestMetadata),
                  ),

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
            InstallButton(app: app, release: latestRelease),
          ],
        ),
      ),
    );
  }
}
