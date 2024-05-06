import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/card.dart';
import 'package:zapstore/widgets/user_avatar.dart';

const kAndroidMimeType = 'application/vnd.android.package-archive';

class SearchScreen extends HookConsumerWidget {
  const SearchScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final controller = useTextEditingController();
    final focusNode = useFocusNode();
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
                        controller.clear();
                        focusNode.requestFocus();
                      },
                      icon: Icon(Icons.close),
                    ),
                ],
                hintText: 'Search for apps',
                hintStyle: MaterialStateProperty.all(
                    TextStyle(color: Colors.grey[600])),
                autoFocus: state.model.isEmpty,
                elevation: MaterialStateProperty.all(2.2),
                onSubmitted: (query) async {
                  ref.read(searchQueryProvider.notifier).state = query;
                },
              ),
            ),
          ],
        ),
        Gap(10),
        if (state.hasMessage)
          Expanded(
            child: Center(
              child: Text(
                state.message!,
                textAlign: TextAlign.center,
                style: context.theme.textTheme.bodyLarge,
              ),
            ),
          ),
        if (state.hasException) Text(state.exception!.toString()),
        if (state.hasModel && state.model.isNotEmpty)
          Expanded(
            child: SingleChildScrollView(
              child: Column(
                key: UniqueKey(),
                children: [
                  for (final app in state.model) AppCard(app: app),
                ],
              ),
            ),
          ),
      ],
    );
  }
}

final searchQueryProvider = StateProvider<String?>((ref) => null);

final searchStateProvider = StateNotifierProvider.autoDispose<
    DataStateNotifier<List<App>>, DataState<List<App>>>((ref) {
  final n = DataStateNotifier(
      data: DataState<List<App>>(
    [],
    isLoading: false,
    message: 'Welcome to zap.store!\n\nUse the search bar to find apps',
  ));

  update() {
    final query = ref.read(searchQueryProvider);
    if (query != null) {
      if (query.length < 3) {
        return n.updateWith(
            isLoading: false, message: 'Please provide a longer search term');
      }
      n.updateWith(model: <App>[], isLoading: true, message: null);
      final r = RegExp(query.replaceAll(' ', '|'), caseSensitive: false);
      final apps = ref.apps
          .findAllLocal()
          .where((app) =>
              (app.url ?? '').contains(r) ||
              app.name!.contains(r) ||
              app.content.contains(r) ||
              app.tags.any((e) => e.contains(r)))
          .toList();
      n.updateWith(model: apps, isLoading: false);
    }
  }

  final sub = ref.listen(searchQueryProvider, (_, query) async {
    if (query != null) {
      update();
      try {
        n.updateWith(isLoading: true);

        final apps = await ref.apps.findAll(params: {'search': query});

        if (apps.isEmpty && n.data.model.isEmpty) {
          n.updateWith(isLoading: false, message: 'No apps for term: $query');
          return;
        }

        // load all signers and developers
        final userIds = {
          for (final app in apps) app.signer.id,
          for (final app in apps) app.developer.id
        }.nonNulls;
        await ref.users.findAll(params: {'ids': userIds});
        n.updateWith(isLoading: false);
      } catch (e) {
        n.updateWith(isLoading: false, exception: e);
      }
    }
  });

  final dispose = ref.apps.watchAllNotifier().addListener((state) {
    update();
  });

  ref.onDispose(() {
    sub.close();
    dispose();
  });

  return n;
});
