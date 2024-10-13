import 'dart:async';

import 'package:async_button_builder/async_button_builder.dart';
import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/widgets/app_card.dart';

class LatestReleasesContainer extends HookConsumerWidget {
  final ScrollController scrollController;
  const LatestReleasesContainer({super.key, required this.scrollController});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(latestReleasesAppProvider);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text('Latest releases',
                style: TextStyle(fontSize: 28, fontWeight: FontWeight.bold)),
            Gap(20),
            if (state.isLoading || state.isRefreshing || state.isReloading)
              SizedBox(
                height: 14,
                width: 14,
                child: CircularProgressIndicator(strokeWidth: 4),
              ),
            ElevatedButton(
              onPressed: () {
                scrollController.animateTo(
                  scrollController.position.maxScrollExtent,
                  duration: Duration(milliseconds: 300),
                  curve: Curves.easeOut,
                );
              },
              style:
                  ElevatedButton.styleFrom(backgroundColor: Colors.transparent),
              child: Text('See more'),
            )
          ],
        ),
        Gap(12),
        Column(
          children: [
            if (state.hasError) Text('Error fetching: ${state.error}'),
            if (state.isLoading)
              for (final _ in List.generate(3, (_) => _)) AppCard(app: null),
            if (state.hasValue)
              for (final app in state.value!) AppCard(app: app),
            if (state.hasValue)
              AsyncButtonBuilder(
                loadingWidget: SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(),
                ),
                onPressed: () async {
                  return ref
                      .read(latestReleasesAppProvider.notifier)
                      .fetch(next: true);
                },
                builder: (context, child, callback, state) {
                  return Padding(
                    padding: const EdgeInsets.only(bottom: 2),
                    child: SizedBox(
                      child: ElevatedButton(
                        onPressed: callback,
                        style: ElevatedButton.styleFrom(
                            disabledBackgroundColor: Colors.transparent,
                            backgroundColor: Colors.grey[900]),
                        child: child,
                      ),
                    ),
                  );
                },
                child: Text('Load more'),
              ),
          ],
        )
      ],
    );
  }
}

class LatestReleasesAppNotifier extends AutoDisposeAsyncNotifier<List<App>> {
  int? oldestTimestamp;
  int page = 1;

  @override
  Future<List<App>> build() async {
    // TODO: Should be ref.watching a pool state change (from purplebase)
    final timer = Timer.periodic(Duration(minutes: 1), (_) => fetch());
    ref.onDispose(timer.cancel);
    // Trigger fetch ONLY on the most recent 10 (next=false)
    await fetch();
    return localFetch();
  }

  List<App> localFetch() {
    final apps = ref.apps
        .findAllLocal()
        .sorted((a, b) => b.createdAt!.compareTo(a.createdAt!))
        .take(page * 10)
        .toList();
    if (apps.isNotEmpty) {
      oldestTimestamp = apps.last.createdAtMs;
    }
    return apps;
  }

  Future<void> fetch({bool next = false}) async {
    if (next) {
      page++;
    }
    await ref.apps
        .findAll(params: {'limit': 10, 'until': next ? oldestTimestamp : null});
    update((_) => localFetch());
  }
}

final latestReleasesAppProvider =
    AsyncNotifierProvider.autoDispose<LatestReleasesAppNotifier, List<App>>(
        LatestReleasesAppNotifier.new);
