import 'package:flutter_data/flutter_data.dart';
import 'package:ndk/domain_layer/usecases/lnurl/lnurl.dart';
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
    final nwcConnection  = ref.read(nwcConnectionProvider.notifier).state;
    if (nwcConnection==null || !nwcConnection.hasValue) {
      return;
    }
    state = AsyncValue.loading();
    String? lud16Link = Lnurl.getLud16LinkFromLud16(lnurl);
    final signer = Nip55EventSigner(publicKey: user.pubkey);
    final socialRelays = ref.read(relayProviderFamily(kSocialRelays).notifier);
    String? invoice = await Lnurl.getInvoiceCode(
        lud16Link: lud16Link!,
        sats: amount,
        recipientPubkey: pubKey,
        eventId: eventId,
        signer: signer,
        relays: socialRelays.ndk!=null ? socialRelays.ndk!.config.bootstrapRelays: kSocialRelays.where((r) => r!='ndk')
    );
    if (invoice == null) {
      state = AsyncValue.error("could not generate invoice for $lnurl", StackTrace.current);
      return;
    }
    try {
      PayInvoiceResponse response =
      await ndkForNwc.nwc.payInvoice(nwcConnection.value!, invoice: invoice);
      if (response.preimage.isNotEmpty && response.errorCode != null) {
        state = AsyncValue.data(response.preimage);
      } else {
        state = AsyncValue.error(
            response.errorMessage ?? "couldn't pay, unknown error",
            StackTrace.current);
      }
    } catch (e) {
      state = AsyncValue.error(
          e.toString(),
          StackTrace.current);
    }
    Future.delayed(Duration(seconds: 5)).then((_) {
      state = null;
    });
  }
}

final zapProvider = StateNotifierProvider<ZapNotifier, AsyncValue<String>?>((ref) {
  return ZapNotifier(ref.read(nwcConnectionProvider.notifier).ref);
});
