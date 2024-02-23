import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:ndk/ndk.dart' as ndk;
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/user.dart';

final loggedInUser = StateProvider<User?>((ref) => null);

class ProfileScreen extends HookConsumerWidget {
  const ProfileScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final u2 = ref.watch(loggedInUser);
    final controller = useTextEditingController();

    return Padding(
      padding: const EdgeInsets.all(12.0),
      child: Column(
        children: [
          if (u2 == null) TextField(controller: controller),
          SizedBox(height: 10),
          if (u2 == null)
            ElevatedButton(
              onPressed: () async {
                final pubkey = controller.text.hexKey;
                ref.read(loggedInUser.notifier).state =
                    await ref.users.findOne(pubkey, params: {
                  'kinds': {3}
                });
              },
              child: Text('Log in with npub'),
            ),
          if (u2 != null)
            Expanded(
              child: Column(
                children: [
                  Card(
                    child: ListTile(
                      dense: true,
                      leading: CircleAvatar(
                        backgroundImage: NetworkImage(u2.profilePicture ?? ''),
                      ),
                      title: Text(
                        u2.name ?? u2.id?.toString() ?? '',
                      ),
                      subtitle: Text('following ${u2.following.length}'),
                    ),
                  ),
                  ElevatedButton(
                    onPressed: () {
                      ref.read(loggedInUser.notifier).state = null;
                      controller.clear();
                    },
                    child: Text('Log out'),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}
