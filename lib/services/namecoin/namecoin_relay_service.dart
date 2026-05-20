import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/services/namecoin/electrumx_client.dart';
import 'package:zapstore/services/namecoin/record_parser.dart';
import 'package:zapstore/services/namecoin/relay_resolver.dart';

/// Outcome of resolving a `wss://...bit` URL.
///
/// Sealed-class state model so call sites (UI, validation helpers,
/// relay-pool startup) must handle every outcome explicitly.
sealed class NamecoinRelayResolveState {
  const NamecoinRelayResolveState();
}

/// The URL is not a Namecoin `.bit` URL — no resolution needed.
class NamecoinRelayNotApplicable extends NamecoinRelayResolveState {
  const NamecoinRelayNotApplicable();
}

/// The chain resolved the `.bit` hostname to a real `wss://` endpoint.
class NamecoinRelayResolved extends NamecoinRelayResolveState {
  /// The original `wss://...bit` URL the user typed.
  final String canonicalUrl;

  /// The real `wss://` endpoint to dial.
  final String resolvedUrl;

  /// Other `wss://` candidates from the same record (if any).
  final List<String> alternates;

  /// `ws[s]://...onion` aliases, if the record advertises any.
  final List<String> onionEndpoints;

  const NamecoinRelayResolved({
    required this.canonicalUrl,
    required this.resolvedUrl,
    required this.alternates,
    required this.onionEndpoints,
  });
}

/// The chain has no record for that name, or the record advertises
/// no clearnet relay (and no Tor endpoint Zapstore can use today).
class NamecoinRelayUnresolved extends NamecoinRelayResolveState {
  /// Diagnostic message (the chain name we looked up, plus a brief
  /// reason).
  final String reason;

  const NamecoinRelayUnresolved(this.reason);
}

/// Every configured ElectrumX server failed. The user can retry.
class NamecoinRelayUnreachable extends NamecoinRelayResolveState {
  final Object? lastError;
  const NamecoinRelayUnreachable([this.lastError]);
}

/// Resolves `wss://...bit` relay URLs to their clearnet endpoint via
/// Namecoin.
///
/// Does the chain query directly so it can distinguish definitive
/// negatives (name not registered / no `wss` endpoint) from
/// transport failures — the underlying resolver in `relay_resolver.dart`
/// collapses both into `null`.
class NamecoinRelayService {
  final ElectrumxClient _client;
  final bool _ownsClient;

  /// Creates a service using the bundled default ElectrumX list.
  NamecoinRelayService()
      : _client = DefaultElectrumxClient(),
        _ownsClient = true;

  /// Creates a service with a caller-supplied [client]. Useful in
  /// tests and for users who want to override the server list.
  NamecoinRelayService.withClient(ElectrumxClient client)
      : _client = client,
        _ownsClient = false;

  /// Returns `true` for `wss://...bit` / `ws://...bit` URLs.
  static bool isApplicable(String url) =>
      NamecoinRelayResolver.isBitUrl(url);

  /// Attempts to resolve [url] against the Namecoin chain. Never
  /// throws — every failure maps to a state.
  Future<NamecoinRelayResolveState> resolve(String url) async {
    if (!isApplicable(url)) {
      return const NamecoinRelayNotApplicable();
    }

    final uri = Uri.tryParse(url);
    if (uri == null) {
      return const NamecoinRelayNotApplicable();
    }
    final hostFlat = parseHostFlat(uri.host);
    if (hostFlat == null) {
      return const NamecoinRelayNotApplicable();
    }

    String valueJson;
    try {
      valueJson = await _client.nameShow(hostFlat.namecoinName);
    } on NameNotFoundException catch (e) {
      LogService.I.info(
        'namecoin relay: name not found',
        tag: 'namecoin',
        fields: {'name': hostFlat.namecoinName},
      );
      return NamecoinRelayUnresolved('name not registered: ${e.name}');
    } on NameExpiredException catch (e) {
      return NamecoinRelayUnresolved('name expired: ${e.name}');
    } on Exception catch (e) {
      LogService.I.warn(
        'namecoin relay: electrumx unreachable',
        tag: 'namecoin',
        fields: {'url': url, 'error': e.toString()},
      );
      return NamecoinRelayUnreachable(e);
    }

    final candidates =
        parseRelayUrls(valueJson, hostFlat.subdomainLabels);
    final onionEndpoints =
        parseTorEndpoints(valueJson, hostFlat.subdomainLabels);

    if (candidates.isEmpty) {
      if (onionEndpoints.isNotEmpty) {
        return NamecoinRelayUnresolved(
          'record has no clearnet wss endpoint (onion-only)',
        );
      }
      return NamecoinRelayUnresolved(
        'no wss endpoint advertised by ${hostFlat.namecoinName}',
      );
    }

    return NamecoinRelayResolved(
      canonicalUrl: url,
      resolvedUrl: candidates.first,
      alternates: candidates.length > 1
          ? candidates.skip(1).toList(growable: false)
          : const [],
      onionEndpoints: onionEndpoints,
    );
  }

  /// Releases ElectrumX client resources owned by this service.
  Future<void> close() async {
    if (_ownsClient) await _client.close();
  }
}

/// Riverpod provider exposing a single shared [NamecoinRelayService].
final namecoinRelayServiceProvider =
    Provider.autoDispose<NamecoinRelayService>((ref) {
  final service = NamecoinRelayService();
  ref.onDispose(() => unawaited(service.close()));
  return service;
});
