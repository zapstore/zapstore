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

## Problem Solved (Phase 3 - Complete)

User report: Apps stuck in "Queued for update" / "Requesting update" state indefinitely after app restart.

Root cause: When the app is killed during install phase, native Android retains pending install sessions but Dart's operations map starts fresh. On restart, native sends `pendingUserAction` events for sessions that Dart doesn't track. Previously these were ignored, leaving orphaned sessions that blocked the PackageInstaller.

## Tasks Completed (Phase 3)

- [x] 1. Abort orphaned native sessions on event receipt
  - Modified `_handleInstallEvent()` to call `abortInstall(appId)` when receiving events for untracked appIds
  - Satisfies FEAT-001 requirement: "If the app is killed during install, reset UI and clean up temp files on next launch"
  - Allows user to retry from clean state

- [x] 2. Prevent abort spam when native keeps sending events
  - Added `_abortedOrphans: Set<String>` to track appIds already aborted
  - Only call `abortInstall()` once per appId per session
  - Clear from set in `setOperation()` override so future orphans can be aborted

## Problem Solved (Phase 4 - Complete)

User report: Multiple apps stuck in "Queued for update" state indefinitely, with no app actively installing.

Root cause: In `processQueue()`, `activeInstall` is set BEFORE calling `triggerInstall()`. If `triggerInstall()` returns early (operation changed, or downloaded file not found), `activeInstall` is never cleared. This permanently blocks the install queue since `activeInstall != null` prevents any new installs from starting.

## Tasks Completed (Phase 4)

- [x] 1. Add `clearInstallSlot()` helper method in base class
  - Clears `activeInstall` if it matches the appId
  - Removes appId from `installQueue`
  - Calls `scheduleProcessQueue()` to advance to next app
  - Replaces duplicate `_onInstallComplete()` in android subclass

- [x] 2. Fix `triggerInstall()` early return paths
  - Call `clearInstallSlot(appId)` when operation is not ReadyToInstall
  - Call `clearInstallSlot(appId)` when downloaded file is missing
  - Prevents install queue from getting permanently stuck

## Problem Solved (Phase 5 - Complete)

Code audit found several potential hanging state bugs not covered by existing timeout mechanisms:

1. `_performInstall` catch block didn't call `clearInstallSlot`, causing queue hang if exception escapes
2. Restored downloads weren't added to `activeDownloads`, potentially exceeding concurrent limits
3. Download resume failures during restoration were silently swallowed, causing permanent hang
4. EventChannel disconnect was silently ignored with no reconnection
5. No Dart-side watchdog as fallback if native events stop arriving

## Tasks Completed (Phase 5)

- [x] 1. Fix `_performInstall` catch block to call `clearInstallSlot`
  - Ensures install queue advances even if exception escapes `install()` internal handling

- [x] 2. Track restored downloads in `activeDownloads`
  - Added `activeDownloads.add(appId)` in `_restoreOperation` for running downloads
  - Prevents exceeding `maxConcurrentDownloads` limit after app restart

- [x] 3. Handle download resume failures during restoration
  - On resume failure, transition to `OperationFailed` instead of silent failure
  - Prevents operations stuck in `Downloading` state with no active task

- [x] 4. Add EventChannel reconnection logic
  - Added `onError` and `onDone` handlers to event stream
  - Automatic reconnection with 2-second delay on disconnect
  - Prevents installs hanging forever if EventChannel breaks

- [x] 5. Add Dart-side watchdog timer as fallback
  - Added `startedAt` field to `Verifying` and `SystemProcessing` states
  - Added `needsWatchdog` and `watchdogTimestamp` getters to extension for uniform access
  - Activity-based watchdog for downloads: tracks `lastProgressAt` (reset on each progress update) so slow-but-active downloads are not killed
  - Phase-start-based watchdog for Verifying/Installing/SystemProcessing (uses `startedAt`)
  - Single 2-minute timeout for all watchdog-monitored states
  - Timer runs every 30s, only when there are operations needing watchdog
  - Transitions stale operations to `OperationFailed` state

- [x] 6. Improve pause/resume error handling
  - Added debug logging for pause failures
  - Resume failure now transitions to `OperationFailed` instead of silent failure

## Decisions

### 2026-02-04 — Dart-side watchdog timer

**Context:** Native Kotlin has timeouts but if EventChannel breaks or native crashes before sending events, Dart operations hang forever.
**Options:** A) Trust native completely, B) Add Dart watchdog as fallback, C) Duplicate all timeout logic in Dart.
**Decision:** Option B - Dart-side watchdog as defense-in-depth.
**Rationale:** Minimal complexity (~50 lines), only runs when needed, uses generous timeouts (2-10min) so it doesn't interfere with native handling but catches catastrophic failures.

### 2026-02-04 — Install slot clearing on early return

**Context:** `triggerInstall()` can bail early if operation changed or file is missing, but `activeInstall` was set before the call, blocking the queue forever.
**Options:** A) Move `activeInstall` assignment inside `triggerInstall()` after validation, B) Have `triggerInstall()` clear slot on early returns, C) Return success boolean and only set slot if true.
**Decision:** Option B - Clear install slot on early returns.
**Rationale:** Minimal change, maintains existing flow, easy to audit. Added `clearInstallSlot()` to base class to consolidate the pattern.

### 2026-02-04 — Orphaned session cleanup

**Context:** Native install sessions persist across app restarts, but Dart state doesn't. Events arrive for untracked appIds.
**Options:** A) Ignore events (existing), B) Abort orphaned sessions, C) Attempt to restore operations from native state.
**Decision:** Option B - Abort orphaned sessions immediately.
**Rationale:** Simplest fix that satisfies "no hanging states" invariant. User can retry from clean state. Option C adds complexity and may restore stale/invalid state.

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

### Watchdog Timeouts (Kotlin Native)

| Phase | Initial | Max | Behavior |
|-------|---------|-----|----------|
| Verify | 10s | 10s | Fail if thread dead |
| Install (uncommitted) | 10s | 120s | Abandon session, emit FAILED |
| Install (committed) | 10s | ∞ | Cannot abandon; extend deadline, show "processing" |

### Dart-side Watchdog (Fallback)

2-minute timeout for all watchdog-monitored states. Timer runs every 30s, only when monitored operations exist.
Defense-in-depth for cases where EventChannel breaks or native crashes before sending events.

| Phase | Measures from | Rationale |
|-------|---------------|-----------|
| Downloading | Last progress update (`lastProgressAt`) | Slow-but-active downloads must not be killed |
| Verifying | Phase start (`startedAt`) | Short operation, elapsed time is appropriate |
| Installing | Phase start (`startedAt`) | Short operation, elapsed time is appropriate |
| SystemProcessing | Phase start (`startedAt`) | Committed session, elapsed time is appropriate |

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
