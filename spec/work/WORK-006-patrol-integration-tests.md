# WORK-006 — Patrol Integration Test Infrastructure

**Feature:** N/A — testing infrastructure
**Status:** In Progress

## Context

Integration tests run against a real Android emulator via ADB using
[Patrol](https://pub.dev/packages/patrol), which wraps UIAutomator and allows
Dart test code to interact with Android system UI (e.g. PackageInstaller dialogs).

Tests are run with `patrol test` against a connected emulator or device.
No Maestro, no Maestro Cloud, no Linux-specific setup required — any machine
with an Android emulator or device works.

## Setup (one-time per machine)

```bash
# Install patrol CLI
dart pub global activate patrol_cli

# Bootstrap patrol into the Android project (adds required permissions etc.)
patrol bootstrap

# Accept Android SDK licenses if needed
yes | sdkmanager --licenses

# Pre-grant install permission before running install tests
adb shell appops set dev.zapstore.app REQUEST_INSTALL_PACKAGES allow
```

## Running Tests

```bash
# Run all integration tests
patrol test integration_test/

# Run a single file
patrol test integration_test/install_flow_test.dart
```

## Tasks

- [x] 1. Add `patrol` to `dev_dependencies` in `pubspec.yaml`
  - Files: `pubspec.yaml`
- [x] 2. Document test strategy in `QUALITY_BAR.md`
  - Files: `spec/guidelines/QUALITY_BAR.md`
- [x] 3. Create proof-of-concept install flow test
  - Files: `integration_test/install_flow_test.dart`
- [ ] 4. Run `patrol bootstrap` to configure Android project
  - Files: `android/app/src/main/AndroidManifest.xml`, `android/app/build.gradle`
- [ ] 5. Verify tests pass on a connected emulator or device

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| Search returns results | App cards visible | [ ] |
| App detail screen loads | Install or Open button visible | [ ] |
| Full install flow | Button transitions to Open after system dialog | [ ] |
| Already installed | Test skips gracefully | [ ] |

## Decisions

### 2026-03-06 — Patrol over Maestro

**Context:** Needed a way to automate Android system UI dialogs (PackageInstaller)
from integration tests without a real device or macOS-only tooling.
**Options:** Maestro, Flutter integration_test alone, Patrol.
**Decision:** Patrol.
**Rationale:** Patrol wraps UIAutomator, runs as part of `flutter test`, works
headlessly against any emulator, and lets Dart test code tap native system dialogs.
Maestro is black-box and AI cannot produce reliable flows without a running device.

### 2026-03-06 — No root required

**Context:** Earlier considered AOSP images + `adb root` to bypass PackageInstaller.
**Decision:** Not needed. Patrol taps the real PackageInstaller dialog via UIAutomator.
Any standard emulator image works.

### 2026-03-06 — Pre-grant REQUEST_INSTALL_PACKAGES

**Context:** Without the permission pre-granted, Zapstore shows a "Grant Permission"
screen before the install dialog, adding a variable step.
**Decision:** Pre-grant via `adb shell appops set` in CI setup, not in test code.
**Rationale:** Keeps tests focused on the install flow, not permission setup.

## Progress Notes

**2026-03-06:** Added `patrol ^3.15.0` (resolved to 4.3.0), wrote POC test,
updated QUALITY_BAR.md. `patrol bootstrap` and emulator run pending — blocked
by insufficient disk space on dev machine (1.4 GB free, emulator needs ~3 GB).

## On Merge

Delete this work packet. Promote the Patrol decision to `spec/knowledge/` if
the UIAutomator-for-system-dialogs pattern is non-obvious to future contributors.
