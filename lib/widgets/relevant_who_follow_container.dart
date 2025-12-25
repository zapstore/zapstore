import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/common/profile_avatar.dart';
import 'package:zapstore/widgets/profiles_rich_text.dart';

class RelevantWhoFollowContainer extends HookConsumerWidget {
  const RelevantWhoFollowContainer({
    super.key,
    required this.app,
    this.loadingText = 'Checking reputation...',
    this.size,
  });

  final String loadingText;
  final App app;
  final double? size;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final baseStyle = (size != null
        ? context.textTheme.bodyMedium?.copyWith(
            fontSize: size,
            color: Theme.of(
              context,
            ).colorScheme.onSurface.withValues(alpha: 0.7),
          )
        : context.textTheme.bodyMedium?.copyWith(
            color: Theme.of(
              context,
            ).colorScheme.onSurface.withValues(alpha: 0.7),
          ));
    final boldStyle = baseStyle?.copyWith(fontWeight: FontWeight.w600);

    // Get signed-in user's pubkey
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);

    // Query author profile from local storage (should already be preloaded)
    final authorState = ref.watch(
      query<Profile>(authors: {app.pubkey}, source: const LocalSource()),
    );
    final author = authorState.models.firstOrNull;

    // If author not loaded yet, show loading
    if (author == null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(loadingText, style: baseStyle),
              const Gap(8),
              const SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 3),
              ),
            ],
          ),
        ),
      );
    }

    final targetNpub = author.npub;

    // Query preloaded zaps for the app (LocalSource only)
    final zapsState = ref.watch(
      query<Zap>(
        tags: app.event.addressableIdTagMap,
        source: const LocalSource(),
        and: (zap) => {zap.author, zap.zapRequest},
        subscriptionPrefix: 'trust-zaps',
      ),
    );

    // Also query zaps on the latest metadata if available
    final latestMetadata = app.latestFileMetadata;
    final metadataZapsState = latestMetadata != null
        ? ref.watch(
            query<Zap>(
              tags: {
                '#e': {latestMetadata.id},
              },
              source: const LocalSource(),
              and: (zap) => {zap.author, zap.zapRequest},
              subscriptionPrefix: 'trust-metadata-zaps',
            ),
          )
        : null;

    // Combine all zaps
    final allZaps = {...zapsState.models, ...?metadataZapsState?.models};

    final data = ref.watch(relevantWhoFollowProvider((to: targetNpub)));

    Widget buildBody({
      required bool userFollowsTarget,
      required Set<String>? followingPubkeys,
    }) {
      // Extract zap author profiles
      // 1. Check if signed-in user zapped
      // 2. Get zappers that the user follows
      bool userZapped = false;
      final followedZappers = <Profile>[];
      final seenPubkeys = <String>{};

      if (allZaps.isNotEmpty) {
        for (final zap in allZaps) {
          final requestAuthor = zap.zapRequest.value?.author.value;
          final walletAuthor = zap.author.value;
          final chosenAuthor = requestAuthor ?? walletAuthor;
          if (chosenAuthor == null) continue;

          final pubkey = chosenAuthor.pubkey;

          // Check if signed-in user zapped
          if (signedInPubkey != null && pubkey == signedInPubkey) {
            userZapped = true;
            continue; // Don't add to followedZappers list
          }

          // Add followed zappers (exclude duplicates)
          if (followingPubkeys != null &&
              followingPubkeys.contains(pubkey) &&
              !seenPubkeys.contains(pubkey)) {
            seenPubkeys.add(pubkey);
            followedZappers.add(chosenAuthor);
          }
        }
      }

      return switch (data) {
        AsyncData<List<Profile>>(value: final users) => Builder(
          builder: (context) {
            // Find relevant who follow, remove the target that comes included
            final relevantWhoFollow = users
                .take(5)
                .where(
                  (u) => u.pubkey.encodeShareable(type: 'npub') != targetNpub,
                )
                .toList();

            if (relevantWhoFollow.isEmpty &&
                !userFollowsTarget &&
                followedZappers.isEmpty) {
              return Text(
                'No reputation information found for this publisher.',
                style: baseStyle,
              );
            }

            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Main sentence: "(App) is published by (profile) (view profile link) and zapped by ..."
                _buildPublishedBySection(
                  context,
                  author,
                  baseStyle,
                  boldStyle,
                  userZapped: userZapped,
                  followedZappers: followedZappers.take(4).toList(),
                ),
                const Gap(16),
                // Reputation section
                if (relevantWhoFollow.isNotEmpty || userFollowsTarget) ...[
                  ProfilesRichText(
                    profiles: relevantWhoFollow,
                    leadingText: userFollowsTarget ? 'You, ' : null,
                    commasOnly: true,
                    trailingText:
                        ' and others follow ${author.nameOrNpub} on Nostr.',
                    textStyle: baseStyle,
                    avatarRadius: 10,
                  ),
                  const Gap(8),
                ] else if (!userFollowsTarget) ...[
                  Text('You are not following them.', style: baseStyle),
                  const Gap(8),
                ],
                // Disclaimer
                Text(
                  'These are not endorsements of the ${app.name} app.',
                  style: baseStyle?.copyWith(fontStyle: FontStyle.italic),
                ),
              ],
            );
          },
        ),
        AsyncError(:final error) => Center(
          child: Text(
            error.toString().replaceFirst('Exception: ', ''),
            style: baseStyle,
          ),
        ),
        // Loading state
        _ => Center(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(loadingText, style: baseStyle),
                const Gap(8),
                const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 3),
                ),
              ],
            ),
          ),
        ),
      };
    }

    if (signedInPubkey == null) {
      return buildBody(userFollowsTarget: false, followingPubkeys: null);
    }

    return Consumer(
      builder: (context, ref, _) {
        final contactListState = ref.watch(
          query<ContactList>(
            authors: {signedInPubkey},
            limit: 1,
            source: const LocalAndRemoteSource(relays: 'social', stream: false),
            subscriptionPrefix: 'user-contacts',
          ),
        );

        final targetPubkey = targetNpub.decodeShareable();
        final contactList = contactListState.models.firstOrNull;
        final followingPubkeys = contactList?.followingPubkeys;
        final userFollowsTarget =
            followingPubkeys?.contains(targetPubkey) ?? false;

        return buildBody(
          userFollowsTarget: userFollowsTarget,
          followingPubkeys: followingPubkeys,
        );
      },
    );
  }

  Widget _buildPublishedBySection(
    BuildContext context,
    Profile author,
    TextStyle? baseStyle,
    TextStyle? boldStyle, {
    required bool userZapped,
    required List<Profile> followedZappers,
  }) {
    final targetNpub = author.npub;
    final avatarSize = (size ?? 14) * 1.4;
    final hasZapInfo = userZapped || followedZappers.isNotEmpty;

    final spans = <InlineSpan>[
      TextSpan(text: '${app.name} is published by ', style: baseStyle),
      WidgetSpan(
        alignment: PlaceholderAlignment.middle,
        child: Padding(
          padding: const EdgeInsets.only(right: 4),
          child: SizedBox(
            width: avatarSize,
            height: avatarSize,
            child: ProfileAvatar(profile: author, radius: avatarSize / 2),
          ),
        ),
      ),
      TextSpan(text: author.nameOrNpub, style: boldStyle),
      TextSpan(text: ' (', style: baseStyle),
      TextSpan(
        text: 'view profile on Nostr',
        style: baseStyle?.copyWith(
          color: Colors.white,
          fontWeight: FontWeight.bold,
          decoration: TextDecoration.underline,
        ),
        recognizer: TapGestureRecognizer()
          ..onTap = () async {
            final url = Uri.parse('https://npub.world/$targetNpub');
            if (await canLaunchUrl(url)) {
              await launchUrl(url, mode: LaunchMode.externalApplication);
            }
          },
      ),
      TextSpan(text: ')', style: baseStyle),
    ];

    // Add zap info if present: " and was zapped by you and (z1)"
    if (hasZapInfo) {
      spans.add(TextSpan(text: ' and was zapped by ', style: baseStyle));

      // Add "you" if user zapped
      if (userZapped) {
        spans.add(TextSpan(text: 'you', style: boldStyle));
        // Add separator: "and" if only 1 more, ", " if more than 1 more
        if (followedZappers.length == 1) {
          spans.add(TextSpan(text: ' and ', style: baseStyle));
        } else if (followedZappers.length > 1) {
          spans.add(TextSpan(text: ', ', style: baseStyle));
        }
      }

      // Add followed zappers with avatars
      for (int i = 0; i < followedZappers.length; i++) {
        final zapper = followedZappers[i];
        final isLast = i == followedZappers.length - 1;
        final isSecondToLast = i == followedZappers.length - 2;

        spans.add(
          WidgetSpan(
            alignment: PlaceholderAlignment.middle,
            child: Padding(
              padding: const EdgeInsets.only(right: 4),
              child: SizedBox(
                width: avatarSize,
                height: avatarSize,
                child: ProfileAvatar(profile: zapper, radius: avatarSize / 2),
              ),
            ),
          ),
        );
        spans.add(TextSpan(text: zapper.nameOrNpub, style: boldStyle));

        // Add separator
        if (!isLast) {
          if (isSecondToLast && (!userZapped || followedZappers.length == 1)) {
            // "and" before last item when no user prefix or only 2 total
            spans.add(TextSpan(text: ' and ', style: baseStyle));
          } else if (isSecondToLast) {
            // ", and" (Oxford comma) before last when user + multiple
            spans.add(TextSpan(text: ', and ', style: baseStyle));
          } else {
            spans.add(TextSpan(text: ', ', style: baseStyle));
          }
        }
      }
    }

    spans.add(TextSpan(text: '.', style: baseStyle));

    return Text.rich(TextSpan(children: spans));
  }
}

final relevantWhoFollowProvider = FutureProvider.autoDispose
    .family<List<Profile>, ({String to})>((ref, arg) async {
      // Require active signer and profile to submit the request
      final signer = ref.read(Signer.activeSignerProvider);
      final pubkey = ref.watch(Signer.activePubkeyProvider);
      if (signer == null || pubkey == null) {
        throw 'Not signed in';
      }

      // Build and sign a reputation verification request
      final partial = PartialVerifyReputationRequest(
        source: pubkey.encodeShareable(type: 'npub'),
        target: arg.to,
        limit: 7,
      );
      final request = await partial.signWith(signer);

      // Execute against the 'vertex' relay group and await response
      final response = await request.run('vertex');

      if (response is VerifyReputationResponse) {
        // Fetch corresponding profiles for the returned pubkeys
        final storage = ref.read(storageNotifierProvider.notifier);
        final profiles = await storage.query(
          Request<Profile>([RequestFilter<Profile>(authors: response.pubkeys)]),
          source: const LocalAndRemoteSource(relays: 'social', stream: false),
        );
        return profiles;
      }

      // Handle DVM error responses
      if (response is DVMError) {
        throw Exception(response.status ?? 'DVM service error');
      }

      // Handle null (no response received - timeout or connection issue)
      if (response == null) {
        throw Exception('No response from reputation service');
      }

      throw Exception('Unexpected response from service');
    });
