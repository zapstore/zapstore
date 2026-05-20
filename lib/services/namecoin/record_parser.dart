import 'dart:convert';

/// The result of splitting a `.bit` host into its registered Namecoin
/// name plus the subdomain path beneath it.
///
/// `relay.testls.bit` → `(d/testls, ["relay"])`
/// `testls.bit`       → `(d/testls, [])`
/// `a.b.c.testls.bit` → `(d/testls, ["a", "b", "c"])`
class ParsedHostFlat {
  /// e.g. `d/testls`.
  final String namecoinName;

  /// DNS order, most-specific first. Empty for bare hosts.
  final List<String> subdomainLabels;

  /// Creates a [ParsedHostFlat] with the given fields.
  const ParsedHostFlat({
    required this.namecoinName,
    required this.subdomainLabels,
  });
}

/// Parses [host] into the `(namecoinName, subdomainLabels)` pair used
/// by the relay resolver.
///
/// Returns `null` for non-`.bit` hosts and for the bare `.bit` TLD.
ParsedHostFlat? parseHostFlat(String host) {
  var lower = host.trim().toLowerCase();
  while (lower.endsWith('.')) {
    lower = lower.substring(0, lower.length - 1);
  }
  if (!lower.endsWith('.bit')) return null;
  final withoutTld = lower.substring(0, lower.length - 4);
  if (withoutTld.isEmpty) return null;
  final labels = withoutTld.split('.').where((l) => l.isNotEmpty).toList();
  if (labels.isEmpty) return null;
  // Last DNS label = registered Namecoin name.
  final registered = labels.last;
  final subdomain = labels.sublist(0, labels.length - 1);
  return ParsedHostFlat(
    namecoinName: 'd/$registered',
    subdomainLabels: subdomain,
  );
}

/// Walks a Namecoin domain object's [`map`][ifa-0001] tree to find the
/// effective Domain Name Object for [subdomainLabels].
///
/// Lookup at each level, in order:
///   1. Exact label match: `map[label]`.
///   2. Wildcard match:    `map["*"]`.
///   3. No match → returns `null`.
///
/// A `""` (empty-string) key at any level acts as a fallback whose
/// items merge into the parent; this rule is applied before recursing
/// deeper, so the returned object has the merged view at that level.
///
/// Pass an empty list to get the top-level object back unchanged.
///
/// **Returns the JsonObject AT the requested subdomain.** Does NOT
/// inherit `tls` / `relay` / etc. from ancestors — inheritance is
/// not part of the spec for these item types and would let a parent
/// silently authorise a subdomain it didn't create.
///
/// [ifa-0001]: https://github.com/namecoin/proposals/blob/master/ifa-0001.md
Map<String, dynamic>? walkSubdomain(
  Map<String, dynamic> rootObj,
  List<String> subdomainLabels,
) {
  var current = _mergeEmptyKeyDefaults(rootObj);
  for (final label in subdomainLabels.reversed) {
    final map = current['map'];
    if (map is! Map<String, dynamic>) return null;
    final raw = map[label] ?? map['*'];
    if (raw == null) return null;
    final childObj = _promoteShorthand(raw);
    if (childObj == null) return null;
    current = _mergeEmptyKeyDefaults(childObj);
  }
  return current;
}

Map<String, dynamic> _mergeEmptyKeyDefaults(Map<String, dynamic> obj) {
  final map = obj['map'];
  if (map is! Map<String, dynamic>) return obj;
  final defaults = map[''];
  if (defaults is! Map<String, dynamic>) return obj;
  final merged = Map<String, dynamic>.from(obj);
  defaults.forEach((k, v) {
    merged.putIfAbsent(k, () => v);
  });
  return merged;
}

Map<String, dynamic>? _promoteShorthand(Object? el) {
  if (el is Map<String, dynamic>) return el;
  if (el is String) {
    return {
      'ip': [el],
    };
  }
  return null;
}

/// Parses the `relay` / `relays` / `nostr.relay` / `nostr.relays` /
/// pubkey-keyed `nostr.relays[<pubkey>]` fields from [rawValueJson],
/// optionally walking into a subdomain first.
///
/// Order priority (de-duplicated, only `ws://` / `wss://` kept):
///   1. `relay` (string)
///   2. `relays` (array of strings)
///   3. `nostr.relay` (string)
///   4. `nostr.relays` (array of strings)
///   5. `nostr.relays[<pubkey>]` (array of strings keyed by pubkey)
List<String> parseRelayUrls(
  String rawValueJson, [
  List<String> subdomainLabels = const [],
]) {
  final root = _decodeJsonObject(rawValueJson);
  if (root == null) return const [];
  final target = walkSubdomain(root, subdomainLabels);
  if (target == null) return const [];
  return _collectRelayUrls(target);
}

List<String> _collectRelayUrls(Map<String, dynamic> obj) {
  final out = <String>[];

  _pushWsString(obj['relay'], out);
  _pushWsArray(obj['relays'], out);

  final nostr = obj['nostr'];
  if (nostr is Map<String, dynamic>) {
    _pushWsString(nostr['relay'], out);
    _pushWsArray(nostr['relays'], out);

    // pubkey-keyed shape: nostr.relays[<pubkey>] = ["wss://..."]
    final relays = nostr['relays'];
    if (relays is Map<String, dynamic>) {
      for (final v in relays.values) {
        _pushWsArray(v, out);
      }
    }
  }
  return out.toSet().toList(growable: false);
}

// Note: TLSA pin parsing lives in a separate file (added by the
// follow-up N3 proposal — see zapstore #364). N1 deliberately
// does not ship TLSA enforcement.

/// Parses the `tor` and `_tor.txt` fields for `.onion` endpoints,
/// optionally walking into a subdomain first.
///
/// Bare `.onion` hostnames are promoted to `ws://<hostname>/`.
/// Pre-formed `ws[s]://...onion[...]` URLs pass through as-is.
/// Anything else is dropped.
///
/// **Multi-label `.onion` hosts** (e.g. `evil.deadbeef.onion`) are
/// REJECTED so a record can't say "send my Tor traffic to a
/// subdomain of someone else's onion".
List<String> parseTorEndpoints(
  String rawValueJson, [
  List<String> subdomainLabels = const [],
]) {
  final root = _decodeJsonObject(rawValueJson);
  if (root == null) return const [];
  final target = walkSubdomain(root, subdomainLabels);
  if (target == null) return const [];
  return _collectTorEndpoints(target);
}

List<String> _collectTorEndpoints(Map<String, dynamic> obj) {
  final out = <String>[];
  _pushOnionField(obj['tor'], out);
  final torSub = obj['_tor'];
  if (torSub is Map<String, dynamic>) {
    _pushOnionField(torSub['txt'], out);
    _pushOnionField(torSub['tor'], out);
  }
  return out.toSet().toList(growable: false);
}

void _pushOnionField(Object? value, List<String> out) {
  if (value == null) return;
  if (value is String) {
    final n = _normalizeOnionUrl(value);
    if (n != null) out.add(n);
    return;
  }
  if (value is List) {
    for (final entry in value) {
      if (entry is String) {
        final n = _normalizeOnionUrl(entry);
        if (n != null) out.add(n);
      }
    }
  }
}

String? _normalizeOnionUrl(String raw) {
  final trimmed = raw.trim();
  if (trimmed.isEmpty) return null;
  // Pre-formed ws[s]://...
  final lower = trimmed.toLowerCase();
  if (lower.startsWith('ws://') || lower.startsWith('wss://')) {
    return trimmed.contains('.onion') ? trimmed : null;
  }
  // Bare host
  var bareHost = trimmed
      .replaceFirst(RegExp('^https?://', caseSensitive: false), '');
  final slash = bareHost.indexOf('/');
  if (slash >= 0) bareHost = bareHost.substring(0, slash);
  while (bareHost.endsWith('.')) {
    bareHost = bareHost.substring(0, bareHost.length - 1);
  }
  bareHost = bareHost.toLowerCase();
  if (!bareHost.endsWith('.onion')) return null;
  final label = bareHost.substring(0, bareHost.length - 6);
  if (label.isEmpty) return null;
  // Reject multi-label onion (subdomain of another onion).
  if (label.contains('.')) return null;
  return 'ws://$bareHost/';
}

void _pushWsString(Object? value, List<String> out) {
  if (value is! String) return;
  final trimmed = value.trim();
  if (_isWsUrl(trimmed)) out.add(trimmed);
}

void _pushWsArray(Object? value, List<String> out) {
  if (value is! List) return;
  for (final entry in value) {
    _pushWsString(entry, out);
  }
}

bool _isWsUrl(String url) {
  final lower = url.toLowerCase();
  return lower.startsWith('ws://') || lower.startsWith('wss://');
}

Map<String, dynamic>? _decodeJsonObject(String raw) {
  try {
    final decoded = json.decode(raw);
    if (decoded is Map<String, dynamic>) return decoded;
    return null;
  } on FormatException {
    return null;
  }
}
