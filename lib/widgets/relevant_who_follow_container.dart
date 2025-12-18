import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/profiles_rich_text.dart';

class RelevantWhoFollowContainer extends HookConsumerWidget {
  const RelevantWhoFollowContainer({
    super.key,
    required this.toNpub,
    this.loadingText = 'Checking reputation...',
    this.trailingText = '',
    this.size,
  });

  final String loadingText;
  final String trailingText;
  final String toNpub;
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

    // Get signed-in user's pubkey and contact list
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);

    final data = ref.watch(relevantWhoFollowProvider((to: toNpub)));

    Widget buildBody(bool userFollowsTarget) => switch (data) {
      AsyncData<List<Profile>>(value: final users) => Builder(
        builder: (context) {
          // Find relevant who follow, remove the target that comes included
          final relevantWhoFollow = users
              .where((u) => u.pubkey.encodeShareable(type: 'npub') != toNpub)
              .toList();

          if (relevantWhoFollow.isEmpty && !userFollowsTarget) {
            return Text(
              'No reputation information found for this publisher.',
              style: baseStyle,
            );
          }

          // If user follows but no Vertex followers, show a simple message
          if (relevantWhoFollow.isEmpty && userFollowsTarget) {
            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                RichText(
                  text: TextSpan(
                    text: 'You follow this publisher. ',
                    style: baseStyle,
                    children: [TextSpan(text: trailingText)],
                  ),
                ),
                const Gap(8),
                _buildViewProfileLink(context, toNpub, baseStyle),
              ],
            );
          }

          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (!userFollowsTarget) ...[
                Text('You are not following them.', style: baseStyle),
                const Gap(8),
              ],
              ProfilesRichText(
                profiles: relevantWhoFollow,
                leadingText: userFollowsTarget ? 'You, ' : null,
                commasOnly: true,
                trailingText: trailingText,
                textStyle: baseStyle,
                avatarRadius: 10,
              ),
              const Gap(8),
              _buildViewProfileLink(context, toNpub, baseStyle),
            ],
          );
        },
      ),
      AsyncError() => Center(
        child: Text(
          'Reputation service is unreachable. Try again later.',
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

    if (signedInPubkey == null) return buildBody(false);

    return Consumer(
      builder: (context, ref, _) {
        final contactListState = ref.watch(
          query<ContactList>(
            authors: {signedInPubkey},
            limit: 1,
            source: LocalAndRemoteSource(relays: 'social', stream: false),
            subscriptionPrefix: 'user-contacts',
          ),
        );

        final targetPubkey = toNpub.decodeShareable();
        final contactList = contactListState.models.firstOrNull;
        final userFollowsTarget =
            contactList?.followingPubkeys.contains(targetPubkey) ?? false;

        return buildBody(userFollowsTarget);
      },
    );
  }

  Widget _buildViewProfileLink(
    BuildContext context,
    String npub,
    TextStyle? baseStyle,
  ) {
    return RichText(
      text: TextSpan(
        text: 'View profile on nostr',
        style: baseStyle?.copyWith(
          color: Colors.white,
          fontWeight: FontWeight.bold,
          decoration: TextDecoration.underline,
        ),
        recognizer: TapGestureRecognizer()
          ..onTap = () async {
            final url = Uri.parse('https://npub.world/$npub');
            if (await canLaunchUrl(url)) {
              await launchUrl(url, mode: LaunchMode.externalApplication);
            }
          },
      ),
    );
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
      final response = await request
          .run('vertex')
          .timeout(Duration(seconds: 6));

      if (response is VerifyReputationResponse) {
        // Fetch corresponding profiles for the returned pubkeys
        final storage = ref.read(storageNotifierProvider.notifier);
        final profiles = await storage.query(
          Request<Profile>([RequestFilter<Profile>(authors: response.pubkeys)]),
          source: const LocalAndRemoteSource(relays: 'social', stream: false),
        );
        return profiles;
      }

      throw Exception('Bad response from service');
    });
