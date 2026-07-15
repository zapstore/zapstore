# WORK-024 — Device Event PoW Queue

**Feature:** FEAT-006-device-key.md
**Status:** In Progress

## Tasks

- [x] 1. Add secure-storage pending markers keyed by device-event kind and `d`
  tag.
  - Files: `lib/services/device_private_event_service.dart`
- [x] 2. Save local-only device-event drafts before queueing PoW and relay work.
  - Files: `lib/services/device_private_event_service.dart`,
    `lib/services/device_state_service.dart`
- [x] 3. Process pending markers asynchronously at startup, after mutations,
  and on app resume without polling.
  - Files: `lib/main.dart`, `lib/services/device_private_event_service.dart`
- [x] 4. Route bookmarks, installed-app backups, and unmanaged-app writes
  through the queued draft workflow.
  - Files: `lib/services/bookmarks_service.dart`,
    `lib/services/updates_service.dart`,
    `lib/services/unmanaged_apps_service.dart`
- [x] 5. Route device-owned AppCatalog relay-list events through the queue,
  including restart-handoff recovery and bootstrap-relay publication.
  - Files: `lib/services/app_catalog_relay_service.dart`
- [x] 6. Add behavior tests for draft recovery, PoW, relay failures,
  cancellation, and marker removal after acceptance.
- [x] 7. Self-review against `INVARIANTS.md`.

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| First persisted device change | No empty bootstrap event; local draft and pending marker are created | [x] |
| PoW completion | Relay receives a valid 20-bit-PoW replacement event | [x] |
| Relay failure | Marker remains and the draft is retried on the next trigger | [x] |
| Mining cancellation | Local draft and marker remain; later processing resumes the work | [x] |
| Restart/resume | Pending marker rebuilds its latest matching local draft | [x] |
| Relay acceptance | Marker is removed only after acceptance | [x] |
| Relay-list update | Kind 10067 is mined and published to the bootstrap relay | [ ] |

## Decisions

### 2026-07-15 — Queue ownership and persistence

**Context:** Mining can be cancelled before a relay-publishable event exists.
**Options:** Add a generic Purplebase outbox; persist complete payloads in secure
storage; store local drafts in Purplebase and only pending identifiers in secure
storage.
**Decision:** Store local-only PoW-less drafts in Purplebase. Persist only
pending event kind and `d` tag markers in Flutter secure storage.
**Rationale:** It survives cancellation and restart without modifying
dependencies or duplicating encrypted event content in secure storage.

## Spec Issues

_None_

## Progress Notes

**2026-07-15:** Implemented the local-draft queue, including relay-list
handoff recovery. `fvm flutter test` passes.
**2026-07-15:** Added structured queue lifecycle diagnostics without logging
event content or secrets.
**2026-07-15:** Purplebase now preserves canonical event IDs in new blobs and
reconstructs them for legacy blobs, so persisted signed replaceable drafts
remain verifiable after restart.
**2026-07-15:** Queue triggers before device-key availability now safely no-op
instead of surfacing an uncaught error.
