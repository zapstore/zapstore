import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:go_router/go_router.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:skeletonizer/skeletonizer.dart';
import '../utils/extensions.dart';
import '../utils/url_utils.dart';
import '../theme.dart';
import '../services/package_manager/package_manager.dart';
import 'common/profile_avatar.dart';
import 'common/profile_name_widget.dart';

/// Number of stacks to show initially and load per batch
const int _kInitialStacks = 6;
const int _kBatchSize = 6;

/// Get the raw `a` tag values from the stack's event (available immediately)
Set<String> _getRawAppTagValues(AppStack stack) {
  return stack.event.getTagSetValues('a');
}

/// Extract just the d-tag (identifier) from a full addressable id
String? _extractIdentifier(String addressableId) {
  final parts = addressableId.split(':');
  return parts.length >= 3 ? parts.sublist(2).join(':') : null;
}

/// Helper to compute preview app identifiers for a stack (3 apps max)
List<String> _getPreviewIdentifiers(AppStack stack) {
  final rawTags = _getRawAppTagValues(stack).toList()
    ..shuffle(Random(stack.id.hashCode));
  return rawTags.take(3).map(_extractIdentifier).whereType<String>().toList();
}

/// Sort app stacks: franzap/following first, then by recency
List<AppStack> _sortStacks(
  List<AppStack> stacks, {
  String? signedInPubkey,
  Set<String>? followingPubkeys,
}) {
  final today = DateTime.now();
  final dateSeed = today.year * 10000 + today.month * 100 + today.day;
  final userSeed = signedInPubkey?.hashCode ?? 0;
  final random = Random(dateSeed ^ userSeed);

  if (signedInPubkey != null &&
      followingPubkeys != null &&
      followingPubkeys.isNotEmpty) {
    final followed = <AppStack>[];
    final others = <AppStack>[];

    for (final stack in stacks) {
      if (followingPubkeys.contains(stack.pubkey)) {
        followed.add(stack);
      } else {
        others.add(stack);
      }
    }

    followed.shuffle(random);
    others.sort((a, b) => b.event.createdAt.compareTo(a.event.createdAt));
    return [...followed, ...others];
  } else {
    final franzapStacks = stacks
        .where((s) => s.pubkey == kFranzapPubkey)
        .toList();
    final otherStacks = stacks
        .where((s) => s.pubkey != kFranzapPubkey)
        .toList();

    franzapStacks.shuffle(random);
    otherStacks.sort((a, b) => b.event.createdAt.compareTo(a.event.createdAt));
    return [...franzapStacks, ...otherStacks];
  }
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

    // Get platform from package manager for filtering
    final platform = ref.read(packageManagerProvider.notifier).platform;

    // Query 30 stacks WITHOUT loading apps (fast, from local storage)
    final appStacksState = ref.watch(
      query<AppStack>(
        limit: 30,
        tags: {
          '#f': {platform},
        },
        source: const LocalAndRemoteSource(relays: 'social'),
        subscriptionPrefix: 'app-stack',
        schemaFilter: appStackEventFilter,
      ),
    );

    // Get contact list for sorting
    final contactListState = signedInPubkey != null
        ? ref.watch(
            query<ContactList>(
              authors: {signedInPubkey},
              limit: 1,
              source: const LocalAndRemoteSource(
                relays: 'social',
                stream: false,
                cachedFor: Duration(hours: 1),
              ),
            ),
          )
        : null;

    final followingPubkeys =
        contactListState?.models.firstOrNull?.followingPubkeys;

    final allStacks = switch (appStacksState) {
      StorageData(:final models) => models.toList(),
      _ => <AppStack>[],
    };

    if (allStacks.isEmpty) {
      return _buildSkeleton(context);
    }

    // Sort stacks
    final sortedStacks = _sortStacks(
      allStacks,
      signedInPubkey: signedInPubkey,
      followingPubkeys: followingPubkeys,
    );

    // Only show up to visibleCount
    final displayedStacks = sortedStacks.take(visibleCount.value).toList();

    // Batch load author profiles for displayed stacks
    final authorPubkeys = displayedStacks.map((s) => s.event.pubkey).toSet();
    final authorsState = ref.watch(
      query<Profile>(
        authors: authorPubkeys,
        source: const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          cachedFor: Duration(hours: 2),
        ),
        subscriptionPrefix: 'app-stack-authors',
      ),
    );
    final authorsMap = {
      for (final profile in authorsState.models) profile.pubkey: profile,
    };
    final isAuthorsLoading = authorsState is StorageLoading;

    // Helper to check if a specific author is loading (loading AND not in cache)
    bool isAuthorLoading(String pubkey) =>
        isAuthorsLoading && authorsMap[pubkey] == null;

    // Batch load preview apps for displayed stacks (3 per stack)
    final allPreviewIdentifiers = <String>{};
    final stackPreviewIds = <String, List<String>>{};
    for (final stack in displayedStacks) {
      final ids = _getPreviewIdentifiers(stack);
      stackPreviewIds[stack.id] = ids;
      allPreviewIdentifiers.addAll(ids);
    }

    final previewAppsState = allPreviewIdentifiers.isNotEmpty
        ? ref.watch(
            query<App>(
              tags: {'#d': allPreviewIdentifiers},
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
        app.identifier: app,
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

    // 2-row horizontal scroll layout
    final numColumns = (displayedStacks.length + 1) ~/ 2;

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
                    // Top item
                    if (col * 2 < displayedStacks.length)
                      _StackCard(
                        stack: displayedStacks[col * 2],
                        author:
                            authorsMap[displayedStacks[col * 2].event.pubkey],
                        isAuthorLoading: isAuthorLoading(
                          displayedStacks[col * 2].event.pubkey,
                        ),
                        previewIdentifiers:
                            stackPreviewIds[displayedStacks[col * 2].id] ?? [],
                        appsMap: appsMap,
                      ),
                    // Bottom item
                    if (col * 2 + 1 < displayedStacks.length) ...[
                      const SizedBox(height: 10),
                      _StackCard(
                        stack: displayedStacks[col * 2 + 1],
                        author:
                            authorsMap[displayedStacks[col * 2 + 1]
                                .event
                                .pubkey],
                        isAuthorLoading: isAuthorLoading(
                          displayedStacks[col * 2 + 1].event.pubkey,
                        ),
                        previewIdentifiers:
                            stackPreviewIds[displayedStacks[col * 2 + 1].id] ??
                            [],
                        appsMap: appsMap,
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
                      _SkeletonStackCard(),
                      SizedBox(height: 10),
                      _SkeletonStackCard(),
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

/// Skeleton card for loading state
class _SkeletonStackCard extends StatelessWidget {
  const _SkeletonStackCard();

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
class _StackCard extends StatelessWidget {
  const _StackCard({
    required this.stack,
    required this.author,
    required this.previewIdentifiers,
    required this.appsMap,
    this.isAuthorLoading = false,
  });

  final AppStack stack;
  final Profile? author;
  final List<String> previewIdentifiers;
  final Map<String, App> appsMap;
  final bool isAuthorLoading;

  @override
  Widget build(BuildContext context) {
    final totalApps = _getRawAppTagValues(stack).length;

    // Resolve preview apps from the pre-loaded map
    final previewApps = previewIdentifiers
        .map((id) => appsMap[id])
        .whereType<App>()
        .toList();

    final profileStyle = context.textTheme.bodySmall?.copyWith(
      color: AppColors.darkOnSurfaceSecondary,
      fontSize: 13,
    );

    return GestureDetector(
      onTap: () {
        final segments = GoRouterState.of(context).uri.pathSegments;
        final first = segments.isNotEmpty ? segments.first : 'search';
        final naddr = Utils.encodeShareableIdentifier(
          AddressInput(
            identifier: stack.identifier,
            author: stack.pubkey,
            kind: stack.event.kind,
            relays: const [],
          ),
        );
        context.push('/$first/stack/$naddr');
      },
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
            _FadingText(
              stack.name ?? stack.identifier,
              style: context.textTheme.titleMedium?.copyWith(
                fontFamily: 'Inter',
                fontSize: (context.textTheme.titleMedium?.fontSize ?? 16) * 0.9,
              ),
            ),
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
  const _FadingText(this.text, {required this.style});

  final String text;
  final TextStyle? style;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final textPainter = TextPainter(
          text: TextSpan(text: text, style: style),
          maxLines: 1,
          textDirection: TextDirection.ltr,
        )..layout(maxWidth: double.infinity);

        final isOverflowing = textPainter.width > constraints.maxWidth;

        final textWidget = Text(
          text,
          style: style,
          maxLines: 1,
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
