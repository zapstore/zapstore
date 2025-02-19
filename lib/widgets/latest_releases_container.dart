import 'dart:async';

import 'package:async_button_builder/async_button_builder.dart';
import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/screens/settings_screen.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/notifier.dart';
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
              for (final _ in List.generate(3, (_) => _)) SkeletonAppCard(),
            if (state.hasValue)
              // NOTE: Since we're showing apps but it's really a list of releases
              // apps will appear repeated, to convert to set
              for (final app in state.value!) AppCard(model: app),
            if (state.hasValue)
              AsyncButtonBuilder(
                loadingWidget: SmallCircularProgressIndicator(),
                onPressed: () async {
                  return ref
                      .read(latestReleasesAppProvider.notifier)
                      .fetchRemote(next: true);
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

class LatestReleasesAppNotifier extends PreloadingStateNotifier<List<App>> {
  DateTime? _oldestCreatedAt;
  int _page = 1;

  LatestReleasesAppNotifier(super.ref);

  @override
  Future<void> fetchRemote({bool next = false}) async {
    if (next) {
      _page++;
    }

    // Can't use ignoreReturn, as super.findAll does use the result
    await ref.apps.findAll(
      params: {
        'includes': true,
        'limit': 10,
        'until': next ? _oldestCreatedAt : null,
        'since': null,
      },
    );

    state = fetchLocal();
  }

  @override
  AsyncValue<List<App>> fetchLocal() {
    final models = ref.apps.findAllLocal();

    if (models.isEmpty) {
      return AsyncLoading();
    }

    // Do we have a Zapstore version that needs update?
    final zapstoreApp =
        models.firstWhereOrNull((a) => a.identifier == kZapstoreAppIdentifier);

    final apps = models
        .where((a) => a.latestMetadata != null)
        .sortedByLatest
        .take(_page * 10)
        .toList();

    // Set timestamp of oldest, to prepare for next query
    if (apps.isNotEmpty) {
      _oldestCreatedAt = apps.last.event.createdAt;
    }
    // "Pin" Zapstore if it needs to be updated
    if (zapstoreApp != null && zapstoreApp.canUpdate) {
      apps.remove(zapstoreApp);
      apps.insert(0, zapstoreApp);
    }
    return AsyncData(apps);
  }
}

final latestReleasesAppProvider =
    StateNotifierProvider<LatestReleasesAppNotifier, AsyncValue<List<App>>>(
        LatestReleasesAppNotifier.new);
