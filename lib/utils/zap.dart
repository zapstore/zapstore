import 'package:flutter_data/flutter_data.dart';
import 'package:ndk/domain_layer/usecases/zaps/zap_receipt.dart';
import 'package:ndk/domain_layer/usecases/zaps/zaps.dart';
import 'package:ndk/ndk.dart';
import 'package:ndk_amber/data_layer/repositories/signers/nip55_event_signer.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/utils/nwc.dart';

import '../models/user.dart';

class ZapNotifier extends StateNotifier<AsyncValue<String>?> {
  Ref<AsyncValue<NwcConnection>?> ref;

  ZapNotifier(this.ref) : super(null);

  Future<void> zap(
      {required User user,
      String? eventId,
      required String lnurl,
      required int amount,
      required String pubKey}) async {
    final nwcConnection = ref.read(nwcConnectionProvider.notifier).state;
    if (nwcConnection == null || !nwcConnection.hasValue) {
      return;
    }
    state = AsyncValue.loading();
    final signer = Nip55EventSigner(publicKey: user.pubkey);
    final socialRelays = ref.read(relayProviderFamily(kSocialRelays).notifier);
    final relays = socialRelays.ndk != null
        ? socialRelays.ndk!.config.bootstrapRelays
        : kSocialRelays.where((r) => r != 'ndk');
    try {
      ZapResponse zapResponse = await ndkForNwc.zaps.zap(
        nwcConnection: nwcConnection.value!,
        lnurl: lnurl,
        amountSats: amount,
        fetchZapReceipt: true,
        signer: signer,
        relays: relays,
        pubKey: pubKey,
        eventId: eventId,
      );
      ZapReceipt? zapReceipt = await zapResponse.zapReceipt;
      if (zapReceipt!=null) {
        print("ZAP RECEIPT: $zapReceipt");
      }
      if (zapResponse.payInvoiceResponse != null &&
          zapResponse.payInvoiceResponse!.preimage.isNotEmpty) {
        state = AsyncValue.data(zapResponse.payInvoiceResponse!.preimage);
      } else {
        state = AsyncValue.error(
            zapResponse.payInvoiceResponse?.errorMessage ??
                "couldn't pay, unknown error",
            StackTrace.current);
      }
    } catch (e) {
      state = AsyncValue.error(e.toString(), StackTrace.current);
    }
    Future.delayed(Duration(seconds: 5)).then((_) {
      state = null;
    });
  }
}

final zapProvider =
    StateNotifierProvider<ZapNotifier, AsyncValue<String>?>((ref) {
  return ZapNotifier(ref.read(nwcConnectionProvider.notifier).ref);
});
