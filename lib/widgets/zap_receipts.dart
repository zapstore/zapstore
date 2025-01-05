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
    final signedInUser = ref.watch(signedInUserProvider);

    // NOTE: These watchers will become more efficient
    ref.users.watchAll();

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

    final zapperIds = receipts.map((r) => r.senderPubkey).toSet();
    final zappers = ref.users.findManyLocalByIds(zapperIds);

    return Padding(
      padding: const EdgeInsets.only(top: 10, bottom: 10),
      child: UsersRichText(
        leadingTextSpan: TextSpan(
          style: TextStyle(fontSize: 16),
          children: [
            TextSpan(text: '⚡'),
            TextSpan(
                text: ' $totalAmountInSats sats ',
                style: TextStyle(fontWeight: FontWeight.bold)),
            TextSpan(text: 'zapped by'),
          ],
        ),
        users: zappers,
        signedInUser: signedInUser,
        maxUsersToDisplay: 5,
      ),
    );
  }
}

class ZapReceiptsNotifier extends StateNotifier<AsyncValue<Set<ZapReceipt>>> {
  Ref ref;
  final FileMetadata fileMetadata;
  StreamSubscription<List<Nip01Event>>? sub;

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

    final localZapReceipts = ref.zapReceipts.zapReceiptAdapter
        .findByRecipient(
            pubkey: developerPubkey, eventId: fileMetadata.id!.toString())
        .toSet();
    _loadZappers(localZapReceipts);
    state = AsyncData(localZapReceipts);

    // NOTE: ideally this caching stuff should be handled by purplebase
    final latestReceiptTimestamp = localZapReceipts
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

    sub = bufferByTime(receiptsResponse.stream, Duration(seconds: 1))
        .listen((data) async {
      final zapReceipts =
          data.map((r) => ZapReceipt.fromJson(r.toJson()).init().saveLocal());
      await _loadZappers(zapReceipts);
      state = AsyncData({if (state.hasValue) ...state.value!, ...zapReceipts});
    });
  }

  // Load senders - this tedious work will be handled by purplebase at some point
  Future<void> _loadZappers(Iterable<ZapReceipt> receipts) async {
    // If we do not have the users locally, trigger a remote fetch
    final receiptsPubkeys = receipts.map((r) => r.senderPubkey).toSet();
    final existingPubkeysLocally =
        ref.users.nostrAdapter.existingIds(receiptsPubkeys).toSet();
    final missingPubkeysLocally =
        receiptsPubkeys.difference(existingPubkeysLocally);
    if (missingPubkeysLocally.isNotEmpty) {
      final fetchedSenders =
          await ref.users.findAll(params: {'authors': missingPubkeysLocally});
      final stillMissingPubkeys = missingPubkeysLocally
          .toSet()
          .difference(fetchedSenders.map((s) => s.id!.toString()).toSet());
      for (final pubkey in stillMissingPubkeys) {
        // If relays do not have it, create a dummy local user
        User.fromPubkey(pubkey).init().saveLocal();
      }
    }
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
