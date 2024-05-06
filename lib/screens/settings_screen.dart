import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/services/session_service.dart';
import 'package:zapstore/utils/extensions.dart';

class SettingsScreen extends HookConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final controller = useTextEditingController();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Share your feedback',
          style: context.theme.textTheme.headlineLarge,
        ),
        Gap(10),
        Text('Comments, suggestions and error reports welcome here.'),
        Gap(20),
        TextField(
          controller: controller,
          autofocus: true,
          maxLines: 10,
        ),
        Gap(20),
        AsyncButtonBuilder(
          loadingWidget: SizedBox(
              width: 14, height: 14, child: CircularProgressIndicator()),
          onPressed: () async {
            final user = ref.read(loggedInUser);
            final text = '${controller.text.trim()} [from ${user?.npub}]';
            final event = BaseEvent.partial(content: text).sign(kI);
            // TODO only send to relay.zap.store?
            await ref.apps.nostrAdapter.notifier.publish(event);
            controller.clear();
          },
          builder: (context, child, callback, buttonState) {
            return ElevatedButton(
              onPressed: callback,
              child: child,
            );
          },
          child: Text('Send'),
        ),
      ],
    );
  }
}

const kI = "e593c54f840b32054dcad0fac15d57e4ac6523e31fe26b3087de6b07a2e9af58";
