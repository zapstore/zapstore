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
    print('got $map');
    return map['npub'] ?? map['result'];
  }

  @override
  Future<T> sign<T extends BaseEvent<T>>(T model, {String? asUser}) async {
    if (!isAvailable) {
      throw Exception("Cannot sign, missing Amber");
    }

    if (model is BaseDirectMessage) {
      final signedMessage = await _signerPlugin.nip04Encrypt(
          model.content,
          "",
          (asUser ?? model.pubkey).npub,
          (model as BaseDirectMessage).receiver.hexKey);
      final encryptedContent = signedMessage['result'];
      model =
          (model as BaseDirectMessage).copyWith(content: encryptedContent) as T;
    }

    final pubkey = asUser ?? model.pubkey;
    // Remove all null fields (Amber otherwise crashes)
    final map = {
      for (final e in model.toMap().entries)
        if (e.value != null) e.key: e.value
    };
    final signedMessage =
        await _signerPlugin.signEvent(jsonEncode(map), "", pubkey);
    final signedEvent = jsonDecode(signedMessage['event']);
    final fn = BaseEvent.constructorForKind<T>(model.kind)!;
    return fn.call(signedEvent);
  }
}

final pkSigner = PrivateKeySigner(
    'e593c54f840b32054dcad0fac15d57e4ac6523e31fe26b3087de6b07a2e9af58');
