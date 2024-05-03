import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/utils/extensions.dart';

class SettingsScreen extends HookConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final controller = useTextEditingController();
    return Column(
      children: [
        Text(
          'Share your feedback with us',
          style: context.theme.textTheme.headlineLarge,
        ),
        Gap(20),
        TextField(
          controller: controller,
          autofocus: true,
          maxLines: 10,
        ),
        Gap(20),
        ElevatedButton(
          onPressed: () async {
            ref.apps.logLevel = 2;
            await ref.apps.clearLocal();
          },
          child: Text('Send'),
        ),
      ],
    );
  }
}

const kI = "e593c54f840b32054dcad0fac15d57e4ac6523e31fe26b3087de6b07a2e9af58";
