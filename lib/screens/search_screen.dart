import 'dart:async';

import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:flutter_layout_grid/flutter_layout_grid.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/widgets/app_card.dart';
import 'package:zapstore/widgets/pill_widget.dart';
import 'package:zapstore/widgets/user_avatar.dart';

const kAndroidMimeType = 'application/vnd.android.package-archive';

class SearchScreen extends HookConsumerWidget {
  const SearchScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final controller = useTextEditingController();
    final focusNode = useFocusNode();
    final state = ref.watch(searchResultProvider);
    final scrollController = useScrollController();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
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
                controller: controller,
                focusNode: focusNode,
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
                trailing: [
                  if (state.isLoading)
                    Padding(
                      padding: const EdgeInsets.only(right: 10),
                      child: SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 3),
                      ),
                    ),
                  if (controller.text.isNotEmpty && !state.isLoading)
                    IconButton(
                      hoverColor: Colors.transparent,
                      onPressed: () {
                        // clear input and results, then return focus
                        controller.clear();
                        ref.read(searchQueryProvider.notifier).state = null;
                        focusNode.requestFocus();
                      },
                      icon: Icon(Icons.close),
                    ),
                ],
                hintText: 'Search for apps',
                hintStyle: MaterialStateProperty.all(
                    TextStyle(color: Colors.grey[600])),
                elevation: MaterialStateProperty.all(2.2),
                onSubmitted: (query) async {
                  ref.read(searchQueryProvider.notifier).state = query;
                  scrollController.animateTo(
                    scrollController.position.minScrollExtent,
                    duration: Duration(milliseconds: 300),
                    curve: Curves.easeOut,
                  );
                },
              ),
            ),
          ],
        ),
        Gap(10),
        if (state.hasError) Text(state.error!.toString()),
        Expanded(
          child: SingleChildScrollView(
            controller: scrollController,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              key: UniqueKey(),
              children: [
                if ((state.value?.isEmpty ?? false) && !state.isLoading)
                  Padding(
                    padding: const EdgeInsets.only(top: 16),
                    child: Center(
                        child: Text(
                      'No results for ${ref.read(searchQueryProvider)}',
                      style: TextStyle(fontSize: 16),
                    )),
                  ),
                if (state.value?.isNotEmpty ?? false)
                  for (final app in state.value!) AppCard(app: app),
                Gap(20),
                CategoriesContainer(),
                Gap(20),
                LatestReleasesContainer(),
                Gap(10),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

// Categories

class AppCategoriesNotifier
    extends FamilyAsyncNotifier<List<App>, AppCategory> {
  @override
  FutureOr<List<App>> build(AppCategory arg) async {
    if (ref.appCurationSets.countLocal < 4) {
      // Find apps for all categories, so next provider will find it locally
      final appSets = await ref.appCurationSets
          .findAll(params: {'#d': AppCategory.values.map((e) => e.name)});
      await ref.apps
          .findAll(params: {'#d': appSets.map((s) => s.appIds).flattened});
    }

    final curationSet = ref.appCurationSets.findOneLocalById(arg.name)!;
    return ref.apps
        .findAllLocal()
        .where((a) => curationSet.appIds.contains(a.id))
        .toList();
  }
}

final categoriesAppProvider =
    AsyncNotifierProvider.family<AppCategoriesNotifier, List<App>, AppCategory>(
        AppCategoriesNotifier.new);

final selectedAppCategoryProvider = StateProvider((_) => AppCategory.basics);

class CategoriesContainer extends HookConsumerWidget {
  const CategoriesContainer({
    super.key,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scrollController = useScrollController();
    final selectedCategory = ref.watch(selectedAppCategoryProvider);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Discover apps',
            style: TextStyle(fontSize: 30, fontWeight: FontWeight.bold)),
        Gap(12),
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          controller: scrollController,
          child: Row(
            children: [
              for (final i in AppCategory.values)
                Padding(
                  padding: const EdgeInsets.only(right: 12),
                  child: GestureDetector(
                    onTap: () => ref
                        .read(selectedAppCategoryProvider.notifier)
                        .state = i,
                    child: PillWidget(
                      text: i.label,
                      color: i == selectedCategory
                          ? Colors.blue[700]!
                          : Colors.grey[800]!,
                    ),
                  ),
                ),
            ],
          ),
        ),
        Gap(12),
        Consumer(
          builder: (context, ref, _) {
            final state = ref.watch(categoriesAppProvider(selectedCategory));
            return WrapLayout(
              apps: switch (state) {
                AsyncData(:final value) => value,
                _ => [],
              },
            );
          },
        ),
      ],
    );
  }
}

class WrapLayout extends StatelessWidget {
  const WrapLayout({
    super.key,
    required this.apps,
    this.columns = 4,
  });

  final List<App> apps;
  final int columns;

  @override
  Widget build(BuildContext context) {
    return LayoutGrid(
      key: UniqueKey(),
      columnGap: 10,
      rowGap: 10,
      rowSizes:
          List<FixedTrackSize>.generate((8 / columns).ceil(), (_) => 130.px),
      columnSizes: List<FlexibleTrackSize>.generate(columns, (_) => 1.fr),
      children:
          List.generate(8, (i) => TinyAppCard(app: apps.elementAtOrNull(i))),
    );
  }
}

class LatestReleasesAppNotifier extends AutoDisposeAsyncNotifier<List<App>> {
  @override
  Future<List<App>> build() async {
    final timer = Timer.periodic(Duration(hours: 4), (_) => fetch());
    ref.onDispose(timer.cancel);
    fetch();
    return localFetch();
  }

  List<App> localFetch() {
    return ref.releases
        .findAllLocal()
        .sorted((a, b) => b.createdAt.compareTo(a.createdAt))
        .take(10)
        .map((r) => r.app.value)
        .nonNulls
        .toSet()
        .toList();
  }

  Future<void> fetch() async {
    final releases = await ref.releases.findAll(params: {'limit': 10});
    final appIds = releases.map((r) => r.app.id!.toString()).toSet();
    await ref.apps.findAll(params: {'#d': appIds});
    update((_) => localFetch());
  }
}

final latestReleasesAppProvider =
    AsyncNotifierProvider.autoDispose<LatestReleasesAppNotifier, List<App>>(
        LatestReleasesAppNotifier.new);

class LatestReleasesContainer extends HookConsumerWidget {
  const LatestReleasesContainer({
    super.key,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(latestReleasesAppProvider);
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

// Search

final searchQueryProvider = StateProvider<String?>((ref) => null);

final searchResultProvider =
    AsyncNotifierProvider<SearchResultNotifier, List<App>?>(
        SearchResultNotifier.new);

class SearchResultNotifier extends AsyncNotifier<List<App>?> {
  @override
  Future<List<App>?> build() async {
    final query = ref.watch(searchQueryProvider);
    if (query != null) {
      return await ref.apps.findAll(params: {'search': query});
    }
    return null;
  }
}

enum AppCategory {
  basics(label: 'Basics'),
  nostr(label: 'Nostr'),
  bitcoin(label: 'Bitcoin'),
  privacy(label: 'Privacy & Security');

  final String label;
  const AppCategory({required this.label});
}
