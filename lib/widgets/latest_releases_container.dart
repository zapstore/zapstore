import 'dart:async';

import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/release.dart';
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
              // NOTE: Since we're showing apps but it's really a list of releases
              // apps will appear repeated, to convert to set
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
    final timer = Timer.periodic(Duration(minutes: 10), (_) => fetch());
    ref.onDispose(timer.cancel);
    return localFetch();
  }

  List<App> localFetch() {
    // Find all releases that are the latest of each, and sort chronologically
    final releases = ref.releases
        .findAllLocal()
        .where((r) => r.app.value?.latestMetadata != null)
        .sortedByLatest;
    // Set timestamp of oldest, to prepare for next query
    if (releases.isNotEmpty) {
      oldestTimestamp = releases.last.createdAt!.toInt();
    }
    // Return only (first 10 * page) releases that have an associated app
    // (it should be the case, but keep as we migrate
    // from older event format)
    return releases
        .map((r) => r.app.value)
        .nonNulls
        .toSet()
        .take(page * 10)
        .toList();
  }

  Future<void> fetch({bool next = false}) async {
    if (next) {
      page++;
    }
    await ref.apps.findAll(
      params: {
        'by-release': true,
        'limit': 10,
        'until': next ? oldestTimestamp : null
      },
    );
    update((_) => localFetch());
  }
}

final latestReleasesAppProvider =
    AsyncNotifierProvider.autoDispose<LatestReleasesAppNotifier, List<App>>(
        LatestReleasesAppNotifier.new);
