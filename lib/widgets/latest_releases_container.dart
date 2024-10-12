import 'dart:async';

import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/widgets/app_card.dart';

class LatestReleasesContainer extends HookConsumerWidget {
  const LatestReleasesContainer({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(latestReleasesAppProvider);
    if (state.hasError) {
      return Text(state.error.toString());
    }
    final apps = state.value ?? [];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Latest releases',
            style: TextStyle(fontSize: 30, fontWeight: FontWeight.bold)),
        Gap(12),
        Column(
          children: (apps.isEmpty ? [null, null, null] : apps)
              .map((app) => AppCard(app: app))
              .toList(),
        )
      ],
    );
  }
}

class LatestReleasesAppNotifier extends AutoDisposeAsyncNotifier<List<App>> {
  @override
  Future<List<App>> build() async {
    final timer = Timer.periodic(Duration(minutes: 1), (_) => fetch());
    ref.onDispose(timer.cancel);
    fetch();
    // .catchError((e) {
    //   print('caught here');
    //   throw e;
    // });
    return localFetch();
  }

  List<App> localFetch() {
    return ref.apps
        .findAllLocal()
        .sorted((a, b) => b.createdAt!.compareTo(a.createdAt!))
        .take(10)
        .toList();
  }

  Future<void> fetch() async {
    await ref.apps.findAll(params: {'limit': 10});
    update((_) => localFetch());
  }
}

final latestReleasesAppProvider =
    AsyncNotifierProvider.autoDispose<LatestReleasesAppNotifier, List<App>>(
        LatestReleasesAppNotifier.new);
