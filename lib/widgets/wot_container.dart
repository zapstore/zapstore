import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/rounded_image.dart';

class WebOfTrustContainer extends HookConsumerWidget {
  const WebOfTrustContainer({
    super.key,
    this.user,
    String? npub,
    required this.npub2,
  });

  final User? user;
  final String npub2;
  String get npub => user?.npub ?? kFranzapNpub;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return switch (
        ref.watch(followsWhoFollowProvider((from: npub, to: npub2)))) {
      AsyncData<List<User>>(value: final trustedUsers) => Builder(
          builder: (context) {
            final hasUser =
                trustedUsers.firstWhereOrNull((u) => u.npub == npub) != null;
            final trustedUsersWithoutUser =
                trustedUsers.where((u) => u.npub != npub).toList();
            return RichText(
              text: TextSpan(
                children: [
                  if (hasUser && npub != kFranzapNpub) TextSpan(text: 'You, '),
                  for (final trustedUser in trustedUsersWithoutUser)
                    TextSpan(
                      style: TextStyle(height: 1.6),
                      children: [
                        WidgetSpan(
                          alignment: PlaceholderAlignment.middle,
                          child: Wrap(
                            crossAxisAlignment: WrapCrossAlignment.center,
                            children: [
                              RoundedImage(
                                  url: trustedUser.avatarUrl, size: 20),
                              Text(
                                ' ${trustedUser.nameOrNpub}${trustedUsersWithoutUser.indexOf(trustedUser) == trustedUsersWithoutUser.length - 1 ? '' : ',  '}',
                                style: TextStyle(fontWeight: FontWeight.bold),
                              ),
                            ],
                          ),
                        ),
                        if (trustedUsersWithoutUser.indexOf(trustedUser) ==
                            trustedUsersWithoutUser.length - 1)
                          TextSpan(
                            text: ' and others follow this signer on nostr.',
                          )
                      ],
                    ),
                ],
              ),
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
