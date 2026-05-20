---
issue: https://github.com/zapstore/zapstore/issues/364
nip: https://github.com/mstrofnone/nips/blob/master/N3.md
status: draft (Stage 1 only)
---

# WORK-N3 — TLSA pin parsing for `.bit` relay WebSockets

## Goal

Parse on-chain TLSA pin records (RFC 6698) for `.bit` Nostr relay
hostnames and expose them through a `NamecoinTlsaService` so the
rest of the app can:

* Surface "TLSA pin available, not yet enforced" diagnostics to the
  user.
* (Future) Hand the pins to a platform-channel TLS verifier that
  enforces them at WebSocket connect time.

## Honest scope: Stage 1 only

Flutter's high-level `WebSocket` API does **not** expose the peer
certificate. Enforcing on-chain pins requires either:

* A platform channel into OkHttp's `CertificatePinner` (Android), or
* A custom `IOClient` + `SecurityContext` per connection.

Either path is invasive enough to deserve its own conversation. This
packet is therefore **Stage 1**: parser + service + tests + a clearly
labelled "diagnostics-only" disclaimer in the public API.

**No connection-time enforcement is added by this PR.**

### In scope
- `lib/services/namecoin/tlsa.dart` \u2014 RFC 6698 parser, ported CC0
  from [dart-nostr#44](https://github.com/ethicnology/dart-nostr/pull/44).
- `lib/services/namecoin/record_parser.dart` \u2014 same module as
  #366 / #367 with the TLSA functions restored.
- `lib/services/namecoin/namecoin_tlsa_service.dart` \u2014 service
  with sealed-class state model:
  `NotApplicable | PinsAvailable | NoPins | Unknown | Unreachable`.
- 27 new tests (8 service + 19 inherited parser/TLSA).

### Explicitly out of scope
- WebSocket connect-time enforcement.
- Visible UI badge in the relay card. (One-line follow-up once
  Stage 1 lands and is reviewed.)
- Diagnostics-screen integration. Same.

## Why ship Stage 1 alone

Per `spec/guidelines/QUALITY_BAR.md`: "Happy-path-only
implementations are insufficient" \u2014 but it would be worse to ship
fake enforcement. The Stage 1 service is *honest*: it tells you a
pin exists and what it pins, but does not pretend to enforce it.

Stage 2 can ship later as a follow-up that wires the pins into a
platform channel, and Stage 1's API is stable across that
transition.

## Done when

- [x] TLSA parser + service.
- [x] 45/45 tests passing in `test/services/namecoin/`.
- [x] `flutter analyze` clean.
- [ ] Stage 1.5: diagnostics-screen surface (follow-up).
- [ ] Stage 2: platform-channel enforcement (follow-up).
