import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/rounded_image.dart';

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

class UsersRichText extends StatelessWidget {
  const UsersRichText({
    super.key,
    this.preSpan,
    this.trailingText,
    required this.users,
  });

  final TextSpan? preSpan;
  final String? trailingText;
  final List<User> users;

  @override
  Widget build(BuildContext context) {
    return RichText(
      text: TextSpan(
        children: [
          if (preSpan != null) preSpan!,
          for (final user in users)
            TextSpan(
              style: TextStyle(height: 1.6),
              children: [
                WidgetSpan(
                  alignment: PlaceholderAlignment.middle,
                  child: Wrap(
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      RoundedImage(url: user.avatarUrl, size: 20),
                      Text(
                        ' ${user.nameOrNpub}',
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      Text(users.indexOf(user) == users.length - 1
                          ? ''
                          : (users.indexOf(user) == users.length - 2
                              ? ' and '
                              : ', ')),
                    ],
                  ),
                ),
                if (users.indexOf(user) == users.length - 1)
                  TextSpan(text: trailingText, style: TextStyle(fontSize: 15))
              ],
            ),
        ],
      ),
    );
  }
}

final followsWhoFollowProvider = FutureProvider.autoDispose
    .family<List<User>, ({String from, String to})>((ref, arg) async {
  final _ =
      ref.settings.watchOne('_', alsoWatch: (_) => {_.user}).model!.user.value;
  final users = await ref.users.userAdapter.getTrusted(arg.from, arg.to);
  return users.toSet().toList();
});
