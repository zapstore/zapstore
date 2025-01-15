import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/screens/settings_screen.dart';
import 'package:zapstore/widgets/users_rich_text.dart';

class FollowsWhoFollowContainer extends HookConsumerWidget {
  const FollowsWhoFollowContainer({
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
    final signedInUser = ref.watch(signedInUserProvider);

    return switch (data) {
      AsyncData<List<User>>(value: final trustedUsers) => Builder(
          builder: (context) {
            if (trustedUsers.isEmpty) {
              return Text('You don\'t follow any users who follow the signer.');
            }

            return UsersRichText(
              users: trustedUsers,
              signedInUser: signedInUser,
              onlyUseCommaSeparator: true,
              trailingText: ' and others follow this signer on nostr.',
            );
          },
        ),
      AsyncError(:final error) =>
        Center(child: Text('Error connecting with web of trust DVM: $error')),
      // Loading state
      _ => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              children: [
                Text('Loading profiles in your web of trust...'),
                Gap(10),
                SmallCircularProgressIndicator(),
              ],
            ),
          ),
        )
    };
  }
}

final followsWhoFollowProvider = FutureProvider.autoDispose
    .family<List<User>, ({String from, String to})>((ref, arg) async {
  final _ = ref.watch(signedInUserProvider);
  final users =
      await ref.users.userAdapter.getFollowsWhoFollow(arg.from, arg.to);
  return users.toSet().toList();
});
