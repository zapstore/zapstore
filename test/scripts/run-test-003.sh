#!/usr/bin/env bash
#
# TEST-003 — Bulk Update Flow (standalone, no AI needed)
#
# Orchestrates adb + Maestro CLI to:
#   1. Uninstall 4 target apps via adb
#   2. Install older versions via Zapstore UI (Maestro flows)
#   3. Verify all updates appear in one list
#   4. Execute "Update All"
#   5. Verify all apps updated to latest via adb
#
# Prerequisites:
#   - adb in PATH, device connected via USB
#   - maestro CLI in PATH (https://maestro.mobile.dev)
#   - Zapstore (dev.zapstore.app) installed on device
#   - Amber signer app installed (test account is imported automatically)
#   - Device language set to English
#
# The script auto-imports the test keypair into Amber if not present.
# The test account's pubkey is in allowedHexKeys (lib/utils/debug_utils.dart),
# which is required for the "Debug: All Versions" section to appear.
# See test/fixtures/test-account.env for the full keypair.
#
# Usage:
#   ./run-test-003.sh                      # auto-detect device, 4 apps
#   ./run-test-003.sh <DEVICE_ID>          # target specific device, 4 apps
#   ./run-test-003.sh <DEVICE_ID> <N>      # target device, N apps (1–4)
#
set -euo pipefail

# ── Configuration ────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
FLOWS_DIR="$PROJECT_ROOT/maestro/flows"
REPORT_DIR="$PROJECT_ROOT/test/runs"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
REPORT_FILE="$REPORT_DIR/TEST-003-auto-$TIMESTAMP.md"
ZAPSTORE_PKG="dev.zapstore.app"
FIXTURES_DIR="$PROJECT_ROOT/test/fixtures"
MAX_RETRIES=3

# Load test account nsec from fixtures
NOSTR_TEST_NSEC=""
if [ -f "$FIXTURES_DIR/test-account.env" ]; then
  NOSTR_TEST_NSEC=$(grep '^NOSTR_TEST_NSEC=' "$FIXTURES_DIR/test-account.env" | cut -d= -f2)
fi
if [ -z "$NOSTR_TEST_NSEC" ]; then
  echo "ERROR: test/fixtures/test-account.env missing or NOSTR_TEST_NSEC not set"
  exit 1
fi

DEVICE_ID="${1:-}"
REQUESTED_COUNT="${2:-4}"

# App catalog (parallel arrays — bash 3.2 compatible)
ALL_APP_NAMES=(   "Flotilla"              "Amethyst"                     "OpenBible"                    "DuckDuckGo" )
ALL_APP_PKGS=(    "social.flotilla"       "com.vitorpamplona.amethyst"   "com.schwegelbin.openbible"    "com.duckduckgo.mobile.android" )
ALL_APP_SEARCH=(  "Flotilla"              "Amethyst"                     "OpenBible"                    "DuckDuckGo" )
ALL_APP_MATCH=(   ".*Flotilla.*hodlbod.*" ".*all-in-one Nostr.*"         ".*provides the Bible.*"       ".*comprehensive online privacy.*" )

MAX_APPS=${#ALL_APP_NAMES[@]}

# Clamp to 1..MAX_APPS
if [ "$REQUESTED_COUNT" -lt 1 ] 2>/dev/null; then REQUESTED_COUNT=1; fi
if [ "$REQUESTED_COUNT" -gt "$MAX_APPS" ] 2>/dev/null || ! [ "$REQUESTED_COUNT" -eq "$REQUESTED_COUNT" ] 2>/dev/null; then
  REQUESTED_COUNT=$MAX_APPS
fi

# Slice arrays to requested count
APP_NAMES=();  APP_PKGS=();  APP_SEARCH=();  APP_MATCH=()
VER_BEFORE=(); VER_AFTER=()
i=0
while [ $i -lt "$REQUESTED_COUNT" ]; do
  APP_NAMES+=( "${ALL_APP_NAMES[$i]}" )
  APP_PKGS+=( "${ALL_APP_PKGS[$i]}" )
  APP_SEARCH+=( "${ALL_APP_SEARCH[$i]}" )
  APP_MATCH+=( "${ALL_APP_MATCH[$i]}" )
  VER_BEFORE+=( "" )
  VER_AFTER+=( "" )
  i=$((i + 1))
done

APP_COUNT=${#APP_NAMES[@]}

# ── State ────────────────────────────────────────────────────────────

FAILURES=""
FAILURE_COUNT=0
LOG_TEXT=""
INSTALL_RESULTS=""
START_TIME="$(date +%s)"

# ── Helpers ──────────────────────────────────────────────────────────

log() {
  local msg="[$(date +%H:%M:%S)] $*"
  echo "$msg"
  LOG_TEXT="${LOG_TEXT}${msg}
"
}

fail() {
  log "FAIL: $*"
  FAILURES="${FAILURES}- $*
"
  FAILURE_COUNT=$((FAILURE_COUNT + 1))
}

get_version() {
  local package="$1"
  adb -s "$DEVICE_ID" shell dumpsys package "$package" 2>/dev/null \
    | grep versionName | head -1 | sed 's/.*versionName=//' | tr -d '[:space:]'
}

run_maestro() {
  local flow="$1"; shift
  local env_args=()
  while [ $# -gt 0 ]; do
    env_args+=(-e "$1")
    shift
  done
  if [ ${#env_args[@]} -eq 0 ]; then
    maestro test --udid "$DEVICE_ID" "$flow"
  else
    maestro test --udid "$DEVICE_ID" "${env_args[@]}" "$flow"
  fi
}

# ── Phases ───────────────────────────────────────────────────────────

discover_device() {
  if [ -z "$DEVICE_ID" ]; then
    DEVICE_ID=$(adb devices | grep -v "^List" | grep -v "^$" | head -1 | awk '{print $1}')
    if [ -z "$DEVICE_ID" ]; then
      echo "ERROR: No device found. Connect a device and retry."
      exit 1
    fi
  fi
  log "Device: $DEVICE_ID"
}

setup_screen() {
  adb -s "$DEVICE_ID" shell settings put system screen_off_timeout 2147483647
  adb -s "$DEVICE_ID" shell svc power stayon true
  log "Screen stay-on enabled"
}


uninstall_apps() {
  log "── Setup: Uninstalling target apps ──"
  local i=0
  while [ $i -lt $APP_COUNT ]; do
    local name="${APP_NAMES[$i]}"
    local package="${APP_PKGS[$i]}"
    if adb -s "$DEVICE_ID" shell pm list packages 2>/dev/null | grep -q "$package"; then
      adb -s "$DEVICE_ID" shell pm uninstall "$package" >/dev/null 2>&1 || true
      log "  Uninstalled $name ($package)"
    else
      log "  $name not installed, skipping"
    fi
    i=$((i + 1))
  done
}

restart_zapstore() {
  adb -s "$DEVICE_ID" shell am force-stop "$ZAPSTORE_PKG" 2>/dev/null || true
  sleep 2
  adb -s "$DEVICE_ID" shell am start --activity-clear-task \
    -n "$ZAPSTORE_PKG/.MainActivity" >/dev/null 2>&1
  sleep 10
  log "Zapstore restarted"
}

setup_amber() {
  log "── Phase 0: Ensure test account in Amber ──"
  # Clean up: dismiss notifications, stop stale apps, go to home screen
  adb -s "$DEVICE_ID" shell input keyevent KEYCODE_HOME 2>/dev/null || true
  adb -s "$DEVICE_ID" shell am force-stop com.android.settings 2>/dev/null || true
  adb -s "$DEVICE_ID" shell am force-stop com.greenart7c3.nostrsigner 2>/dev/null || true
  adb -s "$DEVICE_ID" shell am force-stop "$ZAPSTORE_PKG" 2>/dev/null || true
  sleep 2
  adb -s "$DEVICE_ID" shell monkey -p com.greenart7c3.nostrsigner -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 5
  if run_maestro "$FLOWS_DIR/setup_amber_test_account.yaml" "NOSTR_TEST_NSEC=$NOSTR_TEST_NSEC"; then
    log "  Amber test account ready"
  else
    fail "Could not set up Amber test account"
    generate_report
    exit 1
  fi
}

sign_in() {
  log "── Phase 1: Sign in ──"
  local attempt=0
  while [ $attempt -lt $MAX_RETRIES ]; do
    attempt=$((attempt + 1))
    log "  Sign in attempt $attempt/$MAX_RETRIES"
    # Dismiss any pending Amber dialogs by force-stopping it
    adb -s "$DEVICE_ID" shell am force-stop com.greenart7c3.nostrsigner 2>/dev/null || true
    sleep 1
    if run_maestro "$FLOWS_DIR/sign_in_amber.yaml"; then
      log "  Sign in verified"
      return 0
    fi
    log "  Sign in attempt $attempt failed, restarting Zapstore..."
    restart_zapstore
  done
  fail "Sign in failed after $MAX_RETRIES attempts"
  generate_report
  exit 1
}

install_older_version() {
  local idx="$1"
  local name="${APP_NAMES[$idx]}"
  local package="${APP_PKGS[$idx]}"
  local search="${APP_SEARCH[$idx]}"
  local match="${APP_MATCH[$idx]}"
  local attempt=0

  while [ $attempt -lt $MAX_RETRIES ]; do
    attempt=$((attempt + 1))
    log "  Installing older $name (attempt $attempt/$MAX_RETRIES)"

    run_maestro "$FLOWS_DIR/search_app.yaml" \
      "APP_SEARCH_TERM=$search" "APP_MATCH_TEXT=$match" && \
    run_maestro "$FLOWS_DIR/install_older_version.yaml" || true

    # Check if the app got installed (flow might fail but install can still succeed)
    local ver
    ver=$(get_version "$package")
    if [ -n "$ver" ]; then
      VER_BEFORE[$idx]="$ver"
      log "  ✓ $name installed: $ver"
      INSTALL_RESULTS="${INSTALL_RESULTS}| $name | $ver | ✓ (attempt $attempt) |
"
      # Restart to clean state for next app (flow may have left dialogs open)
      restart_zapstore
      return 0
    fi

    log "  Attempt $attempt failed (not installed), resetting Zapstore..."
    restart_zapstore
  done

  VER_BEFORE[$idx]="FAILED"
  INSTALL_RESULTS="${INSTALL_RESULTS}| $name | FAILED | ✗ |
"
  fail "Could not install older $name after $MAX_RETRIES attempts"
  return 1
}

install_all_older() {
  log "── Phase 2: Install older versions ──"
  local installed=0
  local i=0

  while [ $i -lt $APP_COUNT ]; do
    if install_older_version "$i"; then
      installed=$((installed + 1))
    fi
    i=$((i + 1))
  done

  log "  Installed $installed/$APP_COUNT apps"

  if [ $installed -eq 0 ]; then
    fail "No apps installed — cannot proceed"
    generate_report
    exit 1
  fi
}

verify_and_update() {
  log "── Phase 3+4: Verify updates & Update All ──"
  restart_zapstore
  if run_maestro "$FLOWS_DIR/verify_and_update_all.yaml"; then
    log "  Update All completed successfully"
  else
    fail "verify_and_update_all flow failed"
  fi
}

verify_post_update() {
  log "── Phase 5: Post-update verification ──"
  sleep 5
  local i=0

  while [ $i -lt $APP_COUNT ]; do
    local name="${APP_NAMES[$i]}"
    local package="${APP_PKGS[$i]}"
    local before="${VER_BEFORE[$i]}"

    if [ "$before" = "FAILED" ]; then
      VER_AFTER[$i]="SKIPPED"
      log "  ⊘ $name skipped (install had failed)"
      i=$((i + 1))
      continue
    fi

    local ver
    ver=$(get_version "$package")
    VER_AFTER[$i]="$ver"

    if [ -n "$ver" ] && [ "$ver" != "$before" ]; then
      log "  ✓ $name: $before → $ver"
    else
      fail "$name not updated (before=$before, after=$ver)"
    fi
    i=$((i + 1))
  done
}

# ── Report ───────────────────────────────────────────────────────────

generate_report() {
  mkdir -p "$REPORT_DIR"

  local end_time
  end_time="$(date +%s)"
  local duration=$((end_time - START_TIME))
  local mins=$((duration / 60))
  local secs=$((duration % 60))

  local result="PASS"
  [ $FAILURE_COUNT -gt 0 ] && result="FAIL"

  # Build post-update table
  local post_update_table=""
  local i=0
  while [ $i -lt $APP_COUNT ]; do
    local name="${APP_NAMES[$i]}"
    local before="${VER_BEFORE[$i]:-N/A}"
    local after="${VER_AFTER[$i]:-N/A}"
    local status="✓"
    if [ "$before" = "$after" ] || [ "$after" = "SKIPPED" ] || [ -z "$after" ]; then
      status="✗"
    fi
    post_update_table="${post_update_table}| $name | $before | $after | $status |
"
    i=$((i + 1))
  done

  # Failures
  local failures_section="None"
  if [ $FAILURE_COUNT -gt 0 ]; then
    failures_section="$FAILURES"
  fi

  # Criteria checks
  local c_install="x"; echo "$INSTALL_RESULTS" | grep -q "FAILED" && c_install=" "
  local c_update="x"; [ $FAILURE_COUNT -gt 0 ] && c_update=" "

  cat > "$REPORT_FILE" << EOF
# TEST-003 — Bulk Update Flow (Automated)

- **Result**: $result
- **Date**: $(date '+%Y-%m-%d %H:%M:%S')
- **Duration**: ${mins}m ${secs}s
- **Device**: $DEVICE_ID
- **Generated by**: \`run-test-003.sh\` (standalone, no AI)

## Success Criteria

- [$c_install] All older versions installed successfully
- [$c_update] Update All completed — all apps updated
- [$c_update] No apps stuck or batched

## Phase 2: Installed Older Versions

| App | Version | Result |
|-----|---------|--------|
$INSTALL_RESULTS

## Phase 5: Post-Update Verification

| App | Before | After | Updated |
|-----|--------|-------|---------|
$post_update_table

## Failures

$failures_section

## Execution Log

\`\`\`
$LOG_TEXT
\`\`\`
EOF

  log "Report saved: $REPORT_FILE"
}

# ── Main ─────────────────────────────────────────────────────────────

main() {
  log "═══ TEST-003: Bulk Update Flow ═══"

  command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found in PATH"; exit 1; }
  command -v maestro >/dev/null 2>&1 || { echo "ERROR: maestro not found in PATH"; exit 1; }

  discover_device
  setup_screen
  setup_amber
  # Clear Zapstore data to avoid NIP04 conflicts between
  # the previous session (e.g. hvmelo) and the test account in Amber
  adb -s "$DEVICE_ID" shell pm clear "$ZAPSTORE_PKG" >/dev/null 2>&1 || true
  # Re-grant notification permission so the dialog won't appear on fresh launch
  adb -s "$DEVICE_ID" shell pm grant "$ZAPSTORE_PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
  adb -s "$DEVICE_ID" shell am force-stop com.greenart7c3.nostrsigner 2>/dev/null || true
  uninstall_apps
  restart_zapstore
  sign_in
  install_all_older
  verify_and_update
  verify_post_update
  generate_report

  if [ $FAILURE_COUNT -gt 0 ]; then
    log "═══ RESULT: FAIL ($FAILURE_COUNT failures) ═══"
    exit 1
  else
    log "═══ RESULT: PASS ═══"
    exit 0
  fi
}

main "$@"
