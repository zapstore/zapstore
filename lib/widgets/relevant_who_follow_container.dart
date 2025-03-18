import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/screens/settings_screen.dart';
import 'package:zapstore/widgets/users_rich_text.dart';

class RelevantWhoFollowContainer extends HookConsumerWidget {
  const RelevantWhoFollowContainer({
    super.key,
    required this.toNpub,
  });

  final String toNpub;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signedInUser = ref.watch(signedInUserProvider);
    final data = ref.watch(relevantWhoFollowProvider((to: toNpub)));

    return switch (data) {
      AsyncData<List<User>>(value: final trustedUsers) => Builder(
          builder: (context) {
            if (trustedUsers.isEmpty) {
              return Text(
                  'No reputable profiles follow the signer. Make sure you know the signer.');
            }

            return UsersRichText(
              users: trustedUsers,
              signedInUser: signedInUser,
              onlyUseCommaSeparator: true,
              trailingText: ' and others follow this signer on nostr.',
            );
          },
        ),
      AsyncError(:final error) => Center(child: Text(error.toString())),
      // Loading state
      _ => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              children: [
                Text('Checking reputation...'),
                Gap(10),
                SmallCircularProgressIndicator(),
              ],
            ),
          ),
        )
    };
  }
}

final relevantWhoFollowProvider = FutureProvider.autoDispose
    .family<List<User>, ({String to})>((ref, arg) async {
  final signedInUser = ref.watch(signedInUserProvider);
  final users = await ref.users.userAdapter
      .getRelevantWhoFollow(signedInUser!.npub, arg.to);
  return users.toSet().toList();
});
