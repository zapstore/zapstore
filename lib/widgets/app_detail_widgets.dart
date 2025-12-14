import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:share_plus/share_plus.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zapstore/services/bookmarks_service.dart';
import 'package:zapstore/services/download_service.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/auth_widgets.dart';
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
      styleSheet: MarkdownStyleSheet.fromTheme(Theme.of(context)),
    );
  }
}

/// Row of social action buttons (zap, bookmark, app pack, share)
class SocialActionsRow extends HookConsumerWidget {
  const SocialActionsRow({super.key, required this.app, required this.author});

  final App app;
  final Profile? author;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);
    final isSignedIn = signedInPubkey != null;

    // Watch bookmarks from centralized provider (handles decryption)
    final bookmarksAsync = ref.watch(bookmarksProvider);
    final bookmarkedIds = bookmarksAsync.when(
      data: (ids) => ids,
      loading: () => <String>{},
      error: (_, __) => <String>{},
    );

    // Check if this app is bookmarked
    final appAddressableId =
        '${app.event.kind}:${app.pubkey}:${app.identifier}';
    final isPrivatelySaved = bookmarkedIds.contains(appAddressableId);

    // Query public packs for the dialog
    final publicPacksState = isSignedIn
        ? ref.watch(
            query<AppPack>(
              authors: {signedInPubkey},
              and: (pack) => {pack.apps},
              source: const LocalAndRemoteSource(
                relays: 'social',
                stream: false,
              ),
              andSource: const LocalSource(),
              subscriptionPrefix: 'user-packs',
            ),
          )
        : null;

    final allPacks = publicPacksState?.models ?? [];

    // Split packs for dialog (exclude the bookmark pack)
    final publicPacks = allPacks
        .where((pack) => pack.identifier != kAppBookmarksIdentifier)
        .toList();

    // Query zaps for zappers list
    final latestMetadata = app.latestFileMetadata;
    final zapsState = latestMetadata != null
        ? ref.watch(
            query<Zap>(
              tags: app.event.addressableIdTagMap,
              source: LocalAndRemoteSource(relays: 'social'),
              and: (zap) => {zap.author},
              andSource: LocalAndRemoteSource(relays: 'social', stream: false),
              subscriptionPrefix: 'app-zaps',
            ),
          )
        : null;

    final zapsOnMetadataState = latestMetadata != null
        ? ref.watch(
            query<Zap>(
              tags: {
                '#e': {latestMetadata.id},
              },
              and: (zap) => {zap.author},
              source: LocalAndRemoteSource(relays: 'social'),
              andSource: LocalAndRemoteSource(relays: 'social', stream: false),
              subscriptionPrefix: 'metadata-zaps',
            ),
          )
        : null;

    final zaps = zapsState != null && zapsOnMetadataState != null
        ? {...zapsState.models, ...zapsOnMetadataState.models}
        : <Zap>{};

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Zap + Bookmark + App Pack + Share button row (50% / 16% / 16% / 16% split)
        SizedBox(
          height: 36,
          child: Row(
            children: [
              // Zap button (50%)
              Expanded(
                flex: 50,
                child: ZapButton(app: app, author: author),
              ),
              const SizedBox(width: 8),
              // Bookmark button (16%)
              Expanded(
                flex: 16,
                child: FilledButton(
                  onPressed: () {
                    if (isSignedIn) {
                      _showBookmarkDialog(context, ref, app, isPrivatelySaved);
                    } else {
                      _showSignInPrompt(context, ref);
                    }
                  },
                  style: FilledButton.styleFrom(
                    padding: EdgeInsets.zero,
                    backgroundColor: isPrivatelySaved && isSignedIn
                        ? (Theme.of(context).brightness == Brightness.dark
                              ? const Color(0xFF4A6BA0) // Deep blue
                              : const Color(0xFF1E4D8B)) // Darker blue
                        : Theme.of(context).colorScheme.surfaceContainerHighest,
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
              // App Pack button (16%)
              Expanded(
                flex: 16,
                child: FilledButton(
                  onPressed: () {
                    if (isSignedIn) {
                      _showAddToPackDialog(context, ref, app, publicPacks);
                    } else {
                      _showSignInPrompt(context, ref);
                    }
                  },
                  style: FilledButton.styleFrom(
                    padding: EdgeInsets.zero,
                    backgroundColor:
                        Theme.of(context).brightness == Brightness.dark
                        ? const Color(0xFF3A6FCC) // Theme dark primary
                        : const Color(0xFF2563A8), // Muted blue
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  child: const Icon(Icons.apps, size: 20),
                ),
              ),
              const SizedBox(width: 8),
              // Share button (16%)
              Expanded(
                flex: 16,
                child: FilledButton(
                  onPressed: () => _shareApp(app),
                  style: FilledButton.styleFrom(
                    padding: EdgeInsets.zero,
                    backgroundColor:
                        Theme.of(context).brightness == Brightness.dark
                        ? const Color(0xFF2D5A8F) // Navy blue
                        : const Color(0xFF1A4673), // Darker navy
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  child: const Icon(Icons.share, size: 20),
                ),
              ),
            ],
          ),
        ),

        // Zappers list - only show for apps with zaps
        if (zaps.isNotEmpty && !app.isRelaySigned)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: ZappersHorizontalList(zaps: zaps.toList()),
          ),
      ],
    );
  }

  Future<void> _showBookmarkDialog(
    BuildContext context,
    WidgetRef ref,
    App app,
    bool isPrivatelySaved,
  ) async {
    await showBaseDialog(
      context: context,
      dialog: BookmarkDialog(app: app, isPrivatelySaved: isPrivatelySaved),
    );
  }

  Future<void> _showAddToPackDialog(
    BuildContext context,
    WidgetRef ref,
    App app,
    List<AppPack> publicPacks,
  ) async {
    await showBaseDialog(
      context: context,
      dialog: AddToPackDialog(app: app, publicPacks: publicPacks),
    );
  }

  void _shareApp(App app) {
    try {
      // Generate naddr for the app
      final naddr = Utils.encodeShareableIdentifier(
        AddressInput(
          identifier: app.identifier,
          author: app.pubkey,
          kind: app.event.kind,
          relays: [],
        ),
      );
      final shareUrl = 'https://zapstore.dev/apps/$naddr';

      // Share using Android's share sheet
      SharePlus.instance.share(ShareParams(text: shareUrl));
    } catch (e) {
      // Failed to share app
    }
  }

  Future<void> _showSignInPrompt(BuildContext context, WidgetRef ref) async {
    await showBaseDialog(context: context, dialog: const SignInPromptDialog());
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
        and: (release) => {release.latestMetadata},
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
    // Use the download service with specific metadata
    final downloadService = ref.read(downloadServiceProvider.notifier);
    await downloadService.downloadAppWithMetadata(
      app.identifier,
      app.name ?? app.identifier,
      metadata,
    );
  }
}
