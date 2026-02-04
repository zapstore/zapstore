# FEAT-001 — Package Manager

## Goal

Allow users to install, update, and uninstall apps with clear progress feedback and reliable error handling.

## Core Invariant: No Hanging States

**Every operation MUST resolve to a terminal state.** The UI must never get stuck in an in-progress state indefinitely.

- On success, present a launch action
- On failure, show an error with an actionable message
- On cancel, restore the pre-operation UI

If the system stops responding, the operation must either:
1. **Advance** to completion when the system eventually responds, OR
2. **Fall back** to an error state with clear feedback

There is no third option. Timeouts, crashes, backgrounding, network loss—all paths lead to a resolved state.

### Timeouts

- Operations must enforce timeouts to avoid hanging states.
- Timeout durations are defined in implementation (not in this spec).

## State Model

### Download States (per app)

| State | Description | User Action |
|-------|-------------|-------------|
| `DownloadQueued` | Waiting for download slot (max 3 active) | Cancel available |
| `Downloading` | Actively downloading, shows progress % | Pause/Cancel available |
| `DownloadPaused` | Download paused by user | Resume/Cancel available |
| `Verifying` | Computing hash, shows progress % | Cannot cancel |

**Transitions:**
- `Idle` → tap download → `DownloadQueued` or `Downloading`
- `DownloadQueued` → slot available → `Downloading`
- `DownloadQueued` → cancel → `Idle`
- `Downloading` ↔ `DownloadPaused` (pause/resume)
- `Downloading` → cancel → `Idle` (clears partial file)
- `Downloading` → success → `Verifying`
- `Downloading` → failure/timeout → `Error`
- `Verifying` → success → `AwaitingPermission` or `ReadyToInstall`
- `Verifying` → failure/timeout → `Error`
- App restart while downloading → `Idle` (partial file cleared)

### Permission State (per app)

| State | Description | User Action |
|-------|-------------|-------------|
| `AwaitingPermission` | User must grant "Install unknown apps" | Tap opens Android settings |

**Transitions:**
- `Verifying` success + missing permission → `AwaitingPermission`
- `Verifying` success + has permission → `ReadyToInstall`
- `AwaitingPermission` → permission granted → `ReadyToInstall`
- `AwaitingPermission` → permission denied → `Error`

### Install States (per app)

| State | Description | User Action |
|-------|-------------|-------------|
| `ReadyToInstall` | File verified, queued for install slot | Automatic (no user action) |
| `Installing` | System install dialog shown or processing | Confirm/Cancel dialog |
| `InstallCancelled` | User cancelled dialog, file still ready | "Install (retry)" button |
| `SystemProcessing` | Committed to Android, cannot cancel | Wait for completion |
| `Installed` | Successfully installed | "Open" button |

**Transitions:**
- `ReadyToInstall` → install slot available → `Installing`
- `Installing` → user confirms → continues `Installing`
- `Installing` → user cancels dialog → `InstallCancelled`
- `InstallCancelled` → user taps "Install (retry)" → `Installing`
- `Installing` (silent) → system completes → `Installed`
- `Installing` → failure/timeout → `Error`
- `Installing` → committed + slow → `SystemProcessing`
- `SystemProcessing` → completes → `Installed`
- `SystemProcessing` → failure/timeout → `Error`

### Update State (per app)

- `UpdateAvailable` → start update → `Downloading`
- `Installing` (silent not allowed) → manual dialog flow
  - If the app was originally installed by Zapstore and platform allows, update may be silent

### Uninstall State (per app)

- `Installed` → uninstall → `Uninstalling`
- `Uninstalling` → user confirms → `Idle`
- `Uninstalling` → user cancels → `Installed`
- `Uninstalling` → failure/timeout → `Error`

### Queue State (global)

- Download queue: insertion order (best-effort), max 3 active
- Install queue: insertion order (best-effort), max 1 active
  - Only one install dialog is shown at a time; others wait

## Non-Goals

- iOS/macOS/desktop support (Android only)
- Split APK support
- Background installs without any user awareness

## User-Visible Behavior

### Download

- Progress bar shows percentage (0-100%)
- Pause/resume buttons available during download
- Cancel clears the download
- Multiple apps can download simultaneously (up to 3)
- Pause does not persist across app restarts
- Partial downloads are deleted on app restart

### Verification

- Progress bar shows percentage (0-100%) during hash verification
- Verification cannot be paused or cancelled
- Large APKs (100MB+) may show noticeable verification time

### Install

- System confirmation dialog appears for first-time installs
- If user cancels dialog, shows "Install (retry)" button (no re-download needed)
- Silent install skips dialog when Zapstore is the original installer

### Update

- Same flow as install
- Silent update possible if Zapstore originally installed the app
- If silent update is not allowed, proceed with manual update flow

### Uninstall

- System uninstall dialog appears
- If uninstall succeeds, the app is removed from the installed list
- If uninstall is canceled, there is no change

### Force Update (Certificate Mismatch)

- When app signature changed (different developer/build):
  - Show a certificate mismatch error
  - Force update action offered
  - Tapping shows uninstall dialog first
  - After uninstall, install proceeds automatically
  - Warning: user data in the app will be lost

## Edge Cases

### Network/Download Issues

- On download failure, provide a retry option
- On 404, retry from `cdn.zapstore.dev`, otherwise fail
- If network is lost mid-download, support resume when reconnected

### Verification Failures

- Hash mismatch suggests re-download
- Invalid file (not an APK) shows a clear error

### Install Failures

- Insufficient storage shows a clear error message
- Incompatible device shows a clear "Device incompatible" error message
- Blocked by device policy shows a clear error message
- If the user cancels the dialog, show "Install (retry)" (`InstallCancelled` state)

### Permission Issues

- If install unknown apps permission is missing, show `AwaitingPermission` state
- Tapping opens Android settings to grant permission
- If the user denies permission, show a clear error with instructions

### App Lifecycle

- If the app is backgrounded during install dialog, the dialog reappears on return
- If the app is killed during install, reset UI and clean up temp files on next launch
- If install takes too long, it cannot be canceled once committed

### Concurrent Operations

- Multiple downloads: limited based on device capability (1-4 simultaneous), others queued
- Multiple installs: 1 at a time, others wait in queue
- Queue order: best-effort insertion order (not guaranteed)
- Queue advances automatically after each completion

### Batch Progress

- When operations are in progress, show a summary banner on the updates screen
- Works for both "Update All" and individual update taps
- Banner displays current phase: downloading, verifying, or installing
- Shows completion progress: "3 of 10 updated"
- Shows count of failures if any
- "Update All" button is disabled while operations are in progress
- All progress state is fully derived from operations map:
  - Successful operations transition to `Completed` state (stay in map)
  - Total = operations.length (includes completed)
  - Completed = count of `Completed` operations
  - In-progress = Total - Completed - Failed
  - Phase = derived from operation types currently active
- When all operations reach terminal state, banner shows "X of X updated ✓"
- After 3 seconds with no in-progress operations, completed operations auto-clear

### Device Adaptation

- Download concurrency adapts to device RAM to prevent crashes on low-capability devices
- Behavior degrades gracefully on constrained devices, never hangs

## Acceptance Criteria

- [ ] **No operation hangs indefinitely** — every state resolves to success, failure, or cancelled
- [ ] User sees download progress percentage
- [ ] User sees verification progress percentage
- [ ] User can pause and resume downloads
- [ ] User can cancel downloads
- [ ] Verification errors show clear message
- [ ] Install dialog appears for new apps
- [ ] Silent update works for Zapstore-owned apps
- [ ] Certificate mismatch offers force update option
- [ ] Force update uninstalls then installs
- [ ] Backgrounding app preserves install dialog
- [ ] Long installs transition to SystemProcessing (cannot cancel)
- [ ] All errors show actionable messages
- [ ] Multiple downloads queue correctly
- [ ] Multiple installs proceed one at a time
- [ ] Batch progress banner shows during "Update All"
- [ ] "Update All" button disabled while operations in progress

## Notes

- Silent install requires Android 12+ and Zapstore as original installer
- Certificate mismatch requires uninstall (app data lost) - ensure user understands
- Some device policies may block all installs from unknown sources

