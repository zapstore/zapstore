# WORK-020 — PoW Difficulty and Progress

**Feature:** User-requested bootstrap proof-of-work feedback
**Status:** Complete

## Tasks

- [x] 1. Increase bootstrap proof-of-work difficulty to 24 bits.
  - Files: `lib/services/device_private_event_service.dart`
- [x] 2. Show elapsed mining time beneath the progress spinner.
  - Files: `lib/services/device_state_service.dart`, `lib/screens/profile_screen.dart`
- [x] 3. Verify isolate mining and lifecycle behavior.
- [x] 4. Self-review against `INVARIANTS.md`

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| Bootstrap policy | Uses 24 proof-of-work bits | [x] |
| Bootstrap in progress | Spinner and elapsed time are visible | [x] |
| App backgrounded | Mining is not cancelled by the app lifecycle observer | [x] |
| Bootstrap failure | UI leaves the indefinite progress state | [x] |

## Decisions

### 2026-07-14 — Elapsed time source

**Context:** The mining executor does not expose progress callbacks to the UI.
**Decision:** Store the bootstrap start time in the device-state status and derive
elapsed time in the widget. This keeps mining isolated from presentation code.

## Spec Issues

_None_

## Progress Notes

**2026-07-14:** Reduced bootstrap difficulty to 24 bits, added elapsed mining
feedback, and confirmed the focused service tests pass. Flutter analyze passes
when run with a writable temporary `HOME` and the project package cache.
