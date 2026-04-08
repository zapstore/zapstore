import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/nostr_route.dart';
import 'package:zapstore/widgets/common/profile_avatar.dart';

const _kAvatarSize = 24.0;
const _kOverlap = 8.0;

class StackedByRow extends ConsumerWidget {
  const StackedByRow({super.key, required this.app});

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);
    if (signedInPubkey == null) return const SizedBox.shrink();

    final contactListState = ref.watch(
      query<ContactList>(
        authors: {signedInPubkey},
        limit: 1,
        source: const LocalSource(),
      ),
    );
    final followingPubkeys =
        contactListState.models.firstOrNull?.followingPubkeys;
    if (followingPubkeys == null || followingPubkeys.isEmpty) {
      return const SizedBox.shrink();
    }

    // Followed users who included this app in a stack
    final stacksState = ref.watch(
      query<AppStack>(
        tags: {
          '#a': {app.id},
        },
        source: const LocalSource(),
        subscriptionPrefix: 'app-stacked-by-${app.identifier}',
      ),
    );
    final followedPubkeys = stacksState.models
        .map((s) => s.event.pubkey)
        .where((pk) => pk != signedInPubkey && followingPubkeys.contains(pk))
        .toSet();

    // Followed users who zapped this app
    final zapsState = ref.watch(
      query<Zap>(
        tags: app.event.addressableIdTagMap,
        source: const LocalSource(),
        subscriptionPrefix: 'app-stacked-by-zaps-${app.identifier}',
      ),
    );
    for (final zap in zapsState.models) {
      final pk = zap.event.metadata['author'] as String?;
      if (pk != null && pk != signedInPubkey && followingPubkeys.contains(pk)) {
        followedPubkeys.add(pk);
      }
    }

    if (followedPubkeys.isEmpty) return const SizedBox.shrink();

    final profilesState = ref.watch(
      query<Profile>(
        authors: followedPubkeys,
        source: const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          cachedFor: Duration(hours: 2),
        ),
        subscriptionPrefix: 'app-stacked-by-profiles-${app.identifier}',
      ),
    );

    final profiles = profilesState.models;
    if (profiles.isEmpty) return const SizedBox.shrink();

    final baseStyle = context.textTheme.bodyMedium?.copyWith(
      fontSize: 14,
      color: Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.7),
    );

    return Padding(
      padding: const EdgeInsets.only(top: 2, bottom: 4),
      child: Row(
        children: [
          Text('Stacked or zapped by ', style: baseStyle),
          Expanded(
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: _OverlappingAvatars(
                profiles: profiles,
                onTap: (profile) => pushUser(context, profile.pubkey),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _OverlappingAvatars extends StatelessWidget {
  const _OverlappingAvatars({required this.profiles, this.onTap});

  final List<Profile> profiles;
  final void Function(Profile)? onTap;

  @override
  Widget build(BuildContext context) {
    final totalWidth =
        _kAvatarSize + (profiles.length - 1) * (_kAvatarSize - _kOverlap);

    return SizedBox(
      width: totalWidth,
      height: _kAvatarSize,
      child: Stack(
        children: [
          for (var i = profiles.length - 1; i >= 0; i--)
            Positioned(
              left: i * (_kAvatarSize - _kOverlap),
              child: GestureDetector(
                onTap: onTap != null ? () => onTap!(profiles[i]) : null,
                child: Container(
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: Theme.of(context).scaffoldBackgroundColor,
                      width: 1.5,
                    ),
                  ),
                  child: ProfileAvatar(
                    profile: profiles[i],
                    radius: (_kAvatarSize - 3) / 2,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
