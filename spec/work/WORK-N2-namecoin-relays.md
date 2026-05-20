---
issue: https://github.com/zapstore/zapstore/issues/365
nip: https://github.com/mstrofnone/nips/blob/master/N2.md
status: draft
---

# WORK-N2 — Namecoin `.bit` relay hostname resolution

## Goal

When the user adds a `wss://relay.example.bit` URL in
`RelayManagementCard`, look up the matching Namecoin record on the
blockchain and surface the **resolved clearnet endpoint** so the user
can see what their `.bit` name actually points at.

Today the URL is treated as a literal DNS hostname; `.bit` is not in
DNS, so connection fails silently. This packet adds the resolution
layer that makes the chain-anchored hostname actionable.

## Scope

### In scope
- Self-contained `lib/services/namecoin/` module (shared with #366 /
  N1 PR — same resolver code).
- `NamecoinRelayService` with sealed-class state model:
  `NotApplicable | Resolved | Unresolved | Unreachable`.
- Background resolution at relay-add time: after the user adds a
  `.bit` URL the chain is queried and the resolved endpoint is shown
  in a snackbar. The canonical `.bit` URL is preserved in the list
  (so the user identifier remains stable).
- 11 service tests + 18 parser tests = 29 new tests total.

### Out of scope
- **Actually connecting via the resolved endpoint.** Per
  `spec/guidelines/ARCHITECTURE.md` "Relay pools and subscriptions
  are owned by purplebase." Wiring the resolved URL into the actual
  WebSocket dial path requires a coordinated purplebase change. This
  PR delivers the *visible verification* and the *reusable
  resolver*; the activation step is a separate proposal across the
  zapstore→purplebase boundary.
- TLSA pin enforcement on the resolved connection (#364 / N3).
- NIP-05 verification (#363 / N1 — separate PR, shares this module).
- Service attestations (#362 / N4).

## Architecture

```
RelayManagementCard.addRelay()
  └─► (existing) normalizes + dedupes + stages URL
  └─► if URL.host endsWith '.bit':
        └─► NamecoinRelayService.resolve(url)  ◄── *NEW*
              └─► ElectrumX nameShow(d/<name>)
              └─► parseRelayUrls / parseTorEndpoints
              └─► returns one of:
                    Resolved   → snackbar "resolved → wss://..."
                    Unresolved → snackbar "no wss endpoint"
                    Unreachable → snackbar "could not reach chain"
                    NotApplicable → no-op
```

The user-facing behaviour is *additive* — the existing relay-add
flow is unchanged for DNS hostnames.

## Invariants honoured

- **UI safety**: resolution is awaited via `unawaited()`; never
  blocks the add-relay button. Snackbar fires only if the widget is
  still mounted.
- **Async discipline**: explicit per-state result; no silent
  failures.
- **Security**: resolver returns a structured `Resolved` only when
  the on-chain record actually carries a `wss://` endpoint. No
  silent fall-through to a synthesised URL.
- **Data robustness**: parser tolerates missing/malformed fields
  (parser tests cover this).

## Open questions for review

- **Snackbar UX** vs inline status row in the relay card. Currently
  snackbar — happy to move to inline status text per the relay if
  the team prefers.
- **Cache TTL**: the `NamecoinRelayResolver` (inherited from
  dart-nostr#44) caches positive results for 1 h. Reasonable default
  but configurable if needed.
- **Server set**: ships the same 8-server default as the upstream
  reference. Zapstore-specific override is a one-line follow-up.

## Done when

- [x] Resolver code lands at `lib/services/namecoin/`.
- [x] `NamecoinRelayService` with sealed state.
- [x] `RelayManagementCard.addRelay()` triggers chain preview for
      `.bit` URLs.
- [x] Tests: 29 new passing; full project 44/44 green.
- [x] `flutter analyze` clean.
- [ ] Maintainer review.
- [ ] Optional follow-up: thread the resolved URL through to
      `purplebase` so the dial actually uses the chain endpoint.
