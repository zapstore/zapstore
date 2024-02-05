import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';

class ProfileScreen extends HookConsumerWidget {
  const ProfileScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final watcher = ref.notes.watchAll(remote: false);
    return Expanded(
      child: Column(
        children: [
          TextButton(
            onPressed: () => {},
            child: Text('press me'),
          ),
          switch (watcher) {
            DataState(isLoading: false, exception: null, model: final value) =>
              Text(value.length.toString()),
            _ => Text('loading')
          }
        ],
      ),
    );
    // return Column(
    //   children: [
    //     Text(
    //         'profile, ${Nip19.decodePubkey('npub1wf4pufsucer5va8g9p0rj5dnhvfeh6d8w0g6eayaep5dhps6rsgs43dgh9')}'),
    //     Text(value.isLoading ? 'loading' : value.value!.join('\n')),
    //   ],
    // );
  }
}
