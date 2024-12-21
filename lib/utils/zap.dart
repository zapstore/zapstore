import 'package:flutter_data/flutter_data.dart';
import 'package:ndk/domain_layer/usecases/lnurl/lnurl.dart';
import 'package:ndk/ndk.dart';
import 'package:ndk_amber/data_layer/repositories/signers/nip55_event_signer.dart';
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
    final signer = Nip55EventSigner(publicKey: user.pubkey);
    String? invoice = await Lnurl.getInvoiceCode(
        lud16Link: lud16Link!,
        sats: amount,
        recipientPubkey: pubKey,
        eventId: eventId,
        signer: signer,
        relays: kSocialRelays.where((e) => e != 'ndk'));
    if (invoice == null) {
      // TODO: how show error?
      state = null;
      return;
    }
    PayInvoiceResponse response =
        await ndkForNwc.nwc.payInvoice(nwcConnection, invoice: invoice);
    if (response.preimage.isNotEmpty) {
      state = "zapped";
      Future.delayed(Duration(seconds: 3)).then((_) {
        state = null;
      });
    } else {
      state = null;
    }
  }
}

final zapProvider = StateNotifierProvider<ZapNotifier, String?>((ref) {
  return ZapNotifier();
});
