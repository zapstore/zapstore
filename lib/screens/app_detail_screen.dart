import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:zapstore/utils/debug_utils.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/services/profile_service.dart';
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
  const AppDetailScreen({super.key, required this.app});

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Check if debug mode should be enabled
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);
    final showDebugSections = isDebugMode(signedInPubkey);

    // Watch the app for real-time updates using model provider
    final appState = ref.watch(
      model<App>(
        app,
        and: (a) => {
          a.latestRelease,
          if (a.latestRelease.value != null)
            a.latestRelease.value!.latestMetadata,
        },
        source: LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
      ),
    );

    // Query author profile from social relays
    final authorAsync = ref.watch(profileProvider(app.pubkey));
    final author = authorAsync.value;

    // Use loaded version from state
    final currentApp = appState.models.firstOrNull ?? app;
    final latestRelease = currentApp.latestRelease.value;
    final latestMetadata = currentApp.latestFileMetadata;

    // Show skeleton while loading essential relationships
    if (latestRelease == null || latestMetadata == null) {
      return Scaffold(
        body: SafeArea(
          child: Stack(
            children: [
              RefreshIndicator(
                onRefresh: () => [app].loadMetadata(),
                child: SingleChildScrollView(
                  padding: const EdgeInsets.only(top: 16, bottom: 80),
                  child: const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 16),
                    child: AppDetailSkeleton(),
                  ),
                ),
              ),
              InstallButton(app: currentApp, release: latestRelease),
            ],
          ),
        ),
      );
    }

    return Scaffold(
      body: SafeArea(
        child: Stack(
          children: [
            // Main scrollable content
            RefreshIndicator(
              onRefresh: () => [app].loadMetadata(),
              child: SingleChildScrollView(
                padding: const EdgeInsets.only(
                  top: 16,
                  bottom: 80,
                ), // Horizontal padding applied per-section to allow screenshots to be full-bleed
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // App header with icon, name, version, and author (always show app info)
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      child: AppHeader(app: currentApp),
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
                                  context.push(
                                    '/$first/developer/${author.pubkey}',
                                  );
                                },
                              )
                            : const AuthorSkeleton(),
                      ),

                    // Screenshots gallery
                    if (currentApp.images.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 8),
                        child: ScreenshotsGallery(app: currentApp),
                      ),

                    // App description (always available from app)
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

                    // Social action buttons (Zap + Share + Save)
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const SizedBox(height: 16),
                          SocialActionsRow(app: currentApp, author: author),
                          const SizedBox(height: 16),
                        ],
                      ),
                    ),

                    if (!currentApp.isInstalled || currentApp.hasUpdate)
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 16),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const SizedBox(height: 12),

                            // Latest release title
                            Text(
                              'Latest release',
                              style: context.textTheme.headlineMedium?.copyWith(
                                fontWeight: FontWeight.bold,
                              ),
                            ),

                            const SizedBox(height: 8),

                            // Latest version info
                            Row(
                              children: [
                                VersionPillWidget(
                                  app: currentApp,
                                  forceVersion: latestMetadata.version,
                                ),
                                const SizedBox(width: 12),
                                Text(
                                  formatDate(latestMetadata.createdAt),
                                  style: context.textTheme.bodyMedium?.copyWith(
                                    color: Theme.of(context)
                                        .colorScheme
                                        .onSurface
                                        .withValues(alpha: 0.6),
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
                      child: AppInfoTable(
                        app: currentApp,
                        fileMetadata: latestMetadata,
                      ),
                    ),

                    CommentsSection(fileMetadata: latestMetadata),

                    // Debug section - only visible for specific pubkey
                    if (showDebugSections)
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 16),
                        child: DebugVersionsSection(app: currentApp),
                      ),

                    const SizedBox(height: 100), // Space for install button
                  ],
                ),
              ),
            ),

            // Sticky install/uninstall row (show even if release is loading)
            InstallButton(app: currentApp, release: latestRelease),
          ],
        ),
      ),
    );
  }
}
