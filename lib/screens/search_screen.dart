// ignore_for_file: prefer_const_literals_to_create_immutables

import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/screens/app_detail_screen.dart';
import 'package:zapstore/widgets/card.dart';
import 'package:zapstore/widgets/user_avatar.dart';

const kAndroidMimeType = 'application/vnd.android.package-archive';

final searchStateProvider =
    StateProvider<DataState<List<App>>>((_) => DataState([]));

final screenInfoProvider = StateProvider(
    (ref) => 'Welcome to zap.store!\nUse the search bar to find apps');

class SearchScreen extends HookConsumerWidget {
  const SearchScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(searchStateProvider);

    if (state.isLoading) {
      return Center(child: CircularProgressIndicator());
    }

    final apps = state.model;

    return Column(
      children: [
        Row(
          children: [
            GestureDetector(
              onTap: () => Scaffold.of(context).openDrawer(),
              child: UserAvatar(),
            ),
            Gap(20),
            Expanded(
              child: SearchBar(
                shape: MaterialStateProperty.all(
                  RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(28),
                  ),
                ),
                leading: Padding(
                  padding: const EdgeInsets.fromLTRB(10, 8, 6, 6),
                  child: Icon(
                    Icons.search,
                    color: Colors.blueGrey,
                    size: 20,
                  ),
                ),
                hintText: 'Search for apps',
                hintStyle: MaterialStateProperty.all(
                    TextStyle(color: Colors.grey[600])),
                autoFocus: true,
                elevation: MaterialStatePropertyAll(2.2),
                onSubmitted: (query) async {
                  ref.read(screenInfoProvider.notifier).state = 'No items';
                  final apps = [App(), App(), App(), App()];
                  // await ref.apps.findAll(params: {'search': query});

                  // final releases = await ref.releases.findAll(
                  //     params: {'#i': apps.map((a) => a.identifier).toSet()});

                  // await ref.fileMetadata.findAll(
                  //   params: {
                  //     'ids':
                  //         releases.map((r) => r.tagMap['e']!.first).toSet(),
                  //     '#m': [kAndroidMimeType],
                  //   },
                  // );
                  ref.read(searchStateProvider.notifier).state =
                      DataState(apps);
                },
              ),
            ),
          ],
        ),
        Gap(10),
        if (apps.isEmpty)
          Expanded(
            child: Center(
              child: Text(
                ref.read(screenInfoProvider),
                textAlign: TextAlign.center,
              ),
            ),
          ),
        if (apps.isNotEmpty)
          Expanded(
            child: ListView.builder(
              shrinkWrap: true,
              itemCount: apps.length,
              itemBuilder: (context, index) {
                final app = apps[index];
                return CardWidget(app: app);
              },
            ),
          ),
      ],
    );
  }
}
