import 'package:android_package_manager/android_package_manager.dart';
import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/widgets/card.dart';

final testApp = {
  "id": "945b30320b5b9ca315adecda68b1e18effd10e8933d2fd770cd04c014af153a4",
  "pubkey": "78ce6faa72264387284e647ba6938995735ec8c7d5c5a65737e55130f026307d",
  "sig":
      "c5d4d8a0dfe4a3e52a45866f4c29e3ae063cbc3a5607a6a01979d30a31655cc6a567e7f3388f3f0772fddb6ad6702b181fc24d3d9e9cdff795af8edec6122af5",
  "kind": 32267,
  "created_at": 1714425637,
  "content":
      "Primal is a Nostr client featuring:\n\n- Easy onboarding\\\n- Smooth, fast and rich feeds\\\n- Content discovery\n",
  "tags": [
    ["d", "net.primal.android"],
    ["name", "Primal"],
    ["repository", "https://github.com/PrimalHQ/primal-android-app"],
    [
      "icon",
      "https://cdn.zap.store/778bc7fc496a95187b9dcc7c2ba8325a156912d64a2e22e91ed52d0dbf00716c.png"
    ],
  ]
};

final installedAppsStateProvider = StateNotifierProvider.autoDispose<
    DataStateNotifier<List<App>>, DataState<List<App>>>((ref) {
  final packageManager = AndroidPackageManager();
  final n = DataStateNotifier(
      data: DataState<List<App>>(
    [],
    // ref.apps.deserialize(testApp).models,
    isLoading: true,
  ));

  () async {
    final infos = await packageManager.getInstalledPackages();
    final ids = infos!
        .map((i) => i.packageName)
        .nonNulls
        .where((n) => !n.startsWith('android') || !n.startsWith('com.android'))
        .toSet();
    print('querying for $ids');
    final apps = await ref.apps.findAll(params: {'#d': ids});
    print('got ${apps.length}');
    n.updateWith(model: apps);
  }();
  return n;
});

class UpdatesScreen extends HookConsumerWidget {
  const UpdatesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(installedAppsStateProvider);

    return ListView.builder(
      shrinkWrap: true,
      itemCount: state.model.length,
      itemBuilder: (context, index) {
        final app = state.model[index];
        return CardWidget(app: app);
      },
    );
  }
}
