# WORK-XXX — Short Name

**Feature:** FEAT-XXX-short-name.md
**Status:** In Progress | Complete

## Tasks

- [ ] 1. Task description
  - Files: `lib/path/to/file.dart`
  - Notes: any relevant context
- [ ] 2. Task description
- [ ] 3. Handle edge cases per spec
- [ ] 4. Self-review against INVARIANTS.md

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| Happy path | Describe expected behavior | [ ] |
| Edge case: network failure | Graceful degradation | [ ] |
| Edge case: cancellation | Clean cleanup | [ ] |

## Decisions

### YYYY-MM-DD — Decision title

**Context:** Why this decision came up.
**Options:** A, B, C considered.
**Decision:** Chosen option.
**Rationale:** Why.

## Spec Issues

Report blockers here instead of guessing. Format:

- **Issue:** Description of unclear/incorrect spec
- **Question:** What clarification is needed

## Progress Notes

Brief updates as work proceeds.

---

# Example: WORK-002 — NWC Zaps

**Feature:** FEAT-002-nwc-zaps.md
**Status:** In Progress

## Tasks

- [x] 1. Add nwc_wallet package dependency
- [x] 2. Create NwcService in lib/services/
  - Files: `lib/services/nwc_service.dart`
  - Stores connection string in SecureStorageService
- [x] 3. Add NWC settings UI in profile screen
  - Files: `lib/screens/profile_screen.dart`, `lib/widgets/nwc_widgets.dart`
- [ ] 4. Create ZapButton widget
  - Files: `lib/widgets/zap_button.dart`
- [ ] 5. Create ZapDialog for amount selection
- [ ] 6. Integrate into AppDetailScreen
- [ ] 7. Implement zap request flow (kind 9734)
- [ ] 8. Self-review against INVARIANTS.md

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| Connect valid NWC | Connection saved, status updated | [x] |
| Connect invalid NWC | Error shown, nothing saved | [x] |
| Zap with sufficient balance | Success toast | [ ] |
| Zap with insufficient balance | Wallet error shown | [ ] |
| Developer has no LN address | Button disabled with tooltip | [ ] |

## Decisions

### 2026-01-15 — NWC string storage

**Context:** Need to persist NWC connection string securely.
**Options:** New encrypted file, SecureStorageService, Nostr event.
**Decision:** SecureStorageService.
**Rationale:** Already used for nsec, platform-native secure storage.

## Spec Issues

_None_

## Progress Notes

**2026-01-15:** Completed NWC connection flow. Reused SecureStorageService.
**2026-01-17:** ZapButton done. Added edge case for missing LN address to test matrix.
