# FEAT-001 — Package Manager

## Goal

Single source of truth for installed packages and active install operations.
Manages the complete lifecycle: download → verify → install, with pause/resume/cancel support.

## Non-Goals

- Managing non-APK file types
- Auto-updating without user awareness
- Installing from sources other than Nostr-published releases

## User-Visible Behavior

### Download Phase

- User taps "Install" → download begins, progress shown
- User can pause/resume/cancel active downloads
- Multiple downloads queue automatically (max 3 concurrent)
- "Update All" queues all updates immediately with visual feedback

### Verification Phase

- After download completes, hash verification runs
- Verification state is visible (not hidden)
- Hash mismatch blocks install with clear error

### Permission Phase

- If "Install unknown apps" permission not granted, user is prompted
- Permission state is explicit in UI
- Once granted, all waiting installs advance automatically

### Install Phase

- Native Android install dialog shown
- One install dialog at a time (serialized)
- If user dismisses dialog, install shows "Tap to retry" state
- Success updates installed list immediately (no stale UI)

### Failure States

- Download failed → clear error, can retry
- Hash mismatch → error, cannot proceed
- Certificate mismatch → offer "Uninstall and reinstall" option
- Permission denied → guidance to enable in Settings

## State Machine

Operations follow this sealed class hierarchy (`install_operation.dart`):

```text
DownloadQueued → Downloading ↔ DownloadPaused
                      ↓
                 Verifying
                      ↓
              AwaitingPermission (if needed)
                      ↓
               ReadyToInstall
                      ↓
                Installing → AwaitingUserAction (if dismissed)
                      ↓
             [cleared] or OperationFailed
```

State transitions are unidirectional except Downloading ↔ DownloadPaused.

## Edge Cases

- Network drops mid-download → download pauses or fails gracefully, can retry
- App backgrounded during install → install completes, UI updates on return
- 404 from origin server → automatic CDN fallback before failing
- Stale operations (>7 days) → garbage collected on app restart
- Android package DB race condition → state updated from target metadata, not sync

## Invariants

These are non-negotiable. Violations mean the implementation is broken.

1. **UI never blocks** — `install()` returns immediately; events drive state via EventChannel
2. **One install dialog at a time** — Android PackageInstaller limitation, enforced by serialization
3. **Hash verification before install** — Native side verifies before install session opens
4. **Permission flow is explicit** — `AwaitingPermission` state exists for UI feedback
5. **Downloaded files are cleaned up** — Deleted after success or dismissal
6. **No polling** — All state changes via callbacks/events, never periodic checks

## Integration Boundaries

```text
┌─────────────────────────────────────────────────────────────┐
│ PackageManager (Dart)                                       │
│   - State machine owner                                     │
│   - Download management (background_downloader)             │
│   - Orchestrates flow                                       │
└─────────────────────────┬───────────────────────────────────┘
                          │ MethodChannel / EventChannel
┌─────────────────────────▼───────────────────────────────────┐
│ AndroidPackageManagerPlugin (Kotlin)                        │
│   - Hash verification                                       │
│   - PackageInstaller session                                │
│   - Permission checks                                       │
│   - Emits: verifying/started/success/failed/cancelled       │
└─────────────────────────────────────────────────────────────┘
```

## Acceptance Criteria

- [ ] User can download, pause, resume, cancel downloads
- [ ] User can install apps with proper verification
- [ ] Multiple downloads queue correctly (max 3 concurrent)
- [ ] Install failures show actionable error messages
- [ ] Certificate mismatch offers force-update option
- [ ] UI remains responsive throughout all operations
- [ ] No operations block the UI thread

## Files

- `lib/services/package_manager/package_manager.dart` — Base class, state machine
- `lib/services/package_manager/install_operation.dart` — State definitions
- `lib/services/package_manager/android_package_manager.dart` — Android implementation
- `android/.../AndroidPackageManagerPlugin.kt` — Native side
