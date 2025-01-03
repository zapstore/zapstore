import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:purplebase/purplebase.dart' as base;
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/users_rich_text.dart';

class WebOfTrustContainer extends HookConsumerWidget {
  const WebOfTrustContainer({
    super.key,
    required this.fromNpub,
    required this.toNpub,
  });

  final String fromNpub;
  final String toNpub;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final data =
        ref.watch(followsWhoFollowProvider((from: fromNpub, to: toNpub)));

    return switch (data) {
      AsyncData<List<User>>(value: final trustedUsers) => Builder(
          builder: (context) {
            final hasUser =
                trustedUsers.firstWhereOrNull((u) => u.npub == fromNpub) !=
                    null;
            final trustedUsersWithoutLoggedInUser =
                trustedUsers.where((u) => u.npub != fromNpub).toList();
            if (trustedUsersWithoutLoggedInUser.isEmpty) {
              return Text(
                  'No trusted users between you and the signer. (This may be a service error)');
            }

            return UsersRichText(
              preSpan: hasUser && fromNpub != kFranzapPubkey.npub
                  ? TextSpan(text: 'You, ')
                  : null,
              trailingText: ' and others follow this signer on nostr.',
              users: trustedUsersWithoutLoggedInUser,
            );
          },
        ),
      AsyncError(:final error) =>
        Center(child: Text('Error checking web of trust: $error')),
      // Loading state
      _ => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              children: [
                Text('Loading web of trust...'),
                Gap(10),
                SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(),
                ),
              ],
            ),
          ),
        )
    };
  }
}

final followsWhoFollowProvider = FutureProvider.autoDispose
    .family<List<User>, ({String from, String to})>((ref, arg) async {
  final _ =
      ref.settings.watchOne('_', alsoWatch: (_) => {_.user}).model!.user.value;
  final users = await ref.users.userAdapter.getTrusted(arg.from, arg.to);
  return users.toSet().toList();
});
