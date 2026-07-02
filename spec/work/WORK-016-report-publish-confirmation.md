# WORK-016 — Report Publish Confirmation

**Feature:** FEAT-009-nip56-app-reporting.md
**Status:** Complete

## Tasks

- [x] 1. Treat a NIP-56 report as published only after an AppCatalog relay
  explicitly accepts its event.
  - Files: `lib/widgets/app_report_sheet.dart`
- [x] 2. Preserve the report and expose a retryable failure when no relay
  accepts the event, including timeouts and rejections.
  - Files: `lib/widgets/app_report_sheet.dart`
- [x] 3. Cover accepted, rejected, and absent relay responses.
  - Files: `test/widgets/app_report_sheet_test.dart`
- [x] 4. Display the relay's rejection reason when it responds negatively.
  - Files: `lib/widgets/app_report_sheet.dart`,
    `test/widgets/app_report_sheet_test.dart`

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| Relay accepts report event | Success is reported | [x] |
| Relay rejects or times out | Form stays open with retryable error | [x] |
| No response for report event | Form stays open with retryable error | [x] |
| Relay rejection includes a reason | User sees the relay's reason | [x] |
| Relay rejection has no reason | User sees that the relay omitted it | [x] |

## Decisions

### 2026-07-10 — Explicit relay acceptance

**Decision:** The report flow waits for purplebase's publish response and
considers publishing successful only when at least one AppCatalog relay accepts
the signed report event.

**Rationale:** A timeout or relay rejection is a completed transport response,
not a successful submission.
