import 'dart:convert';

import 'package:zapstore/services/namecoin/identifier.dart';

final RegExp _hexPubKeyRegex = RegExp(r'^[0-9a-fA-F]{64}$');

/// A pubkey + optional relays extracted from a Namecoin name value.
class NamecoinNostrEntry {
  /// Lowercase hex-encoded 32-byte public key.
  final String pubkey;

  /// Relay URLs published alongside the pubkey, or empty if none.
  final List<String> relays;

  /// Creates a [NamecoinNostrEntry] with the given fields.
  const NamecoinNostrEntry({required this.pubkey, this.relays = const []});
}

/// Pull the `nostr` pubkey and optional relay list out of a Namecoin
/// name value [valueJson].
///
/// Supports both:
///   * the simple `"nostr": "hex-pubkey"` form, and
///   * the extended `"nostr": { "names": {...}, "relays": {...} }`
///     form used by Amethyst and the `.bit` NIP-05 spec draft.
///
/// Returns `null` if the JSON is malformed, has no `nostr` field, or
/// no valid pubkey matches the requested local-part.
NamecoinNostrEntry? extractNostrFromValue(
  String valueJson,
  ParsedIdentifier parsed,
) {
  Map<String, dynamic> root;
  try {
    final decoded = json.decode(valueJson);
    if (decoded is! Map<String, dynamic>) return null;
    root = decoded;
  } on FormatException {
    return null;
  }

  final nostrField = root['nostr'];
  if (nostrField == null) return null;

  // Simple form: "nostr": "hex-pubkey"
  if (nostrField is String) {
    if (parsed.isDomain && parsed.localPart != '_') return null;
    if (!_hexPubKeyRegex.hasMatch(nostrField)) return null;
    return NamecoinNostrEntry(pubkey: nostrField.toLowerCase());
  }

  // Extended form: object with "names" and optional "relays".
  if (nostrField is! Map<String, dynamic>) return null;

  if (parsed.isDomain) {
    return _extractFromDomainNamesObject(nostrField, parsed);
  }
  return _extractFromIdentityObject(nostrField, parsed);
}

NamecoinNostrEntry? _extractFromDomainNamesObject(
  Map<String, dynamic> obj,
  ParsedIdentifier parsed,
) {
  final names = obj['names'];
  if (names is! Map<String, dynamic>) return null;

  String? pickedPubkey;
  final exact = names[parsed.localPart];
  if (exact is String && _hexPubKeyRegex.hasMatch(exact)) {
    pickedPubkey = exact;
  } else {
    final underscore = names['_'];
    if (underscore is String && _hexPubKeyRegex.hasMatch(underscore)) {
      pickedPubkey = underscore;
    } else if (parsed.localPart == '_') {
      // Weak fallback: first valid pubkey (only when caller asked for root).
      for (final v in names.values) {
        if (v is String && _hexPubKeyRegex.hasMatch(v)) {
          pickedPubkey = v;
          break;
        }
      }
    }
  }

  if (pickedPubkey == null) return null;

  final relays = _extractRelays(obj, pickedPubkey);
  return NamecoinNostrEntry(
    pubkey: pickedPubkey.toLowerCase(),
    relays: relays,
  );
}

NamecoinNostrEntry? _extractFromIdentityObject(
  Map<String, dynamic> obj,
  ParsedIdentifier parsed,
) {
  // Try "pubkey" field first (id/ shape).
  final pk = obj['pubkey'];
  if (pk is String && _hexPubKeyRegex.hasMatch(pk)) {
    final relaysRaw = obj['relays'];
    final relays = relaysRaw is List
        ? relaysRaw.whereType<String>().toList(growable: false)
        : const <String>[];
    return NamecoinNostrEntry(pubkey: pk.toLowerCase(), relays: relays);
  }

  // Fall back to NIP-05-like "names" with "_" root.
  final names = obj['names'];
  if (names is Map<String, dynamic>) {
    final underscore = names['_'];
    if (underscore is String && _hexPubKeyRegex.hasMatch(underscore)) {
      final relays = _extractRelays(obj, underscore);
      return NamecoinNostrEntry(
        pubkey: underscore.toLowerCase(),
        relays: relays,
      );
    }
  }

  return null;
}

List<String> _extractRelays(Map<String, dynamic> obj, String pubkey) {
  final raw = obj['relays'];
  if (raw is! Map<String, dynamic>) return const [];
  final candidate = raw[pubkey.toLowerCase()] ?? raw[pubkey];
  if (candidate is! List) return const [];
  return candidate.whereType<String>().toList(growable: false);
}
