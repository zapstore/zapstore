import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:purplebase/purplebase.dart' as base;
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app_curation_set.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/horizontal_grid.dart';
import 'package:zapstore/widgets/pill_widget.dart';
import 'package:zapstore/widgets/rounded_image.dart';

class AppCurationContainer extends HookConsumerWidget {
  const AppCurationContainer({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scrollController = useScrollController();
    final selectedAppCurationSet = ref.watch(_selectedIdProvider);
    final appCurationSets = ref.appCurationSets
        .findAllLocal()
        .sortedBy((s) => s.event.createdAt)
        .reversed
        .toList();

    // Custom curation set to place nostr set first (as its preloaded)
    final nostrCurationSet = appCurationSets.firstWhereOrNull(
        (s) => s.getReplaceableEventLink() == kNostrCurationSetLink);
    if (nostrCurationSet != null) {
      appCurationSets.remove(nostrCurationSet);
      appCurationSets.insert(0, nostrCurationSet);
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          controller: scrollController,
          child: Row(
            children: [
              for (final appCurationSet in appCurationSets)
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
              error: (e, _) => Text('Error: $e ; $_'),
              loading: () => HorizontalGrid(apps: []),
            );
          },
        ),
      ],
    );
  }
}

// Providers

class AppCurationSetNotifier
    extends FamilyAsyncNotifier<AppCurationSet, base.ReplaceableEventLink> {
  @override
  Future<AppCurationSet> build(base.ReplaceableEventLink arg) async {
    final appCurationSet = ref.appCurationSets.findOneLocalById(arg.formatted)!;
    fetch();
    return appCurationSet;
  }

  Future<void> fetch() async {
    final appCurationSet = await future;

    if (appCurationSet.apps.isEmpty) {
      state = AsyncLoading();
    }
    state = await AsyncValue.guard(() async {
      // Only load apps (no releases for now, includes=false)
      // We can use ignoreReturn here as we do not care about releases
      await ref.apps.findAll(
        params: {
          '#d': appCurationSet.appIds,
          'includes': false,
          'ignoreReturn': true,
        },
      );
      return appCurationSet;
    });
  }
}

final appCurationSetProvider = AsyncNotifierProvider.family<
    AppCurationSetNotifier,
    AppCurationSet,
    base.ReplaceableEventLink>(AppCurationSetNotifier.new);

final _selectedIdProvider =
    StateProvider<base.ReplaceableEventLink>((_) => kNostrCurationSetLink);

const kNostrCurationSetLink = (30267, kFranzapPubkey, 'nostr');
