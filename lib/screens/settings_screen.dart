import 'dart:convert';
import 'dart:io';

import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:flutter_phoenix/flutter_phoenix.dart';
import 'package:gap/gap.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:http/http.dart' as http;
import 'package:intl/intl.dart';
import 'package:path_provider/path_provider.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/feedback.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/nwc.dart';
import 'package:zapstore/utils/system_info.dart';
import 'package:zapstore/widgets/app_drawer.dart';

class SettingsScreen extends HookConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final systemInfoState = ref.watch(systemInfoNotifierProvider);

    final feedbackController = useTextEditingController();
    final nwcController = useTextEditingController();
    final user = ref.settings
        .watchOne('_', alsoWatch: (_) => {_.user})
        .model!
        .user
        .value;
    final isTextFieldEmpty = useState(true);

    final nwcConnection = ref.watch(nwcConnectionProvider);

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
            'Zapstore is free and open source software',
            style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
          ),
          Gap(10),
          Text('Comments, suggestions and error reports welcome here.'),
          Gap(20),
          TextField(
            controller: feedbackController,
            maxLines: 10,
          ),
          Gap(20),
          if (user == null) LoginContainer(minimal: true),
          if (user != null)
            AsyncButtonBuilder(
              loadingWidget: SizedBox(
                  width: 14, height: 14, child: CircularProgressIndicator()),
              onPressed: () async {
                if (feedbackController.text.trim().isNotEmpty) {
                  final text =
                      '${feedbackController.text.trim()} [from ${user.npub} on ${DateFormat('MMMM d, y').format(DateTime.now())}]';
                  final event = AppFeedback(content: text).sign(kI);
                  try {
                    await http.post(Uri.parse('https://relay.zapstore.dev/'),
                        body: jsonEncode(event.toMap()),
                        headers: {'Content-Type': 'application/json'});
                    if (context.mounted) {
                      context.showInfo('Thank you',
                          description: 'Message sent successfully');
                    }
                    feedbackController.clear();
                  } catch (e, stack) {
                    if (context.mounted) {
                      context.showError(
                          title: (e as dynamic).message ?? e.toString(),
                          description: stack.toString());
                    }
                  }
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
          if (user != null)
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Gap(20),
                Text(
                    "Nostr Wallet Connect ${nwcConnection != null ? 'connected' : 'URI'}"),
                if (nwcConnection != null && nwcConnection != '')
                  ElevatedButton(
                    onPressed: () async {
                      await ref
                          .read(nwcSecretProvider.notifier)
                          .updateNwcSecret(null);
                      await ref
                          .read(nwcConnectionProvider.notifier)
                          .setConnection(null);
                    },
                    style: ElevatedButton.styleFrom(
                        disabledBackgroundColor: Colors.transparent,
                        backgroundColor: Colors.transparent),
                    child: Text('Disconnect'),
                  ),
                if (nwcConnection == null)
                  TextField(
                    autocorrect: false,
                    controller: nwcController,
                    onChanged: (value) {
                      isTextFieldEmpty.value = value.isEmpty;
                    },
                    decoration: InputDecoration(
                      hintText: 'nostr+walletconnect://...',
                      suffixIcon: AsyncButtonBuilder(
                        disabled: isTextFieldEmpty.value,
                        loadingWidget: SizedBox(
                          width: 14,
                          height: 14,
                          child: CircularProgressIndicator(),
                        ),
                        onPressed: () async {
                          final nwcUri = nwcController.text.trim();
                          await ref
                              .read(nwcSecretProvider.notifier)
                              .updateNwcSecret(nwcUri);
                          await ref
                              .read(nwcConnectionProvider.notifier)
                              .ensureConnected(nwcUri);
                        },
                        builder: (context, child, callback, buttonState) {
                          return Padding(
                            padding: const EdgeInsets.only(bottom: 2),
                            child: SizedBox(
                              child: ElevatedButton(
                                onPressed: callback,
                                style: ElevatedButton.styleFrom(
                                    disabledBackgroundColor: Colors.transparent,
                                    backgroundColor: Colors.transparent),
                                child: child,
                              ),
                            ),
                          );
                        },
                        child: Text('Connect'),
                      ),
                    ),
                  ),
              ],
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
                            if (context.mounted) {
                              Phoenix.rebirth(context);
                              Navigator.of(context).pop();
                              context.go('/');
                            }
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
          Row(
            children: [
              Text(
                'System info',
                style: context.theme.textTheme.headlineLarge!
                    .copyWith(fontWeight: FontWeight.bold),
              ),
              Gap(20),
              ElevatedButton(
                onPressed: () {
                  ref.read(systemInfoNotifierProvider.notifier).fetch();
                },
                child: Text('Refresh'),
              ),
            ],
          ),
          Gap(20),
          GestureDetector(
            onLongPress: () async {
              final info =
                  await ref.read(systemInfoNotifierProvider.notifier).fetch();
              final dir = await getApplicationDocumentsDirectory();
              final errors = jsonDecode(
                  await File('${dir.path}/errors.json').readAsString());
              errors as Map<String, dynamic>;
              errors['_'] = info.toString();
              errors['_user'] =
                  ref.settings.findOneLocalById('_')!.user.value?.npub;
              try {
                await http.post(
                  Uri.parse('https://cdn.zapstore.dev/upload'),
                  body: utf8.encode(jsonEncode(errors)),
                  headers: {
                    'Content-Type': 'application/json',
                    'X-Filename': 'errors.json',
                  },
                );
                if (context.mounted) {
                  context.showInfo('System info sent',
                      description: 'Thank you');
                }
              } catch (e) {
                if (context.mounted) {
                  context.showError(
                      title: 'Unable to send system info',
                      description: e.toString());
                }
              }
            },
            child: Text(switch (systemInfoState) {
              AsyncData(:final value) => value.toString(),
              _ => '',
            }),
          ),
        ],
      ),
    );
  }
}
