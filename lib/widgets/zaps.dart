import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/utils/zap.dart';
import 'package:zapstore/widgets/rounded_image.dart';

class Zaps extends HookConsumerWidget {
  Zaps({
    super.key,
    required this.app,
  });

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final zapReceipts = ref.watch(zapReceiptsNotifier);

    if (app.developer.value?.lud16 != null && app.latestMetadata != null) {
      if (zapReceipts == null) {
        Future(() {
          ref.read(zapReceiptsNotifier.notifier).fetchZaps(app.latestMetadata!);
        });
      } else {
        final receipts = zapReceipts.value;
        if (receipts != null) {
          receipts
              .sort((a, b) => (b.amountSats ?? 0).compareTo(a.amountSats ?? 0));

          var eventSum = 0;
          for (var receipt in receipts) {
            eventSum += receipt.amountSats ?? 0;
          }
          final senderIds =
              receipts.map((receipt) => receipt.sender).nonNulls.toSet();

          final senders =
              ref.watch(zappersProvider((zapperIds: senderIds.take(10))));

          final zapperAvatars = switch (senders) {
            AsyncData<List<User>>(value: final users) => Builder(
                builder: (context) {
                  return RichText(
                      text: TextSpan(children: [
                    for (final user in users)
                      TextSpan(
                        style: TextStyle(height: 1.6),
                        children: [
                          WidgetSpan(
                            alignment: PlaceholderAlignment.middle,
                            child: Wrap(
                              crossAxisAlignment: WrapCrossAlignment.center,
                              children: [
                                RoundedImage(url: user.avatarUrl, size: 20),
                              ],
                            ),
                          ),
                        ],
                      )
                  ]));
                },
              ),
            AsyncError(:final error) =>
              Center(child: Text('Error loading zapper profiles: $error')),
            // Loading state
            _ => Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    children: [
                      SizedBox(
                        width: 14,
                        height: 14,
                        child: CircularProgressIndicator(),
                      ),
                    ],
                  ),
                ),
              )
          };
          return Row(
            children: [
              Text("⚡ $eventSum sats (${receipts.length} zaps)"),
              Gap(5),
              zapperAvatars
            ],
          );
        }
      }
    }
    return Container();
  }
}

final zappersProvider = FutureProvider.autoDispose
    .family<List<User>, ({Iterable<String> zapperIds})>((ref, arg) {
  // TODO it should load from either local or remote if not cached locally still
  return ref.users.findManyLocalByIds(arg.zapperIds);
});
