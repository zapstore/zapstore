import 'package:async_button_builder/async_button_builder.dart';
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
                    if (user != null)
                      Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Text(
                                user.nameOrNpub,
                                style: TextStyle(fontWeight: FontWeight.bold),
                              ),
                              Gap(4),
                              Icon(Icons.verified,
                                  color: Colors.lightBlue, size: 18),
                            ],
                          ),
                          Text('${user.following.length} contacts'),
                        ],
                      ),
                  ],
                ),
              ),
              if (user == null)
                TextField(
                  autofocus: true,
                  autocorrect: false,
                  controller: controller,
                  decoration: InputDecoration(
                    labelText: 'NIP-05 address or npub (no nsec!)',
                  ),
                ),
              Gap(5),
              if (user == null)
                AsyncButtonBuilder(
                  loadingWidget: SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(),
                  ),
                  onPressed: () async {
                    ref.read(loggedInUser.notifier).state = await ref.users
                        .findOne(controller.text.trim(),
                            params: {'contacts': true});
                  },
                  builder: (context, child, callback, buttonState) {
                    return ElevatedButton(
                      onPressed: callback,
                      child: child,
                    );
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
              Gap(20),
              if (user == null)
                Text(
                    'Feel free to log in with fran@zap.store if you don\'t want to use yours.'),
            ],
          ),
        ],
      ),
    );
  }
}
