import 'dart:convert';

import 'package:ndk/ndk.dart';
import 'package:ndk/shared/nips/nip19/nip19.dart';
import 'package:signer_plugin/signer_plugin.dart';

class Nip55EventSigner implements EventSigner {
  final _signerPlugin = SignerPlugin();
  bool isAvailable = false;

  final String publicKey;

  /// get a amber event signer
  Nip55EventSigner({
    required this.publicKey,
  });

  @override
  Future<void> sign(Nip01Event event) async {
    final npub = publicKey.startsWith('npub')
        ? publicKey
        : Nip19.encodePubKey(publicKey);
    final signedMessage =
        await _signerPlugin.signEvent(jsonEncode(event.toJson()), "", npub);
    final signedEvent = jsonDecode(signedMessage['event']);

    event.sig = signedEvent['sig'];
  }

  @override
  String getPublicKey() {
    return publicKey;
  }

  @override
  Future<String?> decrypt(String msg, String destPubKey, {String? id}) async {
    final npub = publicKey.startsWith('npub')
        ? publicKey
        : Nip19.encodePubKey(publicKey);
    Map<dynamic, dynamic> map =
        await _signerPlugin.nip04Decrypt(msg, id!, npub, destPubKey);
    return map['signature'];
  }

  @override
  Future<String?> encrypt(String msg, String destPubKey, {String? id}) async {
    final npub = publicKey.startsWith('npub')
        ? publicKey
        : Nip19.encodePubKey(publicKey);
    Map<dynamic, dynamic> map =
        await _signerPlugin.nip04Encrypt(msg, id!, npub, destPubKey);
    return map['signature'];
  }

  @override
  bool canSign() {
    return publicKey.isNotEmpty;
  }
}
