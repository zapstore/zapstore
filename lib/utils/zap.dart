import 'dart:async';

import 'package:flutter_data/flutter_data.dart';
import 'package:ndk/domain_layer/usecases/zaps/zap_receipt.dart';
import 'package:ndk/domain_layer/usecases/zaps/zaps.dart';
import 'package:purplebase/purplebase.dart' hide FileMetadata, User;
import 'package:zapstore/models/file_metadata.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/utils/nip55_event_signer.dart';
import 'package:zapstore/utils/nwc.dart';

import '../models/user.dart';

class ZapReceiptsNotifier extends StateNotifier<AsyncValue<List<ZapReceipt>>?> {
  Ref ref;

  ZapReceiptsNotifier(this.ref) : super(null);

  StreamSubscription<ZapReceipt>? sub;

  Future<void> fetchZaps(FileMetadata fileMetadata) async {
    state = null;
    final socialRelays = ref.read(relayProviderFamily(kSocialRelays).notifier);
    if (socialRelays.ndk != null) {
      state = AsyncValue.loading();

      final receiptsResponse = socialRelays.ndk!.zaps.fetchZappedReceipts(
          pubKey: fileMetadata.event.pubkey,
          eventId: fileMetadata.id!.toString());
      sub = receiptsResponse.listen((receipt) {
        addZapReceipt(receipt);
      });
    }
  }

  void addZapReceipt(ZapReceipt receipt) {
    if (state == null || state!.value == null) {
      state = AsyncValue.data([receipt]);
    } else {
      final receipts = state!.value!;
      receipts.add(receipt);
      state = AsyncValue.data(receipts);
    }
  }

  @override
  void dispose() {
    sub?.cancel();
    super.dispose();
  }
}

class ZapNotifier extends StateNotifier<AsyncValue<ZapResponse?>> {
  Ref ref;

  ZapNotifier(this.ref) : super(AsyncData(null));

  Future<void> zap(
      {required User user,
      String? eventId,
      required String lnurl,
      required int amount,
      required String pubKey}) async {
    final nwcConnectionState = ref.read(nwcConnectionProvider.notifier).state;
    if (nwcConnectionState.value == null) {
      return;
    }
    state = AsyncValue.loading();
    final signer = Nip55EventSigner(publicKey: user.pubkey);
    final socialRelays = ref.read(relayProviderFamily(kSocialRelays).notifier);
    final relays = socialRelays.ndk != null
        ? socialRelays.ndk!.config.bootstrapRelays
        : kSocialRelays.where((r) => r != 'ndk');
    try {
      final zapResponse = await ndkForNwc.zaps.zap(
        nwcConnection: nwcConnectionState.value!,
        lnurl: lnurl,
        amountSats: amount,
        fetchZapReceipt: true,
        signer: signer,
        relays: relays,
        pubKey: pubKey,
        eventId: eventId,
      );
      if (zapResponse.payInvoiceResponse != null &&
          zapResponse.payInvoiceResponse!.preimage.isNotEmpty) {
        state = AsyncValue.data(zapResponse);
        final zapReceipt = await zapResponse.zapReceipt;
        if (zapReceipt != null) {
          ref.read(zapReceiptsNotifier.notifier).addZapReceipt(zapReceipt);
        }
      } else {
        state = AsyncValue.error(
            zapResponse.payInvoiceResponse?.errorMessage ??
                "couldn't pay, unknown error",
            StackTrace.current);
      }
    } catch (e, stack) {
      state = AsyncValue.error(e, stack);
    }
  }
}

final zapProvider =
    StateNotifierProvider<ZapNotifier, AsyncValue<ZapResponse?>>(
        ZapNotifier.new);

final zapReceiptsNotifier =
    StateNotifierProvider<ZapReceiptsNotifier, AsyncValue<List<ZapReceipt>>?>(
        ZapReceiptsNotifier.new);
