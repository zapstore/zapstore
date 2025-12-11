import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:go_router/go_router.dart';
import 'pill_widget.dart';
import 'rounded_image.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:auto_size_text/auto_size_text.dart';
import '../utils/extensions.dart';
import '../utils/url_utils.dart';
import '../services/profile_service.dart';
import '../theme.dart';

/// App Pack Container
/// Shows horizontally scrollable pills for different app packs
/// Each pill represents a curated collection of apps (e.g. "Nostr by franzap", "Utilities by franzap")
/// When a pill is tapped, it shows the apps from that pack below
/// Design: Horizontal scroll with colored pills, selected state changes pill color
/// Usage: Featured at top of search screen, allows users to browse curated app collections
class AppPackContainer extends HookConsumerWidget {
  const AppPackContainer({super.key, this.showSkeleton = false});

  final bool showSkeleton;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scrollController = useScrollController();

    // If showing skeleton, return skeleton state immediately
    if (showSkeleton) {
      return _buildLoadingState(context);
    }

    final appPacksState = ref.watch(
      query<AppPack>(
        limit: 20,
        and: (pack) => {pack.apps},
        source: const LocalAndRemoteSource(
          stream: true,
          background: true,
          relays: 'social', // Load from social relay group
        ),
        // App relationships should come from default/zapstore relay
        andSource: const LocalAndRemoteSource(
          relays: 'AppCatalog',
          stream: false,
          background: true,
        ),
        subscriptionPrefix: 'app-pack',
      ),
    );

    return switch (appPacksState) {
      StorageLoading() => _buildLoadingState(context),
      StorageError(:final exception) => _buildErrorState(context, exception),
      StorageData(:final models) when models.isEmpty => const SizedBox.shrink(),
      StorageData(:final models) => _buildLoadedState(
        context,
        ref,
        models,
        scrollController,
      ),
    };
  }

  static Widget _buildLoadingState(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Skeleton pills
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12.0),
            child: SkeletonizerConfig(
              data: AppColors.getSkeletonizerConfig(
                Theme.of(context).brightness,
              ),
              child: Skeletonizer(
                enabled: true,
                child: Row(
                  children: List.generate(
                    3,
                    (index) => Container(
                      width: 120,
                      height: 32,
                      margin: const EdgeInsets.only(right: 12),
                      decoration: BoxDecoration(
                        color: AppColors.darkSkeletonBase,
                        borderRadius: BorderRadius.circular(16),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),

        // Skeleton grid
        const SizedBox(height: 24),
        _buildSelectedAppsGrid(context, []), // Empty list shows skeletons
      ],
    );
  }

  Widget _buildErrorState(BuildContext context, Object exception) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16.0),
      child: PillWidget(
        const TextSpan(text: 'Error loading collections'),
        color: Colors.red[700]!,
      ),
    );
  }

  Widget _buildLoadedState(
    BuildContext context,
    WidgetRef ref,
    List<AppPack> appPacks,
    ScrollController scrollController,
  ) {
    // Filter out empty app packs, packs where apps don't have basic data,
    // and the private bookmarks pack
    final nonEmptyPacks = appPacks.where((pack) {
      // Exclude private bookmarks pack
      if (pack.identifier == kAppBookmarksIdentifier) {
        return false;
      }
      // Check if pack has at least one app with name or identifier
      final hasValidApps = pack.apps.toList().any(
        (app) => app.name != null || app.identifier.isNotEmpty,
      );
      return hasValidApps;
    }).toList();

    // If no packs with apps, show loading state
    if (nonEmptyPacks.isEmpty) {
      return _buildLoadingState(context);
    }

    // Default to Nostr pack, or first available pack
    final defaultSelection = () {
      final nostrPack = nonEmptyPacks
          .where((pack) => pack.id == kNostrCurationSetShareableId)
          .firstOrNull;
      return nostrPack?.id ?? nonEmptyPacks.first.id;
    }();

    final selectedAppPack = useState<String?>(defaultSelection);

    // Find the selected app pack
    final selectedPack = nonEmptyPacks
        .where((pack) => pack.id == selectedAppPack.value)
        .firstOrNull;

    // Controller for the horizontal apps grid
    final gridController = useScrollController();
    // Rebuild when the grid scrolls to adjust left padding dynamically
    useListenable(gridController);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          controller: scrollController,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12.0),
            child: Row(
              children: [
                _buildPackPills(
                  context,
                  ref,
                  nonEmptyPacks,
                  selectedAppPack.value,
                  (id) => selectedAppPack.value = id,
                ),
              ],
            ),
          ),
        ),

        // Show apps from selected pack with professional spacing
        if (selectedPack != null) ...[
          const SizedBox(height: 16),
          _buildSelectedAppsGrid(
            context,
            // Filter apps that have basic data (name or identifier)
            selectedPack.apps
                .toList()
                .where((app) => app.name != null || app.identifier.isNotEmpty)
                .toList(),
            controller: gridController,
            padding: EdgeInsets.only(
              left: gridController.hasClients && gridController.offset > 0
                  ? 0
                  : 12,
              right: 12,
            ),
          ),
        ],
      ],
    );
  }

  Widget _buildPackPills(
    BuildContext context,
    WidgetRef ref,
    List<AppPack> appPacks,
    String? selectedId,
    Function(String?) onSelect,
  ) {
    // Sort app packs: Nostr pack first, then others
    final sortedPacks = [...appPacks];
    sortedPacks.sort((a, b) {
      // Nostr pack goes first
      if (a.id == kNostrCurationSetShareableId) return -1;
      if (b.id == kNostrCurationSetShareableId) return 1;
      // Others by creation date (newest first)
      return b.event.createdAt.compareTo(a.event.createdAt);
    });

    return Row(
      children: sortedPacks.map((appPack) {
        final isSelected = selectedId == appPack.id;
        // Load author via profileProvider with caching
        final authorAsync = ref.watch(profileProvider(appPack.event.pubkey));
        final author = authorAsync.value;

        return Padding(
          padding: const EdgeInsets.only(right: 16),
          child: GestureDetector(
            onTap: () => onSelect(appPack.id),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
              decoration: BoxDecoration(
                color: isSelected
                    ? Theme.of(
                        context,
                      ).colorScheme.primary.withValues(alpha: 0.15)
                    : Theme.of(context).colorScheme.surfaceContainerHighest
                          .withValues(alpha: 0.8),
                borderRadius: BorderRadius.circular(18),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  Text(
                    appPack.name ?? appPack.identifier,
                    style: context.textTheme.labelLarge?.copyWith(
                      fontWeight: FontWeight.w600,
                      color: Theme.of(context).colorScheme.onSurface,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  if (author != null) ...[
                    Text(
                      ' by ',
                      style: context.textTheme.labelLarge?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                    _InlineAuthorWidget(author: author, isSelected: isSelected),
                  ],
                ],
              ),
            ),
          ),
        );
      }).toList(),
    );
  }

  static Widget _buildSelectedAppsGrid(
    BuildContext context,
    List<App> apps, {
    ScrollController? controller,
    EdgeInsets? padding,
  }) {
    return SizedBox(
      height: 220, // Slightly reduced from 240 to remove extra space
      child: GridView.builder(
        controller: controller,
        padding: padding ?? const EdgeInsets.symmetric(horizontal: 12.0),
        scrollDirection: Axis.horizontal,
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          childAspectRatio: 0.8, // Made smaller (less tall)
          crossAxisSpacing: 2,
          mainAxisSpacing: 2,
        ),
        itemCount: apps.isEmpty ? 6 : apps.length,
        itemBuilder: (context, index) {
          if (apps.isEmpty) {
            return const _AppGridCard(isLoading: true);
          }
          return _AppGridCard(app: apps[index]);
        },
      ),
    );
  }
}

class _AppGridCard extends ConsumerWidget {
  const _AppGridCard({this.app, this.isLoading = false});

  final App? app;
  final bool isLoading;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (isLoading || app == null) {
      return _buildSkeleton(context);
    }

    final iconUrl = firstValidHttpUrl(app!.icons);

    return GestureDetector(
      onTap: () {
        // Trigger loading of latestRelease relationship if not available (async, no wait)
        if (app!.latestRelease.value == null) {
          final request = app!.latestRelease.req;
          if (request != null) {
            ref.storage
                .query(
                  request,
                  source: const LocalAndRemoteSource(stream: false),
                )
                .catchError((e) {
                  // Failed to load latest release
                  return <Release>[];
                });
          }
        }

        // Navigate immediately with the app (detail screen will handle loading states)
        final segments = GoRouterState.of(context).uri.pathSegments;
        final first = segments.isNotEmpty ? segments.first : 'search';
        context.push('/$first/app/${app!.id}', extra: app!);
      },
      child: Container(
        margin: const EdgeInsets.all(2),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(16),
          color: Theme.of(context).colorScheme.surface.withValues(alpha: 0.8),
          border: Border.all(
            color: Theme.of(context).colorScheme.outline.withValues(alpha: 0.2),
            width: 1,
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                // Smaller app icon to make room for text
                Container(
                  width: 40,
                  height: 40,
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(12),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withValues(alpha: 0.1),
                        blurRadius: 4,
                        offset: const Offset(0, 2),
                      ),
                    ],
                  ),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(12),
                    child: iconUrl != null
                        ? CachedNetworkImage(
                            imageUrl: iconUrl,
                            fit: BoxFit.cover,
                            fadeInDuration: const Duration(milliseconds: 500),
                            fadeOutDuration: const Duration(milliseconds: 200),
                            placeholder: (_, url) => const SizedBox.shrink(),
                            errorWidget: (_, error, stackTrace) => Center(
                              child: Icon(
                                Icons.broken_image_outlined,
                                size: 16,
                                color: Colors.grey[400],
                              ),
                            ),
                          )
                        : Center(
                            child: Icon(
                              Icons.apps_outlined,
                              size: 16,
                              color: Colors.grey[400],
                            ),
                          ),
                  ),
                ),
                const SizedBox(height: 12),

                // Auto-sizing app name to prevent cutoff
                AutoSizeText(
                  app!.name ?? app!.identifier,
                  style: context.textTheme.labelMedium?.copyWith(
                    fontWeight: FontWeight.w600,
                    height: 1.2,
                  ),
                  maxLines: 1,
                  minFontSize: 10,
                  maxFontSize: 12,
                  textAlign: TextAlign.center,
                  overflow: TextOverflow.fade,
                  softWrap: false,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSkeleton(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(
        14,
      ), // Slightly more padding to center within grid cell
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          // App icon skeleton - simple rounded square
          Container(
            width: 32,
            height: 32,
            decoration: BoxDecoration(
              color: AppColors.darkSkeletonBase,
              borderRadius: BorderRadius.circular(8),
            ),
          ),
          const SizedBox(height: 8),

          // App name skeleton - single centered bar
          Container(
            height: 10,
            width: 40,
            decoration: BoxDecoration(
              color: AppColors.darkSkeletonBase,
              borderRadius: BorderRadius.circular(5),
            ),
          ),
        ],
      ),
    );
  }
}

/// Inline author widget for showing profile in pills
class _InlineAuthorWidget extends StatelessWidget {
  const _InlineAuthorWidget({required this.author, this.isSelected = false});

  final Profile author;
  final bool isSelected;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 18,
          height: 18,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(9),
            border: Border.all(
              color: Theme.of(
                context,
              ).colorScheme.outline.withValues(alpha: 0.3),
              width: 1,
            ),
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: RoundedImage(url: author.pictureUrl, size: 18),
          ),
        ),
        const SizedBox(width: 6),
        Text(
          author.nameOrNpub,
          style: context.textTheme.labelLarge?.copyWith(
            fontWeight: FontWeight.w500,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
      ],
    );
  }
}
