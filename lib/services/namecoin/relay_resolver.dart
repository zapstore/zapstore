import 'package:zapstore/services/namecoin/electrumx_client.dart';
import 'package:zapstore/services/namecoin/record_parser.dart';

/// The result of a `.bit` relay resolution attempt.
class RelayResolution {
  /// The canonical `.bit` URL the user typed. Identity for
  /// rendering / event tags / NIP-65 lists. Never rewritten.
  final String canonicalUrl;

  /// The real `ws[s]://` URL to actually open the WebSocket against,
  /// or `null` if the record advertises only a Tor hidden service.
  final String? clearnetUrl;

  /// All clearnet `ws[s]://` URLs found in the record (in priority
  /// order). [clearnetUrl] is the first usable one, with the
  /// [canonicalUrl]'s path merged when needed.
  final List<String> candidates;

  /// Per-record TLSA pin payloads (opaque to N2 — the parser /
  /// enforcement layer lives with the N3 follow-up).
  ///
  /// Always `const []` in N2 — reserved so N3 can add parsing
  /// without breaking the API.
  final List<Object> tlsaRecords;

  /// `ws[s]://...onion[/...]` URLs, if the record advertises any.
  /// Empty if the record has no Tor endpoint.
  ///
  /// Callers with Tor routing enabled SHOULD prefer one of these
  /// over [clearnetUrl]; callers without Tor should ignore them.
  final List<String> onionEndpoints;

  /// Creates a [RelayResolution] with the given fields.
  const RelayResolution({
    required this.canonicalUrl,
    required this.clearnetUrl,
    required this.candidates,
    required this.tlsaRecords,
    required this.onionEndpoints,
  });
}

/// Resolves `wss://example.bit/`-style Nostr relay URLs to their
/// underlying real endpoint, plus any TLSA pin records and Tor
/// aliases published in the same Namecoin record.
///
/// This is a **resolver only** — it returns data and never opens the
/// WebSocket itself. Host applications wire the returned URL +
/// `tlsaRecords` into their TLS stack (e.g. via `HttpClient`'s
/// `badCertificateCallback` on Dart VM) and decide whether to prefer
/// `onionEndpoints` based on their Tor settings.
///
/// Single ElectrumX call per registered name, regardless of subdomain
/// depth: `wss://relay.example.bit/` and `wss://example.bit/` share
/// the same `d/example` lookup. Cache uses positive 1 h / negative 1 m
/// TTLs by default.
class NamecoinRelayResolver {
  /// Servers / settings inherited from the supplied [ElectrumxClient].
  final ElectrumxClient client;

  /// Positive cache TTL for resolutions.
  final Duration positiveTtl;

  /// Negative cache TTL for unresolved hosts.
  final Duration negativeTtl;

  final Map<String, _CachedResolution> _cache = {};

  /// Creates a resolver. Pass [client] to share an ElectrumX
  /// connection pool with `NamecoinIdentifier.fetch` (recommended);
  /// otherwise a [DefaultElectrumxClient] is created lazily.
  NamecoinRelayResolver({
    ElectrumxClient? client,
    this.positiveTtl = const Duration(hours: 1),
    this.negativeTtl = const Duration(minutes: 1),
  }) : client = client ?? DefaultElectrumxClient();

  /// Returns `true` if [wssUrl] is a `ws://` or `wss://` URL whose
  /// host ends in `.bit`.
  static bool isBitUrl(String wssUrl) {
    final parsed = _parseWsUrl(wssUrl);
    if (parsed == null) return false;
    return parsed.host.endsWith('.bit');
  }

  /// Resolves a `.bit` relay URL.
  ///
  /// Returns `null` if [wssUrl] is not a `.bit` URL, the Namecoin
  /// record is absent / has no relay or tor field, or the lookup
  /// fails at the transport level.
  ///
  /// On success, the returned [RelayResolution]'s [RelayResolution.clearnetUrl]
  /// is the URL the caller should hand to their WebSocket client, and
  /// [RelayResolution.tlsaRecords] / [RelayResolution.onionEndpoints]
  /// are the same record's pin and Tor data for higher layers.
  Future<RelayResolution?> resolve(String wssUrl) async {
    final parsed = _parseWsUrl(wssUrl);
    if (parsed == null) return null;
    if (!parsed.host.endsWith('.bit')) return null;

    final host = parsed.host;
    final cached = _cache[host];
    if (cached != null && !cached.isExpired) {
      return cached.resolution(canonicalUrl: wssUrl);
    }

    final hostFlat = parseHostFlat(host);
    if (hostFlat == null) return null;

    String valueJson;
    try {
      valueJson = await client.nameShow(hostFlat.namecoinName);
    } on NameNotFoundException {
      _cache[host] = _CachedResolution.miss(negativeTtl);
      return null;
    } on Exception {
      // Transport-level failure: don't cache.
      return null;
    }

    final candidates =
        parseRelayUrls(valueJson, hostFlat.subdomainLabels);
    // TLSA parsing intentionally deferred to N3 (#364).
    const List<Object> tlsaRecords = [];
    final onionEndpoints =
        parseTorEndpoints(valueJson, hostFlat.subdomainLabels);

    if (candidates.isEmpty && onionEndpoints.isEmpty) {
      _cache[host] = _CachedResolution.miss(negativeTtl);
      return null;
    }

    final clearnet = candidates.isNotEmpty ? candidates.first : null;
    final resolved = clearnet != null
        ? _mergeOriginalPath(wssUrl, clearnet)
        : null;

    final cacheEntry = _CachedResolution.hit(
      candidates: candidates,
      tlsaRecords: tlsaRecords,
      onionEndpoints: onionEndpoints,
      ttl: positiveTtl,
    );
    _cache[host] = cacheEntry;
    return RelayResolution(
      canonicalUrl: wssUrl,
      clearnetUrl: resolved,
      candidates: candidates,
      tlsaRecords: tlsaRecords,
      onionEndpoints: onionEndpoints,
    );
  }

  /// Drops a single cached `.bit` host (e.g. after a connection
  /// failure).
  void invalidate(String host) {
    _cache.remove(host.toLowerCase());
  }

  /// Drops all cached `.bit` resolutions.
  void clear() {
    _cache.clear();
  }
}

/// If the user typed `wss://example.bit/rooms/foo` but the record only
/// exposes `wss://relay.example.com/`, preserve the user's path so
/// NIP-29 / room scoping keeps working.
String _mergeOriginalPath(String originalUrl, String resolvedUrl) {
  final resolvedPath = _pathOf(resolvedUrl);
  if (resolvedPath.length > 1) return resolvedUrl;
  final origPath = _pathOf(originalUrl);
  if (origPath.length > 1) {
    final base = resolvedUrl.endsWith('/')
        ? resolvedUrl.substring(0, resolvedUrl.length - 1)
        : resolvedUrl;
    return '$base$origPath';
  }
  return resolvedUrl;
}

String _pathOf(String url) {
  try {
    final uri = Uri.parse(url);
    return uri.path;
  } on FormatException {
    return '';
  }
}

class _ParsedWs {
  final String scheme;
  final String host;
  const _ParsedWs(this.scheme, this.host);
}

_ParsedWs? _parseWsUrl(String raw) {
  Uri uri;
  try {
    uri = Uri.parse(raw.trim());
  } on FormatException {
    return null;
  }
  final scheme = uri.scheme.toLowerCase();
  final host = uri.host.toLowerCase();
  if (scheme != 'ws' && scheme != 'wss') return null;
  if (host.isEmpty) return null;
  return _ParsedWs(scheme, host);
}

class _CachedResolution {
  final List<String> candidates;
  final List<Object> tlsaRecords;
  final List<String> onionEndpoints;
  final DateTime expiresAt;
  final bool isMiss;

  _CachedResolution({
    required this.candidates,
    required this.tlsaRecords,
    required this.onionEndpoints,
    required this.expiresAt,
    required this.isMiss,
  });

  factory _CachedResolution.hit({
    required List<String> candidates,
    required List<Object> tlsaRecords,
    required List<String> onionEndpoints,
    required Duration ttl,
  }) {
    return _CachedResolution(
      candidates: candidates,
      tlsaRecords: tlsaRecords,
      onionEndpoints: onionEndpoints,
      expiresAt: DateTime.now().add(ttl),
      isMiss: false,
    );
  }

  factory _CachedResolution.miss(Duration ttl) {
    return _CachedResolution(
      candidates: const [],
      tlsaRecords: const [],
      onionEndpoints: const [],
      expiresAt: DateTime.now().add(ttl),
      isMiss: true,
    );
  }

  bool get isExpired => DateTime.now().isAfter(expiresAt);

  RelayResolution? resolution({required String canonicalUrl}) {
    if (isMiss) return null;
    final clearnet = candidates.isNotEmpty
        ? _mergeOriginalPath(canonicalUrl, candidates.first)
        : null;
    return RelayResolution(
      canonicalUrl: canonicalUrl,
      clearnetUrl: clearnet,
      candidates: candidates,
      tlsaRecords: tlsaRecords,
      onionEndpoints: onionEndpoints,
    );
  }
}
