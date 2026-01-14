#!/usr/bin/env bash
set -euo pipefail

TAG="${BUILD_TAG:-X}"
REPO_SRC="/repo"
WORK_ROOT="/work"
WORK_DIR="${WORK_ROOT}/src"
OUT_DIR="/out"

# Make the container fully self-contained and writable even under stricter FS setups.
export HOME="/tmp/home"
mkdir -p "${HOME}"

mkdir -p "${WORK_ROOT}" "${OUT_DIR}"

# Ensure cache dirs exist (paths come from the runner via env vars).
if [ -n "${GRADLE_USER_HOME:-}" ]; then
  mkdir -p "${GRADLE_USER_HOME}"
fi
if [ -n "${PUB_CACHE:-}" ]; then
  mkdir -p "${PUB_CACHE}"
fi

echo "== Environment =="
echo "TAG=${TAG}"
echo "GRADLE_USER_HOME=${GRADLE_USER_HOME:-}"
echo "PUB_CACHE=${PUB_CACHE:-}"

echo "== Tool versions =="
java -version
git --version
flutter --version

# Hygiene: disable Flutter analytics/telemetry in the container runtime.
flutter --disable-analytics >/dev/null 2>&1 || true

echo "== Preparing working copy =="
rm -rf "${WORK_DIR}"
mkdir -p "${WORK_DIR}"

# Copy the repo into the container filesystem before building.
# This avoids macOS bind-mount filesystem quirks that can trigger Dart/Gradle issues (e.g. EINTR).
(cd "${REPO_SRC}" && tar --exclude='./build' --exclude='./.dart_tool' -cf - .) | (cd "${WORK_DIR}" && tar -xf -)

cd "${WORK_DIR}"

echo "== Git =="
git rev-parse HEAD

echo "== Deterministic timestamp =="
export SOURCE_DATE_EPOCH="$(git log -1 --pretty=%ct)"
echo "SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH}"

echo "== Ensure unsigned release =="
if [ -f "android/key.properties" ]; then
  echo "ERROR: android/key.properties exists; release may be signed. Remove it for reproducible/FDroid proof."
  exit 10
fi

echo "== Flutter build =="
flutter clean
flutter pub get --enforce-lockfile

# Gradle file watching can break inside containers/bind mounts.
# Disable it to avoid: "Couldn't poll for events, error = 4".
if [ -n "${GRADLE_USER_HOME:-}" ]; then
  mkdir -p "${GRADLE_USER_HOME}"
  {
    echo "org.gradle.vfs.watch=false"
    # Avoid Gradle build cache influencing outputs across runs.
    echo "org.gradle.caching=false"
    # Avoid parallelism affecting packaging order.
    echo "org.gradle.parallel=false"
    # CI/container stability: avoid flaky Kotlin compile daemon startup.
    # This removes intermittent "daemon has terminated unexpectedly on startup attempt #N".
    echo "kotlin.compiler.execution.strategy=in-process"
    echo "kotlin.daemon.enabled=false"
  } > "${GRADLE_USER_HOME}/gradle.properties"
fi

if [ "${REPRO_SPLIT_PER_ABI:-0}" = "1" ]; then
  echo "Building with --split-per-abi (hard mode)"
  flutter build apk --release --split-per-abi
  APK_PATH="$(ls -1 build/app/outputs/flutter-apk/*arm64-v8a*release*.apk | head -n 1)"
  OUT_APK="${OUT_DIR}/app-${SOURCE_DATE_EPOCH}-${TAG}-arm64-v8a-release.apk"
else
  echo "Building single APK (default)"
  flutter build apk --release
  APK_PATH="$(ls -1 build/app/outputs/flutter-apk/*release*.apk | head -n 1)"
  OUT_APK="${OUT_DIR}/app-${SOURCE_DATE_EPOCH}-${TAG}-release.apk"
fi

echo "Built APK: ${APK_PATH}"

echo "== Validate unsigned APK =="
if unzip -l "${APK_PATH}" | grep -Eq 'META-INF/.*\.(RSA|DSA|EC)$'; then
  echo "ERROR: APK appears to be signed (META-INF/*.RSA|*.DSA|*.EC found)."
  exit 11
fi

cp "${APK_PATH}" "${OUT_APK}"
sha256sum "${OUT_APK}" | tee "${OUT_APK}.sha256"

echo "Wrote: ${OUT_APK}"
