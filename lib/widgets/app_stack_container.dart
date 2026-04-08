import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/nostr_route.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:skeletonizer/skeletonizer.dart';
import '../utils/extensions.dart';
import '../utils/url_utils.dart';
import '../theme.dart';
import '../services/package_manager/package_manager.dart';
import 'common/profile_avatar.dart';
import 'common/profile_name_widget.dart';

/// Number of stacks to show initially and load per batch
const int _kInitialStacks = 8;
const int _kBatchSize = 6;

/// Get the raw `a` tag values from the stack's event (available immediately)
Set<String> getRawAppTagValues(AppStack stack) {
  return stack.event.getTagSetValues('a');
}

/// Helper to compute preview app addressable IDs for a stack (3 apps max).
/// Returns full addressable IDs (e.g. '32267:pubkey:identifier').
List<String> getPreviewAddressableIds(AppStack stack) {
  final rawTags = getRawAppTagValues(stack)
      .where((id) => id.startsWith('32267:'))
      .toList()
    ..shuffle(Random(stack.id.hashCode));
  return rawTags.take(3).toList();
}

/// Decompose addressable IDs (e.g. '32267:pubkey:identifier') into
/// the sets of authors and identifiers needed for a query filter.
({Set<String> authors, Set<String> identifiers}) decomposeAddressableIds(
    Iterable<String> addressableIds) {
  final authors = <String>{};
  final identifiers = <String>{};
  for (final id in addressableIds) {
    final parts = id.split(':');
    if (parts.length >= 3) {
      authors.add(parts[1]);
      identifiers.add(parts.skip(2).join(':'));
    }
  }
  return (authors: authors, identifiers: identifiers);
}

/// Seed generated once per app session for stable shuffle order
final int _sessionSeed = Random().nextInt(1 << 32);

/// Shuffle stacks with a per-session seed for variety
List<AppStack> _shuffleStacks(List<AppStack> stacks, {String? signedInPubkey}) {
  final userSeed = signedInPubkey?.hashCode ?? 0;
  return stacks.toList()..shuffle(Random(_sessionSeed ^ userSeed));
}

/// App Stack Container - horizontally scrollable 2-row grid of stack cards
/// Uses lazy loading: stacks load immediately, preview apps load as visible
class AppStackContainer extends HookConsumerWidget {
  const AppStackContainer({super.key, this.showSkeleton = false});

  final bool showSkeleton;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scrollController = useScrollController();
    final visibleCount = useState(_kInitialStacks);

    if (showSkeleton) {
      return _buildSkeleton(context);
    }

    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);

    final platform = ref.read(packageManagerProvider.notifier).platform;

    // Only show stacks curated by the zapstore community
    final appStacksState = ref.watch(
      query<AppStack>(
        authors: {kZapstoreCommunityPubkey},
        limit: 20,
        tags: {
          '#f': {platform},
        },
        source: const LocalAndRemoteSource(relays: 'AppCatalog'),
        subscriptionPrefix: 'app-stack',
        schemaFilter: appStackEventFilter,
      ),
    );

    final allStacks = appStacksState.models.toList();

    if (allStacks.isEmpty) {
      if (appStacksState is StorageLoading<AppStack>) {
        return _buildSkeleton(context);
      }
      return const SizedBox.shrink();
    }

    final sortedStacks = _shuffleStacks(
      allStacks,
      signedInPubkey: signedInPubkey,
    );

    final displayedStacks = sortedStacks.take(visibleCount.value).toList();

    // Batch load preview apps for displayed stacks (3 per stack)
    final allPreviewIds = <String>{};
    final stackPreviewIds = <String, List<String>>{};
    for (final stack in displayedStacks) {
      final ids = getPreviewAddressableIds(stack);
      stackPreviewIds[stack.id] = ids;
      allPreviewIds.addAll(ids);
    }

    final (:authors, :identifiers) = decomposeAddressableIds(allPreviewIds);

    final previewAppsState = allPreviewIds.isNotEmpty
        ? ref.watch(
            query<App>(
              authors: authors,
              tags: {'#d': identifiers},
              source: const LocalAndRemoteSource(
                relays: 'AppCatalog',
                stream: false,
              ),
              subscriptionPrefix: 'app-stack-preview-apps',
            ),
          )
        : null;

    final appsMap = {
      for (final app in previewAppsState?.models ?? <App>[])
        app.id: app,
    };

    // Infinite horizontal scroll: load more when near end
    useEffect(() {
      void onScroll() {
        if (scrollController.position.pixels >=
            scrollController.position.maxScrollExtent - 200) {
          // Load more if we haven't shown all stacks yet
          if (visibleCount.value < sortedStacks.length) {
            visibleCount.value = (visibleCount.value + _kBatchSize).clamp(
              0,
              sortedStacks.length,
            );
          }
        }
      }

      scrollController.addListener(onScroll);
      return () => scrollController.removeListener(onScroll);
    }, [scrollController, sortedStacks.length]);

    // 2-row horizontal scroll layout with "See more" card
    final showSeeMore = sortedStacks.length > _kInitialStacks;
    final totalItems = displayedStacks.length + (showSeeMore ? 1 : 0);
    final numColumns = (totalItems + 1) ~/ 2;

    return SingleChildScrollView(
      controller: scrollController,
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.only(left: 12, right: 12),
      clipBehavior: Clip.none,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (int col = 0; col < numColumns; col++)
            Padding(
              padding: const EdgeInsets.only(right: 10),
              child: SizedBox(
                width: 160,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    _buildItemAtIndex(
                      context,
                      col * 2,
                      displayedStacks,
                      stackPreviewIds,
                      appsMap,
                      showSeeMore,
                      totalItems,
                    ),
                    if (col * 2 + 1 < totalItems) ...[
                      const SizedBox(height: 10),
                      _buildItemAtIndex(
                        context,
                        col * 2 + 1,
                        displayedStacks,
                        stackPreviewIds,
                        appsMap,
                        showSeeMore,
                        totalItems,
                      ),
                    ],
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildItemAtIndex(
    BuildContext context,
    int index,
    List<AppStack> displayedStacks,
    Map<String, List<String>> stackPreviewIds,
    Map<String, App> appsMap,
    bool showSeeMore,
    int totalItems,
  ) {
    final isSeeMore = showSeeMore && index == totalItems - 1;
    if (isSeeMore) {
      return _SeeMoreCard();
    }
    if (index >= displayedStacks.length) return const SizedBox.shrink();
    final stack = displayedStacks[index];
    return StackCard(
      stack: stack,
      showAuthor: false,
      previewIdentifiers: stackPreviewIds[stack.id] ?? [],
      appsMap: appsMap,
    );
  }

  Widget _buildSkeleton(BuildContext context) {
    return SkeletonizerConfig(
      data: AppColors.getSkeletonizerConfig(Theme.of(context).brightness),
      child: Skeletonizer(
        child: SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.only(left: 12, right: 12),
          clipBehavior: Clip.none,
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (int column = 0; column < 3; column++) ...[
                if (column > 0) const SizedBox(width: 10),
                SizedBox(
                  width: 160,
                  child: const Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      StackCardSkeleton(),
                      SizedBox(height: 10),
                      StackCardSkeleton(),
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

/// "See more" card that navigates to AllStacksScreen
class _SeeMoreCard extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () => pushStacks(context),
      child: Container(
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: Theme.of(context).colorScheme.outline.withValues(alpha: 0.2),
            width: 1,
          ),
        ),
        padding: const EdgeInsets.all(12),
        child: Center(
          child: Text(
            'See more',
            style: context.textTheme.titleMedium?.copyWith(
              color: Theme.of(context).colorScheme.primary,
            ),
          ),
        ),
      ),
    );
  }
}

/// Skeleton card for loading state
class StackCardSkeleton extends StatelessWidget {
  const StackCardSkeleton({super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(14),
      ),
      padding: const EdgeInsets.all(12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            height: 18,
            width: double.infinity,
            decoration: BoxDecoration(
              color: AppColors.darkSkeletonBase,
              borderRadius: BorderRadius.circular(4),
            ),
          ),
          const SizedBox(height: 8),
          Container(
            height: 14,
            width: 90,
            decoration: BoxDecoration(
              color: AppColors.darkSkeletonBase,
              borderRadius: BorderRadius.circular(4),
            ),
          ),
          const SizedBox(height: 12),
          AspectRatio(
            aspectRatio: 4,
            child: Container(
              decoration: BoxDecoration(
                color: AppColors.darkSkeletonBase,
                borderRadius: BorderRadius.circular(8),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Individual stack card with vertical layout
class StackCard extends StatelessWidget {
  const StackCard({
    super.key,
    required this.stack,
    required this.previewIdentifiers,
    required this.appsMap,
    this.author,
    this.isAuthorLoading = false,
    this.showAuthor = true,
  });

  final AppStack stack;
  final Profile? author;
  final List<String> previewIdentifiers;
  final Map<String, App> appsMap;
  final bool isAuthorLoading;
  final bool showAuthor;

  @override
  Widget build(BuildContext context) {
    final totalApps = getRawAppTagValues(stack).length;

    final previewApps = previewIdentifiers
        .map((id) => appsMap[id])
        .whereType<App>()
        .toList();

    final profileStyle = context.textTheme.bodySmall?.copyWith(
      color: AppColors.darkOnSurfaceSecondary,
      fontSize: 13,
    );

    return GestureDetector(
      onTap: () => pushStack(
        context,
        stack.identifier,
        author: stack.pubkey,
        kind: stack.event.kind,
      ),
      child: Container(
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: Theme.of(context).colorScheme.outline.withValues(alpha: 0.2),
            width: 1,
          ),
        ),
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Builder(builder: (context) {
              final titleStyle = context.textTheme.titleMedium?.copyWith(
                fontFamily: 'Inter',
                fontSize:
                    (context.textTheme.titleMedium?.fontSize ?? 16) * 0.9,
              );
              final lineHeight = (titleStyle?.fontSize ?? 14.4) *
                  (titleStyle?.height ?? 1.2);
              return SizedBox(
                height: lineHeight * 2,
                child: _FadingText(
                  stack.name ?? stack.identifier,
                  style: titleStyle,
                  maxLines: 2,
                ),
              );
            }),
            if (showAuthor) ...[
              const SizedBox(height: 6),
              Row(
                children: [
                  ProfileAvatar(profile: author, radius: 9),
                  const SizedBox(width: 5),
                  Expanded(
                    child: ProfileNameWidget(
                      pubkey: stack.event.pubkey,
                      profile: author,
                      isLoading: isAuthorLoading,
                      style: profileStyle,
                      skeletonWidth: 80,
                    ),
                  ),
                ],
              ),
            ],
            const SizedBox(height: 8),
            _AppIconsRow(apps: previewApps, totalApps: totalApps),
          ],
        ),
      ),
    );
  }
}

/// Horizontal row of app icons for stack preview
class _AppIconsRow extends StatelessWidget {
  const _AppIconsRow({required this.apps, required this.totalApps});

  final List<App> apps;
  final int totalApps;

  @override
  Widget build(BuildContext context) {
    final hasMore = totalApps > 3;
    final extraCount = totalApps - 3;

    return Row(
      children: List.generate(4, (index) {
        // Show "+X" indicator in the 4th slot if there are more than 3 apps
        if (hasMore && index == 3) {
          return Expanded(
            child: Padding(
              padding: const EdgeInsets.all(2),
              child: AspectRatio(
                aspectRatio: 1,
                child: Container(
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.surfaceContainerHighest
                        .withValues(alpha: 0.8),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Center(
                    child: Text(
                      '+$extraCount',
                      style: context.textTheme.labelMedium?.copyWith(
                        fontWeight: FontWeight.w600,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          );
        }
        if (index < apps.length) {
          return Expanded(child: _AppIconTile(app: apps[index]));
        }
        // Empty placeholder for missing apps
        return Expanded(
          child: Padding(
            padding: const EdgeInsets.all(2),
            child: AspectRatio(
              aspectRatio: 1,
              child: Container(
                decoration: BoxDecoration(
                  color: Theme.of(
                    context,
                  ).colorScheme.surfaceContainerHighest.withValues(alpha: 0.5),
                  borderRadius: BorderRadius.circular(10),
                ),
              ),
            ),
          ),
        );
      }),
    );
  }
}

/// Individual app icon tile
class _AppIconTile extends StatelessWidget {
  const _AppIconTile({required this.app});

  final App app;

  @override
  Widget build(BuildContext context) {
    final iconUrl = firstValidHttpUrl(app.icons);

    return Padding(
      padding: const EdgeInsets.all(2),
      child: AspectRatio(
        aspectRatio: 1,
        child: Opacity(
          opacity: 0.87,
          child: ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: iconUrl != null
                ? CachedNetworkImage(
                    imageUrl: iconUrl,
                    fit: BoxFit.cover,
                    fadeInDuration: const Duration(milliseconds: 200),
                    placeholder: (_, __) => Container(
                      color: Theme.of(
                        context,
                      ).colorScheme.surfaceContainerHighest,
                    ),
                    errorWidget: (_, __, ___) => Container(
                      color: Theme.of(
                        context,
                      ).colorScheme.surfaceContainerHighest,
                      child: const Icon(
                        Icons.broken_image_outlined,
                        size: 16,
                        color: Colors.grey,
                      ),
                    ),
                  )
                : Container(
                    color: Theme.of(
                      context,
                    ).colorScheme.surfaceContainerHighest,
                    child: const Icon(
                      Icons.apps_outlined,
                      size: 16,
                      color: Colors.grey,
                    ),
                  ),
          ),
        ),
      ),
    );
  }
}

/// Text widget that applies fade-out effect when text overflows
class _FadingText extends HookWidget {
  const _FadingText(this.text, {required this.style, this.maxLines = 1});

  final String text;
  final TextStyle? style;
  final int maxLines;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final textPainter = TextPainter(
          text: TextSpan(text: text, style: style),
          maxLines: maxLines,
          textDirection: TextDirection.ltr,
        )..layout(maxWidth: constraints.maxWidth);

        final isOverflowing = textPainter.didExceedMaxLines;

        final textWidget = Text(
          text,
          style: style,
          maxLines: maxLines,
          overflow: TextOverflow.clip,
        );

        if (!isOverflowing) {
          return textWidget;
        }

        return ShaderMask(
          shaderCallback: (bounds) {
            return LinearGradient(
              begin: Alignment.centerLeft,
              end: Alignment.centerRight,
              colors: [
                Theme.of(context).colorScheme.onSurface,
                Theme.of(context).colorScheme.onSurface,
                Theme.of(context).colorScheme.onSurface.withValues(alpha: 0),
              ],
              stops: const [0.0, 0.8, 1.0],
            ).createShader(bounds);
          },
          blendMode: BlendMode.dstIn,
          child: textWidget,
        );
      },
    );
  }
}
