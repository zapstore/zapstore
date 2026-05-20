---
issue: https://github.com/zapstore/zapstore/issues/362
nip: https://github.com/mstrofnone/nips/blob/master/N4.md
status: draft (experimental \u2014 ingest only)
---

# WORK-N4 — Service attestations (kind 38383/38384) ingest

## Goal

Add a read-only ingest path for NIP-N4 `kind:38383` service
attestations \u2014 events that say "attester X rates attestee Y at
score Z for service category S, optionally anchored at Namecoin
block H."

This packet ships **ingest + summary + tests** only. No UI surface
on the developer-detail screen, no influence on whitelisting /
blocking / install \u2014 those are explicitly excluded by the proposal
in #362.

## Honest scope: experimental ingest

NIP-N4 is draft-only with no production producer yet. The proposal
is comfortable parking this until at least one independent
implementation exists. What this PR delivers is the *reader*:

* `ServiceAttestation` plain-Dart model
* `parseServiceAttestation()` from `(pubkey, tags, content)` to
  the model, with all the tolerance INVARIANTS.md demands
* `dedupeAttestations()` collapsing to newest per `(attester, dTag)`
* `AttestationSummary` aggregates (attester count, average rating,
  Namecoin-anchored count)
* `AttestationQueryService` that pulls kind:38383 events from local
  storage and produces a summary
* Riverpod provider so a UI surface can subscribe later

That's it. No badge on the dev profile, no rating row on the app
detail screen. Those land in a follow-up after the kind has rough
consensus on `nostr-protocol/nips`.

## Scope

### In scope
- `lib/services/attestations/` \u2014 models, parser, dedupe, summary,
  query service, provider.
- 18 new tests covering all of the parser semantics including
  unknown-tag tolerance.

### Out of scope
- UI surface on developer profile / app detail.
- Settings toggle.
- Influence on whitelisting / blocking / install \u2014 explicitly
  excluded by the #362 proposal.
- Namecoin-anchor *verification* (re-running NIP-N1 at the cited
  block height). The `nmc` tag is parsed and surfaced through
  `namecoinAnchoredCount`, but no chain query happens at this stage.

## Architecture fit

* No `models` change \u2014 `ServiceAttestation` is a plain-Dart type
  decoupled from the kind registry, matching the experimental
  nature of the kind. When the kind stabilises in
  `nostr-protocol/nips`, the right move is to land it in `models`
  and migrate this surface to that type.
* No `purplebase` change \u2014 queries use the existing
  `storage.query(Request<Model<dynamic>>(...))` path that
  `deletion_processor.dart` already uses for ad-hoc kind queries.
* `LocalSource` only \u2014 the service does not generate background
  network traffic when the feature is unused.

## Invariants honoured

- **Data robustness**: parser drops malformed tags / ratings /
  anchors without throwing. Tolerates unknown tags (one test
  explicitly covers `future-tag`).
- **Async discipline**: ingest is fully async; never throws
  (errors logged + empty summary returned).
- **Security**: parser rejects events whose `p` tag is not 64-hex,
  and explicitly checks that the attestee in the parsed event
  matches the requested pubkey (mitigates relay-side mis-routing).

## Done when

- [x] Models + parser + dedupe + summary.
- [x] Query service + Riverpod provider.
- [x] 18 parser tests passing; full project 35/35 green.
- [x] `flutter analyze` clean.
- [ ] Maintainer review \u2014 *explicitly comfortable with "not yet"*.
- [ ] Follow-up: UI surface, once the kind has consensus.
