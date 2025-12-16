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
  const AppDetailScreen({super.key, this.app, this.appId})
    : assert(app != null || appId != null);

  /// The app to display (if already loaded)
  final App? app;

  /// The app identifier to load (used for deep links / market:// intents)
  final String? appId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // If we only have appId, query for the app first
    if (app == null && appId != null) {
      return _AppLoaderView(appId: appId!);
    }

    return _AppDetailView(app: app!);
  }
}

/// Internal view that loads an app by ID
class _AppLoaderView extends ConsumerWidget {
  final String appId;
  const _AppLoaderView({required this.appId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final platform = ref.read(packageManagerProvider.notifier).platform;

    // Check if appId is an naddr and decode it
    String? identifier;
    String? author;
    
    if (appId.startsWith('naddr1')) {
      try {
        final decoded = Utils.decodeShareableIdentifier(appId);
        if (decoded is AddressData) {
          identifier = decoded.identifier;
          author = decoded.author;
        }
      } catch (e) {
        // Invalid naddr, fall through to use appId as-is
      }
    }
    
    // If we decoded an naddr, query by author/identifier, otherwise by #d tag
    final appsState = ref.watch(
      query<App>(
        authors: author != null ? {author} : null,
        tags: {
          '#d': {identifier ?? appId},
          '#f': {platform},
        },
        limit: 1,
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'app-detail-loader',
      ),
    );

    final app = appsState.models.firstOrNull;

    if (app != null) {
      return _AppDetailView(app: app);
    }

    // Handle states
    switch (appsState) {
      case StorageError(:final exception):
        return _ErrorScaffold(message: exception.toString());
      case StorageData():
        return _ErrorScaffold(message: 'App "$appId" not found in Zapstore.');
      default:
        break;
    }

    // Loading state
    return const Scaffold(
      body: SafeArea(
        child: SingleChildScrollView(
          padding: EdgeInsets.all(16),
          child: AppDetailSkeleton(),
        ),
      ),
    );
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

/// Internal view that displays app details (app already loaded)
class _AppDetailView extends HookConsumerWidget {
  final App app;
  const _AppDetailView({required this.app});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Check if debug mode should be enabled
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);
    final showDebugSections = isDebugMode(signedInPubkey);

    // Watch the app for real-time updates using model provider
    final appState = ref.watch(
      model<App>(
        app,
        and: (a) => {a.latestRelease, a.latestRelease.value?.latestMetadata},
        source: LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'app-detail',
      ),
    );

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
              SingleChildScrollView(
                padding: const EdgeInsets.only(top: 16, bottom: 80),
                child: const Padding(
                  padding: EdgeInsets.symmetric(horizontal: 16),
                  child: AppDetailSkeleton(),
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
            SingleChildScrollView(
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
                                  context.push('/$first/user/${author.pubkey}');
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
                                  color: Theme.of(context).colorScheme.onSurface
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

                    CommentsSection(
                      app: currentApp,
                      fileMetadata: latestMetadata,
                    ),

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

            // Sticky install/uninstall row (show even if release is loading)
            InstallButton(app: currentApp, release: latestRelease),
          ],
        ),
      ),
    );
  }
}
