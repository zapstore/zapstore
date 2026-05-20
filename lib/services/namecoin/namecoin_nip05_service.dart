import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/services/namecoin/electrumx_client.dart';
import 'package:zapstore/services/namecoin/identifier.dart';
import 'package:zapstore/services/namecoin/value.dart';

/// Verification state for a NIP-05 identifier resolved against the
/// Namecoin blockchain.
///
/// Modelled as a sealed class so call sites must handle every state
/// explicitly — per QUALITY_BAR.md "loading, empty, error, retry states
/// are mandatory".
sealed class NamecoinNip05State {
  const NamecoinNip05State();
}

/// The identifier does not point at a Namecoin name (`.bit`, `d/`,
/// or `id/`). The DNS-based NIP-05 path should be used instead.
class NamecoinNip05NotApplicable extends NamecoinNip05State {
  const NamecoinNip05NotApplicable();
}

/// Resolution is in flight.
class NamecoinNip05Resolving extends NamecoinNip05State {
  const NamecoinNip05Resolving();
}

/// Resolution succeeded and the on-chain pubkey matches the
/// claimed Nostr pubkey.
class NamecoinNip05Verified extends NamecoinNip05State {
  /// The Namecoin name that resolved (e.g. `d/example`).
  final String namecoinName;

  /// The local-part queried (`alice` or `_`).
  final String localPart;

  /// The hex pubkey returned by the chain. Always lowercase.
  final String pubkey;

  /// Optional per-pubkey relay hints from `nostr.relays` in the record.
  final List<String> relays;

  const NamecoinNip05Verified({
    required this.namecoinName,
    required this.localPart,
    required this.pubkey,
    required this.relays,
  });
}

/// Resolution succeeded but the on-chain pubkey does **not** match
/// the claimed Nostr pubkey. UI MUST treat this as an explicit
/// negative signal — not as "unverified".
class NamecoinNip05Mismatch extends NamecoinNip05State {
  /// The on-chain pubkey. Always lowercase.
  final String onChainPubkey;

  /// The pubkey we asked about.
  final String claimedPubkey;

  const NamecoinNip05Mismatch({
    required this.onChainPubkey,
    required this.claimedPubkey,
  });
}

/// The blockchain returned a definitive negative (name not registered
/// or expired), or the on-chain record had no usable `nostr` field.
class NamecoinNip05Unverified extends NamecoinNip05State {
  /// Why the lookup failed (for logs / diagnostics).
  final String reason;

  const NamecoinNip05Unverified(this.reason);
}

/// Transport-layer failure (every configured ElectrumX server failed,
/// or the call timed out). Distinct from [NamecoinNip05Unverified]
/// because the answer is *unknown*, not *no*.
class NamecoinNip05Unreachable extends NamecoinNip05State {
  /// Optional last underlying error for diagnostics.
  final Object? lastError;

  const NamecoinNip05Unreachable([this.lastError]);
}

/// Verifies NIP-05 identifiers backed by Namecoin (`.bit` / `d/<name>`
/// / `id/<name>`).
///
/// **Off by default.** Call sites that need verification must opt in
/// per their settings — this service does not auto-resolve every
/// `profile.nip05` it sees. See `NamecoinNip05ServiceSettings`.
///
/// Threading: every call is async and cancellable via [close]. The
/// service never blocks the UI thread (per INVARIANTS.md).
class NamecoinNip05Service {
  /// ElectrumX client to use. Defaults to the bundled WebSocket
  /// implementation against [defaultElectrumxServers].
  final ElectrumxClient _client;

  /// Whether the service owns the client (and should close it on
  /// [close]).
  final bool _ownsClient;

  /// Creates a service that opens fresh WebSockets per query using
  /// the bundled default ElectrumX server list.
  NamecoinNip05Service()
    : _client = DefaultElectrumxClient(),
      _ownsClient = true;

  /// Creates a service with a caller-supplied [client] — useful for
  /// tests and for users who want to override the server list.
  NamecoinNip05Service.withClient(this._client) : _ownsClient = false;

  /// Returns `true` when [identifier] is a Namecoin-shaped NIP-05
  /// (`.bit`, `d/<name>`, or `id/<name>`).
  static bool isApplicable(String? identifier) =>
      isBitIdentifier(identifier);

  /// Verifies that [identifier] resolves on the Namecoin chain to
  /// [claimedPubkey].
  ///
  /// Never throws — every error is mapped to a [NamecoinNip05State].
  Future<NamecoinNip05State> verify({
    required String identifier,
    required String claimedPubkey,
  }) async {
    final parsed = parseIdentifier(identifier);
    if (parsed == null) {
      return const NamecoinNip05NotApplicable();
    }

    String valueJson;
    try {
      valueJson = await _client.nameShow(parsed.namecoinName);
    } on NameNotFoundException catch (e) {
      LogService.I.info(
        'namecoin nip05: name not found',
        tag: 'namecoin',
        fields: {'name': parsed.namecoinName},
      );
      return NamecoinNip05Unverified('name not registered: ${e.name}');
    } on NameExpiredException catch (e) {
      LogService.I.info(
        'namecoin nip05: name expired',
        tag: 'namecoin',
        fields: {'name': parsed.namecoinName},
      );
      return NamecoinNip05Unverified('name expired: ${e.name}');
    } on Exception catch (e) {
      LogService.I.warn(
        'namecoin nip05: electrumx unreachable',
        tag: 'namecoin',
        fields: {'name': parsed.namecoinName, 'error': e.toString()},
      );
      return NamecoinNip05Unreachable(e);
    }

    final entry = extractNostrFromValue(valueJson, parsed);
    if (entry == null) {
      return const NamecoinNip05Unverified(
        'no usable nostr field in record',
      );
    }

    final claimed = claimedPubkey.toLowerCase();
    final onChain = entry.pubkey.toLowerCase();
    if (onChain != claimed) {
      return NamecoinNip05Mismatch(
        onChainPubkey: onChain,
        claimedPubkey: claimed,
      );
    }

    return NamecoinNip05Verified(
      namecoinName: parsed.namecoinName,
      localPart: parsed.localPart,
      pubkey: onChain,
      relays: entry.relays,
    );
  }

  /// Cancels any in-flight WebSocket activity. Safe to call multiple
  /// times.
  Future<void> close() async {
    if (_ownsClient) await _client.close();
  }
}

/// Riverpod provider exposing a single shared [NamecoinNip05Service]
/// for the lifetime of the app. Auto-disposes when no listeners
/// remain (Riverpod default for `.autoDispose`).
final namecoinNip05ServiceProvider =
    Provider.autoDispose<NamecoinNip05Service>((ref) {
  final service = NamecoinNip05Service();
  ref.onDispose(() => unawaited(service.close()));
  return service;
});

/// Resolves a single `(identifier, claimedPubkey)` pair. Use
/// `ref.watch(namecoinNip05VerificationProvider(...))` from a widget
/// to render verification state reactively.
final namecoinNip05VerificationProvider = FutureProvider.autoDispose
    .family<NamecoinNip05State, ({String identifier, String claimedPubkey})>(
  (ref, args) async {
    if (!NamecoinNip05Service.isApplicable(args.identifier)) {
      return const NamecoinNip05NotApplicable();
    }
    final service = ref.watch(namecoinNip05ServiceProvider);
    return service.verify(
      identifier: args.identifier,
      claimedPubkey: args.claimedPubkey,
    );
  },
);
