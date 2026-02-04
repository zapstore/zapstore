# WORK-001 — Package Manager

**Feature:** FEAT-001-package-manager.md
**Status:** Complete

## Problem Solved (Phase 1 - Complete)

Install sessions could complete in Android PackageInstaller even after Zapstore's watchdog timed out and reported failure. This caused:
1. Apps "silently" installed without user awareness
2. Certificate mismatch errors on retry (stale session still active)
3. Confusing UX where failed installs suddenly appeared as completed

Root cause: `abandonSession()` doesn't work after `session.commit()` is called.

## Problem Solved (Phase 2 - Complete)

User report: "Update All" crashes on low-RAM devices (Lenovo Tab M10 Plus, Android 13).

Root causes identified and fixed:
1. Fixed concurrent download limit (3) overwhelms low-RAM devices → Dynamic limit based on RAM
2. State update floods when queueing many apps (20+) → Staggered 50ms delays
3. Race conditions in implicit queue derivation → Explicit queue lists with processing lock

## Tasks Completed (Phase 1)

- [x] 1. Track committed sessions separately
  - Added `committedSessions` set to track sessions post-commit
  - Added `sessionsCompletedSuccessfully` set to avoid duplicate SUCCESS events
  - Mark sessions as committed after `session.commit()`

- [x] 2. Modify watchdog behavior for committed sessions
  - For committed sessions: emit INSTALLING status and extend timeout
  - For non-committed sessions: existing timeout/fail behavior

- [x] 3. Handle onFinished callback for missed SUCCESS events
  - SessionCallback.onFinished now emits SUCCESS if not already emitted
  - Catches installs that completed after timeout or missed broadcast

- [x] 4. Add explicit abort method for user-initiated cancellation
  - New method channel: `abortInstall`
  - Clears all tracking for package (best effort)
  - Returns `wasCommitted` flag to warn if Android may still complete

## Tasks (Phase 2 - Batch Operation Robustness)

- [x] 1. Add device capability detection (Dart)
  - Added `device_info_plus` package dependency
  - Created `DeviceCapabilitiesCache` in `device_capabilities.dart`
  - Estimates RAM from Android device characteristics (SDK version, ABIs)
  - Calculates `maxConcurrentDownloads` based on RAM tier:
    - < 3GB: 1 concurrent download
    - 3-4GB: 2 concurrent downloads
    - 4-6GB: 3 concurrent downloads
    - > 6GB: 4 concurrent downloads
  - Initialized at app startup in `appInitializationProvider`

- [x] 2. Implement explicit queue tracking
  - Added `downloadQueue: List<String>` for ordered download queue
  - Added `installQueue: List<String>` for ordered install queue
  - Added `activeInstall: String?` as single source of truth for install slot
  - Added `activeDownloads: Set<String>` for tracking active download slots
  - Replaced implicit queue derivation with explicit membership
  - Single `processQueue()` entry point with `_processingQueue` lock
  - Protected members for subclass access

- [x] 3. Add staggered batch queueing
  - Added 50ms delay between state updates in `queueDownloads()`
  - Defined `batchQueueDelayMs` constant in `install_operation.dart`
  - Prevents Riverpod rebuild flood on "Update All" with many apps

## Decisions

### 2026-02-03 — Committed session handling

**Context:** `abandonSession()` is ineffective after commit.
**Options:** A) Ignore timeout for committed, B) Keep waiting, C) Show different status.
**Decision:** Option B+C - Keep waiting and show "System is processing".
**Rationale:** User needs feedback; Android will eventually complete or fail. No hanging states allowed.

### 2026-02-04 — Explicit vs implicit queue

**Context:** Current queue derived from operation state causes race conditions with 20+ apps.
**Options:** A) Keep implicit + add more defensive checks, B) Explicit queue lists + lock.
**Decision:** Option B - Explicit queue lists with processing lock.
**Rationale:** Simpler to reason about, eliminates race conditions, easier debugging.

### 2026-02-04 — Dynamic concurrent downloads

**Context:** Fixed limit of 3 crashes low-RAM devices; too conservative for high-end devices.
**Options:** A) Lower fixed limit, B) Dynamic based on RAM, C) User configurable.
**Decision:** Option B - Dynamic based on device RAM.
**Rationale:** Adapts to device capability without user intervention.

### 2026-02-04 — Verification chunk size

**Context:** Should chunk size (64KB) be dynamic based on device?
**Decision:** Keep fixed at 64KB.
**Rationale:** Memory savings negligible (640KB max for 10 concurrent ops), complexity not justified.

---

## Implementation Reference

### Architecture

Event-driven with native Kotlin as single source of truth.

```
┌─────────────────┐         ┌─────────────────┐
│   Dart/Flutter  │◄───────►│  Kotlin/Native  │
│                 │ Events  │                 │
│  PackageManager │◄────────│ AndroidPackage  │
│  (StateNotifier)│         │ ManagerPlugin   │
└─────────────────┘         └─────────────────┘
        │                           │
        │ MethodChannel             │ PackageInstaller
        │ (commands)                │ SessionCallback
        │                           │ BroadcastReceiver
        ▼                           ▼
┌─────────────────┐         ┌─────────────────┐
│ background_     │         │ Android System  │
│ downloader      │         │ PackageInstaller│
└─────────────────┘         └─────────────────┘
```

### State Machine

```
Idle → DownloadQueued → Downloading → Verifying → ReadyToInstall → Installing → Terminal
                                                                         │
                                                         pendingUserAction
                                                                         │
                                                         ┌───────────────┼───────────────┐
                                                         ▼               ▼               ▼
                                                      SUCCESS         FAILED        CANCELLED
```

Every state MUST resolve to a terminal state. No exceptions.

### Native Status Events

| Status | Meaning |
|--------|---------|
| `verifying` | Hash verification in progress |
| `started` | Install session created |
| `pendingUserAction` | Waiting for user confirmation |
| `installing` | User accepted, system processing |
| `success` | Install completed |
| `failed` | Error occurred |
| `cancelled` | User cancelled |

### Error Codes

| Code | Meaning |
|------|---------|
| `downloadFailed` | Network/file error |
| `hashMismatch` | SHA-256 mismatch |
| `invalidFile` | Not valid APK |
| `installFailed` | Generic failure |
| `certMismatch` | Signature conflict |
| `permissionDenied` | No install permission |
| `insufficientStorage` | No space |
| `incompatible` | Device incompatible |
| `blocked` | Device policy |
| `installTimeout` | Watchdog timeout (uncommitted only) |

### Watchdog Timeouts

| Phase | Initial | Max | Behavior |
|-------|---------|-----|----------|
| Verify | 10s | 10s | Fail if thread dead |
| Install (uncommitted) | 10s | 120s | Abandon session, emit FAILED |
| Install (committed) | 10s | ∞ | Cannot abandon; extend deadline, show "processing" |

### Session Tracking Maps

| Map | Purpose |
|-----|---------|
| `sessionToPackage` | sessionId → packageName lookup |
| `pendingUserActionIntents` | Stored intents for dialog re-launch |
| `sessionsPendingUserAction` | Sessions awaiting user confirmation |
| `sessionsInstalling` | Sessions where user accepted |
| `committedSessions` | Sessions post-commit (cannot abandon) |
| `sessionsCompletedSuccessfully` | Prevents duplicate SUCCESS events |

### Silent Install Conditions (Android 12+)

1. `canRequestPackageInstalls()` permission granted
2. Zapstore was original installer
3. `USER_ACTION_NOT_REQUIRED` flag set
4. `PACKAGE_SOURCE_STORE` declared
5. `setRequestUpdateOwnership(true)` on API 34+

### Key Files

| File | Responsibility |
|------|----------------|
| `AndroidPackageManagerPlugin.kt` | Native install orchestration |
| `InstallResultReceiver.kt` | Broadcast → status event mapping |
| `android_package_manager.dart` | Dart state management |
| `package_manager.dart` | Base class, download management |
| `install_operation.dart` | State machine types |
