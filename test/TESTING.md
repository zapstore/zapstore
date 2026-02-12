# Agent-Orchestrated Testing

This document defines how AI agents run end-to-end tests for Zapstore.
Tests combine **agent intelligence** (setup, analysis, retries, reporting)
with **Maestro UI automation** (tapping, scrolling, asserting).

## Architecture

```
test/
  specs/              # Test specifications (human-owned)
    _TEMPLATE.md      # Template with example
    TEST-001-*.md     # Actual test specs

  runs/               # Run logs per test (agent-written, NOT git-tracked)
    TEST-001-update-flow.md

  reports/            # Run reports with screenshots (agent-written, NOT git-tracked)
    TEST-001-update-flow-2026-02-10.md

  TESTING.md          # This file

maestro/              # Maestro flows (shared, git-tracked)
  flows/              # Reusable sub-flows
  *_test.yaml         # Test flows
```

## Ownership

| Path              | Owner  | AI May Modify                       |
| ----------------- | ------ | ----------------------------------- |
| `test/specs/*`    | Human  | No (propose changes via Spec Issue) |
| `test/runs/*`     | Agent  | Yes (append run results)            |
| `test/reports/*`  | Agent  | Yes (generate reports)              |
| `test/TESTING.md` | Human  | No                                  |
| `maestro/**`      | Shared | Yes                                 |

## Agent Workflow

When asked to run a test, the agent follows these steps **in order**.

### 0. Discover Device

Before anything else, identify the connected device:

adb devices -l

Extract the device ID (first column, e.g., RQCT1029N9J). If no device is
connected, abort and report as NO_DEVICE.
If multiple devices are listed, use the first non-emulator physical device.
Log the device ID and model in the run log.

### 1. Read the Spec

Parse the test spec from `test/specs/TEST-XXX-*.md`. Extract:

- Parameters (defaults and alternatives)
- Setup commands
- Maestro flow path
- Success criteria
- Known flaky patterns
- Retry strategy

### 2. Execute Setup

Run each command in the **Setup** section via ADB/shell:

```bash
adb shell pm uninstall <APP_PACKAGE>                              # may fail, ok
adb shell am start -S --activity-clear-task -n dev.zapstore.alpha/.MainActivity
sleep 3
```

- Log each command's exit code.
- If a critical command fails (e.g., `am start`), retry once.
- If it fails again, abort and log as **SETUP_FAILURE**.

### 2.5. Capture Baseline (Agent-Verified Criteria)

If the spec has an **Agent-Verified Criteria** section, run the **Before**
captures now — after setup but before Maestro. These are checks that require
comparing state across the test run (e.g., badge counts, version numbers).

Maestro is stateless, so only the agent can hold values across the flow.

For each row in the table:
1. Execute the **Before (capture)** command or MCP action.
2. Store the captured value (e.g., badge count = 4).
3. Log the baseline value.

These stored values will be compared in step 5.5.

### 3. Run Maestro Flow

Execute the Maestro flow via the MCP tool:

```
run_flow_files(device_id, maestro_flow_path)
```

Capture the result: success/failure, commands executed, error message.

### 4. Analyze Result

**If PASS:**

- Proceed to Success Criteria verification (step 5).

**If FAIL:**

- Extract the error message.
- Match against **Known Flaky Patterns** from the spec.
- If a pattern matches:
  - Log the flaky occurrence.
  - Apply the **Recovery Action** from the spec.
  - Re-run Setup and retry (up to max attempts).
- If no pattern matches:
  - Take a screenshot and inspect the view hierarchy.
  - Log as a **real failure** with context.
  - Still retry (the spec's retry strategy applies to all failures).

### 5. Verify Success Criteria

After a passing Maestro run, execute any post-test verifications:

```bash
adb shell dumpsys package <APP_PACKAGE> | grep versionName
```

Compare output against expected values in the spec.

### 5.5. Verify Agent-Verified Criteria

If the spec has an **Agent-Verified Criteria** section, run the **After**
captures now and compare with the baseline values from step 2.5.

For each row in the table:
1. Execute the **After (verify)** command or MCP action.
2. Compare with the stored **Before** value using the specified logic.
3. Mark the check as pass/fail.

Example:
```
Before: "Update All (4)" → badge_count = 4
After:  "Update All (5)" → badge_count = 5
Check:  5 == 4 + 1 → PASS
```

Include all agent-verified results in the run log and report.

### 6. Execute Teardown

Run Teardown commands (if any) regardless of pass/fail.

### 7. Log the Run

Prepend the result to `test/runs/TEST-XXX-*.md`:

```markdown
## Run #N — YYYY-MM-DD HH:MM UTC

- **Result**: PASS | FAIL | PASS (retry) | SETUP_FAILURE
- **Duration**: Xm Ys
- **Attempts**: N/max
- **Parameters**: default | alternative
- **Setup**: [command outcomes]
- **Maestro**: N commands executed, error (if any)
- **Verification**: [post-test check results]
- **Success Criteria**:
  - [x] Criterion from spec
  - [x] Another criterion
  - [ ] Failed criterion
- **Agent-Verified Criteria**:
  - [x] Badge count: before=4, after=5 (expected +1) — PASS
  - [ ] Version check: 1.6.4 (expected older than latest) — FAIL
- **Notes**: [agent observations, flaky pattern matches, etc.]
```

### 8. Generate Report (if requested)

Create or update `test/reports/TEST-XXX-*.md` with:

- Run summary with pass/fail status
- **Success Criteria checklist** (each criterion from the spec, marked pass/fail)
- **Agent-Verified Criteria** (before/after values and comparison results)
- Screenshots at each step (via MCP `take_screenshot`)
- Error details and recovery actions taken
- Comparison with previous runs (regression detection)

## Running Multiple Tests

When asked to run a suite (multiple tests or "all tests"):

1. Discover all `test/specs/TEST-*.md` files.
2. Sort by test number (smoke/light tests first, heavy tests last).
3. Run each test sequentially following the workflow above.
4. Generate a **suite report** summarizing all results:

```markdown
# Test Suite Report — YYYY-MM-DD HH:MM

| Test                 | Result       | Attempts | Duration | Notes                       |
| -------------------- | ------------ | -------- | -------- | --------------------------- |
| TEST-001 Update Flow | PASS         | 1/3      | 2m 30s   | Clean run                   |
| TEST-002 Install App | PASS (retry) | 2/3      | 3m 15s   | Flaky: keyboard issue       |
| TEST-003 Smoke       | FAIL         | 3/3      | 5m 00s   | Real failure: search broken |
```

The user decides which tests to run. The agent does NOT run all tests unless
explicitly asked. Valid requests:

- "Run TEST-001" — single test
- "Run TEST-001 and TEST-002" — specific subset
- "Run all tests" — full suite

## Proposing Spec Changes

When the agent discovers a **new flaky pattern** not listed in the spec:

1. Log the discovery in the run notes.
2. At the end of the run, propose adding it:
   > "I encountered a new error pattern: `Element not found: XYZ`. This appeared
   > to be a timing issue (resolved on retry). Should I propose adding this to
   > the Known Flaky Patterns in TEST-001?"
3. Wait for human approval before modifying any spec file.

The agent **never** modifies files in `test/specs/` without explicit approval.

## Maestro Limitations (Samsung-specific)

These are known device-specific issues the agent must account for:

| Issue                                              | Workaround                                                                       |
| -------------------------------------------------- | -------------------------------------------------------------------------------- |
| `hideKeyboard` exits the app                       | Never use `hideKeyboard`. Use `pressKey: Enter` or tab navigation before search. |
| `launchApp` fails via CLI/MCP                      | Use `adb shell am start` in the Setup section instead.                           |
| Keyboard covers bottom tabs                        | Structure tests to do tab navigation BEFORE search input.                        |
| `pressKey: back` exits app when keyboard is hidden | Only use `back` (Maestro command) for navigation, not keyboard dismissal.        |
| App retains search state across restarts           | Always use `--activity-clear-task` flag in Setup.                                |

## Maestro MCP Quirks

Known issues when running Maestro via the MCP tool (as opposed to CLI):

| Issue                                                                | Workaround                                                                                                                                                      |
| -------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `run_flow_files` prepends `~/` to the path                           | Pass paths **without** the home directory prefix. E.g., use `/Developer/codecode/Zapstore/zapstore/maestro/flow.yaml` instead of `/Users/hvmelo/Developer/...`. |
| `takeScreenshot` files are not saved to a discoverable disk location | Use the MCP `take_screenshot` tool for post-test evidence instead of relying on in-flow screenshots.                                                            |
|                                                                      |

## Device Prerequisites

Before running any test, ensure:

- Android device is connected and authorized (`adb devices` shows it)
- Amber signer app is installed (for tests requiring sign-in)
- Device has internet connectivity
- Zapstore (`dev.zapstore.alpha`) is installed
