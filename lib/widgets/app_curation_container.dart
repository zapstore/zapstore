import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/app_curation_set.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/app_card.dart';
import 'package:zapstore/widgets/pill_widget.dart';
import 'package:zapstore/widgets/rounded_image.dart';

class AppCurationContainer extends HookConsumerWidget {
  const AppCurationContainer({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scrollController = useScrollController();
    final selectedAppCurationSet = ref.watch(_selectedIdProvider);
    final appCurationSets = ref.appCurationSets.findAllLocal();

    // Custom curation set to place nostr set first (as its preloaded)
    final nostrCurationSet = appCurationSets
        .firstWhere((s) => s.getReplaceableEventLink() == kNostrCurationSet);
    final customAppCurationSets = [
      nostrCurationSet,
      ...appCurationSets..remove(nostrCurationSet)
    ];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          controller: scrollController,
          child: Row(
            children: [
              for (final appCurationSet in customAppCurationSets)
                Padding(
                  padding: const EdgeInsets.only(right: 12),
                  child: GestureDetector(
                    onTap: () => ref.read(_selectedIdProvider.notifier).state =
                        appCurationSet.getReplaceableEventLink(),
                    child: PillWidget(
                      text: WidgetSpan(
                        alignment: PlaceholderAlignment.middle,
                        child: Wrap(
                          crossAxisAlignment: WrapCrossAlignment.center,
                          children: [
                            Text(
                              appCurationSet.name,
                              style: TextStyle(fontWeight: FontWeight.bold),
                            ),
                            if (appCurationSet.signer.isPresent)
                              Row(
                                children: [
                                  Text(
                                      ' by ${appCurationSet.signer.value!.name}'),
                                  Gap(5),
                                  RoundedImage(
                                      url: appCurationSet
                                          .signer.value!.avatarUrl,
                                      size: 16),
                                ],
                              ),
                          ],
                        ),
                      ),
                      color: appCurationSet.getReplaceableEventLink() ==
                              selectedAppCurationSet
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
                ref.watch(appCurationSetProvider(selectedAppCurationSet));
            return state.when(
              data: (set) => HorizontalGrid(apps: set.apps.toList()),
              error: (e, _) => Text('Error: $e'),
              loading: () => HorizontalGrid(apps: []),
            );
          },
        ),
      ],
    );
  }
}

class HorizontalGrid extends StatelessWidget {
  final List<App> apps;

  HorizontalGrid({super.key, required this.apps});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 230,
      child: GridView.builder(
        scrollDirection: Axis.horizontal,
        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          childAspectRatio: 1.15,
          crossAxisSpacing: 8,
          mainAxisSpacing: 8,
        ),
        itemCount: apps.isEmpty ? 8 : apps.length,
        itemBuilder: (context, i) => TinyAppCard(app: apps.elementAtOrNull(i)),
      ),
    );
  }
}

// Providers

class AppCurationSetNotifier
    extends FamilyAsyncNotifier<AppCurationSet, ReplaceableEventLink> {
  @override
  Future<AppCurationSet> build(ReplaceableEventLink arg) async {
    final appCurationSet = ref.appCurationSets.findOneLocalById(arg.formatted)!;
    await ref.apps.findAll(params: {'#d': appCurationSet.appIds});
    return appCurationSet;
  }
}

final appCurationSetProvider = AsyncNotifierProvider.family<
    AppCurationSetNotifier,
    AppCurationSet,
    ReplaceableEventLink>(AppCurationSetNotifier.new);

final _selectedIdProvider =
    StateProvider<ReplaceableEventLink>((_) => kNostrCurationSet);

const kNostrCurationSet = (30267, kZapstorePubkey, 'nostr');
