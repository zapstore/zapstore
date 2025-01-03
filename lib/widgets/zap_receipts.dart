import 'dart:async';

import 'package:collection/collection.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:ndk/domain_layer/entities/filter.dart';
import 'package:ndk/domain_layer/entities/nip_01_event.dart';
import 'package:zapstore/main.data.dart';

import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/file_metadata.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/models/zap_receipt.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/users_rich_text.dart';

class ZapReceipts extends HookConsumerWidget {
  ZapReceipts({
    super.key,
    required this.app,
  });

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final fileMetadata = app.latestMetadata;
    if (fileMetadata == null) {
      return Container();
    }

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

    return Padding(
      padding: const EdgeInsets.only(top: 10, bottom: 10),
      child: UsersRichText(
        preSpan: TextSpan(text: '⚡ $totalAmountInSats sats zapped from '),
        users: senders,
      ),
    );
  }
}

class ZapReceiptsNotifier extends StateNotifier<AsyncValue<Set<ZapReceipt>>> {
  Ref ref;
  final FileMetadata fileMetadata;
  StreamSubscription<Nip01Event>? sub;

  ZapReceiptsNotifier(this.ref, this.fileMetadata) : super(AsyncLoading()) {
    final adapter = ref.users.nostrAdapter;
    final developerPubkey =
        fileMetadata.event.getTag('zap') ?? fileMetadata.event.pubkey;

    // If it is a Zapstore-signed release from the indexer, return
    if (developerPubkey == kZapstorePubkey &&
        fileMetadata.release.value!.app.value!.identifier !=
            kZapstoreAppIdentifier) {
      state = AsyncData({});
      return;
    }

    final localReceipts = ref.zapReceipts.zapReceiptAdapter
        .findByRecipient(
            pubkey: developerPubkey, eventId: fileMetadata.id!.toString())
        .toSet();
    state = AsyncData(localReceipts);

    print('querying for $developerPubkey and id ${fileMetadata.event.id}');

    final latestReceiptTimestamp = localReceipts
        .sortedBy((z) => z.event.createdAt)
        .lastOrNull
        ?.event
        .createdAt
        .millisecondsSinceEpoch;

    final receiptsResponse = adapter.socialRelays.ndk!.requests.subscription(
      filters: [
        Filter(
          kinds: [9735],
          since: latestReceiptTimestamp != null
              ? latestReceiptTimestamp ~/ 1000 + 1
              : null,
          eTags: [fileMetadata.event.id],
          pTags: [developerPubkey],
        )
      ],
    );

    sub = receiptsResponse.stream.listen((data) {
      final model = ZapReceipt.fromJson(data.toJson()).init().saveLocal();
      state = AsyncData({if (state.hasValue) ...state.value!, model});
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
