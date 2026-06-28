# WORK-015 — NIP-56 App Reporting

**Feature:** FEAT-009-nip56-app-reporting.md
**Status:** Complete

## Tasks

- [x] 1. Correct NIP-56 report tag parsing and cover valid tag structure.
  - Files: `../models/lib/src/models/reporting.dart`,
    `../models/test/models/reporting_test.dart`
- [x] 2. Add an app-report sheet with required policy category and description.
  - Files: `lib/widgets/app_report_sheet.dart`
- [x] 3. Add the report action to the app-detail overflow menu.
  - Files: `lib/widgets/floating_overflow_menu.dart`
- [x] 4. Verify model tests, Flutter analysis, and focused Flutter tests.

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| Valid report | Kind 1984 event targets app event and author | [x] |
| Missing category or description | Submit remains unavailable | [x] |
| No active signer | Clear error and no event published | [ ] |
| Signing/publish failure | Input remains available for retry | [ ] |

## Decisions

### 2026-07-10 — Report transport and identity

**Context:** Reports need a first relay destination and an accountable identity.
**Options:** Publish to all user relays, AppCatalog only, or use the device key.
**Decision:** Publish only to AppCatalog with the active user signer.
**Rationale:** This matches the current moderation destination decision and
keeps a public report distinct from device-local app state.

## Spec Issues

_None_

## Progress Notes

**2026-07-10:** Feature spec authorized by the product owner. Implemented the
report sheet and AppCatalog-only NIP-56 publishing. Model and focused Flutter
tests pass; focused Flutter analysis is clean.
