#!/usr/bin/env bash
#
# TEST-003 — Bulk Update Flow (standalone, no AI needed)
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
AMBER_PKG="com.greenart7c3.nostrsigner"
AMBER_APK_URL="https://github.com/greenart7c3/Amber/releases/download/v4.1.2/amber-free-universal-release-v4.1.2.apk"
AMBER_APK_CACHE="/tmp/amber-latest.apk"
FIXTURES_DIR="$PROJECT_ROOT/test/fixtures"

# Always run from a fully clean state
FORCE_FRESH_START=1

# Load test account nsec from fixtures
NOSTR_TEST_NSEC=""
if [ -f "$FIXTURES_DIR/test-account.env" ]; then
  NOSTR_TEST_NSEC=$(grep '^NOSTR_TEST_NSEC=' "$FIXTURES_DIR/test-account.env" | cut -d= -f2)
fi
if [ -z "$NOSTR_TEST_NSEC" ]; then
  echo "ERROR: test/fixtures/test-account.env missing or NOSTR_TEST_NSEC not set"
  exit 1
fi

print_usage() {
  cat <<'EOF'
Usage:
  run-test-003.sh [DEVICE_ID] [APP_COUNT] [--from N] [--to N]
                  [--device DEVICE_ID] [--apps APP_COUNT]

Stages:
  0 = clean_state     (reset apps/data, uninstall targets, amber cache)
  1 = auth            (setup Amber + sign in Zapstore)
  2 = install_old     (install older versions)
  3 = update_all      (verify updates + tap Update All)
  4 = post_verify     (verify versions changed)

Examples:
  ./run-test-003.sh RQCT1029N9J 2
  ./run-test-003.sh --device RQCT1029N9J --apps 2 --from 2 --to 4
  ./run-test-003.sh RQCT1029N9J 2 --from 3 --to 4
EOF
}

DEVICE_ID=""
REQUESTED_COUNT="2"
FROM_STAGE=0
TO_STAGE=4

POSITIONAL=()
while [ $# -gt 0 ]; do
  case "$1" in
    --from)
      FROM_STAGE="${2:-}"
      shift 2
      ;;
    --to)
      TO_STAGE="${2:-}"
      shift 2
      ;;
    --device)
      DEVICE_ID="${2:-}"
      shift 2
      ;;
    --apps|--count)
      REQUESTED_COUNT="${2:-}"
      shift 2
      ;;
    -h|--help)
      print_usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "ERROR: unknown option: $1"
      print_usage
      exit 1
      ;;
    *)
      POSITIONAL+=("$1")
      shift
      ;;
  esac
done

if [ -z "$DEVICE_ID" ] && [ ${#POSITIONAL[@]} -ge 1 ]; then
  DEVICE_ID="${POSITIONAL[0]}"
fi
if [ ${#POSITIONAL[@]} -ge 2 ]; then
  REQUESTED_COUNT="${POSITIONAL[1]}"
fi

if ! [[ "$FROM_STAGE" =~ ^[0-4]$ ]]; then
  echo "ERROR: --from must be between 0 and 4"
  exit 1
fi
if ! [[ "$TO_STAGE" =~ ^[0-4]$ ]]; then
  echo "ERROR: --to must be between 0 and 4"
  exit 1
fi
if [ "$FROM_STAGE" -gt "$TO_STAGE" ]; then
  echo "ERROR: --from cannot be greater than --to"
  exit 1
fi

# App catalog (parallel arrays — bash 3.2 compatible)
ALL_APP_NAMES=(   "Flotilla"              "Amethyst"                     "OpenBible"                    "DuckDuckGo" )
ALL_APP_PKGS=(    "social.flotilla"       "com.vitorpamplona.amethyst"   "com.schwegelbin.openbible"    "com.duckduckgo.mobile.android" )
ALL_APP_SEARCH=(  "Flotilla"              "Amethyst"                     "OpenBible"                    "DuckDuckGo" )
ALL_APP_MATCH=(   ".*Flotilla.*hodlbod.*" ".*all-in-one Nostr.*"         ".*provides the Bible.*"       ".*comprehensive online privacy.*" )

MAX_APPS=${#ALL_APP_NAMES[@]}

# Clamp to 2..MAX_APPS (Update All requires at least 2 apps)
if [ "$REQUESTED_COUNT" -lt 2 ] 2>/dev/null; then REQUESTED_COUNT=2; fi
if [ "$REQUESTED_COUNT" -gt "$MAX_APPS" ] 2>/dev/null || ! [ "$REQUESTED_COUNT" -eq "$REQUESTED_COUNT" ] 2>/dev/null; then
  REQUESTED_COUNT=$MAX_APPS
fi

# Slice arrays to requested count
APP_NAMES=(); APP_PKGS=(); APP_SEARCH=(); APP_MATCH=()
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
DID_POST_VERIFY=0

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
  local flow_name
  flow_name="$(basename "$flow")"
  local started
  started="$(date +%s)"

  local env_args=()
  while [ $# -gt 0 ]; do
    env_args+=(-e "$1")
    shift
  done

  log "    -> Maestro start: $flow_name"

  local rc=0
  if [ ${#env_args[@]} -eq 0 ]; then
    maestro test --udid "$DEVICE_ID" "$flow" || rc=$?
  else
    maestro test --udid "$DEVICE_ID" "${env_args[@]}" "$flow" || rc=$?
  fi

  local ended elapsed
  ended="$(date +%s)"
  elapsed=$((ended - started))
  log "    <- Maestro end: $flow_name (${elapsed}s, rc=$rc)"
  return "$rc"
}

disable_notifications_permission() {
  local package="$1"
  local label="$2"

  adb -s "$DEVICE_ID" shell pm revoke "$package" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  adb -s "$DEVICE_ID" shell pm set-permission-flags "$package" android.permission.POST_NOTIFICATIONS user-set user-fixed >/dev/null 2>&1 || true
  adb -s "$DEVICE_ID" shell cmd appops set "$package" POST_NOTIFICATION ignore >/dev/null 2>&1 || true

  log "  Notifications disabled for $label"
}

wait_for_foreground() {
  local package="$1"
  local timeout_secs="${2:-8}"
  local i=0
  while [ "$i" -lt "$timeout_secs" ]; do
    if adb -s "$DEVICE_ID" shell dumpsys window 2>/dev/null | grep -q "$package"; then
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done
  return 1
}

should_run_stage() {
  local stage="$1"
  [ "$stage" -ge "$FROM_STAGE" ] && [ "$stage" -le "$TO_STAGE" ]
}

prepare_stage_window_prereqs() {
  # When starting from a later stage, still do the minimal prep needed
  # for deterministic installs.
  if ! should_run_stage 0 && should_run_stage 2; then
    log "── Pre-setup for stage window: reset target install apps ──"
    uninstall_apps
  fi
}

capture_versions_as_before() {
  log "── Snapshot: capture installed versions as baseline ──"
  local i=0
  while [ $i -lt $APP_COUNT ]; do
    local package="${APP_PKGS[$i]}"
    local name="${APP_NAMES[$i]}"
    local ver
    ver=$(get_version "$package")
    if [ -n "$ver" ]; then
      VER_BEFORE[$i]="$ver"
      log "  Baseline $name: $ver"
    else
      VER_BEFORE[$i]="FAILED"
      log "  Baseline $name: not installed"
    fi
    i=$((i + 1))
  done
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

prepare_clean_state() {
  log "── Setup: Reset device state (clean start) ──"

  adb -s "$DEVICE_ID" shell am force-stop "$ZAPSTORE_PKG" 2>/dev/null || true
  adb -s "$DEVICE_ID" shell am force-stop "$AMBER_PKG" 2>/dev/null || true
  adb -s "$DEVICE_ID" shell am force-stop com.android.settings 2>/dev/null || true

  adb -s "$DEVICE_ID" shell pm clear "$ZAPSTORE_PKG" >/dev/null 2>&1 || true

  if adb -s "$DEVICE_ID" shell pm list packages 2>/dev/null | grep -q "$AMBER_PKG"; then
    adb -s "$DEVICE_ID" shell pm uninstall "$AMBER_PKG" >/dev/null 2>&1 || true
    log "  Amber uninstalled for fresh start"
  else
    log "  Amber not installed, nothing to remove"
  fi
  rm -f "$AMBER_APK_CACHE" >/dev/null 2>&1 || true
  log "  Amber APK cache cleared"

  uninstall_apps
}

restart_zapstore() {
  adb -s "$DEVICE_ID" shell am force-stop "$ZAPSTORE_PKG" 2>/dev/null || true
  sleep 0.5
  adb -s "$DEVICE_ID" shell am start --activity-clear-task -n "$ZAPSTORE_PKG/.MainActivity" >/dev/null 2>&1
  wait_for_foreground "$ZAPSTORE_PKG" 8 || true
  log "Zapstore restarted"
}

ensure_amber() {
  if adb -s "$DEVICE_ID" shell pm list packages 2>/dev/null | grep -q "$AMBER_PKG"; then
    log "  Amber already installed"
    disable_notifications_permission "$AMBER_PKG" "Amber"
    return 0
  fi
  log "  Amber not installed — downloading and installing..."
  if [ ! -f "$AMBER_APK_CACHE" ]; then
    curl -L -o "$AMBER_APK_CACHE" "$AMBER_APK_URL" || { fail "Failed to download Amber APK"; return 1; }
  fi
  adb -s "$DEVICE_ID" install "$AMBER_APK_CACHE" || { fail "Failed to install Amber APK"; return 1; }
  log "  Amber installed successfully"
  disable_notifications_permission "$AMBER_PKG" "Amber"
}

setup_amber() {
  log "── Phase 0: Ensure test account in Amber ──"
  adb -s "$DEVICE_ID" shell input keyevent KEYCODE_HOME 2>/dev/null || true
  adb -s "$DEVICE_ID" shell am force-stop com.android.settings 2>/dev/null || true
  adb -s "$DEVICE_ID" shell am force-stop "$AMBER_PKG" 2>/dev/null || true
  adb -s "$DEVICE_ID" shell am force-stop "$ZAPSTORE_PKG" 2>/dev/null || true
  sleep 0.2
  adb -s "$DEVICE_ID" shell am start -n "$AMBER_PKG/.MainActivity" >/dev/null 2>&1
  wait_for_foreground "$AMBER_PKG" 6 || true
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
  adb -s "$DEVICE_ID" shell am force-stop "$AMBER_PKG" 2>/dev/null || true
  if run_maestro "$FLOWS_DIR/sign_in_amber.yaml"; then
    log "  Sign in verified"
    return 0
  fi
  fail "Sign in failed"
  generate_report
  exit 1
}

install_older_version() {
  local idx="$1"
  local name="${APP_NAMES[$idx]}"
  local package="${APP_PKGS[$idx]}"
  local search="${APP_SEARCH[$idx]}"
  local match="${APP_MATCH[$idx]}"
  log "  Installing older $name"

  # Bring Zapstore to foreground in case previous install left system/app-detail state.
  adb -s "$DEVICE_ID" shell am start -n "$ZAPSTORE_PKG/.MainActivity" >/dev/null 2>&1 || true
  wait_for_foreground "$ZAPSTORE_PKG" 6 || true

  run_maestro "$FLOWS_DIR/search_and_install_older.yaml" "APP_SEARCH_TERM=$search" "APP_MATCH_TEXT=$match" || true

  local ver
  ver=$(get_version "$package")
  if [ -n "$ver" ]; then
    VER_BEFORE[$idx]="$ver"
    log "  ✓ $name installed: $ver"
    INSTALL_RESULTS="${INSTALL_RESULTS}| $name | $ver | ✓ |
"
    return 0
  fi

  VER_BEFORE[$idx]="FAILED"
  INSTALL_RESULTS="${INSTALL_RESULTS}| $name | FAILED | ✗ |
"
  fail "Could not install older $name"
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
  if run_maestro "$FLOWS_DIR/verify_and_update_all.yaml" "APP_COUNT=$APP_COUNT"; then
    log "  Update All completed successfully"
  else
    fail "verify_and_update_all flow failed"
  fi
}

verify_post_update() {
  log "── Phase 5: Post-update verification ──"
  sleep 0.5
  DID_POST_VERIFY=1
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

  local post_update_table=""
  local i=0
  while [ $i -lt $APP_COUNT ]; do
    local name="${APP_NAMES[$i]}"
    local before="${VER_BEFORE[$i]:-N/A}"
    local after="${VER_AFTER[$i]:-N/A}"
    local status="✓"
    if [ "$after" = "SKIPPED" ] || [ -z "$after" ]; then
      status="-"
    elif [ "$before" = "$after" ]; then
      status="✗"
    fi
    post_update_table="${post_update_table}| $name | $before | $after | $status |
"
    i=$((i + 1))
  done

  local failures_section="None"
  if [ $FAILURE_COUNT -gt 0 ]; then
    failures_section="$FAILURES"
  fi

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

ensure_signed_in() {
  if [ "${FORCE_FRESH_START:-0}" -eq 1 ]; then
    log "── Fresh start enabled: skipping signed-in check ──"
    ensure_amber
    disable_notifications_permission "$ZAPSTORE_PKG" "Zapstore"
    adb -s "$DEVICE_ID" shell am force-stop "$AMBER_PKG" 2>/dev/null || true
    setup_amber
    restart_zapstore
    sign_in
    return 0
  fi

  log "── Check: Already signed in with test account? ──"
  restart_zapstore
  if run_maestro "$FLOWS_DIR/check_signed_in_test_account.yaml" 2>/dev/null; then
    log "  Already signed in with test account, skipping Amber setup"
    return 0
  fi
  log "  Not signed in (or wrong account), setting up Amber and signing in..."
  ensure_amber
  adb -s "$DEVICE_ID" shell pm clear "$ZAPSTORE_PKG" >/dev/null 2>&1 || true
  disable_notifications_permission "$ZAPSTORE_PKG" "Zapstore"
  adb -s "$DEVICE_ID" shell am force-stop "$AMBER_PKG" 2>/dev/null || true
  setup_amber
  restart_zapstore
  sign_in
}

main() {
  log "═══ TEST-003: Bulk Update Flow ═══"
  command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found in PATH"; exit 1; }
  command -v maestro >/dev/null 2>&1 || { echo "ERROR: maestro not found in PATH"; exit 1; }

  log "Stage window: $FROM_STAGE..$TO_STAGE"

  discover_device
  setup_screen
  prepare_stage_window_prereqs

  if should_run_stage 0; then
    prepare_clean_state
  else
    log "── Skip Stage 0 (clean_state) ──"
  fi

  if should_run_stage 1; then
    ensure_signed_in
  else
    log "── Skip Stage 1 (auth) ──"
  fi

  if should_run_stage 2; then
    install_all_older
  else
    log "── Skip Stage 2 (install_old) ──"
  fi

  if ! should_run_stage 2 && ( should_run_stage 3 || should_run_stage 4 ); then
    capture_versions_as_before
  fi

  if should_run_stage 3; then
    verify_and_update
  else
    log "── Skip Stage 3 (update_all) ──"
  fi

  if should_run_stage 4; then
    verify_post_update
  else
    log "── Skip Stage 4 (post_verify) ──"
  fi

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
