import 'dart:convert';
import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:gap/gap.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zapstore/services/bookmarks_service.dart';
import 'package:zapstore/services/notification_service.dart';
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
          a.latestRelease.query(
            and: (release) => {
              release.latestMetadata.query(),
              release.latestAsset.query(),
            },
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
          a.latestRelease.query(
            and: (release) => {
              release.latestMetadata.query(),
              release.latestAsset.query(),
            },
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

    // Handle loading/error states when app not yet available
    final isLoading = appState is StorageLoading;
    final app = appState.models.firstOrNull;

    if (appState case StorageError(:final exception)) {
      return _ErrorScaffold(message: exception.toString());
    }

    // Show skeleton only if loading with no models yet
    if (isLoading && app == null) {
      return const Scaffold(
        body: SafeArea(
          child: SingleChildScrollView(
            padding: EdgeInsets.all(16),
            child: AppDetailSkeleton(),
          ),
        ),
      );
    }

    return _AppDetailContent(
      app: app,
      appState: appState,
      isLoading: isLoading,
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

/// Internal widget that displays app details
class _AppDetailContent extends HookConsumerWidget {
  final App? app;
  final StorageState<App> appState;
  final bool isLoading;

  const _AppDetailContent({
    required this.app,
    required this.appState,
    required this.isLoading,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final app = this.app;
    if (app == null) {
      return const _ErrorScaffold(message: 'App not found');
    }

    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);
    final isSignedIn = signedInPubkey != null;
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

    // Check if app is installed for menu options
    final installedPackage = ref.watch(
      installedPackageProvider(app.identifier),
    );
    final isInstalled = installedPackage != null;

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
              _buildFloatingMenu(context, ref, app, isInstalled, isSignedIn),
              if (isLoading) _buildLoadingIndicator(context),
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

                  // Latest release section
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
                        Container(
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
                                style: context.textTheme.bodyMedium?.copyWith(
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                              Gap(4),
                              Text(
                                '(${formatDate(latestMetadata.createdAt)})',
                                style: context.textTheme.bodyMedium?.copyWith(
                                  color: Theme.of(context).colorScheme.onSurface
                                      .withValues(alpha: 0.6),
                                ),
                              ),
                            ],
                          ),
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

            // Floating three-dot menu
            _buildFloatingMenu(context, ref, app, isInstalled, isSignedIn),

            // Loading indicator while fetching more data
            if (isLoading) _buildLoadingIndicator(context),
          ],
        ),
      ),
    );
  }

  Widget _buildLoadingIndicator(BuildContext context) {
    return Positioned(
      top: 8,
      left: 0,
      right: 0,
      child: Center(
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          decoration: BoxDecoration(
            color: Theme.of(
              context,
            ).colorScheme.surface.withValues(alpha: 0.95),
            borderRadius: BorderRadius.circular(20),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.1),
                blurRadius: 8,
                offset: const Offset(0, 2),
              ),
            ],
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: Theme.of(context).colorScheme.primary,
                ),
              ),
              const SizedBox(width: 8),
              Text(
                'Refreshing...',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(
                    context,
                  ).colorScheme.onSurface.withValues(alpha: 0.7),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildFloatingMenu(
    BuildContext context,
    WidgetRef ref,
    App app,
    bool isInstalled,
    bool isSignedIn,
  ) {
    return Positioned(
      top: 8,
      right: 8,
      child: _buildOverflowMenu(context, ref, app, isInstalled, isSignedIn),
    );
  }

  Widget _buildOverflowMenu(
    BuildContext context,
    WidgetRef ref,
    App app,
    bool isInstalled,
    bool isSignedIn,
  ) {
    // Watch saved apps to check if app is saved
    final savedAppsAsync = ref.watch(bookmarksProvider);
    final savedAppIds = savedAppsAsync.when(
      data: (ids) => ids,
      loading: () => <String>{},
      error: (_, __) => <String>{},
    );
    final appAddressableId =
        '${app.event.kind}:${app.pubkey}:${app.identifier}';
    final isSaved = savedAppIds.contains(appAddressableId);

    return Material(
      color: Theme.of(context).colorScheme.surface.withValues(alpha: 0.9),
      shape: const CircleBorder(),
      elevation: 2,
      child: PopupMenuButton<String>(
        icon: const Icon(Icons.more_vert),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        onSelected: (value) {
          switch (value) {
            case 'share':
              _shareApp(context, app);
              break;
            case 'copy_link':
              _copyLink(context, app);
              break;
            case 'save_app':
              _toggleSaveApp(context, ref, app, isSaved);
              break;
            case 'view_publisher':
              _viewPublisher(context, app);
              break;
            case 'open_browser':
              _openInBrowser(context, app);
              break;
            case 'open':
              _openApp(context, ref, app);
              break;
            case 'delete':
              _uninstallApp(context, ref, app);
              break;
          }
        },
        itemBuilder: (context) => [
          const PopupMenuItem<String>(
            value: 'share',
            child: Row(
              children: [Icon(Icons.share), SizedBox(width: 12), Text('Share')],
            ),
          ),
          const PopupMenuItem<String>(
            value: 'copy_link',
            child: Row(
              children: [
                Icon(Icons.link),
                SizedBox(width: 12),
                Text('Copy link'),
              ],
            ),
          ),
          if (isSignedIn)
            PopupMenuItem<String>(
              value: 'save_app',
              child: Row(
                children: [
                  Icon(isSaved ? Icons.bookmark : Icons.bookmark_border),
                  const SizedBox(width: 12),
                  Text(isSaved ? 'Remove from saved' : 'Save app'),
                ],
              ),
            ),
          const PopupMenuItem<String>(
            value: 'view_publisher',
            child: Row(
              children: [
                Icon(Icons.person),
                SizedBox(width: 12),
                Text('View publisher'),
              ],
            ),
          ),
          const PopupMenuItem<String>(
            value: 'open_browser',
            child: Row(
              children: [
                Icon(Icons.open_in_browser),
                SizedBox(width: 12),
                Text('Open in browser'),
              ],
            ),
          ),
          if (isInstalled) ...[
            const PopupMenuItem<String>(
              value: 'open',
              child: Row(
                children: [
                  Icon(Icons.open_in_new),
                  SizedBox(width: 12),
                  Text('Open'),
                ],
              ),
            ),
            const PopupMenuItem<String>(
              value: 'delete',
              child: Row(
                children: [
                  Icon(Icons.delete_outline),
                  SizedBox(width: 12),
                  Text('Delete'),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  String _getAppUrl(App app) {
    final naddr = Utils.encodeShareableIdentifier(
      AddressInput(
        identifier: app.identifier,
        author: app.pubkey,
        kind: app.event.kind,
        relays: [],
      ),
    );
    return 'https://zapstore.dev/apps/$naddr';
  }

  void _shareApp(BuildContext context, App app) {
    try {
      final shareUrl = _getAppUrl(app);
      SharePlus.instance.share(ShareParams(text: shareUrl));
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to share app', description: '$e');
      }
    }
  }

  void _copyLink(BuildContext context, App app) {
    try {
      final shareUrl = _getAppUrl(app);
      Clipboard.setData(ClipboardData(text: shareUrl));
      context.showInfo('Link copied to clipboard');
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to copy link', description: '$e');
      }
    }
  }

  Future<void> _toggleSaveApp(
    BuildContext context,
    WidgetRef ref,
    App app,
    bool isCurrentlySaved,
  ) async {
    try {
      final signer = ref.read(Signer.activeSignerProvider);
      final signedInPubkey = ref.read(Signer.activePubkeyProvider);

      if (signer == null || signedInPubkey == null) {
        if (context.mounted) {
          context.showError(
            'Sign in required',
            description: 'You need to sign in to save apps.',
          );
        }
        return;
      }

      // Query for existing stack
      final existingStackState = await ref.storage.query(
        RequestFilter<AppStack>(
          authors: {signedInPubkey},
          tags: {
            '#d': {kAppBookmarksIdentifier},
          },
        ).toRequest(),
        source: const LocalSource(),
      );
      final existingStack = existingStackState.firstOrNull;

      // Get existing app IDs by decrypting if stack exists
      List<String> existingAppIds = [];
      if (existingStack != null) {
        try {
          final decryptedContent = await signer.nip44Decrypt(
            existingStack.content,
            signedInPubkey,
          );
          existingAppIds = (jsonDecode(decryptedContent) as List)
              .cast<String>();
        } catch (e) {
          if (context.mounted) {
            context.showError(
              'Could not read existing saved apps',
              description:
                  'Your previous saved apps could not be decrypted. Starting fresh.\n\n$e',
            );
          }
        }
      }

      // Modify the list
      final appAddressableId =
          '${app.event.kind}:${app.pubkey}:${app.identifier}';

      if (isCurrentlySaved) {
        existingAppIds.remove(appAddressableId);
      } else {
        if (!existingAppIds.contains(appAddressableId)) {
          existingAppIds.add(appAddressableId);
        }
      }

      // Create new partial stack with updated list
      final partialStack = PartialAppStack.withEncryptedApps(
        name: 'Saved Apps',
        identifier: kAppBookmarksIdentifier,
        apps: existingAppIds,
      );

      // Sign (encrypts the content)
      final signedStack = await partialStack.signWith(signer);

      // Save to local storage and publish to relays
      await ref.storage.save({signedStack});
      ref.storage.publish({
        signedStack,
      }, source: RemoteSource(relays: 'social'));

      if (context.mounted) {
        context.showInfo(
          isCurrentlySaved ? 'App removed from saved' : 'App saved',
        );
      }
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to update bookmark', description: '$e');
      }
    }
  }

  void _viewPublisher(BuildContext context, App app) {
    final segments = GoRouterState.of(context).uri.pathSegments;
    final first = segments.isNotEmpty ? segments.first : 'search';
    context.push('/$first/user/${app.pubkey}');
  }

  Future<void> _openInBrowser(BuildContext context, App app) async {
    try {
      final url = _getAppUrl(app);
      final uri = Uri.parse(url);
      if (await canLaunchUrl(uri)) {
        await launchUrl(uri, mode: LaunchMode.externalApplication);
      } else {
        if (context.mounted) {
          context.showError('Could not open browser');
        }
      }
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to open browser', description: '$e');
      }
    }
  }

  Future<void> _openApp(BuildContext context, WidgetRef ref, App app) async {
    try {
      final packageManager = ref.read(packageManagerProvider.notifier);
      await packageManager.launchApp(app.identifier);
    } catch (e) {
      if (!context.mounted) return;
      context.showError(
        'Failed to launch ${app.name ?? app.identifier}',
        description:
            'The app may have been uninstalled or moved. Try reinstalling.\n\n$e',
      );
    }
  }

  Future<void> _uninstallApp(
    BuildContext context,
    WidgetRef ref,
    App app,
  ) async {
    try {
      final packageManager = ref.read(packageManagerProvider.notifier);
      await packageManager.uninstall(app.identifier);
      // Only reaches here after successful uninstall
      if (context.mounted) {
        context.showInfo('${app.name ?? app.identifier} has been uninstalled');
      }
    } catch (e) {
      if (context.mounted) {
        // Don't show error for user cancellation
        final message = e.toString();
        if (!message.contains('cancelled')) {
          context.showError('Uninstall failed', description: '$e');
        }
      }
    }
  }
}
