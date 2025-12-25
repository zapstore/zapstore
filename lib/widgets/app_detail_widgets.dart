import 'dart:convert';

import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zapstore/services/bookmarks_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/bookmark_widgets.dart';
import 'package:zapstore/widgets/common/base_dialog.dart';
import 'package:zapstore/widgets/expandable_markdown.dart';
import 'package:zapstore/widgets/zap_widgets.dart';

/// Format a date as time ago (e.g., "2 days ago")
String formatDate(DateTime date) {
  final now = DateTime.now();
  final diff = now.difference(date);

  if (diff.inDays > 365) {
    return '${(diff.inDays / 365).floor()} year${diff.inDays ~/ 365 != 1 ? 's' : ''} ago';
  } else if (diff.inDays > 30) {
    return '${(diff.inDays / 30).floor()} month${diff.inDays ~/ 30 != 1 ? 's' : ''} ago';
  } else if (diff.inDays > 0) {
    return '${diff.inDays} day${diff.inDays != 1 ? 's' : ''} ago';
  } else if (diff.inHours > 0) {
    return '${diff.inHours} hour${diff.inHours != 1 ? 's' : ''} ago';
  } else {
    return 'just now';
  }
}

/// Widget displaying release notes with expandable markdown
class ReleaseNotes extends StatelessWidget {
  const ReleaseNotes({super.key, required this.release});

  final Release release;

  @override
  Widget build(BuildContext context) {
    if (release.releaseNotes?.isEmpty != false) {
      return const SizedBox.shrink();
    }
    return ExpandableMarkdown(
      data: release.releaseNotes!,
      onTapLink: (text, url, title) {
        if (url != null) {
          launchUrl(Uri.parse(url));
        }
      },
      styleSheet: MarkdownStyleSheet.fromTheme(Theme.of(context)).copyWith(
        blockquoteDecoration: BoxDecoration(
          color: const Color(0xFF1E3A5F), // Dark blue
          borderRadius: BorderRadius.circular(8),
        ),
      ),
    );
  }
}

/// Row of social action buttons (zap, bookmark, app stack, share)
class SocialActionsRow extends HookConsumerWidget {
  const SocialActionsRow({super.key, required this.app, required this.author});

  final App app;
  final Profile? author;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);
    final isSignedIn = signedInPubkey != null;

    // Watch saved apps from centralized provider (handles decryption)
    final savedAppsAsync = ref.watch(bookmarksProvider);
    final savedAppIds = savedAppsAsync.when(
      data: (ids) => ids,
      loading: () => <String>{},
      error: (_, __) => <String>{},
    );

    // Check if this app is saved
    final appAddressableId =
        '${app.event.kind}:${app.pubkey}:${app.identifier}';
    final isPrivatelySaved = savedAppIds.contains(appAddressableId);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Zap + Save App + App Stack button row
        SizedBox(
          height: 36,
          child: Row(
            children: [
              // Zap button
              Expanded(
                flex: 68,
                child: ZapButton(app: app, author: author),
              ),
              const SizedBox(width: 8),
              // Save App button
              Expanded(
                flex: 16,
                child: FilledButton(
                  onPressed: () => _handleSaveApp(
                    context,
                    ref,
                    app,
                    isPrivatelySaved,
                    isSignedIn,
                  ),
                  style: FilledButton.styleFrom(
                    padding: EdgeInsets.zero,
                    backgroundColor: isPrivatelySaved && isSignedIn
                        ? (Theme.of(context).brightness == Brightness.dark
                              ? const Color(0xFF9B4F5E) // Red wine/burgundy
                              : const Color(0xFF7D3C4D)) // Deep burgundy
                        : (Theme.of(context).brightness == Brightness.dark
                              ? const Color(0xFF3A3A3F) // Dark neutral
                              : const Color(0xFFE8E3E8)), // Light neutral
                    foregroundColor: isPrivatelySaved && isSignedIn
                        ? Colors.white
                        : Theme.of(
                            context,
                          ).colorScheme.onSurface.withValues(alpha: 0.7),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  child: Icon(
                    isPrivatelySaved && isSignedIn
                        ? Icons.bookmark
                        : Icons.bookmark_border,
                    size: 20,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              // App Stack button
              Expanded(
                flex: 16,
                child: FilledButton(
                  onPressed: () => _showAddToStackDialog(context, app),
                  style: FilledButton.styleFrom(
                    padding: EdgeInsets.zero,
                    backgroundColor:
                        Theme.of(context).brightness == Brightness.dark
                        ? const Color(0xFF4A7BA7) // Soft steel blue
                        : const Color(0xFF5B8FB9), // Light ocean blue
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  child: const Icon(Icons.apps, size: 20),
                ),
              ),
            ],
          ),
        ),

        // Zappers list
        if (!app.isRelaySigned) _ZappersListSection(app: app),
      ],
    );
  }

  Future<void> _handleSaveApp(
    BuildContext context,
    WidgetRef ref,
    App app,
    bool isPrivatelySaved,
    bool isSignedIn,
  ) async {
    // If not signed in, show dialog to prompt sign in
    if (!isSignedIn) {
      await showBaseDialog(
        context: context,
        dialog: SaveAppDialog(app: app, isPrivatelySaved: isPrivatelySaved),
      );
      return;
    }

    // If signed in, save directly without dialog
    try {
      final signer = ref.read(Signer.activeSignerProvider);
      final signedInPubkey = ref.read(Signer.activePubkeyProvider);

      if (signer == null || signedInPubkey == null) return;

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
        } catch (_) {
          // Silently start fresh if decryption fails
        }
      }

      // Modify the list
      final appAddressableId =
          '${app.event.kind}:${app.pubkey}:${app.identifier}';

      if (isPrivatelySaved) {
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
          isPrivatelySaved ? 'App removed from saved' : 'App saved privately',
        );
      }
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to save app', description: '$e');
      }
    }
  }

  Future<void> _showAddToStackDialog(BuildContext context, App app) async {
    await showBaseDialog(
      context: context,
      dialog: AddToStackDialog(app: app),
    );
  }
}

class _ZappersListSection extends ConsumerWidget {
  const _ZappersListSection({required this.app});

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final metadataId = app.latestFileMetadata?.id;

    // Query zaps on app (via #a tag)
    final zapsState = ref.watch(
      query<Zap>(
        tags: app.event.addressableIdTagMap,
        source: const LocalAndRemoteSource(relays: 'social'),
        subscriptionPrefix: 'app-zaps',
      ),
    );

    // Query zaps on metadata (via #e tag) - for legacy compatibility
    final zapsOnMetadataState = metadataId != null
        ? ref.watch(
            query<Zap>(
              tags: {
                '#e': {metadataId},
              },
              source: const LocalAndRemoteSource(relays: 'social'),
              subscriptionPrefix: 'metadata-zaps',
            ),
          )
        : null;

    // Combine zaps from both queries
    final allZaps = {
      ...zapsState.models,
      if (zapsOnMetadataState != null) ...zapsOnMetadataState.models,
    };

    if (allZaps.isEmpty) return const SizedBox.shrink();

    // Collect zapper pubkeys from metadata (already extracted from description tag)
    final zapperPubkeys = <String>{};
    for (final zap in allZaps) {
      // The zapper's pubkey is in event.metadata['author'], extracted from description
      final zapperPubkey = zap.event.metadata['author'] as String?;
      if (zapperPubkey != null) {
        zapperPubkeys.add(zapperPubkey);
      }
    }

    // Query profiles separately with cachedFor
    final profilesState = ref.watch(
      query<Profile>(
        authors: zapperPubkeys,
        source: const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          cachedFor: Duration(hours: 2),
        ),
      ),
    );
    final profilesMap = {
      for (final p in profilesState.models) p.pubkey: p,
    };

    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: ZappersHorizontalList(
        zaps: allZaps.toList(),
        profilesMap: profilesMap,
      ),
    );
  }
}

/// Skeleton loading state for app detail screen
class AppDetailSkeleton extends StatelessWidget {
  const AppDetailSkeleton({super.key});

  @override
  Widget build(BuildContext context) {
    return SkeletonizerConfig(
      data: AppColors.getSkeletonizerConfig(Theme.of(context).brightness),
      child: Skeletonizer(
        enabled: true,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Header (AppHeader)
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(16),
                  child: SizedBox(
                    width: 74,
                    height: 74,
                    child: buildGradientLoader(context),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        height: 20,
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: buildGradientLoader(context),
                      ),
                      const SizedBox(height: 8),
                      SizedBox(
                        height: 16,
                        width: 140,
                        child: buildGradientLoader(context),
                      ),
                    ],
                  ),
                ),
              ],
            ),

            const SizedBox(height: 12),

            // Author (AuthorContainer)
            const AuthorSkeleton(),

            const SizedBox(height: 12),

            // Download text (DownloadTextContainer)
            SizedBox(
              height: 16,
              width: double.infinity,
              child: buildGradientLoader(context),
            ),

            const SizedBox(height: 16),

            // Screenshots (ScreenshotsGallery)
            SizedBox(
              height: 200,
              child: ListView.builder(
                scrollDirection: Axis.horizontal,
                itemCount: 6,
                padding: const EdgeInsets.only(right: 12),
                itemBuilder: (context, index) {
                  return Container(
                    width: 120,
                    margin: EdgeInsets.only(left: index == 0 ? 0 : 6, right: 6),
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(8),
                      child: buildGradientLoader(context),
                    ),
                  );
                },
              ),
            ),

            const SizedBox(height: 16),

            // Description (ExpandableMarkdown)
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: List.generate(
                4,
                (i) => Padding(
                  padding: EdgeInsets.only(bottom: i == 3 ? 0 : 8),
                  child: SizedBox(
                    height: 16,
                    width: i == 3 ? 180 : double.infinity,
                    child: buildGradientLoader(context),
                  ),
                ),
              ),
            ),

            const SizedBox(height: 16),

            // Zappers list (when present)
            Row(
              children: [
                // total sats pill
                Container(
                  height: 26,
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: buildGradientLoader(context),
                ),
                const SizedBox(width: 12),
                // avatars + amounts
                ...List.generate(
                  4,
                  (index) => Padding(
                    padding: const EdgeInsets.only(right: 12),
                    child: Row(
                      children: [
                        ClipOval(
                          child: SizedBox(
                            width: 24,
                            height: 24,
                            child: buildGradientLoader(context),
                          ),
                        ),
                        const SizedBox(width: 6),
                        SizedBox(
                          width: 32,
                          height: 12,
                          child: buildGradientLoader(context),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),

            const SizedBox(height: 8),

            // Zap button
            SizedBox(
              height: 48,
              width: double.infinity,
              child: buildGradientLoader(context),
            ),

            const SizedBox(height: 24),

            // Latest release / Up to date title
            SizedBox(
              height: 24,
              width: 160,
              child: buildGradientLoader(context),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                SizedBox(
                  height: 22,
                  width: 100,
                  child: buildGradientLoader(context),
                ),
                const SizedBox(width: 12),
                SizedBox(
                  height: 22,
                  width: 90,
                  child: buildGradientLoader(context),
                ),
              ],
            ),

            const SizedBox(height: 16),

            // Release notes (ExpandableMarkdown)
            Column(
              children: List.generate(
                3,
                (index) => Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: SizedBox(
                    height: 16,
                    width: index == 2 ? 200 : double.infinity,
                    child: buildGradientLoader(context),
                  ),
                ),
              ),
            ),

            const SizedBox(height: 16),

            // App info table (AppInfoTable)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  children: List.generate(
                    4,
                    (index) => Padding(
                      padding: const EdgeInsets.symmetric(vertical: 8),
                      child: Row(
                        children: [
                          Expanded(
                            child: SizedBox(
                              height: 16,
                              child: buildGradientLoader(context),
                            ),
                          ),
                          const SizedBox(width: 16),
                          SizedBox(
                            width: 80,
                            height: 16,
                            child: buildGradientLoader(context),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),

            const SizedBox(height: 16),

            // Comments section (CommentsSection)
            Column(
              children: List.generate(
                3,
                (index) => Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      ClipOval(
                        child: SizedBox(
                          width: 28,
                          height: 28,
                          child: buildGradientLoader(context),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            SizedBox(
                              height: 12,
                              width: 120,
                              child: buildGradientLoader(context),
                            ),
                            const SizedBox(height: 6),
                            SizedBox(
                              height: 14,
                              child: buildGradientLoader(context),
                            ),
                            const SizedBox(height: 6),
                            SizedBox(
                              height: 14,
                              width: 200,
                              child: buildGradientLoader(context),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Build gradient loader for skeleton screens
Widget buildGradientLoader(BuildContext context) {
  return Container(
    decoration: const BoxDecoration(
      gradient: LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(0xFF2A2A2A), Color(0xFF3A3A3A), Color(0xFF2A2A2A)],
        stops: [0.0, 0.5, 1.0],
      ),
    ),
    child: LocalShimmerEffect(context: context),
  );
}

/// Local shimmer effect for skeleton screens
class LocalShimmerEffect extends HookWidget {
  final BuildContext context;

  const LocalShimmerEffect({super.key, required this.context});

  @override
  Widget build(BuildContext context) {
    final controller = useAnimationController(
      duration: const Duration(milliseconds: 1500),
    )..repeat();
    final animation = useMemoized(
      () => Tween<double>(
        begin: -1.0,
        end: 2.0,
      ).animate(CurvedAnimation(parent: controller, curve: Curves.easeInOut)),
      [controller],
    );

    const shimmerColors = [
      Color(0xFF2A2A2A),
      Color(0xFF3A3A3A),
      Color(0xFF2A2A2A),
    ];

    return AnimatedBuilder(
      animation: animation,
      builder: (context, child) {
        return Container(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: shimmerColors,
              stops: [
                (animation.value - 0.3).clamp(0.0, 1.0),
                animation.value.clamp(0.0, 1.0),
                (animation.value + 0.3).clamp(0.0, 1.0),
              ],
            ),
          ),
        );
      },
    );
  }
}

/// Skeleton loader for author information
class AuthorSkeleton extends StatelessWidget {
  const AuthorSkeleton({super.key});

  @override
  Widget build(BuildContext context) {
    return SkeletonizerConfig(
      data: AppColors.getSkeletonizerConfig(Theme.of(context).brightness),
      child: Skeletonizer(
        enabled: true,
        child: Row(
          children: [
            ClipOval(
              child: SizedBox(
                width: 24,
                height: 24,
                child: buildGradientLoader(context),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: SizedBox(height: 14, child: buildGradientLoader(context)),
            ),
          ],
        ),
      ),
    );
  }
}

/// Debug section showing all versions available for the app
class DebugVersionsSection extends HookConsumerWidget {
  const DebugVersionsSection({super.key, required this.app});

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isExpanded = useState(false);

    // Query ALL releases for this app using addressable ID tags
    final releasesState = ref.watch(
      query<Release>(
        tags: app.event.addressableIdTagMap,
        and: (release) => {release.latestMetadata, release.latestAsset},
        source: LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'debug-releases',
      ),
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Divider(),
        const SizedBox(height: 8),
        FilledButton.tonal(
          onPressed: () => isExpanded.value = !isExpanded.value,
          style: FilledButton.styleFrom(
            backgroundColor: Colors.purple.withValues(alpha: 0.2),
            foregroundColor: Colors.purple,
            minimumSize: const Size(double.infinity, 48),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Text('🐛 Debug: All Versions'),
              const SizedBox(width: 8),
              Icon(
                isExpanded.value ? Icons.expand_less : Icons.expand_more,
                size: 20,
              ),
            ],
          ),
        ),
        if (isExpanded.value) ...[
          const SizedBox(height: 16),
          switch (releasesState) {
            StorageLoading() => const Center(
              child: Padding(
                padding: EdgeInsets.all(16),
                child: CircularProgressIndicator(),
              ),
            ),
            StorageError(:final exception) => Center(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Text(
                  'Error loading releases: $exception',
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
            ),
            StorageData(:final models) =>
              models.isEmpty
                  ? const Center(
                      child: Padding(
                        padding: EdgeInsets.all(16),
                        child: Text('No releases found'),
                      ),
                    )
                  : Column(
                      children: models.map((release) {
                        final metadata = release.latestMetadata.value;
                        return Card(
                          margin: const EdgeInsets.only(bottom: 12),
                          child: Padding(
                            padding: const EdgeInsets.all(12),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Expanded(
                                      child: Column(
                                        crossAxisAlignment:
                                            CrossAxisAlignment.start,
                                        children: [
                                          Text(
                                            'Version ${release.version}',
                                            style: Theme.of(context)
                                                .textTheme
                                                .titleMedium
                                                ?.copyWith(
                                                  fontWeight: FontWeight.bold,
                                                ),
                                          ),
                                          if (metadata != null) ...[
                                            Text(
                                              'Released ${formatDate(metadata.createdAt)}',
                                              style: Theme.of(
                                                context,
                                              ).textTheme.bodySmall,
                                            ),
                                            Text(
                                              '${(metadata.size ?? 0) ~/ (1024 * 1024)} MB',
                                              style: Theme.of(context)
                                                  .textTheme
                                                  .bodySmall
                                                  ?.copyWith(
                                                    color: Theme.of(context)
                                                        .colorScheme
                                                        .onSurface
                                                        .withValues(alpha: 0.6),
                                                  ),
                                            ),
                                          ],
                                        ],
                                      ),
                                    ),
                                    if (metadata != null)
                                      SizedBox(
                                        width: 100,
                                        height: 36,
                                        child: AsyncButtonBuilder(
                                          onPressed: () => _installVersion(
                                            ref,
                                            app,
                                            release,
                                            metadata,
                                          ),
                                          builder:
                                              (
                                                context,
                                                child,
                                                callback,
                                                buttonState,
                                              ) {
                                                return FilledButton(
                                                  onPressed: buttonState
                                                      .maybeWhen(
                                                        loading: () => null,
                                                        orElse: () => callback,
                                                      ),
                                                  style: FilledButton.styleFrom(
                                                    padding: EdgeInsets.zero,
                                                  ),
                                                  child: buttonState.maybeWhen(
                                                    loading: () => const SizedBox(
                                                      width: 16,
                                                      height: 16,
                                                      child: CircularProgressIndicator(
                                                        strokeWidth: 2,
                                                        valueColor:
                                                            AlwaysStoppedAnimation<
                                                              Color
                                                            >(Colors.white),
                                                      ),
                                                    ),
                                                    orElse: () =>
                                                        const Text('Install'),
                                                  ),
                                                );
                                              },
                                          child: const SizedBox.shrink(),
                                          onError: () {
                                            if (context.mounted) {
                                              context.showError(
                                                'Installation failed. Please try again.',
                                              );
                                            }
                                          },
                                        ),
                                      ),
                                  ],
                                ),
                                if (release.releaseNotes?.isNotEmpty ==
                                    true) ...[
                                  const SizedBox(height: 8),
                                  Text(
                                    release.releaseNotes!,
                                    style: Theme.of(
                                      context,
                                    ).textTheme.bodySmall,
                                    maxLines: 3,
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ],
                              ],
                            ),
                          ),
                        );
                      }).toList(),
                    ),
          },
        ],
      ],
    );
  }

  Future<void> _installVersion(
    WidgetRef ref,
    App app,
    Release release,
    FileMetadata metadata,
  ) async {
    // Use PackageManager to start download
    final pm = ref.read(packageManagerProvider.notifier);
    await pm.startDownload(app.identifier, metadata, displayName: app.name);
  }
}
