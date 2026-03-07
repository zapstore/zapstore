# WORK-002 — Batch Progress Banner

**Feature:** FEAT-001-package-manager.md (Batch Progress section)
**Status:** Complete

## Problem

When user taps "Update All" or multiple individual updates, there's no summary of overall progress. User sees individual app states but no aggregate view of:
- How many apps total
- How many completed
- Current phase (downloading/verifying/installing)
- How many failed

Also, "Update All" button remains enabled during batch operations, which could cause confusion.

## Tasks

- [x] 1. Add `Completed` state to `InstallOperation`
  - On success, transition to `Completed` instead of removing operation
  - Allows deriving completed count from operations map

- [x] 2. Create `BatchProgress` model and provider
  - All state derived from operations map (no parameters needed)
  - Total = operations.length (includes Completed state)
  - Completed = count of `Completed` operations
  - In-progress = Total - Completed - Failed
  - Phase = derived from operation types (installing > verifying > downloading)
  - Failed = count of `OperationFailed` operations

- [x] 3. Create `BatchProgressBanner` widget
  - Shows only when operations exist
  - Displays: "{completed} of {total} updated"
  - Shows current phase with spinner
  - Shows "✓" when all complete
  - Shows failure count badge if > 0
  - Styled to match app theme

- [x] 4. Integrate banner in updates screen
  - Place at top of updates list, after connection status
  - Banner appears/disappears based on operation state

- [x] 5. Disable "Update All" button during operations
  - Check `activeOperationsCountProvider > 0`
  - Button shows disabled state (dimmed)

- [x] 6. Auto-clear completed operations
  - When all operations reach terminal state, wait 3 seconds
  - Then clear all `Completed` operations (failed stay for user to see)
  - Banner disappears when operations map is empty

## Key Insight: Fully Derived State

The key design decision: **keep completed operations in the map** instead of removing them.

```
Before: Installing → (removed from map) → can't count completed
After:  Installing → Completed → can count completed!
```

This makes all progress state derivable:
```dart
total = operations.length;
completed = operations.whereType<Completed>().length;
failed = operations.whereType<OperationFailed>().length;
inProgress = total - completed - failed;
```

Works for both "Update All" AND individual update taps.

## UI States

| State | Banner Shows | Button |
|-------|--------------|--------|
| No operations | Hidden | Enabled |
| Operations in progress | "2 of 5 updated • Downloading..." | Disabled |
| All complete | "5 of 5 updated ✓" (for 3 sec) | Disabled |
| After auto-clear | Hidden | Enabled |
| Some failed | "3 of 5 updated" + "2 failed" badge | Disabled |

## Files Modified

| File | Change |
|------|--------|
| `install_operation.dart` | Added `Completed` state, `isTerminal`/`isInProgress` extensions |
| `package_manager.dart` | Added `BatchProgress` model and `batchProgressProvider` |
| `android_package_manager.dart` | Transition to `Completed` on success, auto-clear logic |
| `batch_progress_banner.dart` | New widget |
| `updates_screen.dart` | Added banner, disabled button during ops |
