# WORK-018 — App Detail Live Refresh

**Feature:** FEAT-004-asset-first-queries.md
**Status:** In Progress

## Problem

An open app-detail page uses one-shot catalog queries. A newly published release
can therefore leave the page showing a stale installable and an `Open` CTA until
the user leaves and reopens the page.

## Tasks

- [x] 1. Subscribe to the selected app and its current asset/release
  relationships while the detail screen is mounted.
  - Files: `lib/screens/app_detail_screen.dart`
  - Preserve cached rendering and rely on provider disposal for cancellation.
- [ ] 2. Verify static analysis for the changed screen.
- [x] 3. Self-review against `INVARIANTS.md`.

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| Cached detail opened offline | Cached app and CTA render without waiting for relay | [ ] |
| New asset published while open | Relationship updates and CTA re-evaluates | [ ] |
| Detail screen closed | Query subscriptions are auto-disposed | [ ] |
| Relay fails | Cached detail remains usable | [ ] |

## Decisions

### 2026-07-14 — Stream only the open app detail

**Context:** The detail page must receive a release published after it opens.
**Decision:** Use the existing `query` provider with `stream: true` for the
page's app, current asset, release, and release metadata relationships.
**Rationale:** Purplebase owns the subscription lifecycle, local cache renders
first, and closing the auto-disposed provider cancels every subscription.

## Spec Issues

_None — authorized as an obvious bug fix using the existing asset-first spec._

## Progress Notes

**2026-07-14:** Identified one-shot detail queries as the stale-CTA path.
**2026-07-14:** Switched the detail app, asset, release, and metadata queries
to a shared live source. `fvm flutter analyze lib/screens/app_detail_screen.dart`
is blocked in this environment because the analyzer cannot create
`/Users/zed/.dartServer`.
