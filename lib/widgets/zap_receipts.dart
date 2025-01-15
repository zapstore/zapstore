import 'dart:async';

import 'package:collection/collection.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:gap/gap.dart';
import 'package:ndk/domain_layer/entities/filter.dart';
import 'package:ndk/domain_layer/entities/nip_01_event.dart';
import 'package:zapstore/main.data.dart';

import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/file_metadata.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/models/zap_receipt.dart';
import 'package:zapstore/screens/settings_screen.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/users_rich_text.dart';
import 'package:zapstore/widgets/zap_button.dart';

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
    // NOTE: Only show zaps for self-signed apps (at least for now)
    final isSelfSigned = app.signer.value == app.developer.value;
    if (fileMetadata == null || !isSelfSigned) {
      return Container();
    }

    final zapReceipts = ref.watch(zapReceiptsNotifier(fileMetadata));

    if (zapReceipts.isLoading) {
      return _wrap(
        Row(
          children: [
            Gap(4),
            Text('Loading zaps...'),
            Gap(10),
            SmallCircularProgressIndicator(),
          ],
        ),
      );
    }

    if (zapReceipts.value!.isEmpty) {
      return _wrap(Container());
    }

    final receipts = zapReceipts.value!;
    final satsAmount = receipts.fold(0, (acc, e) => acc + e.amount);
    final formattedSatsAmount = kNumberFormatter.format(satsAmount);

    const kMaxUsersToDisplay = 6;

    final zapperIds = receipts.map((r) => r.senderPubkey).toSet();
    final zappers = ref.users.findManyLocalByIds(zapperIds).sortedBy((u) {
      // Calculates total amount per user and uses `wrapped`
      // that compares by descending order
      final totalUserAmount = receipts
          .where((r) => u.pubkey == r.senderPubkey)
          .fold(0, (acc, e) => acc + e.amount);
      return totalUserAmount.wrapped;
    });

    return _wrap(
      UsersRichText(
        leadingTextSpan: TextSpan(
          style: TextStyle(fontSize: 16, height: 1.7),
          children: [
            TextSpan(text: '⚡'),
            TextSpan(
                text: ' $formattedSatsAmount sats ',
                style: TextStyle(fontWeight: FontWeight.bold)),
            TextSpan(text: 'zapped by'),
          ],
        ),
        users: zappers,
        signedInUser: signedInUser,
        maxUsersToDisplay: kMaxUsersToDisplay,
      ),
    );
  }

  Widget _wrap(Widget inner) {
    return Padding(
      padding: const EdgeInsets.only(top: 10, bottom: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: 10,
        children: [
          SizedBox(width: double.maxFinite, child: ZapButton(app: app)),
          inner,
        ],
      ),
    );
  }
}

final _zapsPreviouslyLoadedProvider =
    StateProvider.family<bool, String>((_, id) => false);

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

    final wereZapsPreviouslyLoaded =
        ref.read(_zapsPreviouslyLoadedProvider(fileMetadata.event.id));

    int? latestReceiptTimestamp;

    final localZapReceipts = ref.zapReceipts.zapReceiptAdapter
        .findByEventId(fileMetadata.id!.toString())
        .toSet();

    // If there were no zaps locally and this is the first time loading, keep the loading state
    // (yes, purplebase should handle this)
    if (localZapReceipts.isEmpty && !wereZapsPreviouslyLoaded) {
      state = AsyncLoading();
    } else {
      _loadZappers(localZapReceipts);
      state = AsyncData(localZapReceipts);

      // NOTE: ideally this caching stuff should be handled by purplebase
      latestReceiptTimestamp = localZapReceipts
          .sortedBy((z) => z.event.createdAt)
          .lastOrNull
          ?.event
          .createdAt
          .millisecondsSinceEpoch;
    }

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

    // If after 8 seconds we receive no zaps, then change the state to empty
    // (to prevent forever spinners) and set to loading attempted
    Timer(Duration(seconds: 8), () {
      if (mounted) {
        // Mark loading done for this event
        ref
            .read(_zapsPreviouslyLoadedProvider(fileMetadata.event.id).notifier)
            .state = true;
        if (state is AsyncLoading) {
          state = AsyncData({});
        }
      }
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

// Ridiculous to have to do this in Dart

extension _IntWrapperExt on int {
  _IntDescComparableWrapper get wrapped => _IntDescComparableWrapper(this);
}

class _IntDescComparableWrapper
    implements Comparable<_IntDescComparableWrapper> {
  final int value;

  _IntDescComparableWrapper(this.value);

  @override
  int compareTo(_IntDescComparableWrapper other) {
    return other.value.compareTo(value);
  }

  @override
  String toString() {
    return value.toString();
  }
}
