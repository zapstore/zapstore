import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/rounded_image.dart';

const franzapsNpub =
    'npub1wf4pufsucer5va8g9p0rj5dnhvfeh6d8w0g6eayaep5dhps6rsgs43dgh9';

class WebOfTrustContainer extends HookConsumerWidget {
  const WebOfTrustContainer({
    super.key,
    this.user,
    String? npub,
    required this.npub2,
  });

  final User? user;
  final String npub2;
  String get npub => user?.npub ?? franzapsNpub;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return switch (ref.watch(wotProvider((npub1: npub, npub2: npub2)))) {
      AsyncData<List<User>>(value: final trustedUsers) => Builder(
          builder: (context) {
            final hasUser =
                trustedUsers.firstWhereOrNull((u) => u.npub == npub) != null;
            final trustedUsersWithoutUser =
                trustedUsers.where((u) => u.npub != npub).toList();
            return RichText(
              text: TextSpan(
                children: [
                  if (hasUser && npub != franzapsNpub) TextSpan(text: 'You, '),
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
      _ => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: SizedBox(
                width: 14, height: 14, child: CircularProgressIndicator()),
          ),
        )
    };
  }
}

final wotProvider = FutureProvider.autoDispose
    .family<List<User>, ({String npub1, String npub2})>((ref, arg) async {
  final _ =
      ref.settings.watchOne('_', alsoWatch: (_) => {_.user}).model!.user.value;
  return ref.users.userAdapter.getTrusted(arg.npub1, arg.npub2);
});
