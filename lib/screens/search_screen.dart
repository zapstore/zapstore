import 'dart:developer';
import 'dart:ffi';

import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/release.dart';
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
                  // debugger;

                  // final app = App()
                  //   ..id = '1'
                  //   ..kind = 32267
                  //   ..createdAt = DateTime.now()
                  //   ..pubkey =
                  //       'a9e95a4eb32b55441b222ae5674f063949bfd0759b82deb03d7cd262e82d5626'
                  //   ..content = 'testing'
                  //   ..tags = [
                  //     ['d', 'com.test']
                  //   ]
                  //   ..releases = HasMany()
                  //   ..init()
                  //   ..saveLocal();

                  // final release = ref.releases.deserialize({
                  //   'kind': 30063,
                  //   'created_at': 112899202,
                  //   'pubkey':
                  //       'a9e95a4eb32b55441b222ae5674f063949bfd0759b82deb03d7cd262e82d5626',
                  //   'content': 'mutiny',
                  //   'tags': [
                  //     ['d', 'com.test@v0.6.4']
                  //   ],
                  // });
                  // debugger;

                  // ref.read(searchStateProvider.notifier).state =
                  //     DataState([app]);
                  // print(release.model!.app.value?.id);

                  ref.read(screenInfoProvider.notifier).state =
                      'No apps found for "$query"';
                  ref.read(searchStateProvider.notifier).state =
                      DataState([], isLoading: true);
                  final apps =
                      await ref.apps.findAll(params: {'search': query});

                  // load all signers and developers
                  final userIds = {
                    for (final app in apps) app.signer.id,
                    for (final app in apps) app.developer.id
                  };
                  await ref.users.findAll(params: {'ids': userIds});

                  await ref.releases.findAll(
                      params: {'#i': apps.map((a) => a.identifier).toSet()});

                  // await ref.fileMetadata.findAll(
                  //   params: {
                  //     'ids': releases.map((r) => r.tagMap['e']!.first).toSet(),
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
        if (state.model.isEmpty || state.isLoading)
          Expanded(
            child: Center(
              child: state.isLoading
                  ? CircularProgressIndicator()
                  : Text(
                      ref.read(screenInfoProvider),
                      textAlign: TextAlign.center,
                    ),
            ),
          ),
        if (state.hasModel)
          Expanded(
            child: ListView.builder(
              shrinkWrap: true,
              itemCount: state.model.length,
              itemBuilder: (context, index) {
                final app = state.model[index];
                return CardWidget(app: app);
              },
            ),
          ),
      ],
    );
  }
}
