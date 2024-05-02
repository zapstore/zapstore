import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/services/session_service.dart';
import 'package:zapstore/widgets/card.dart';

class AppDrawer extends HookConsumerWidget {
  AppDrawer({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final u2 = ref.watch(loggedInUser);
    final controller = useTextEditingController();

    return Padding(
      padding: const EdgeInsets.all(12.0),
      child: Column(
        children: [
          // Text('npub or NIP-05'),
          if (u2 == null) TextField(autocorrect: false, controller: controller),
          Gap(5),
          if (u2 == null)
            ElevatedButton(
              onPressed: () async {
                ref.read(loggedInUser.notifier).state = await ref.users
                    .findOne(controller.text, params: {'contacts': true});
              },
              child: Text('Log in'),
            ),
          if (u2 != null)
            Expanded(
              child: Column(
                children: [
                  Card(
                    child: ListTile(
                      dense: true,
                      leading: CircularImage(
                        size: 20,
                        url: u2.avatarUrl,
                      ),
                      title: Text(
                        u2.name ?? u2.id?.toString() ?? '',
                      ),
                      subtitle: Text('following ${u2.following.length}'),
                    ),
                  ),
                  ElevatedButton(
                    onPressed: () async {
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
