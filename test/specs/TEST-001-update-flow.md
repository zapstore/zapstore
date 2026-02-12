# TEST-001 — Update Flow

## Purpose

Verify that installing an older version of an app causes it to appear in the
Updates tab with a newer version available and an "Update All" action.

This exercises the full update detection pipeline: sign-in, search, version
selection, install with system dialogs, and update discovery.

## Parameters

| Parameter         | Default                       | Alternative                     | Description                       |
| ----------------- | ----------------------------- | ------------------------------- | --------------------------------- |
| `APP_PACKAGE`     | `social.flotilla`             | `com.duckduckgo.mobile.android` | Package under test                |
| `APP_SEARCH_TERM` | `"Flotilla"`                  | `"DuckDuckGo"`                  | Search text                       |
| `APP_MATCH_TEXT`  | `".*Self-hosted community.*"` | `".*DuckDuckGo.*"`              | Regex to match search result card |

If Flotilla is unavailable (removed from store, no older versions listed),
the agent should retry with the alternative parameters.

## Maestro Flow

- **File**: `maestro/update_flow_test.yaml`
- **Sub-flows used**:
  - `flows/sign_in_amber.yaml` — Conditional Amber sign-in
  - `flows/search_app.yaml` — Parameterized search + open detail
  - `flows/uninstall_if_installed.yaml` — Conditional UI-based uninstall
  - `flows/install_older_version.yaml` — Expand "All Versions", install second version
- **Expected duration**: ~3 minutes

## Setup (agent-executed)

```bash
# Uninstall the target app to ensure clean state
adb shell pm uninstall social.flotilla    # ignore exit code if not installed

# Restart Zapstore with a clean activity stack
adb shell am start -S --activity-clear-task -n dev.zapstore.alpha/.MainActivity

# Wait for the app to fully initialize
sleep 3
```

If `am start` fails, retry once. If it fails again, abort and report setup failure.

## Success Criteria

- [ ] Maestro flow completes with 0 errors
- [ ] Flotilla is NOT visible in Updates tab before install (Maestro `assertNotVisible`)
- [ ] Flotilla IS visible in Updates tab after install (Maestro `assertVisible`)
- [ ] The Updates tab shows "Update All" (matched by `".*Update All.*"`)
- [ ] Post-test verification:
  ```bash
  adb shell dumpsys package social.flotilla | grep versionName
  ```
  Should return a version older than the latest (e.g., `1.6.2` when latest is `1.6.4`)

## Agent-Verified Criteria

Checks that require state across the test run. The agent captures values before
and after the Maestro flow and compares them.

| Check | Before (capture) | After (verify) | How to compare |
|-------|-------------------|-----------------|----------------|
| Updates tab badge increments by 1 | Navigate to Updates tab via MCP, read the badge number from "Update All (N)" | Same after Maestro flow completes | After N == Before N + 1 |
| Installed version is older than latest | N/A | `adb shell dumpsys package social.flotilla \| grep versionName` | Version returned is strictly less than latest available |

**How to capture the badge count**: Before running the Maestro flow, the agent
should use the MCP `take_screenshot` or `inspect_view_hierarchy` tool on the
Updates tab to read the current "Update All (N)" value. After the Maestro flow,
capture it again and verify it incremented by exactly 1.

## Known Flaky Patterns

| Error Pattern                                                    | Likely Cause                                          | Recovery Action                                                     |
| ---------------------------------------------------------------- | ----------------------------------------------------- | ------------------------------------------------------------------- |
| `"Element not found: .*Tab N of 3"`                              | Samsung keyboard covering bottom tabs                 | Re-run setup (clean restart dismisses keyboard), retry              |
| `"Assertion is false: \".*Self-hosted community.*\" is visible"` | Search results slow to load on poor network           | Retry with same state                                               |
| `"Element not found: Search apps"`                               | Previous search state persisted from earlier run      | Restart with `--activity-clear-task`, retry                         |
| `"Assertion is false: \"Instalar\" is visible"`                  | Samsung "Trust and install app" dialog blocking       | This is handled conditionally in the flow; if it still fails, retry |
| `"Assertion is false: \".*Update All.*\" is visible"`            | Updates tab not refreshed yet after install           | Wait 5 seconds, navigate away from Updates tab and back, re-check   |
| `"Element not found: .*All Versions.*"`                          | Page didn't scroll far enough to reveal debug section | Retry (scroll behavior varies by content height)                    |

## Retry Strategy

- **Max attempts**: 3
- **Between retries**: Re-run all Setup commands (uninstall + clean restart)
- **Escalation**: If all 3 attempts fail with the default app, retry once with
  the alternative app parameters before reporting failure
- **Special case**: If the failure is at the "Install older version" step
  (Amber/system dialogs), the agent should take a screenshot and inspect the
  view hierarchy to determine the actual blocking dialog

## Teardown (agent-executed, optional)

No cleanup required. The older version can remain installed.
To fully clean up (optional):

```bash
adb shell pm uninstall social.flotilla
```

## Related

- **Feature specs**:
  - `spec/features/FEAT-001-package-manager.md` — Install/uninstall behavior
  - `spec/features/FEAT-003-updates-screen.md` — Updates detection and display
- **Acceptance criteria covered**:
  - "User can install a specific app version"
  - "Outdated apps appear in Updates tab"
  - "Update All button is visible when updates are available"
