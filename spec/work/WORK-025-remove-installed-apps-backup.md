# WORK-025 — Remove Installed Apps Backup

**Feature:** FEAT-006-device-key.md
**Status:** In Progress

## Tasks

- [x] 1. Remove the Installed Apps private-stack and backup setting UI.
  - Files: `lib/screens/profile_screen.dart`
- [x] 2. Remove Installed Apps backup persistence and periodic publishing.
  - Files: `lib/services/settings_service.dart`, `lib/services/updates_service.dart`
- [x] 3. Remove legacy Amber Installed Apps recovery.
  - Files: `lib/services/device_backup_service.dart`, `lib/widgets/legacy_installed_apps_dialog.dart`
- [x] 4. Remove obsolete identifiers and update tests.
  - Files: `lib/constants/app_constants.dart`, `test/services/settings_service_test.dart`
- [ ] 5. Self-review against the local-first, lifecycle, and data-robustness invariants.

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| Profile with old Installed Apps stack | Legacy stack is hidden | [x] |
| Portable settings round-trip | Remaining settings serialize and deserialize | [x] |
| Update refresh | Updates continue without publishing an Installed Apps stack | [x] |
| Legacy device restore | Device-key restore completes without Installed Apps recovery UI | [x] |

## Decisions

### 2026-07-15 — Preserve installation and update management

**Context:** Installed Apps backup is separate from package scanning, installation, and the Updates screen.
**Decision:** Remove only the private-stack backup, profile presentation, and legacy recovery paths.
**Rationale:** This preserves core app distribution behavior while eliminating the deprecated backup feature.

### 2026-07-15 — Hide persisted legacy stacks

**Context:** Existing devices may still contain `zapstore-installed-apps` events.
**Decision:** Filter the legacy identifier from the private-stack profile list after removing new writes.
**Rationale:** Old data should not reappear as an unnamed or unsupported feature.

## Spec Issues

_None_

## Progress Notes

**2026-07-15:** Removed the backup UI, setting, update publisher, and legacy Amber recovery path. Existing legacy stacks are hidden from the profile. `fvm flutter analyze` and `HOME=/tmp fvm flutter test` pass.

## On Merge

Delete this work packet. Promote any non-obvious decision to `spec/knowledge/` if needed.
