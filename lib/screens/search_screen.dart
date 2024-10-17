import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/widgets/app_card.dart';
import 'package:zapstore/widgets/app_curation_container.dart';
import 'package:zapstore/widgets/latest_releases_container.dart';
import 'package:zapstore/widgets/user_avatar.dart';

class SearchScreen extends HookConsumerWidget {
  const SearchScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final controller = useTextEditingController();
    final focusNode = useFocusNode();
    final searchResultState = ref.watch(searchResultProvider);
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
                shape: WidgetStateProperty.all(
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
                  if (searchResultState.isLoading)
                    Padding(
                      padding: const EdgeInsets.only(right: 10),
                      child: SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 3),
                      ),
                    ),
                  if (controller.text.isNotEmpty &&
                      !searchResultState.isLoading)
                    IconButton(
                      hoverColor: Colors.transparent,
                      onPressed: () async {
                        // clear input and results, then return focus
                        controller.clear();
                        ref.read(searchQueryProvider.notifier).state = null;

                        await scrollController.animateTo(
                          scrollController.position.minScrollExtent,
                          duration: Duration(milliseconds: 300),
                          curve: Curves.easeOut,
                        );
                        focusNode.requestFocus();
                      },
                      icon: Icon(Icons.close),
                    ),
                ],
                hintText: 'Search for apps',
                hintStyle:
                    WidgetStateProperty.all(TextStyle(color: Colors.grey[600])),
                elevation: WidgetStateProperty.all(2.2),
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
        if (searchResultState.hasError)
          Text(searchResultState.error!.toString()),
        Expanded(
          child: SingleChildScrollView(
            controller: scrollController,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              key: UniqueKey(),
              children: [
                if ((searchResultState.value?.isEmpty ?? false) &&
                    !searchResultState.isLoading)
                  Padding(
                    padding: const EdgeInsets.only(top: 16),
                    child: Center(
                        child: Text(
                      'No results for ${ref.read(searchQueryProvider)}',
                      style: TextStyle(fontSize: 16),
                    )),
                  ),
                if (searchResultState.value?.isNotEmpty ?? false)
                  for (final app in searchResultState.value!)
                    AppCard(model: app),
                Gap(20),
                const AppCurationContainer(),
                Gap(20),
                LatestReleasesContainer(scrollController: scrollController),
                Gap(10),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

final searchQueryProvider = StateProvider<String?>((ref) => null);

final searchResultProvider =
    AsyncNotifierProvider<SearchResultNotifier, List<App>?>(
        SearchResultNotifier.new);

class SearchResultNotifier extends AsyncNotifier<List<App>?> {
  @override
  Future<List<App>?> build() async {
    final query = ref.watch(searchQueryProvider);
    if (query != null) {
      return await ref.apps.findAll(params: {'search': query, 'limit': 16});
    }
    return null;
  }
}
