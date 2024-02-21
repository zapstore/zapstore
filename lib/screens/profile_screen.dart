import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:ndk/ndk.dart' as ndk;
import 'package:zapstore/models/user.dart';

final loggedInUser = StateProvider<User?>((ref) => null);
final downloadsPath = StateProvider<String>((ref) => '/tmp');

class ProfileScreen extends HookConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final u2 = ref.watch(loggedInUser);
    final controller = useTextEditingController();
    final controller2 = useTextEditingController(text: ref.read(downloadsPath));

    return Padding(
      padding: const EdgeInsets.all(12.0),
      child: Column(
        children: [
          TextField(controller: controller),
          SizedBox(height: 10),
          ElevatedButton(
              onPressed: () async {
                final pubkey = controller.text.hexKey;
                ref.read(loggedInUser.notifier).state =
                    await ref.read(usersRepositoryProvider).findOne(pubkey);
              },
              child: Text('Log in with npub')),
          if (u2 != null)
            Expanded(
              child: Column(
                children: [
                  Text('Logged in as ${u2.name}'),
                  Text('(following: ${u2.following.length})'),
                ],
              ),
            ),
          SizedBox(height: 20),
          // ListView.builder(
          //     shrinkWrap: true,
          //     itemCount: contacts.length,
          //     itemBuilder: (context, i) {
          //       return Text(' - ${contacts[i].id}');
          //     }),
          TextField(controller: controller2),
          SizedBox(height: 10),
          ElevatedButton(
            onPressed: () async {
              ref.read(downloadsPath.notifier).state = controller2.text;
            },
            child: Text('Save downloads path'),
          ),
        ],
      ),
    );
  }
}
