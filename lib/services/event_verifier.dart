import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:models/models.dart';

/// Verifier used for every Nostr event received from a relay.
///
/// A BIP-340 signature only commits to the event id, so checking the signature
/// alone proves nothing about the payload: a relay can keep a publisher's
/// `(pubkey, id, sig)` triple and serve arbitrary `kind`, `tags` and `content`
/// with it. Zapstore derives install decisions from those tags — the APK hash,
/// its download URL and the expected signing certificate — so the id is
/// recomputed from the payload before the signature is checked, as required by
/// NIP-01.
class ZapstoreEventVerifier extends Verifier {
  ZapstoreEventVerifier({Verifier? signatureVerifier})
    : _signatureVerifier = signatureVerifier ?? DartVerifier();

  final Verifier _signatureVerifier;

  @override
  bool verify(Map<String, dynamic> map) {
    if (!hasAuthenticId(map)) return false;
    return _signatureVerifier.verify(map);
  }

  /// Whether `id` is the SHA-256 of the NIP-01 serialization of the event.
  ///
  /// Returns `false` for any event that cannot be serialized canonically, so
  /// malformed input is rejected instead of being trusted.
  static bool hasAuthenticId(Map<String, dynamic> map) {
    final id = map['id'];
    final pubkey = map['pubkey'];
    final createdAt = map['created_at'];
    final kind = map['kind'];
    final tags = map['tags'];
    final content = map['content'];

    if (id is! String ||
        pubkey is! String ||
        createdAt is! int ||
        kind is! int ||
        tags is! List ||
        content is! String) {
      return false;
    }

    final String serialized;
    try {
      serialized = json.encode([
        0,
        pubkey.toLowerCase(),
        createdAt,
        kind,
        tags,
        content,
      ]);
    } on JsonUnsupportedObjectError {
      return false;
    }

    final computed = sha256.convert(utf8.encode(serialized)).toString();
    return computed == id.toLowerCase();
  }
}

/// Override applied to every Zapstore [ProviderContainer] so relay events are
/// validated the same way in the UI isolate and in background isolates.
final zapstoreVerifierOverride = verifierProvider.overrideWith(
  (ref) => ZapstoreEventVerifier(),
);
