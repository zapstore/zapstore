import 'dart:async';

import 'package:ndk/domain_layer/entities/nip_01_event.dart';
import 'package:purplebase/purplebase.dart' hide FileMetadata;
import 'package:zapstore/main.data.dart';

import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/models/file_metadata.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/rounded_image.dart';

class ZapReceipts extends HookConsumerWidget {
  ZapReceipts({
    super.key,
    required this.fileMetadata,
  });

  final FileMetadata fileMetadata;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final zapReceipts = ref.watch(zapReceiptsNotifier(fileMetadata));

    if (zapReceipts.isLoading) {
      return CircularProgressIndicator();
    }

    if (zapReceipts.value!.isEmpty) {
      // Do not show anything if there are no zaps
      return Container();
    }

    final receipts = zapReceipts.value!;
    final totalAmountInSats = receipts.fold(0, (acc, e) => acc + e.amount);

    final senderIds = receipts.map((r) => r.senderPubkey).toSet();
    final senders = ref.users.findManyLocalByIds(senderIds);

    return Row(
      children: [
        Text("⚡ $totalAmountInSats sats (${receipts.length} zaps)"),
        Gap(5),
        RichText(
          text: TextSpan(
            children: [
              for (final user in senders)
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
            ],
          ),
        )
      ],
    );
  }
}

class ZapReceiptsNotifier extends StateNotifier<AsyncValue<Set<ZapReceipt>>> {
  Ref ref;
  FileMetadata fileMetadata;
  StreamSubscription<Nip01Event>? sub;

  ZapReceiptsNotifier(this.ref, this.fileMetadata)
      : super(AsyncData(ref.zapReceipts
            // TODO: Perform an actual query
            .findAllLocal()
            .where((r) =>
                r.recipient.id == fileMetadata.author.id &&
                r.eventId == fileMetadata.id)
            .toSet())) {
    final adapter = ref.users.nostrAdapter;

    final recipient =
        fileMetadata.event.getTag('zap') ?? fileMetadata.event.pubkey;
    print('querying for $recipient and id ${fileMetadata.event.id}');
    final receiptsResponse = adapter.socialRelays.ndk!.zaps
        .subscribeToZapReceipts(
            pubKey: recipient, eventId: fileMetadata.event.id);

    sub = receiptsResponse.stream.listen((data) {
      state = AsyncData({
        if (state.hasValue) ...state.value!,
        Event.getConstructor<ZapReceipt>()!.call(data.toJson())
      });
    });
  }

  @override
  void dispose() {
    sub?.cancel();
    super.dispose();
  }
}

final zapReceiptsNotifier = StateNotifierProvider.autoDispose
    .family<ZapReceiptsNotifier, AsyncValue<Set<ZapReceipt>>, FileMetadata>(
        ZapReceiptsNotifier.new);
