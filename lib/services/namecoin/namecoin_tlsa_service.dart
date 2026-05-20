import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/services/namecoin/electrumx_client.dart';
import 'package:zapstore/services/namecoin/record_parser.dart';
import 'package:zapstore/services/namecoin/tlsa.dart';

/// Outcome of looking up TLSA pin records for a `.bit` host.
///
/// **Stage 1 (this service):** parsing + diagnostics only. No
/// enforcement at connection time \u2014 Flutter's high-level `WebSocket`
/// API doesn't expose the peer certificate. Stage 2 (separate
/// follow-up) needs platform-channel TLS pinning on Android.
sealed class NamecoinTlsaState {
  const NamecoinTlsaState();
}

/// The host is not a Namecoin `.bit` host \u2014 no lookup needed.
class NamecoinTlsaNotApplicable extends NamecoinTlsaState {
  const NamecoinTlsaNotApplicable();
}

/// The chain returned at least one parseable TLSA record.
class NamecoinTlsaPinsAvailable extends NamecoinTlsaState {
  /// The pins, in declaration order.
  final List<TlsaRecord> pins;

  /// The Namecoin name that was queried (`d/example`).
  final String namecoinName;

  const NamecoinTlsaPinsAvailable({
    required this.pins,
    required this.namecoinName,
  });
}

/// The chain has a record but it advertises no TLSA pins.
class NamecoinTlsaNoPins extends NamecoinTlsaState {
  const NamecoinTlsaNoPins();
}

/// The chain has no record / the record is unparseable.
class NamecoinTlsaUnknown extends NamecoinTlsaState {
  final String reason;
  const NamecoinTlsaUnknown(this.reason);
}

/// Transport-layer failure (every configured ElectrumX server
/// unreachable).
class NamecoinTlsaUnreachable extends NamecoinTlsaState {
  final Object? lastError;
  const NamecoinTlsaUnreachable([this.lastError]);
}

/// Fetches TLSA pin records for a `.bit` host. **Diagnostics only**
/// in this Stage 1 \u2014 the pins are surfaced through the UI / logs
/// so users can see they exist and inspect them, but they are NOT
/// enforced at WebSocket connect time.
///
/// Stage 2 (separate follow-up) needs an Android platform channel
/// that hooks into OkHttp's `CertificatePinner` (or an equivalent
/// path on other platforms).
class NamecoinTlsaService {
  final ElectrumxClient _client;
  final bool _ownsClient;

  /// Creates a service using the bundled default ElectrumX list.
  NamecoinTlsaService()
      : _client = DefaultElectrumxClient(),
        _ownsClient = true;

  /// Creates a service with a caller-supplied [client]. Useful in
  /// tests.
  NamecoinTlsaService.withClient(ElectrumxClient client)
      : _client = client,
        _ownsClient = false;

  /// Returns `true` if [host] is a `.bit` hostname.
  static bool isApplicable(String host) {
    final lower = host.trim().toLowerCase();
    if (lower.isEmpty) return false;
    return parseHostFlat(lower) != null;
  }

  /// Fetches and parses TLSA pins for [host]. Never throws.
  Future<NamecoinTlsaState> fetchPins(String host) async {
    final hostFlat = parseHostFlat(host);
    if (hostFlat == null) {
      return const NamecoinTlsaNotApplicable();
    }

    String valueJson;
    try {
      valueJson = await _client.nameShow(hostFlat.namecoinName);
    } on NameNotFoundException catch (e) {
      return NamecoinTlsaUnknown('name not registered: ${e.name}');
    } on NameExpiredException catch (e) {
      return NamecoinTlsaUnknown('name expired: ${e.name}');
    } on Exception catch (e) {
      LogService.I.warn(
        'namecoin tlsa: electrumx unreachable',
        tag: 'namecoin',
        fields: {'host': host, 'error': e.toString()},
      );
      return NamecoinTlsaUnreachable(e);
    }

    final pins = parseTlsaRecords(valueJson, hostFlat.subdomainLabels);
    if (pins.isEmpty) {
      return const NamecoinTlsaNoPins();
    }
    return NamecoinTlsaPinsAvailable(
      pins: pins,
      namecoinName: hostFlat.namecoinName,
    );
  }

  /// Releases ElectrumX client resources owned by this service.
  Future<void> close() async {
    if (_ownsClient) await _client.close();
  }
}

/// Riverpod provider exposing a single shared [NamecoinTlsaService].
final namecoinTlsaServiceProvider =
    Provider.autoDispose<NamecoinTlsaService>((ref) {
  final service = NamecoinTlsaService();
  ref.onDispose(() => unawaited(service.close()));
  return service;
});
