#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${ROOT_DIR}/.repro_out"
IMAGE_NAME="zapstore-repro"
NAME_PREFIX="zapstore_repro"

DOCKER_PLATFORM="${REPRO_DOCKER_PLATFORM:-linux/amd64}"
if [ "${DOCKER_PLATFORM}" != "linux/amd64" ]; then
  echo "ERROR: This repro image currently requires linux/amd64 (Flutter Linux ships as x64)."
  echo "Set REPRO_DOCKER_PLATFORM=linux/amd64 or run on an x86_64 host."
  exit 2
fi

HOST_ARCH="$(uname -m)"
if [ "${HOST_ARCH}" = "arm64" ] || [ "${HOST_ARCH}" = "aarch64" ]; then
  echo "NOTE: Host is ${HOST_ARCH}. linux/amd64 will run under emulation and may be unstable."
  echo "If you hit Dart VM 'Unexpected EINTR' crashes, run this proof on an x86_64 Linux machine/CI."
fi

mkdir -p "${OUT_DIR}"
rm -rf "${OUT_DIR:?}/"*
chmod 777 "${OUT_DIR}" || true

FLUTTER_VERSION="$(python3 - <<'PY'
import json
with open('.fvmrc','r',encoding='utf-8') as f:
    print(json.load(f)['flutter'])
PY
)"

FLUTTER_SHA256="$(python3 - "${FLUTTER_VERSION}" <<'PY'
import json, urllib.request, sys
ver = sys.argv[1]
url = 'https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json'
with urllib.request.urlopen(url) as r:
    data = json.load(r)
for rel in data.get('releases', []):
    if rel.get('channel') == 'stable' and rel.get('version') == ver:
        print(rel.get('sha256'))
        raise SystemExit(0)
raise SystemExit('sha256 not found for '+ver)
PY
)"

CMDLINE_TOOLS_SHA256="2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258"

echo "== Inputs =="
echo "Repo: ${ROOT_DIR}"
echo "Out:  ${OUT_DIR}"
echo "Docker platform: ${DOCKER_PLATFORM}"
echo "Flutter: ${FLUTTER_VERSION}"
echo "Flutter sha256: ${FLUTTER_SHA256}"
echo "cmdline-tools sha256: ${CMDLINE_TOOLS_SHA256}"
echo "split-per-abi: ${REPRO_SPLIT_PER_ABI:-0}"

echo "== Building Docker image =="
docker build --platform "${DOCKER_PLATFORM}" \
  -t "${IMAGE_NAME}" \
  --build-arg "FLUTTER_VERSION=${FLUTTER_VERSION}" \
  --build-arg "FLUTTER_SHA256=${FLUTTER_SHA256}" \
  --build-arg "CMDLINE_TOOLS_SHA256=${CMDLINE_TOOLS_SHA256}" \
  -f "${ROOT_DIR}/repro/Dockerfile" \
  "${ROOT_DIR}/repro"

cleanup() {
  docker rm -f "${NAME_PREFIX}_A" "${NAME_PREFIX}_B" >/dev/null 2>&1 || true
  # Remove dangling images left behind by failed builds/rebuilds.
  docker image prune -f --filter dangling=true >/dev/null 2>&1 || true

  # Optional: explicitly remove the built image.
  # (Disabled by default so a known-good image can be reused.)
  if [ "${REPRO_REMOVE_IMAGE:-0}" = "1" ]; then
    docker image rm -f "${IMAGE_NAME}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

run_one () {
  local tag="$1"
  echo "== Running container ${tag} =="
  docker rm -f "${NAME_PREFIX}_${tag}" >/dev/null 2>&1 || true
  docker run --rm --platform "${DOCKER_PLATFORM}" \
    --name "${NAME_PREFIX}_${tag}" \
    -e "BUILD_TAG=${tag}" \
    -e "GRADLE_USER_HOME=/tmp/gradle" \
    -e "GRADLE_OPTS=-Dorg.gradle.vfs.watch=false" \
    -e "PUB_CACHE=/tmp/pub-cache" \
    -e "REPRO_SPLIT_PER_ABI=${REPRO_SPLIT_PER_ABI:-0}" \
    -v "${ROOT_DIR}:/repo:ro" \
    -v "${OUT_DIR}:/out:rw" \
    "${IMAGE_NAME}" \
    bash /repo/repro/build_in_container.sh
}

run_one "A"
run_one "B"

echo "== Comparing hashes =="
APKS=($(ls -1 "${OUT_DIR}"/*.apk 2>/dev/null || true))
if [ "${#APKS[@]}" -ne 2 ]; then
  echo "ERROR: expected 2 APKs in ${OUT_DIR}, found ${#APKS[@]}"
  ls -lah "${OUT_DIR}" || true
  exit 20
fi

SHA_A="$(sha256sum "${APKS[0]}" | awk '{print $1}')"
SHA_B="$(sha256sum "${APKS[1]}" | awk '{print $1}')"

echo "A: ${APKS[0]}  ${SHA_A}"
echo "B: ${APKS[1]}  ${SHA_B}"

if [ "${SHA_A}" = "${SHA_B}" ]; then
  echo "OK: reproducible (hashes match)"
  exit 0
fi

echo "ERROR: not reproducible (hashes differ)"
echo
echo "Next steps:"
echo "  diffoscope \"${APKS[0]}\" \"${APKS[1]}\""
echo "  unzip -q \"${APKS[0]}\" -d /tmp/apkA && unzip -q \"${APKS[1]}\" -d /tmp/apkB && diff -qr /tmp/apkA /tmp/apkB | head -n 80"
exit 21

