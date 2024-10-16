import 'dart:convert';
import 'dart:io';

import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:flutter_phoenix/flutter_phoenix.dart';
import 'package:gap/gap.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:path_provider/path_provider.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/feedback.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/system_info.dart';
import 'package:zapstore/widgets/app_drawer.dart';

class SettingsScreen extends HookConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final systemInfoState = ref.watch(systemInfoProvider);
    final controller = useTextEditingController();
    final user = ref.settings
        .watchOne('_', alsoWatch: (_) => {_.user})
        .model!
        .user
        .value;

    return SingleChildScrollView(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Share your feedback',
            style: context.theme.textTheme.headlineLarge!
                .copyWith(fontWeight: FontWeight.bold),
          ),
          Gap(10),
          Text(
            'zap.store is free and open source software',
            style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
          ),
          Gap(10),
          Text('Comments, suggestions and error reports welcome here.'),
          Gap(20),
          TextField(
            controller: controller,
            maxLines: 10,
          ),
          Gap(20),
          if (user == null) LoginContainer(minimal: true),
          if (user != null)
            AsyncButtonBuilder(
              loadingWidget: SizedBox(
                  width: 14, height: 14, child: CircularProgressIndicator()),
              onPressed: () async {
                if (controller.text.trim().isNotEmpty) {
                  final text =
                      '${controller.text.trim()} [from ${user.npub} on ${DateFormat('MMMM d, y').format(DateTime.now())}]';
                  final event = AppFeedback(content: text).sign(kI);
                  await ref.apps.nostrAdapter.relay.publish(event);
                  controller.clear();
                }
              },
              builder: (context, child, callback, state) {
                return switch (state) {
                  _ => ElevatedButton(
                      onPressed: callback,
                      child: child,
                    ),
                };
              },
              child: Text('Send as ${user.nameOrNpub}'),
            ),
          Gap(40),
          Divider(),
          Gap(40),
          Text(
            'Tools',
            style: context.theme.textTheme.headlineLarge!
                .copyWith(fontWeight: FontWeight.bold),
          ),
          TextButton(
            onPressed: () {
              showDialog(
                context: context,
                builder: (BuildContext context) {
                  return AlertDialog(
                    title: Text('Confirm clear'),
                    content: Text(
                        'Are you sure you want to clear the local cache and restart the app?'),
                    actions: [
                      TextButton(
                        child: Text('Cancel'),
                        onPressed: () {
                          Navigator.of(context).pop();
                        },
                      ),
                      TextButton(
                        child: Text('Confirm'),
                        onPressed: () {
                          ref.read(localStorageProvider).destroy().then((_) {
                            Phoenix.rebirth(context);
                            Navigator.of(context).pop();
                            context.go('/');
                          });
                        },
                      ),
                    ],
                  );
                },
              );
            },
            child: Text('Delete local cache'),
          ),
          Gap(40),
          Text(
            'System information',
            style: context.theme.textTheme.headlineLarge!
                .copyWith(fontWeight: FontWeight.bold),
          ),
          Gap(20),
          GestureDetector(
            onTap: () {
              if (systemInfoState.hasValue) {
                Clipboard.setData(
                    ClipboardData(text: systemInfoState.value!.toString()));
                context.showInfo('Copied system information');
              }
            },
            child: Text(switch (systemInfoState) {
              AsyncData(:final value) => value.toString(),
              _ => '',
            }),
          ),
          HookBuilder(
            builder: (context) {
              final snapshot = useFuture(useMemoized(() async {
                final dir = await getApplicationDocumentsDirectory();
                return jsonDecode(
                    await File('${dir.path}/errors.json').readAsString());
              }));
              if (snapshot.hasData) {
                return Text(
                    'Entries in errors.json: ${(snapshot.data as Map).length}');
              }
              return Container();
            },
          ),
        ],
      ),
    );
  }
}
