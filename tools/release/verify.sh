#!/usr/bin/env bash
# Rebuild a release from a git tag or commit and compare it with a published APK.
#
#   tools/release/verify.sh [--local] [--variant bundle|update] <git-ref> <apk-file | https-url>
#
# Run it from a clone of the repository. The ref is checked out into a fresh clone under build/verify and
# built with tools/release/build-apk.sh, the script the release is built with, inside the container
# defined by dockerfile() below: Temurin JDK 21.0.9, Android platform 37.0, build-tools 36.0.0, Gradle
# checked by sha256, and every dependency checked against gradle/verification-metadata.xml.
#
# The variant is read from the APK: one that carries assets/bundle-0-1.tar.zst is the bundle APK, rebuilt from
# the catalog bundle pinned in gradle.properties at that ref and fetched from the URL it names; any other
# is the update APK.
#
# The check is exact. hotbox signs without changing the APK: the signed file is the unsigned one plus some
# zero padding and the APK signing block. So this puts the published file's padding and signing block into the
# rebuilt APK and requires the same sha256 as the published file. An unsigned APK must equal the rebuild.
#
# Exit 0 means the rebuild matches. Any other exit means it did not verify; the output says why.
#
#   --local   build on this machine with its own JDK and Android SDK instead of the container. It is
#             faster and needs no Docker, but it only shows that this machine's toolchain agrees.
#
# Environment:
#   DOCKER    container CLI (default docker)
#   PLATFORM  container platform: linux/amd64 (default, the reference) or linux/arm64. AGP's aapt2 for Linux
#             is an x86-64 binary. Under amd64 everything is emulated on an Apple Silicon Mac, which is slow.
#             Under arm64 only aapt2 is: the image gets the x86-64 C libraries and the Docker VM runs the
#             binary through its x86-64 emulation (Docker Desktop and Colima both register it). The JDK is
#             a different build then, so a match here is evidence, and the amd64 run is the reference.
#   WORK_DIR  where the clone and the rebuilt APK go (default build/verify, which must be under your
#             home directory for Docker Desktop, Colima and Lima to mount it)
#   KEEP=1    keep the work directory
#
# The container keeps its Gradle dependency cache in the Docker volume zapstore-release-gradle, so only the
# first run downloads dependencies. Every dependency is still checked against gradle/verification-metadata.xml
# when it is first fetched. `docker volume rm zapstore-release-gradle` starts from nothing.
set -euo pipefail

die() { echo "verify.sh: $*" >&2; exit 1; }
sha256_stdin() { if command -v sha256sum >/dev/null 2>&1; then sha256sum | cut -d' ' -f1; else shasum -a 256 | cut -d' ' -f1; fi; }
apk_has() { local entries; entries=$(unzip -Z1 "$1") && grep -qxF "$2" <<<"$entries"; }

# The build environment. Every input is pinned by version and hash. The APK's deflate bytes depend on the
# JDK's zlib, so do not move this to another JDK build without re-verifying a release.
dockerfile() {
	cat <<'DOCKERFILE'
FROM eclipse-temurin:21.0.9_10-jdk-jammy@sha256:41c8a28993a03b5dd042ce1afc7973c9a98bfbbbd6336eb1e32e673b1718d99c

ARG CMDLINE_TOOLS_ZIP=commandlinetools-linux-16111833_latest.zip
ARG CMDLINE_TOOLS_SHA256=0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8
# Keep in step with build.gradle.kts: compileSdk 37, and the build-tools AGP picks.
ARG ANDROID_PLATFORM=platforms;android-37.0
ARG ANDROID_BUILD_TOOLS=build-tools;36.0.0

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/opt/gradle-home \
    LC_ALL=C.UTF-8

# git: AGP records the checked-out commit in the APK. unzip: build-apk.sh checks the result.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git unzip ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

# Two tools are x86-64 binaries: Google's sdkmanager (it hands over to the native Android CLI) and AGP's
# aapt2. On arm64 they need the x86-64 C libraries beside the arm64 ones, and this must come before the SDK
# install below. Nothing else in the image or in a release build is x86-only.
RUN if [ "$(dpkg --print-architecture)" = arm64 ]; then \
        dpkg --add-architecture amd64 \
        && sed -i 's/^deb http/deb [arch=arm64] http/' /etc/apt/sources.list \
        && printf '%s\n' \
            'deb [arch=amd64] http://archive.ubuntu.com/ubuntu jammy main' \
            'deb [arch=amd64] http://archive.ubuntu.com/ubuntu jammy-updates main' \
            'deb [arch=amd64] http://security.ubuntu.com/ubuntu jammy-security main' \
            > /etc/apt/sources.list.d/amd64.list \
        && apt-get update \
        && apt-get install -y --no-install-recommends libc6:amd64 libstdc++6:amd64 zlib1g:amd64 \
        && rm -rf /var/lib/apt/lists/*; \
    fi

RUN curl -fsSL -o /tmp/cmdline-tools.zip "https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}" \
    && echo "${CMDLINE_TOOLS_SHA256}  /tmp/cmdline-tools.zip" | sha256sum -c - \
    && mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip

RUN yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null \
    && "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" "${ANDROID_PLATFORM}" "${ANDROID_BUILD_TOOLS}" \
    && "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --list_installed

WORKDIR /src
DOCKERFILE
}

local_build=
variant=
args=()
while [ $# -gt 0 ]; do
	case "$1" in
	--local) local_build=1 ;;
	--variant) variant=${2:?--variant needs bundle or update}; shift ;;
	-h | --help)
		sed -n '2,/^set -/p' "$0" | sed -n 's/^# \{0,1\}//p'
		exit 0
		;;
	-*) die "unknown option $1" ;;
	*) args+=("$1") ;;
	esac
	shift
done
[ ${#args[@]} -eq 2 ] || die "usage: $0 [--local] [--variant bundle|update] <git-ref> <apk-file | https-url>"
ref=${args[0]}
apk=${args[1]}

root=$(cd "$(dirname "$0")/../.." && pwd)
DOCKER=${DOCKER:-docker}
PLATFORM=${PLATFORM:-linux/amd64}
for tool in git python3 unzip; do command -v "$tool" >/dev/null || die "$tool not found in PATH"; done
if [ -z "$local_build" ]; then
	command -v "$DOCKER" >/dev/null || die "$DOCKER not found in PATH"
	"$DOCKER" info >/dev/null 2>&1 ||
		die "cannot reach the container daemon; start Docker Desktop, or Colima/Lima, or pass --local"
fi

commit=$(git -C "$root" rev-parse --verify "$ref^{commit}" 2>/dev/null) ||
	die "no such commit or tag: $ref (git fetch --tags?)"

work=${WORK_DIR:-$root/build/verify}/${commit:0:12}
cleanup() {
	if [ -n "${KEEP:-}" ]; then echo "kept $work"; else rm -rf "$work"; fi
}
trap cleanup EXIT
rm -rf "$work"
mkdir -p "$work/out"

case "$apk" in
https://*)
	command -v curl >/dev/null || die "curl not found in PATH"
	curl -fsSL --retry 3 -o "$work/published.apk" "$apk"
	apk=$work/published.apk
	;;
*) [ -f "$apk" ] || die "no such file: $apk" ;;
esac

if [ -z "$variant" ]; then
	if apk_has "$apk" assets/bundle-0-1.tar.zst; then variant=bundle; else variant=update; fi
fi
case "$variant" in bundle | update) ;; *) die "variant must be bundle or update" ;; esac

# A real checkout, not git archive: AGP records the commit from .git.
git clone -q --no-checkout "$root" "$work/src"
git -C "$work/src" checkout -q --detach "$commit"
[ "$variant" = update ] || grep -q '^catalogPinSha256=.' "$work/src/gradle.properties" ||
	die "$ref pins no catalog bundle in gradle.properties, so its bundle APK cannot be rebuilt"

echo "commit   $commit"
echo "variant  $variant"
rebuilt=$work/out/zapstore-release-unsigned.apk
# Lint only reads the code, so skipping it cannot change the APK, and under emulation it is the slowest step.
gradle_flags="--no-daemon -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease"

if [ -n "$local_build" ]; then
	echo "builder  this machine (--local)"
	# The clone has no local.properties (sdk.dir). Build with the same Android SDK as this checkout does.
	if [ -f "$root/local.properties" ]; then
		cp "$root/local.properties" "$work/src/local.properties"
	fi
	(cd "$work/src" && GRADLE_FLAGS="$gradle_flags" tools/release/build-apk.sh "$variant" "$rebuilt" >/dev/null)
else
	image="zapstore-release:$(dockerfile | sha256_stdin | cut -c1-12)-${PLATFORM#linux/}"
	echo "builder  $image ($PLATFORM)"
	if ! "$DOCKER" image inspect "$image" >/dev/null 2>&1; then
		echo "building the image; the first run downloads the Android SDK"
		dockerfile | "$DOCKER" build --platform "$PLATFORM" -t "$image" - >"$work/image.log" 2>&1 ||
			{ tail -n 30 "$work/image.log" >&2; die "image build failed"; }
	fi
	"$DOCKER" run --rm --platform "$PLATFORM" \
		-v "$work/src:/src" -v "$work/out:/out" -v zapstore-release-gradle:/opt/gradle-home \
		-e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
		-e CATALOG_BUNDLE_URL="${CATALOG_BUNDLE_URL:-}" -e VARIANT="$variant" -e GRADLE_FLAGS="$gradle_flags" \
		"$image" bash -ec '
			# The container is root. Hand the files back so the host can delete them.
			trap "chown -R $HOST_UID:$HOST_GID /src /out" EXIT
			git config --global --add safe.directory /src
			tools/release/build-apk.sh "$VARIANT" /out/zapstore-release-unsigned.apk
		' >"$work/build.log" 2>&1 || {
		tail -n 40 "$work/build.log" >&2
		if grep -qi "aapt2" "$work/build.log" && [ "$PLATFORM" = linux/arm64 ]; then
			echo "aapt2 could not run: the Docker VM needs x86-64 emulation for linux/arm64 (Docker Desktop: Rosetta or QEMU; Colima: qemu binfmt)" >&2
		fi
		die "build failed (full log: $work/build.log; KEEP=1 keeps it)"
	}
fi

echo "rebuilt  sha256 $(sha256_stdin <"$rebuilt")"
if command -v apksigner >/dev/null 2>&1; then
	# Informational only: it must not stop the comparison, and it fails on an unsigned APK.
	{ apksigner verify --print-certs "$apk" 2>/dev/null || true; } |
		sed -n 's/^Signer #1 certificate SHA-256 digest: /signer   sha256 /p'
fi

# The published file must be the rebuild plus a signature. hotbox signs with apksigner's --alignment-preserved and
# without a v1 signature, so apksigner changes nothing in the zip except to add zero padding and the APK signing
# block between the last entry and the central directory, and to move the central directory's offset. Put those
# back into the rebuild and the bytes, and so the sha256, must be the published file's. An unsigned file (a dev
# build) must simply equal the rebuild.
python3 - "$apk" "$rebuilt" <<'PY'
import hashlib
import io
import struct
import sys
import zipfile

MAGIC = b"APK Sig Block 42"


def eocd(b):
    i = b.rfind(b"PK\x05\x06")
    if i < 0:
        sys.exit("not a zip file")
    return i


def cd_offset(b):
    return struct.unpack_from("<I", b, eocd(b) + 16)[0]


def first_difference(a, b):
    n = min(len(a), len(b))
    i = next((k for k in range(n) if a[k] != b[k]), n)
    with zipfile.ZipFile(io.BytesIO(b)) as z:
        entries = sorted(z.infolist(), key=lambda e: e.header_offset)
    name = next((e.filename for e in reversed(entries) if e.header_offset <= i), "the start of the file")
    return f"first at byte {i}, in or after {name}"


def digest(b):
    return hashlib.sha256(b).hexdigest()


published = open(sys.argv[1], "rb").read()
rebuilt = open(sys.argv[2], "rb").read()
cp, cr = cd_offset(published), cd_offset(rebuilt)

if published[cp - 16:cp] != MAGIC:
    if published != rebuilt:
        sys.exit("DIFFERENT: the file is unsigned and differs from the rebuild, " + first_difference(published, rebuilt))
    print(f"SAME: unsigned, sha256 {digest(rebuilt)}")
    sys.exit(0)

if cp < cr or published[:cr] != rebuilt[:cr]:
    sys.exit("DIFFERENT: the zip entries differ, " + first_difference(published, rebuilt))
if published[cp:eocd(published)] != rebuilt[cr:eocd(rebuilt)]:
    sys.exit("DIFFERENT: the central directory differs (a v1 signature adds entries to it)")
added = published[cr:cp]
block_size = struct.unpack_from("<Q", added, len(added) - 24)[0] + 8
padding = added[: len(added) - block_size]
if block_size > len(added) or any(padding):
    sys.exit("DIFFERENT: unexpected bytes between the last entry and the signing block")
out = bytearray(rebuilt[:cr] + added + rebuilt[cr:])
struct.pack_into("<I", out, eocd(bytes(out)) + 16, cp)
if bytes(out) != published:
    sys.exit("DIFFERENT: the end of the file differs, " + first_difference(published, bytes(out)))
print(f"SAME: sha256 {digest(published)} is the rebuild plus {len(padding)} bytes of padding and a {block_size} byte signing block")
PY
