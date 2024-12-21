import 'package:amberflutter/amberflutter.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:ndk/domain_layer/usecases/lnurl/lnurl.dart';
import 'package:ndk/ndk.dart';
import 'package:ndk_amber/ndk_amber.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/utils/nwc.dart';

import '../models/user.dart';

class ZapNotifier extends StateNotifier<String?> {
  ZapNotifier() : super(null);

  Future<void> zap(
      {required NwcConnection nwcConnection,
      required User user,
      String? eventId,
      required String lnurl,
      required int amount,
      required String pubKey}) async {
    state = "zapping";
    String? lud16Link = Lnurl.getLud16LinkFromLud16(lnurl);
    final signer = AmberEventSigner(
        publicKey: user.pubkey, amberFlutterDS: AmberFlutterDS(Amberflutter()));
    String? invoice = await Lnurl.getInvoiceCode(
        lud16Link: lud16Link!,
        sats: amount,
        recipientPubkey: pubKey,
        eventId: eventId,
        signer: signer,
        relays: ndkForSocial.config.bootstrapRelays);
    if (invoice == null) {
      // TODO how show error?
      // context.showError(
      //     title: "could not get invoice from ${lnurl}");
      state = null;
      return;
    }
    PayInvoiceResponse response =
        await ndkForNwc.nwc.payInvoice(nwcConnection, invoice: invoice);
    if (response.preimage != '') {
      state = "zapped";
      Future.delayed(Duration(seconds: 3)).then((_) {
        state = null;
      });
    } else {
      state = null;
    }
  }
}

final ndkForSocial = Ndk(NdkConfig(
    eventVerifier: Bip340EventVerifier(),
    cache: MemCacheManager(),
    bootstrapRelays: kSocialRelays.toList()));

final zapProvider = StateNotifierProvider<ZapNotifier, String?>((ref) {
  return ZapNotifier();
});
