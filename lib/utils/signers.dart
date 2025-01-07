import 'dart:convert';

import 'package:purplebase/purplebase.dart';
import 'package:signer_plugin/signer_plugin.dart';

class AmberSigner extends Signer {
  final _signerPlugin = SignerPlugin();
  bool isAvailable = false;

  @override
  Future<AmberSigner> initialize() async {
    final hasExternalSigner = await _signerPlugin
        .isExternalSignerInstalled('com.greenart7c3.nostrsigner');
    if (hasExternalSigner) {
      await _signerPlugin.setPackageName('com.greenart7c3.nostrsigner');
      isAvailable = true;
    }
    return this;
  }

  @override
  Future<String?> getPublicKey() async {
    final map = await _signerPlugin.getPublicKey();
    return map['npub'] ?? map['result'];
  }

  @override
  Future<E> sign<E extends Event<E>>(PartialEvent<E> partialEvent,
      {String? withPubkey}) async {
    if (!isAvailable) {
      throw Exception("Cannot sign, missing Amber");
    }

    if (partialEvent is PartialDirectMessage) {
      final signedMessage = await _signerPlugin.nip04Encrypt(
          partialEvent.event.content,
          "",
          withPubkey!.npub,
          (partialEvent as PartialDirectMessage).receiver.hexKey);
      final encryptedContent = signedMessage['result'];
      partialEvent.event.content = encryptedContent;
    }

    // Remove all null fields (Amber otherwise crashes)
    final map = {
      for (final e in partialEvent.toMap().entries)
        if (e.value != null) e.key: e.value
    };
    final signedMessage =
        await _signerPlugin.signEvent(jsonEncode(map), "", withPubkey!);
    final signedEvent = jsonDecode(signedMessage['event']);
    return Event.getConstructor<E>()!.call(signedEvent);
  }
}

final pkSigner = Bip340PrivateKeySigner(
    'e593c54f840b32054dcad0fac15d57e4ac6523e31fe26b3087de6b07a2e9af58');
