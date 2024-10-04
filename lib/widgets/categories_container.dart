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

class AppCategoriesNotifier
    extends FamilyAsyncNotifier<List<App>, AppCategory> {
  @override
  Future<List<App>> build(AppCategory arg) async {
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

final selectedAppCategoryProvider = StateProvider((_) => AppCategory.nostr);

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

enum AppCategory {
  nostr(label: 'Nostr'),
  bitcoin(label: 'Bitcoin'),
  basics(label: 'Basics'),
  privacy(label: 'Privacy & Security');

  final String label;
  const AppCategory({required this.label});
}
