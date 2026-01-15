#!/usr/bin/env bash
set -euo pipefail

# Prove reproducibility on the host (same path, two clean builds).
# This avoids false diffs caused by absolute paths embedded into native libs (e.g. libapp.so).
#
# Usage:
#   bash repro/prove_repro_host.sh
#
# Options:
#   REPRO_SPLIT_PER_ABI=1   Build with --split-per-abi (default)
#   REPRO_ABI=arm64-v8a     ABI to compare when split-per-abi is enabled (default)
#   REPRO_TARGET_PLATFORM=android-arm64  Limit build to a single target platform (default)
#   REPRO_OUT_DIR=.repro_out/host  Output directory (default)
#   REPRO_CLEAN_SDK=1       Clear Flutter SDK caches + precache before building (slow)
#   REPRO_KEEP_WORK=1       Keep intermediate extracted dirs on failure (default: 0)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

REPRO_OUT_DIR="${REPRO_OUT_DIR:-${ROOT_DIR}/.repro_out/host}"
REPRO_ABI="${REPRO_ABI:-arm64-v8a}"
REPRO_KEEP_WORK="${REPRO_KEEP_WORK:-0}"
REPRO_SPLIT_PER_ABI="${REPRO_SPLIT_PER_ABI:-1}"
REPRO_CLEAN_SDK="${REPRO_CLEAN_SDK:-0}"
REPRO_TARGET_PLATFORM="${REPRO_TARGET_PLATFORM:-android-arm64}"

_step_n=0
step() {
  _step_n=$((_step_n + 1))
  # Important: print steps to stderr so command substitutions can capture stdout safely.
  printf "\n== Step %d: %s ==\n" "${_step_n}" "$1" >&2
}

WARNED_INVALID_SDK_HASH=0
INVALID_SDK_HASH_PATTERN="Can't load Kernel binary: Invalid SDK hash."

run_checked() {
  # Run a command, keep output visible, and detect common environment warnings.
  local tmp
  tmp="$(mktemp "/tmp/zapstore-repro-host.XXXXXX.log")"

  set +e
  "$@" 2>&1 | tee "${tmp}" >&2
  local status="${PIPESTATUS[0]}"
  set -e

  if [ "${WARNED_INVALID_SDK_HASH}" != "1" ] && grep -Fq "${INVALID_SDK_HASH_PATTERN}" "${tmp}"; then
    WARNED_INVALID_SDK_HASH=1
    echo >&2
    echo "WARNING: Detected '${INVALID_SDK_HASH_PATTERN}'" >&2
    echo "         This usually indicates a corrupted/partial Flutter/Dart cache on the host." >&2
    echo "         The proof may still succeed, but if you want to eliminate this warning, re-run with:" >&2
    echo "           REPRO_CLEAN_SDK=1 bash repro/prove_repro_host.sh" >&2
    echo >&2
  fi

  rm -f "${tmp}" || true
  return "${status}"
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
}

apk_is_signed() {
  local apk="$1"
  unzip -l "${apk}" | grep -Eq 'META-INF/.*\.(RSA|DSA|EC)$'
}

pick_apk() {
  local split="$1"
  local abi="$2"

  if [ "${split}" = "1" ]; then
    # Choose a single canonical artifact for comparison (default: arm64-v8a).
    # shellcheck disable=SC2012
    ls -1 build/app/outputs/flutter-apk/*"${abi}"*release*.apk 2>/dev/null | LC_ALL=C sort | head -n 1
    return
  fi

  # Non-split: should be app-release.apk (pick first deterministic match).
  # shellcheck disable=SC2012
  ls -1 build/app/outputs/flutter-apk/*release*.apk 2>/dev/null | LC_ALL=C sort | head -n 1
}

build_once() {
  local tag="$1"
  local out_dir="$2"
  local split="$3"
  local abi="$4"
  local target_platform="$5"

  step "Build ${tag} (clean)"

  rm -rf build .dart_tool
  # Keep all tool output on stderr so stdout can be reserved for machine-readable output if needed.
  run_checked fvm flutter clean
  run_checked fvm flutter pub get --enforce-lockfile

  if [ "${split}" = "1" ]; then
    echo "Building with --split-per-abi (ABI to compare: ${abi})" >&2
    if [ -n "${target_platform}" ]; then
      run_checked fvm flutter build apk --release --split-per-abi --target-platform "${target_platform}"
    else
      run_checked fvm flutter build apk --release --split-per-abi
    fi
  else
    echo "Building single APK (default)" >&2
    if [ -n "${target_platform}" ]; then
      run_checked fvm flutter build apk --release --target-platform "${target_platform}"
    else
      run_checked fvm flutter build apk --release
    fi
  fi

  local apk_path
  apk_path="$(pick_apk "${split}" "${abi}")"
  [ -n "${apk_path}" ] || die "Could not find built APK under build/app/outputs/flutter-apk/"
  [ -f "${apk_path}" ] || die "APK path does not exist: ${apk_path}"

  if apk_is_signed "${apk_path}"; then
    die "APK appears to be signed. For reproducibility proof, remove android/key.properties and rebuild. (${apk_path})"
  fi

  local out_apk
  if [ "${split}" = "1" ]; then
    out_apk="${out_dir}/app-${SOURCE_DATE_EPOCH}-${tag}-${abi}-release.apk"
  else
    out_apk="${out_dir}/app-${SOURCE_DATE_EPOCH}-${tag}-release.apk"
  fi

  mkdir -p "${out_dir}"
  cp "${apk_path}" "${out_apk}"
  local sha
  sha="$(shasum -a 256 "${out_apk}" | awk '{print $1}')"
  printf "%s  %s\n" "${sha}" "${out_apk}" | tee "${out_apk}.sha256" >&2

  echo "Built: ${apk_path}" >&2
  echo "Wrote: ${out_apk}" >&2

  # Store result in a global variable (avoid command substitution subshells).
  BUILD_RESULT_APK="${out_apk}"
}

main() {
  cd "${ROOT_DIR}"

  step "Preflight checks"
  need_cmd git
  need_cmd java
  need_cmd fvm
  need_cmd shasum
  need_cmd unzip

  echo "Repo: ${ROOT_DIR}" >&2
  echo "Out:  ${REPRO_OUT_DIR}" >&2
  echo "split-per-abi: ${REPRO_SPLIT_PER_ABI}" >&2
  if [ "${REPRO_SPLIT_PER_ABI}" = "1" ]; then
    echo "ABI: ${REPRO_ABI}" >&2
  fi
  if [ -n "${REPRO_TARGET_PLATFORM}" ]; then
    echo "target-platform: ${REPRO_TARGET_PLATFORM}" >&2
  fi
  echo "clean-sdk: ${REPRO_CLEAN_SDK}" >&2

  echo >&2
  java -version >&2

  echo >&2
  echo "Git:" >&2
  git rev-parse HEAD >&2

  step "Deterministic timestamp (SOURCE_DATE_EPOCH)"
  export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git log -1 --pretty=%ct)}"
  echo "SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH}" >&2

  step "Ensure release is unsigned"
  if [ -f "android/key.properties" ]; then
    die "android/key.properties exists; release may be signed. Remove it for reproducible/FDroid proof."
  fi

  # Ensure output dir exists early so optional cleanup can store artifacts if needed.
  mkdir -p "${REPRO_OUT_DIR}"

  step "Ensure pinned Flutter SDK is available"
  run_checked fvm install

  if [ "${REPRO_CLEAN_SDK}" = "1" ]; then
    step "Clean Flutter SDK caches (optional, slow)"
    # Clearing bin/cache forces Flutter to re-bootstrap its tool snapshots.
    if [ -d ".fvm/flutter_sdk/bin/cache" ]; then
      echo "Removing: ${ROOT_DIR}/.fvm/flutter_sdk/bin/cache" >&2
      rm -rf ".fvm/flutter_sdk/bin/cache"
    else
      echo "Note: .fvm/flutter_sdk/bin/cache not found (skipping direct cache removal)." >&2
    fi
    # Re-download/prepare required artifacts.
    run_checked fvm flutter precache --force
  fi

  run_checked fvm flutter --version

  step "Clean output directory"
  rm -rf "${REPRO_OUT_DIR:?}/"*

  local apkA apkB
  BUILD_RESULT_APK=""
  build_once "A" "${REPRO_OUT_DIR}" "${REPRO_SPLIT_PER_ABI}" "${REPRO_ABI}" "${REPRO_TARGET_PLATFORM}"
  apkA="${BUILD_RESULT_APK}"
  [ -n "${apkA}" ] || die "Internal error: build A did not set BUILD_RESULT_APK"

  BUILD_RESULT_APK=""
  build_once "B" "${REPRO_OUT_DIR}" "${REPRO_SPLIT_PER_ABI}" "${REPRO_ABI}" "${REPRO_TARGET_PLATFORM}"
  apkB="${BUILD_RESULT_APK}"
  [ -n "${apkB}" ] || die "Internal error: build B did not set BUILD_RESULT_APK"

  step "Compare hashes"
  local shaA shaB
  shaA="$(shasum -a 256 "${apkA}" | awk '{print $1}')"
  shaB="$(shasum -a 256 "${apkB}" | awk '{print $1}')"

  echo "A: ${apkA}  ${shaA}" >&2
  echo "B: ${apkB}  ${shaB}" >&2

  if [ "${shaA}" = "${shaB}" ]; then
    echo "OK: reproducible (hashes match)" >&2
    exit 0
  fi

  echo "ERROR: not reproducible (hashes differ)" >&2

  step "Quick internal diff (unzip + diff -qr)"
  local dirA dirB
  dirA="$(mktemp -d "/tmp/zapstore-apkA.XXXXXX")"
  dirB="$(mktemp -d "/tmp/zapstore-apkB.XXXXXX")"
  unzip -oq "${apkA}" -d "${dirA}"
  unzip -oq "${apkB}" -d "${dirB}"

  echo "Extracted:" >&2
  echo "  A -> ${dirA}" >&2
  echo "  B -> ${dirB}" >&2
  echo >&2
  diff -qr "${dirA}" "${dirB}" | head -n 200 || true

  step "diffoscope report (if available)"
  if command -v diffoscope >/dev/null 2>&1; then
    local report="${REPRO_OUT_DIR}/diffoscope.txt"
    echo "Writing: ${report}" >&2
    # diffoscope can be huge; keep it as a file.
    diffoscope "${apkA}" "${apkB}" > "${report}" || true
    echo "Tip: open ${report} and search for the first differing file." >&2
  else
    echo "diffoscope not found. Install with: brew install diffoscope" >&2
  fi

  if [ "${REPRO_KEEP_WORK}" != "1" ]; then
    rm -rf "${dirA}" "${dirB}"
  else
    echo "Keeping extracted dirs (REPRO_KEEP_WORK=1):" >&2
    echo "  ${dirA}" >&2
    echo "  ${dirB}" >&2
  fi

  exit 21
}

main "$@"
