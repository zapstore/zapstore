/// A single Namecoin ElectrumX endpoint.
///
/// The default WSS endpoint convention on public Namecoin ElectrumX
/// servers is `port = base + 4` (so `50002` TLS → `50004` WSS,
/// `57002` TLS → `57004` WSS). Both share the same TLS certificate.
class ElectrumxServer {
  /// Hostname, e.g. `electrumx.testls.space`.
  final String host;

  /// Port, e.g. `50004` for the WSS endpoint.
  final int port;

  /// WSS path. Defaults to `/`.
  final String path;

  /// `true` if the server uses TLS (`wss://`), `false` for plain
  /// (`ws://`).
  final bool useTls;

  /// Creates a server descriptor.
  const ElectrumxServer({
    required this.host,
    required this.port,
    this.path = '/',
    this.useTls = true,
  });

  /// Builds the wire URL (e.g. `wss://host:port/`) for this server.
  String get url {
    final scheme = useTls ? 'wss' : 'ws';
    final p = path.startsWith('/') ? path : '/$path';
    return '$scheme://$host:$port$p';
  }

  @override
  String toString() => url;
}

/// The built-in list of public Namecoin ElectrumX WSS endpoints,
/// tried in order with failover.
///
/// Both currently-listed operators serve **self-signed** TLS
/// certificates. Browser environments will refuse the handshake; in
/// Dart's VM target the default `SecurityContext` validates the
/// chain, so callers using these endpoints will need to plug in
/// pinned-cert trust (see the README) or run their own ElectrumX
/// instance with a CA-issued cert.
///
/// Keep this list aligned with the canonical references:
///   * Go: `mstrofnone/nostrlib-nip05-namecoin/namecoin/servers.go`
///   * TS: `mstrofnone/nostr-tools` PR #533 (`nip05namecoin.ts`)
///   * Kotlin: `vitorpamplona/amethyst` `ElectrumXServer.kt`
const List<ElectrumxServer> defaultElectrumxServers = [
  ElectrumxServer(host: 'electrumx.testls.space', port: 50004),
  ElectrumxServer(host: 'nmc2.bitcoins.sk', port: 57004),
  ElectrumxServer(host: '46.229.238.187', port: 57004),
];

/// Number of blocks after which a Namecoin name expires if not
/// re-registered (≈ 250 days at 10 min/block). Sourced from
/// `chainparams.cpp → consensus.nNameExpirationDepth` in
/// `namecoin/namecoin-core`.
const int namecoinNameExpireDepth = 36000;
