# WORK-021 — Increase Bootstrap PoW to 26 Bits

**Feature:** Bootstrap proof-of-work difficulty adjustment
**Status:** Complete

## Tasks

- [x] 1. Increase bootstrap proof-of-work difficulty to 26 bits.
  - Files: `lib/services/device_private_event_service.dart`
- [x] 2. Update the production policy test.
  - Files: `test/services/device_private_event_service_test.dart`
- [x] 3. Self-review against `INVARIANTS.md`

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| Bootstrap policy | Uses 26 proof-of-work bits | [x] |
| Mining lifecycle | Existing isolate and cancellation behavior remains unchanged | [x] |

## Decisions

### 2026-07-15 — Bootstrap difficulty

**Context:** The bootstrap event currently uses 24 bits and should use the requested higher difficulty.
**Decision:** Set the production bootstrap policy to 26 bits.
**Rationale:** 26 bits doubles the expected work over 25 bits while retaining the existing background isolate execution and lifecycle behavior.

## Spec Issues

_None_

## Progress Notes

**2026-07-15:** Updated bootstrap PoW policy and focused test expectation to 26 bits.
