import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/screens/settings_screen.dart';
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
    final signedInUser = ref.watch(signedInUserProvider);

    return switch (data) {
      AsyncData<List<User>>(value: final trustedUsers) => Builder(
          builder: (context) {
            if (trustedUsers.isEmpty) {
              return Text(
                  'No trusted users between you and the signer. (This may be a service error)');
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
        Center(child: Text('Error checking web of trust: $error')),
      // Loading state
      _ => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              children: [
                Text('Loading web of trust...'),
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
  final users = await ref.users.userAdapter.getTrusted(arg.from, arg.to);
  return users.toSet().toList();
});
