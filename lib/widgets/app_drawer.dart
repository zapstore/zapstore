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
    final user = ref.watch(loggedInUser);
    final controller = useTextEditingController();

    return Padding(
      padding: const EdgeInsets.only(top: 24, left: 16),
      child: ListView(
        children: [
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: EdgeInsets.only(top: 4, bottom: 4),
                child: Row(
                  children: [
                    CircularImage(url: user?.avatarUrl, size: 46),
                    Gap(10),
                    Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        if (user != null)
                          Text(
                            user.name,
                            style: TextStyle(fontWeight: FontWeight.bold),
                          ),
                        if (user != null)
                          Text('${user.following.length} contacts'),
                      ],
                    )
                  ],
                ),
              ),
              if (user == null)
                TextField(
                  autocorrect: false,
                  controller: controller,
                ),
              Gap(5),
              if (user == null)
                ElevatedButton(
                  onPressed: () async {
                    ref.read(loggedInUser.notifier).state = await ref.users
                        .findOne(controller.text.trim(),
                            params: {'contacts': true});
                  },
                  child: Text('Log in'),
                ),
              if (user != null)
                ElevatedButton(
                  onPressed: () async {
                    ref.read(loggedInUser.notifier).state = null;
                    controller.clear();
                  },
                  child: Text('Log out'),
                ),
            ],
          ),
        ],
      ),
    );
  }
}
