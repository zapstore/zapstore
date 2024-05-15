import 'dart:async';

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
                },
              ),
            ),
          ],
        ),
        Gap(10),
        // if (state.hasMessage)
        //   Expanded(
        //     child: Center(
        //       child: Text(
        //         state.message!,
        //         textAlign: TextAlign.center,
        //         style: context.theme.textTheme.bodyLarge,
        //       ),
        //     ),
        //   ),
        if (state.hasError) Text(state.error!.toString()),
        Expanded(
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              key: UniqueKey(),
              children: [
                if (state.hasValue && state.value!.isNotEmpty)
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

final categoriesAppProvider =
    FutureProvider.family<List<App>, AppCategory>((ref, category) async {
  final apps = await ref.apps.findAll(params: {'#d': appCategories[category]!});
  return apps..shuffle();
});

class CategoriesContainer extends HookConsumerWidget {
  const CategoriesContainer({
    super.key,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scrollController = useScrollController();
    final selectedCategory = useState(AppCategory.wallets);

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
                    onTap: () => selectedCategory.value = i,
                    child: PillWidget(
                      text: i.label,
                      color: i == selectedCategory.value
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
            final state =
                ref.watch(categoriesAppProvider(selectedCategory.value));
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
      columnGap: 10, // Adjust the gap between columns as needed
      rowGap: 10, // Adjust the gap between rows as needed
      rowSizes:
          List<FixedTrackSize>.generate((8 / columns).ceil(), (_) => 130.px),
      columnSizes: List<FlexibleTrackSize>.generate(columns, (_) => 1.fr),
      children:
          List.generate(8, (i) => TinyAppCard(app: apps.elementAtOrNull(i))),
    );
  }
}

final latestReleasesAppProvider = FutureProvider((ref) async {
  final releases = await ref.releases.findAll(params: {'limit': 5});
  final appIds = releases.map((r) => r.app.id!.toString());
  return await ref.apps.findAll(params: {'#d': appIds});
});

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
          children:
              List.generate(5, (i) => AppCard(app: apps.elementAtOrNull(i))),
        )
      ],
    );
  }
}

// Data

final searchQueryProvider = StateProvider<String?>((ref) => null);

final searchResultProvider =
    AsyncNotifierProvider<SearchResultNotifier, List<App>>(
        SearchResultNotifier.new);

class SearchResultNotifier extends AsyncNotifier<List<App>> {
  @override
  Future<List<App>> build() async {
    final query = ref.watch(searchQueryProvider);
    if (query != null) {
      return await ref.apps.findAll(params: {'search': query});
    }
    return [];
  }
}

enum AppCategory {
  wallets(label: 'Wallets'),
  nostr(label: 'Nostr'),
  basics(label: 'Basics'),
  privacy(label: 'Privacy & Security'),
  productivity(label: 'Productivity');

  final String label;
  const AppCategory({required this.label});
}

final appCategories = {
  AppCategory.basics: [
    "org.fossify.notes",
    "org.fossify.filemanager",
    "org.fossify.contacts",
    "org.fossify.calendar",
    "io.sanford.wormhole_william",
    "me.zhanghai.android.files",
    "org.breezyweather",
    "app.organicmaps.web",
  ],
  AppCategory.wallets: [
    "com.greenaddress.greenbits_android_wallet",
    "io.nunchuk.android",
    "io.bluewallet.bluewallet",
    "io.aquawallet.android",
    "app.zeusln.zeus",
    "com.mutinywallet.mutinywallet",
    "xyz.elliptica.enuts.beta",
    "fr.acinq.phoenix.mainnet",
  ],
  AppCategory.privacy: [
    "chat.simplex.app",
    "im.molly.app",
    "com.kunzisoft.keepass.free",
    "com.x8bit.bitwarden",
    "io.simplelogin.android.fdroid",
    "eu.darken.myperm",
    "net.ivpn.client",
    "ch.protonvpn.android",
  ],
  AppCategory.nostr: [
    "com.greenart7c3.citrine",
    "com.greenart7c3.nostrsigner",
    "net.primal.android",
    "com.oxchat.nostr",
    "com.vitorpamplona.amethyst",
    "com.nostr.universe",
    "com.dluvian.voyage",
    "com.apps.freerse",
  ],
  AppCategory.productivity: [
    "io.ente.photos.independent",
    "md.obsidian",
    "com.logseq.app",
    "com.nutomic.syncthingandroid",
    "ch.protonmail.android",
    "org.localsend.localsend_app",
    "org.fossify.gallery",
    "org.fossify.musicplayer",
  ],
};
