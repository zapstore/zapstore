# TEST-XXX — Short Name

## Purpose

1–2 sentences describing what this test verifies and why it matters.

## Parameters

| Parameter | Default | Alternative | Description |
|-----------|---------|-------------|-------------|
| `APP_PACKAGE` | `com.example.app` | `com.example.other` | Package ID of the app under test |
| `APP_SEARCH_TERM` | `"Example"` | `"Other App"` | Text to type in the search field |
| `APP_MATCH_TEXT` | `".*example description.*"` | `".*other description.*"` | Regex to match the search result card |

If the primary app is unavailable (not found, install fails), the agent should
retry the test with the alternative parameters before reporting a failure.

## Maestro Flow

- **File**: `maestro/xxx_test.yaml`
- **Sub-flows**: `search_app.yaml`, `install_app.yaml` (list all used)
- **Expected duration**: ~2 minutes

## Setup (agent-executed)

Commands the agent runs BEFORE the Maestro flow. These handle what Maestro
cannot do (ADB commands, app lifecycle, device state).

```bash
# Ensure clean app state
adb shell am start -S --activity-clear-task -n dev.zapstore.alpha/.MainActivity
# Wait for app to initialize
sleep 3
```

Any step that fails should be logged and retried once. If setup fails after
retry, abort the test and report setup failure.

## Success Criteria

How the agent verifies the test passed (beyond Maestro's own assertions).

- [ ] Maestro flow completes with 0 errors
- [ ] Specific post-test verification (e.g., `adb shell dumpsys package ... | grep versionName`)

## Agent-Verified Criteria

Checks that require state across the test run. Maestro is stateless, so only the
agent can capture a "before" value, run the flow, then compare the "after" value.
These checks are executed and reported by the agent, not by Maestro.

| Check | Before (capture) | After (verify) | How to compare |
|-------|-------------------|-----------------|----------------|
| _Example: badge count_ | `adb shell ...` or MCP screenshot to read badge | Same command/screenshot after flow | After value == Before value + 1 |

The agent must:
1. Run the **Before** capture commands during Setup (after standard setup, before Maestro).
2. Run the **After** capture commands during Verification (after Maestro completes).
3. Compare values using the specified logic.
4. Report each check as pass/fail in the run log and report.

## Known Flaky Patterns

Errors that indicate flakiness, not real failures. The agent should match
against these before declaring a test failure.

| Error Pattern | Likely Cause | Recovery Action |
|---------------|-------------|-----------------|
| `"Element not found: .*Tab N of 3"` | Samsung keyboard covers bottom tabs | Re-run setup, retry |
| `"Assertion is false: X is visible"` | Content still loading / slow network | Retry (same state) |
| `"Element not found: Search apps"` | Previous search state persisted | Restart app with `--activity-clear-task`, retry |

## Retry Strategy

- **Max attempts**: 3
- **Between retries**: Re-run all Setup commands
- **Escalation**: If primary parameters fail all attempts, retry once with
  alternative parameters before reporting failure

## Teardown (agent-executed, optional)

Cleanup after the test. Runs regardless of pass/fail.

```bash
# Example: remove test app
adb shell pm uninstall com.example.app
```

## Related

- **Feature spec**: `spec/features/FEAT-XXX-*.md`
- **Acceptance criteria covered**: List specific criteria from the feature spec

---

# Example: TEST-002 — Install App

## Purpose

Verify that the user can search for an app, install it, and see the "Open"
button confirming successful installation.

## Parameters

| Parameter | Default | Alternative | Description |
|-----------|---------|-------------|-------------|
| `APP_PACKAGE` | `social.flotilla` | `com.duckduckgo.mobile.android` | Package to install |
| `APP_SEARCH_TERM` | `"Flotilla"` | `"DuckDuckGo"` | Search text |
| `APP_MATCH_TEXT` | `".*Self-hosted community.*"` | `".*DuckDuckGo.*"` | Result card regex |

## Maestro Flow

- **File**: `maestro/install_app_test.yaml`
- **Sub-flows**: `search_app.yaml`, `uninstall_if_installed.yaml`, `install_app.yaml`
- **Expected duration**: ~2 minutes

## Setup (agent-executed)

```bash
adb shell pm uninstall social.flotilla    # ignore exit code
adb shell am start -S --activity-clear-task -n dev.zapstore.alpha/.MainActivity
sleep 3
```

## Success Criteria

- [ ] Maestro flow completes with 0 errors
- [ ] `adb shell pm list packages | grep social.flotilla` returns the package

## Agent-Verified Criteria

| Check | Before (capture) | After (verify) | How to compare |
|-------|-------------------|-----------------|----------------|
| App not installed before test | `adb shell pm list packages \| grep social.flotilla` → empty | `adb shell pm list packages \| grep social.flotilla` → found | Before: absent, After: present |

## Known Flaky Patterns

| Error Pattern | Likely Cause | Recovery Action |
|---------------|-------------|-----------------|
| `"Assertion is false: \"Install\" is visible"` | App not fully uninstalled | Re-run `pm uninstall`, retry |
| `"Assertion is false: \"Instalar\" is visible"` | Samsung trust dialog appeared instead | Retry (conditional flow handles it) |

## Retry Strategy

- **Max attempts**: 3
- **Between retries**: Re-run Setup
- **Escalation**: Switch to alternative app parameters

## Teardown

None required (installed app can stay).

## Related

- **Feature spec**: `spec/features/FEAT-001-package-manager.md`
- **Acceptance criteria**: "User can install app from store"
