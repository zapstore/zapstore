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
import 'common/profile_avatar.dart';

/// App Stack Container - horizontally scrollable 2-row grid of stack cards
class AppStackContainer extends HookConsumerWidget {
  const AppStackContainer({super.key, this.showSkeleton = false});

  final bool showSkeleton;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scrollController = useScrollController();

    if (showSkeleton) {
      return _buildSkeleton(context);
    }

    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);

    final appStacksState = ref.watch(
      query<AppStack>(
        limit: 50,
        and: (pack) => {pack.apps},
        source: LocalAndRemoteSource(stream: true, relays: 'social'),
        andSource: const LocalAndRemoteSource(
          relays: 'AppCatalog',
          stream: false,
        ),
        subscriptionPrefix: 'app-stack',
        schemaFilter: appStackEventFilter,
      ),
    );

    // Query contact list for signed-in user
    final contactListState = signedInPubkey != null
        ? ref.watch(
            query<ContactList>(
              authors: {signedInPubkey},
              limit: 1,
              source: const LocalAndRemoteSource(
                relays: 'social',
                stream: false,
              ),
              subscriptionPrefix: 'user-contacts-stacks',
            ),
          )
        : null;

    final followingPubkeys =
        contactListState?.models.firstOrNull?.followingPubkeys;

    final stacks = switch (appStacksState) {
      StorageData(:final models) => models.toList(),
      _ => <AppStack>[],
    };

    if (stacks.isEmpty) {
      return _buildSkeleton(context);
    }

    // Sort stacks: followed profiles first (or franzap if not signed in), then by recency
    final sortedStacks = _sortStacks(
      stacks,
      signedInPubkey: signedInPubkey,
      followingPubkeys: followingPubkeys,
    );

    // 2-row horizontal scroll list with partial next card visible (Android UX pattern)
    return SingleChildScrollView(
      controller: scrollController,
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.only(left: 12, right: 12),
      clipBehavior: Clip.none,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (int i = 0; i < sortedStacks.length; i += 2)
            Padding(
              padding: EdgeInsets.only(
                right:
                    i == sortedStacks.length - 2 || i == sortedStacks.length - 1
                    ? 0
                    : 10,
              ),
              child: SizedBox(
                width: 160,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    _StackCard(stack: sortedStacks[i]),
                    if (i + 1 < sortedStacks.length) ...[
                      const SizedBox(height: 10),
                      _StackCard(stack: sortedStacks[i + 1]),
                    ],
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }

  List<AppStack> _sortStacks(
    List<AppStack> stacks, {
    String? signedInPubkey,
    Set<String>? followingPubkeys,
  }) {
    final random = Random();

    if (signedInPubkey != null &&
        followingPubkeys != null &&
        followingPubkeys.isNotEmpty) {
      // Signed in: followed profiles first, then by recency
      final followed = <AppStack>[];
      final others = <AppStack>[];

      for (final stack in stacks) {
        if (followingPubkeys.contains(stack.pubkey)) {
          followed.add(stack);
        } else {
          others.add(stack);
        }
      }

      // Sort both lists by recency
      followed.sort((a, b) => b.event.createdAt.compareTo(a.event.createdAt));
      others.sort((a, b) => b.event.createdAt.compareTo(a.event.createdAt));

      return [...followed, ...others];
    } else {
      // Not signed in: put one random franzap stack first, then by recency
      final franzapStacks = stacks
          .where((s) => s.pubkey == kFranzapPubkey)
          .toList();
      final otherStacks = stacks
          .where((s) => s.pubkey != kFranzapPubkey)
          .toList();

      // Sort others by recency
      otherStacks.sort(
        (a, b) => b.event.createdAt.compareTo(a.event.createdAt),
      );

      if (franzapStacks.isNotEmpty) {
        // Pick one random franzap stack
        final randomFranzap =
            franzapStacks[random.nextInt(franzapStacks.length)];
        return [randomFranzap, ...otherStacks];
      }

      return otherStacks;
    }
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

/// Skeleton card for loading state - matches vertical layout
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
          Row(
            children: List.generate(
              3,
              (index) => Expanded(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 2),
                  child: AspectRatio(
                    aspectRatio: 1,
                    child: Container(
                      decoration: BoxDecoration(
                        color: AppColors.darkSkeletonBase,
                        borderRadius: BorderRadius.circular(8),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Individual stack card with vertical layout:
/// Stack Name, by (profile), horizontal app icons row, total apps
class _StackCard extends HookConsumerWidget {
  const _StackCard({required this.stack});

  final AppStack stack;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Query author profile
    final authorState = ref.watch(
      query<Profile>(
        authors: {stack.event.pubkey},
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

    final stackApps = stack.apps.toList();
    final totalApps = stackApps.length;

    // Pick preview apps deterministically (stable UI) and only fetch those from AppCatalog
    final previewBaseApps = (() {
      final shuffled = List<App>.from(stackApps)
        ..shuffle(Random(stack.id.hashCode));
      return shuffled.take(totalApps > 4 ? 3 : 4).toList();
    })();
    final previewIdentifiers = previewBaseApps
        .map((app) => app.identifier)
        .toSet();

    final appsState = ref.watch(
      query<App>(
        tags: {'#d': previewIdentifiers},
        limit: previewIdentifiers.length,
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'app-stack-preview-apps-${stack.id}',
      ),
    );

    final appsMap = {for (final app in appsState.models) app.identifier: app};
    final previewApps = previewBaseApps
        .map((app) => appsMap[app.identifier] ?? app)
        .toList();

    // Styling like app_card.dart (slightly larger for visibility)
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
            // Stack name with conditional fade out
            _FadingText(
              stack.name ?? stack.identifier,
              style: context.textTheme.titleMedium?.copyWith(
                fontFamily: 'Inter',
                fontSize: (context.textTheme.titleMedium?.fontSize ?? 16) * 0.9,
              ),
            ),
            const SizedBox(height: 6),
            // Author row: avatar + Name
            Row(
              children: [
                ProfileAvatar(profile: author, radius: 9),
                const SizedBox(width: 5),
                Expanded(
                  child: Text(
                    author?.nameOrNpub ?? '',
                    style: profileStyle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            // Horizontal row of app icons
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
    final hasMore = totalApps > 4;
    final extraCount = totalApps - 3;

    return Row(
      children: List.generate(4, (index) {
        // Show "+X" indicator in the 4th slot if there are more than 4 apps
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

/// Individual app icon tile for the horizontal row
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

/// Text widget that only applies fade-out effect when text overflows
class _FadingText extends HookWidget {
  const _FadingText(this.text, {required this.style});

  final String text;
  final TextStyle? style;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        // Measure if text would overflow
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

        // Apply fade effect only when overflowing
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
