import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:go_router/go_router.dart';
import 'rounded_image.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:auto_size_text/auto_size_text.dart';
import '../utils/extensions.dart';
import '../utils/url_utils.dart';
import '../theme.dart';

/// App Pack Container - horizontally scrollable pills with app collections
class AppPackContainer extends HookConsumerWidget {
  const AppPackContainer({super.key, this.showSkeleton = false});

  final bool showSkeleton;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (showSkeleton) {
      return _buildSkeleton(context);
    }

    final appPacksState = ref.watch(
      query<AppPack>(
        limit: 20,
        and: (pack) => {pack.apps},
        source: LocalAndRemoteSource(stream: true, relays: 'social'),
        andSource: const LocalAndRemoteSource(
          relays: 'AppCatalog',
          stream: false,
        ),
        schemaFilter: appPackEventFilter,
        subscriptionPrefix: 'app-pack',
      ),
    );

    final packs = switch (appPacksState) {
      StorageData(:final models) =>
        models..sort((a, b) => b.event.createdAt.compareTo(a.event.createdAt)),
      _ => <AppPack>[],
    };

    if (packs.isEmpty) {
      return _buildSkeleton(context);
    }

    final selectedId = useState(packs.first.id);
    final selectedPack = packs.firstWhere(
      (p) => p.id == selectedId.value,
      orElse: () => packs.first,
    );

    // Scroll controller and hint visibility state
    final scrollController = useScrollController();
    final showRightHint = useState(true);
    final hasScrolledOnce = useState(false);

    // Listen to scroll position to hide hint when near end
    useEffect(() {
      void listener() {
        if (!scrollController.hasClients) return;
        final maxScroll = scrollController.position.maxScrollExtent;
        final currentScroll = scrollController.offset;

        // Hide hint when scrolled past 20px or near the end
        if (currentScroll > 20) {
          hasScrolledOnce.value = true;
        }
        showRightHint.value =
            !hasScrolledOnce.value && currentScroll < maxScroll - 20;
      }

      // Check initial state after first frame
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (scrollController.hasClients) {
          final maxScroll = scrollController.position.maxScrollExtent;
          // Hide hint if content doesn't overflow
          showRightHint.value = maxScroll > 20;
        }
      });

      scrollController.addListener(listener);
      return () => scrollController.removeListener(listener);
    }, [scrollController]);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          height: 48,
          child: Stack(
            children: [
              SingleChildScrollView(
                controller: scrollController,
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.only(left: 12, right: 52),
                child: Row(
                  children: packs
                      .map(
                        (pack) => _PackPill(
                          pack: pack,
                          isSelected: selectedId.value == pack.id,
                          onTap: () => selectedId.value = pack.id,
                        ),
                      )
                      .toList(),
                ),
              ),
              // Right fade gradient with animated chevron
              if (showRightHint.value)
                Positioned(
                  right: 0,
                  top: 0,
                  bottom: 0,
                  width: 56,
                  child: IgnorePointer(
                    child: Container(
                      decoration: const BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.centerLeft,
                          end: Alignment.centerRight,
                          colors: [
                            Colors.transparent,
                            AppColors.darkBackground,
                            AppColors.darkBackground,
                          ],
                          stops: [0.0, 0.5, 1.0],
                        ),
                      ),
                      child: const Align(
                        alignment: Alignment(0.6, -0.1),
                        child: _AnimatedScrollHint(),
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        AppsGrid(apps: selectedPack.apps.toList()),
      ],
    );
  }

  Widget _buildSkeleton(BuildContext context) {
    return SkeletonizerConfig(
      data: AppColors.getSkeletonizerConfig(Theme.of(context).brightness),
      child: Skeletonizer(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Row(children: List.generate(3, (_) => _SkeletonPill())),
            ),
            const SizedBox(height: 16),
            const AppsGrid(apps: []),
          ],
        ),
      ),
    );
  }
}

/// Skeleton pill matching actual pill dimensions
class _SkeletonPill extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(right: 16),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
        decoration: BoxDecoration(
          color: Theme.of(
            context,
          ).colorScheme.surfaceContainerHighest.withValues(alpha: 0.8),
          borderRadius: BorderRadius.circular(18),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(height: 16, width: 50, color: AppColors.darkSkeletonBase),
            const SizedBox(width: 4),
            Container(height: 14, width: 16, color: AppColors.darkSkeletonBase),
            const SizedBox(width: 6),
            Container(
              width: 18,
              height: 18,
              decoration: BoxDecoration(
                color: AppColors.darkSkeletonBase,
                borderRadius: BorderRadius.circular(9),
              ),
            ),
            const SizedBox(width: 6),
            Container(height: 14, width: 50, color: AppColors.darkSkeletonBase),
          ],
        ),
      ),
    );
  }
}

/// Animated chevron hint for horizontal scroll discovery
class _AnimatedScrollHint extends StatefulWidget {
  const _AnimatedScrollHint();

  @override
  State<_AnimatedScrollHint> createState() => _AnimatedScrollHintState();
}

class _AnimatedScrollHintState extends State<_AnimatedScrollHint>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<double> _offsetAnimation;
  late Animation<double> _opacityAnimation;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      duration: const Duration(milliseconds: 900),
      vsync: this,
    )..repeat(reverse: true);

    _offsetAnimation = Tween<double>(
      begin: 0,
      end: 6,
    ).animate(CurvedAnimation(parent: _controller, curve: Curves.easeInOut));

    _opacityAnimation = Tween<double>(
      begin: 0.7,
      end: 1.0,
    ).animate(CurvedAnimation(parent: _controller, curve: Curves.easeInOut));
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _controller,
      builder: (context, child) => Transform.translate(
        offset: Offset(_offsetAnimation.value, 0),
        child: Opacity(
          opacity: _opacityAnimation.value,
          // Stack icons with slight offsets to create bold effect
          child: SizedBox(
            width: 20,
            height: 20,
            child: Stack(
              children: [
                Positioned(
                  left: 0.5,
                  child: Icon(
                    Icons.arrow_forward_ios_rounded,
                    color: Colors.white.withValues(alpha: 0.7),
                    size: 20,
                  ),
                ),
                const Icon(
                  Icons.arrow_forward_ios_rounded,
                  color: Colors.white,
                  size: 20,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Individual pack pill - watches its own author reactively
class _PackPill extends ConsumerWidget {
  const _PackPill({
    required this.pack,
    required this.isSelected,
    required this.onTap,
  });

  final AppPack pack;
  final bool isSelected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authorState = ref.watch(
      query<Profile>(
        authors: {pack.event.pubkey},
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

    return Padding(
      padding: const EdgeInsets.only(right: 16),
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
          decoration: BoxDecoration(
            color: isSelected
                ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.15)
                : Theme.of(
                    context,
                  ).colorScheme.surfaceContainerHighest.withValues(alpha: 0.8),
            borderRadius: BorderRadius.circular(18),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                pack.name ?? pack.identifier,
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
                GestureDetector(
                  onTap: isSelected
                      ? () {
                          final segments = GoRouterState.of(
                            context,
                          ).uri.pathSegments;
                          final first = segments.isNotEmpty
                              ? segments.first
                              : 'search';
                          context.push('/$first/user/${pack.event.pubkey}');
                        }
                      : null,
                  child: Row(
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
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// Horizontally scrolling apps grid - reusable widget for displaying apps in a 2-row grid
class AppsGrid extends StatelessWidget {
  const AppsGrid({super.key, required this.apps});

  final List<App> apps;

  @override
  Widget build(BuildContext context) {
    final items = apps.isEmpty ? List.generate(6, (_) => null) : apps;

    return SizedBox(
      height: 220,
      child: GridView.builder(
        padding: const EdgeInsets.symmetric(horizontal: 12),
        scrollDirection: Axis.horizontal,
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          childAspectRatio: 0.8,
          crossAxisSpacing: 2,
          mainAxisSpacing: 2,
        ),
        itemCount: items.length,
        itemBuilder: (context, index) => AppGridCard(app: items[index]),
      ),
    );
  }
}

/// Individual app card with original styling - reusable widget for grid display
class AppGridCard extends ConsumerWidget {
  const AppGridCard({super.key, this.app});

  final App? app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (app == null) {
      return Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              width: 32,
              height: 32,
              decoration: BoxDecoration(
                color: AppColors.darkSkeletonBase,
                borderRadius: BorderRadius.circular(8),
              ),
            ),
            const SizedBox(height: 8),
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

    final iconUrl = firstValidHttpUrl(app!.icons);

    return GestureDetector(
      onTap: () {
        if (app!.latestRelease.value == null) {
          final req = app!.latestRelease.req;
          if (req != null) {
            ref.storage.query(
              req,
              source: const LocalAndRemoteSource(stream: false),
            );
          }
        }
        final segments = GoRouterState.of(context).uri.pathSegments;
        final first = segments.isNotEmpty ? segments.first : 'search';
        // Prefer naddr so the detail screen can uniquely identify the app
        // (identifier + author) and not accidentally resolve to a different
        // publisher's app with the same identifier.
        final naddr = Utils.encodeShareableIdentifier(
          AddressInput(
            identifier: app!.identifier,
            author: app!.pubkey,
            kind: app!.event.kind,
            relays: const [],
          ),
        );
        context.push('/$first/app/$naddr');
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
                            placeholder: (_, __) => const SizedBox.shrink(),
                            errorWidget: (_, __, ___) => Icon(
                              Icons.broken_image_outlined,
                              size: 16,
                              color: Colors.grey[400],
                            ),
                          )
                        : Icon(
                            Icons.apps_outlined,
                            size: 16,
                            color: Colors.grey[400],
                          ),
                  ),
                ),
                const SizedBox(height: 12),
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
}
