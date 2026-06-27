# WORK-014 — Device Relay List

**Feature:** FEAT-008-device-relays.md
**Status:** Complete

## Tasks

- [x] 1. Remove relay persistence from local settings
  - Files: `lib/services/settings_service.dart`, `lib/main.dart`
- [x] 2. Make kind 10067 app-managed instead of active-signer-resolved
  - Files: `../models/lib/src/models/relay_list.dart`
- [x] 3. Add temporary restart handoff and background default-relay check
  - Files: `lib/services/app_catalog_relay_service.dart`
- [x] 4. Integrate startup, lifecycle cancellation, and relay management UI
  - Files: `lib/main.dart`, `lib/widgets/relay_management_card.dart`
- [x] 5. Cover local-first, confirmation, failure, and handoff behavior
- [x] 6. Self-review against `spec/guidelines/INVARIANTS.md`

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| No local kind 10067 | Hardcoded default is active | [x] |
| Accepted local event | Device event relays are active before UI readiness | [x] |
| Changed remote event | User confirms before restart | [x] |
| Declined remote event | Current relays remain active | [x] |
| SQLite-clearing restart | Temporary event is restored, then erased | [x] |
| Offline check | Current/default list remains usable | [x] |
| Paused check | Remote request is cancelled | [x] |

## Decisions

### 2026-07-10 — SQLite event with temporary restart handoff

**Context:** Relay changes clear SQLite, but secure storage must not remain the
relay configuration source.
**Decision:** Store accepted configuration as kind 10067 in SQLite. Copy only
the signed event map to a dedicated secure-storage key immediately before
restart, restore it after database initialization, then delete the key.
**Rationale:** Preserves the accepted event through the existing restart while
keeping secure storage out of normal relay resolution.

### 2026-07-10 — Fixed bootstrap relay

**Context:** Discovering the relay-list event through the configured relay list
is circular.
**Decision:** Query and publish kind 10067 only through the hardcoded
`wss://relay.zapstore.dev`.
**Rationale:** The fallback is deterministic and available without stored
configuration.

## Spec Issues

_None_

## Progress Notes

**2026-07-10:** Scope intentionally excludes migration and private relay
content. Legacy local settings and Amber-authored events are ignored.

**2026-07-10:** Implementation complete. Zapstore analysis and all 57 app tests
pass; models analysis and the full models test suite pass.
