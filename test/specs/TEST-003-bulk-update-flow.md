# TEST-003 — Bulk Update Flow

## Purpose

Verify that multiple outdated apps appear correctly in the Updates tab
(all visible at once, not in separate batches), and that "Update All"
successfully updates all of them in one action.

**GitHub issue**: User reported updates shown in "5 batches" — not all
updates visible at the same time. Needs a process that installs multiple
older versions, then verifies all updates appear together, and that
"Update All" works smoothly end-to-end.

## Parameters

The agent sideloads older versions via `adb install`. These apps must exist
in Zapstore's catalog so updates are detected. The agent should verify each
app is in the catalog before including it in the test.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `APP_COUNT` | 4 | Number of older apps to sideload |
| `APPS` | See list below | Apps to use (order of preference) |

### App list (agent picks first N available)

| App | Package | How to get older APK |
|-----|---------|---------------------|
| Flotilla | `social.flotilla` | Zapstore UI: "Debug: All Versions" → install 2nd version |
| Amethyst | `com.vitorpamplona.amethyst` | Zapstore UI: "Debug: All Versions" → install 2nd version |
| OpenBible | `com.schwegelbin.openbible` | Zapstore UI: "Debug: All Versions" → install 2nd version |
| DuckDuckGo | `com.duckduckgo.mobile.android` | Zapstore UI: "Debug: All Versions" → install 2nd version |

**Do NOT include Amber** (`com.greenart7c3.nostrsigner`) — uninstalling it
logs the user out and breaks sign-in for the entire test.

The agent should adapt the app list based on what's available. Any app with
at least 2 versions in Zapstore and an accessible older version works.

## Maestro Flows

This test does NOT use a single end-to-end Maestro flow. Instead, the agent
orchestrates multiple phases, using existing sub-flows where applicable:

- `flows/sign_in_amber.yaml` — Conditional Amber sign-in
- `flows/search_app.yaml` — Search + open detail page
- `flows/install_older_version.yaml` — Install 2nd version from "All Versions"

No new Maestro flow file is needed. The agent drives the sequence.

## Setup (agent-executed via adb)

All target apps are uninstalled via `adb` before the Maestro flows run.
This is faster and more reliable than UI-based uninstall.

```bash
# Uninstall all target apps (|| true so it doesn't fail if not installed)
adb shell pm uninstall social.flotilla || true
adb shell pm uninstall com.vitorpamplona.amethyst || true
adb shell pm uninstall com.schwegelbin.openbible || true
adb shell pm uninstall com.duckduckgo.mobile.android || true

# Restart Zapstore with clean activity stack
adb shell am start -S --activity-clear-task -n dev.zapstore.app/.MainActivity

# Wait for app initialization and update polling
sleep 8
```

The agent should also verify each uninstall succeeded:

```bash
adb shell pm list packages | grep <PACKAGE>
# If the package still appears, the uninstall failed — retry or skip that app.
```

## Test Sequence

### Phase 1: Capture baseline

1. Sign in via Amber (if needed) using `sign_in_amber.yaml`.
2. Navigate to Updates tab.
3. Capture the current "Update All (N)" badge count (or note absence if N=0).
4. Record which apps (if any) are already in the Updates list.

### Phase 2: Install older versions (one at a time)

Apps were already uninstalled via `adb` in setup. The detail page should
show "Install" (not "Open" or "Update"). For each app in the list:

1. Use `search_app.yaml` to navigate to the app's detail page.
2. Confirm the app is NOT installed (detail page shows "Install").
3. Use `install_older_version.yaml` to install the 2nd version.
4. Handle all system dialogs (Amber, Samsung trust, Android install).
5. After installation completes ("Open" visible), navigate back.

**Important**: Between each install, check the Updates tab to capture
intermediate badge counts. This helps detect if updates appear incrementally
(expected) vs. not appearing at all (bug).

### Phase 3: Verify all updates visible at once

1. Navigate to Updates tab.
2. Wait up to 30 seconds for update polling to complete.
3. Verify ALL installed apps appear in the Updates list:
   - Use `inspect_view_hierarchy` to read app names in the list.
   - Scroll down if needed — but all updates SHOULD be visible without
     needing to navigate to a different "batch" or page.
4. Verify "Update All (N)" shows the correct count:
   - N should equal the baseline count + number of older apps installed.
5. Take a screenshot of the complete Updates list as evidence.

**Batching bug check**: If not all apps are visible, the agent should:
- Note which apps are missing.
- Scroll the full list to see if they appear further down.
- Pull-to-refresh and re-check.
- Wait an additional 30 seconds and re-check.
- If apps still don't appear, this is the reported batching bug — document it.

### Phase 4: Execute "Update All"

1. Tap the "Update All" button.
2. Handle all system install dialogs as they appear:
   - Samsung "Trust and install app"
   - Android "Instalar" dialogs (one per app)
   - Amber signing requests
3. Wait for all updates to complete:
   - Watch for the "All done" banner (appears after batch completes).
   - Or: periodically check if any "Update" buttons remain.
4. Take a screenshot after all updates complete.

### Phase 5: Verify post-update state

1. All previously outdated apps should now show in "Up to date" section
   (or at minimum, no longer appear in "Updates" section).
2. The "Update All" button should either:
   - Show the baseline count (pre-test updates remain), OR
   - Be absent (if all updates are now resolved).
3. For each app, verify via adb that the latest version is installed:
   ```bash
   adb shell dumpsys package <PACKAGE> | grep versionName
   ```

## Success Criteria

- [ ] All older versions installed successfully (Phase 2)
- [ ] All outdated apps visible in Updates tab simultaneously — not batched (Phase 3)
- [ ] "Update All" count matches expected (baseline + N)
- [ ] "Update All" button tap triggers batch update (Phase 4)
- [ ] All apps updated successfully (Phase 5)
- [ ] No apps remain in "Updates" section after "Update All" completes

## Agent-Verified Criteria

| Check | Before (capture) | After (verify) | How to compare |
|-------|-------------------|-----------------|----------------|
| Badge count after sideload | Baseline badge "Update All (B)" | After installs: "Update All (B + N)" | After == Before + N |
| All apps visible at once | N/A | `inspect_view_hierarchy` on Updates tab | All N apps found in one view (with scrolling, but no pagination/batching) |
| Badge count after Update All | "Update All (B + N)" | "Update All (B)" or button absent | Should return to baseline |
| Each app version updated | `adb dumpsys` before | `adb dumpsys` after Update All | Version increased for each app |

## Known Flaky Patterns

| Error Pattern | Likely Cause | Recovery Action |
|---------------|-------------|-----------------|
| Update polling hasn't detected new installs | Polling interval is 5 min; sideloaded apps may not trigger immediate detection | Pull-to-refresh on Updates tab, wait 30s, re-check |
| "Update All" triggers dialogs for each app sequentially | Expected Samsung/Android behavior — each APK needs separate user confirmation | Agent must monitor and handle each dialog as it appears |
| Some apps show in "Manual Updates" instead of "Updates" | App's signer not in user's web of trust | Still counts as an update; verify in both sections |
| "All Versions" not visible on detail page | Debug section may not be enabled or page didn't scroll enough | Retry scroll, or try a different app |
| `install_older_version.yaml` installs latest instead of older | Version list order changed | Take screenshot, verify which version was installed via `adb dumpsys` |
| Samsung "package analysis error" dialog blocks "Open" assertion | Samsung system dialog covers the "Open" button after APK install | Flow now handles this with conditional `tapOn: "OK"` — if it still fails, retry |
| "All done (N installed)" banner blocks "Update All" button | Banner and button are mutually exclusive in the UI | Tap the banner to dismiss it, or wait for auto-clear (3s) |
| Keyboard covers bottom tabs after search | Samsung keyboard stays open after `inputText` | `search_app.yaml` now dismisses keyboard before tab navigation |

## Retry Strategy

- **Max attempts**: 2
- **Between retries**: Full teardown + setup
- **If an app doesn't have older versions**: Skip it, try the next app in the list
- **If "Update All" partially fails**: Document which apps failed and which succeeded

## Teardown (agent-executed, optional)

No cleanup strictly required — apps are now at their latest version.
To reset for a fresh test:

```bash
adb shell pm uninstall social.flotilla || true
adb shell pm uninstall com.vitorpamplona.amethyst || true
adb shell pm uninstall com.schwegelbin.openbible || true
adb shell pm uninstall com.duckduckgo.mobile.android || true
```

## Related

- **Feature spec**: `spec/features/FEAT-003-updates-screen.md`
- **Acceptance criteria covered**:
  - "Update All button at top updates all apps with automatic update capability"
  - "Badge count updates reactively when new updates discovered"
  - "Shows categorized lists: Installing, Updates, Manual Updates, Up to Date"
- **GitHub issue**: Updates shown in batches, Update All not verified end-to-end
- **Code areas**:
  - `lib/services/updates_service.dart` — `CategorizedUpdatesNotifier`, update polling
  - `lib/screens/updates_screen.dart` — `UpdateAllRow`, categorized list rendering
  - `lib/services/package_manager/package_manager.dart` — `queueDownloads()` (Update All implementation)
  - `lib/widgets/batch_progress_banner.dart` — "All done" banner after batch
