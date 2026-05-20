---
issue: https://github.com/zapstore/zapstore/issues/363
nip: https://github.com/mstrofnone/nips/blob/master/N1.md
status: draft
---

# WORK-N1 — NIP-05 verification via Namecoin (`.bit`)

## Goal

Render a real verification badge next to a profile's `nip05` field
when it ends in `.bit` (or uses the `d/` / `id/` prefixes), backed by
a query against the Namecoin blockchain via ElectrumX over WebSocket.

Today `ProfileIdentityRow` renders `profile.nip05` next to a generic
check icon with no underlying verification. This packet adds an
opt-in verification step for the `.bit` subset and surfaces the
result in explicit per-state UI. DNS-based NIP-05 verification is
**out of scope** for this packet — that is a separate gap.

## Scope

### In scope
- Self-contained `lib/services/namecoin/` module — ElectrumX client,
  Namecoin script parser, NIP-N0 record walker, identifier parser.
  Ported verbatim from
  [ethicnology/dart-nostr#44](https://github.com/ethicnology/dart-nostr/pull/44)
  (merged, CC0). TLSA parsing intentionally not ported here — that
  lives with the N3 follow-up (#364).
- `NamecoinNip05Service` (Riverpod-friendly) with a sealed-class state
  model: `NotApplicable | Resolving | Verified | Mismatch | Unverified
  | Unreachable`. Service never throws; every error maps to a state.
- `NamecoinNip05Badge` widget that renders all six states per
  QUALITY_BAR.
- `ProfileIdentityRow` integration — drops the inline check icon for
  the new badge. No other call-site change.
- Tests in `test/services/namecoin/` covering parser, host-flat
  splitter, and all six verification states with a fake transport.
- New direct dependencies: `crypto`, `convert` (both already
  transitive — promoted, no new wire-format risk).

### Out of scope
- TLSA pinning (#364, N3).
- Relay-hostname-via-Namecoin (#365, N2).
- Service attestations (#362, N4).
- Settings UI for toggling verification — service already supports
  the toggle via the widget's `enabled` parameter, but no settings
  screen wiring yet. Defaults to `enabled: true` for `.bit` only;
  DNS identifiers are not queried.
- DNS-based NIP-05 verification. Tracked separately.

## Architecture

```
\u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510      \u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510      \u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510
\u2502 ProfileIdentityRow  \u2502 \u2500\u2500\u25b6\u2502 NamecoinNip05Badge  \u2502 \u2500\u2500\u25b6\u2502 *Verification*     \u2502
\u2502 (existing widget)   \u2502      \u2502 (Riverpod consumer) \u2502      \u2502 FutureProvider     \u2502
\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518      \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518      \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518
                                                          \u2502
                          \u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518
                          \u25bc
                  \u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510
                  \u2502 NamecoinNip05Service\u2502
                  \u2502 - isApplicable      \u2502
                  \u2502 - verify(\u2026)         \u2502
                  \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518
                          \u2502
              \u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510
              \u25bc                                  \u25bc
   \u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510                  \u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510
   \u2502 ElectrumxClient \u2502\u2500\u2500\u25b6 wss://     \u2502 RecordParser /     \u2502
   \u2502 (interface)     \u2502   electrumx-server\u2502 value extractor    \u2502
   \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518                  \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518
```

Layer fit per ARCHITECTURE.md:
- `NamecoinNip05Service` and the resolver code sit in
  `lib/services/` — UI-facing orchestration, not domain logic.
- No `models` or `purplebase` change. The service does not touch
  storage or the relay pool.

## Invariants honoured (per INVARIANTS.md)

- **UI safety**: badge falls back gracefully through every error
  state — never blocks the UI thread, never crashes on malformed
  records (parser tests cover JSON, type, and unexpected-shape
  failures).
- **Async discipline**: `verify()` is fully async, returns explicit
  states, cancellable via `close()`. `FutureProvider.autoDispose`
  ties the lifetime to the widget tree.
- **Local-first**: when ElectrumX is unreachable the badge renders an
  explicit "cloud off" state rather than swallowing the error.
- **Security & verification**: service never returns Verified on a
  mismatched pubkey; mismatch is its own state and renders an
  explicit warning. Pubkey comparison is lower-cased; never
  short-circuits to true on parse failure.
- **Data robustness**: parser tests cover unknown fields, malformed
  JSON, non-string entries, and trailing-dot host shapes.

## Tests

- `test/services/namecoin/record_parser_test.dart` — 18 tests.
- `test/services/namecoin/namecoin_nip05_service_test.dart` —
  12 tests covering every state in the sealed class plus
  case-insensitivity and never-throws guarantees.

All 30 tests pass under `flutter test test/services/namecoin/`.

## Open questions for review

- **Default ElectrumX server set.** Today the bundled list is the
  same 8 public servers as the Amethyst / dart-nostr / nostr-tools
  implementations. Do we want the Zapstore-default list pinned to a
  smaller set, or made configurable in settings?
- **Badge placement.** Currently shows alongside the npub row. Open
  to moving it elsewhere if the team prefers.
- **Settings toggle UI.** Not added in this PR — the service is
  already gated behind the widget's `enabled` flag; a settings
  surface is a one-line follow-up if requested.

## Done when

- [x] Resolver code lands at `lib/services/namecoin/`.
- [x] `NamecoinNip05Service` + provider.
- [x] `NamecoinNip05Badge` widget covering every sealed state.
- [x] `ProfileIdentityRow` consumes the badge.
- [x] Tests: 30+ passing.
- [x] `flutter analyze` clean.
- [ ] Maintainer review (this PR).
- [ ] Settings toggle wiring (follow-up after maintainer signal).
