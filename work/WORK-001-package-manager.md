# WORK-001 — Package Manager

**Feature:** FEAT-001-package-manager.md
**Status:** Complete

## Problem Solved

Install sessions could complete in Android PackageInstaller even after Zapstore's watchdog timed out and reported failure. This caused:
1. Apps "silently" installed without user awareness
2. Certificate mismatch errors on retry (stale session still active)
3. Confusing UX where failed installs suddenly appeared as completed

Root cause: `abandonSession()` doesn't work after `session.commit()` is called.

## Tasks Completed

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

## Decisions

### 2026-02-03 — Committed session handling

**Context:** `abandonSession()` is ineffective after commit.
**Options:** A) Ignore timeout for committed, B) Keep waiting, C) Show different status.
**Decision:** Option B+C - Keep waiting and show "System is processing".
**Rationale:** User needs feedback; Android will eventually complete or fail. No hanging states allowed.

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
