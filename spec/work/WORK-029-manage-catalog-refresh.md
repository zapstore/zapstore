# WORK-029 — Manage Catalog Refresh

**Feature:** FEAT-003-updates-screen.md  
**Status:** Complete

## Tasks

- [x] Refresh the managed installed-app catalog when the unmanaged set changes.
  - Files: `lib/services/updates_service.dart`
  - The refresh must bypass the manual-refresh cooldown and use the current
    managed set, so a newly managed app can be discovered from AppCatalog.
- [x] Cover the managed-ID selection and its unmanaged transition.
- [x] Self-review against `INVARIANTS.md`.

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| Manage cataloged app | Its package ID is included in the next catalog request | [x] |
| Unmanage app | Its package ID is excluded from the next catalog request | [x] |
| Refresh in progress | A changed managed set is fetched once the current refresh completes | [ ] |

## Decisions

### 2026-07-17 — Preference changes bypass polling cooldown

**Context:** A newly managed app may never have been fetched because it was
excluded while unmanaged.
**Decision:** Trigger a catalog refresh on an unmanaged-set transition without
the user-initiated refresh cooldown.
**Rationale:** The transition changes the query scope; the normal poll interval
cannot leave a known catalog app in “Other installed.”

## Spec Issues

_None_

## Progress Notes

**2026-07-17:** Management-state changes queue a single remote catalog refresh
after hydration. If another update check is active, the refresh runs after it
completes and uses the latest managed set. The existing notifier has no
injectable catalog-fetch boundary, so that in-flight sequencing remains covered
by review rather than an isolated test.
