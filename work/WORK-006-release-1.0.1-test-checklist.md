# Release 1.0.1 Test Checklist

Goal: Validate fixes introduced since `1.0.0` and confirm `1.0.1` is safe to ship.

## 1) Pre-test Setup

- [x] Android build opens and app boots normally
- [x] Test both states: signed-in user and signed-out user
- [x] Have at least 3 apps with available updates for batch tests
- [x] Confirm release version is set to `1.0.1+<build>`

## 2) Blocker Tests (must pass)

### A. Install Queue Stability (`#319`)

- [x] Run `Update All` with 2+ apps
- [x] Confirm progress banner updates while operations run
- [ ] Simulate at least one failure (if possible) and one success
- [x] Confirm queue does not get stuck in an in-between state
- [x] Confirm "All done" appears, then auto-clears after ~3 seconds
- [ ] Confirm app can immediately start a new batch after completion

### B. Install Dialog / Watchdog Behavior

- [x] Start install while app is in foreground
- [x] Confirm Android install dialog appears once per expected step
- [ ] Confirm no repeated dialog relaunch/spam
- [ ] Confirm flow reaches clear success/failure terminal state

### C. Back Gesture on Screenshot Viewer

- [x] Open app detail screen, then open screenshot fullscreen viewer
- [x] Trigger Android back gesture
- [x] Confirm fullscreen viewer closes first
- [x] Confirm underlying route is not popped incorrectly

## 3) Behavior Changes to Verify

### D. "Always trust" Visibility (signed-in only)

- [x] Signed-out: install flow does not show "Always trust"
- [-] Signed-in: "Always trust" appears when applicable

### E. Navigation / Router Regressions

- [x] Switch between tabs repeatedly
- [x] Tap current tab to pop-to-root
- [x] Confirm no weird transitions or unexpected pops

## 4) Fast Regression Pass

- [x] Search tab loads and shows apps normally
- [x] Updates tab loads and "Last checked" behaves correctly
- [x] Single-app update/install works
- [x] Up-to-date apps are categorized correctly
- [x] Profile tab loads without regressions

## 5) Go / No-Go Decision

Release can proceed only if:

- [ ] No blocker failures in sections A, B, C
- [ ] Signed-in/signed-out trust switch behavior is correct
- [ ] No major navigation regression found
- [ ] Final version remains `1.0.1+<build>` in release artifact

## 6) Test Log Template (copy per scenario)

```md
### Scenario

- Device/OS:
- User state: signed-in / signed-out
- Preconditions:

### Expected

- ...

### Observed

- ...

### Status

- PASS / FAIL

### Evidence

- Screenshot/video/log:
```
