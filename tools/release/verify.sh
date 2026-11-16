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
# the catalog bundle in catalog-bundle.lock at that ref and fetched from the URL the lock names; any other
# is the update APK. The comparison reads every file's contents and ignores only the signature.
#
# Exit 0 means the rebuild matches. Any other exit means it did not verify; the output says why.
#
#   --local   build on this machine with its own JDK and Android SDK instead of the container. It is
#             faster and needs no Docker, but it only shows that this machine's toolchain agrees.
#
# Environment:
#   DOCKER    container CLI (default docker)
#   PLATFORM  container platform (default linux/amd64: AGP's aapt2 for Linux is x86-64)
#   WORK_DIR  where the clone and the rebuilt APK go (default build/verify, which must be under your
#             home directory for Docker Desktop, Colima and Lima to mount it)
#   KEEP=1    keep the work directory
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
[ "$variant" = update ] || [ -f "$work/src/catalog-bundle.lock" ] ||
	die "$ref has no catalog-bundle.lock, so its bundle APK cannot be rebuilt"

echo "commit   $commit"
echo "variant  $variant"
rebuilt=$work/out/zapstore-release-unsigned.apk

if [ -n "$local_build" ]; then
	echo "builder  this machine (--local)"
	# The clone has no local.properties (sdk.dir). Build with the same Android SDK as this checkout does.
	if [ -f "$root/local.properties" ]; then
		cp "$root/local.properties" "$work/src/local.properties"
	fi
	(cd "$work/src" && GRADLE_FLAGS=--no-daemon tools/release/build-apk.sh "$variant" "$rebuilt" >/dev/null)
else
	image="zapstore-release:$(dockerfile | sha256_stdin | cut -c1-12)"
	echo "builder  $image ($PLATFORM)"
	if ! "$DOCKER" image inspect "$image" >/dev/null 2>&1; then
		echo "building the image; the first run downloads the Android SDK"
		dockerfile | "$DOCKER" build --platform "$PLATFORM" -t "$image" - >"$work/image.log" 2>&1 ||
			{ tail -n 30 "$work/image.log" >&2; die "image build failed"; }
	fi
	"$DOCKER" run --rm --platform "$PLATFORM" \
		-v "$work/src:/src" -v "$work/out:/out" \
		-e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
		-e CATALOG_BUNDLE_URL="${CATALOG_BUNDLE_URL:-}" -e VARIANT="$variant" \
		"$image" bash -ec '
			# The container is root. Hand the files back so the host can delete them.
			trap "chown -R $HOST_UID:$HOST_GID /src /out" EXIT
			git config --global --add safe.directory /src
			GRADLE_FLAGS=--no-daemon tools/release/build-apk.sh "$VARIANT" /out/zapstore-release-unsigned.apk
		' >"$work/build.log" 2>&1 || { tail -n 40 "$work/build.log" >&2; die "build failed (full log: $work/build.log; KEEP=1 keeps it)"; }
fi

echo "rebuilt  sha256 $(sha256_stdin <"$rebuilt")"
if command -v apksigner >/dev/null 2>&1; then
	# Informational only: it must not stop the comparison, and it fails on an unsigned APK.
	{ apksigner verify --print-certs "$apk" 2>/dev/null || true; } |
		sed -n 's/^Signer #1 certificate SHA-256 digest: /signer   sha256 /p'
fi

# Same files with the same decompressed bytes. Ignored: the APK Signing Block (v2/v3, outside the zip
# entries) and the v1 signature files META-INF/MANIFEST.MF and META-INF/*.SF, *.RSA, *.DSA, *.EC. Zip
# metadata (timestamps, alignment padding, compression level) is not compared.
python3 - "$apk" "$rebuilt" <<'PY'
import hashlib
import re
import sys
import zipfile

V1_SIGNATURE = re.compile(r"^META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))$")


def digests(path):
    entries = {}
    with zipfile.ZipFile(path) as z:
        bad = z.testzip()
        if bad is not None:
            sys.exit(f"{path}: corrupt entry {bad}")
        for info in z.infolist():
            if info.is_dir() or V1_SIGNATURE.match(info.filename):
                continue
            if info.filename in entries:
                sys.exit(f"{path}: duplicate entry {info.filename}")
            h = hashlib.sha256()
            with z.open(info) as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
            entries[info.filename] = (info.file_size, h.hexdigest())
    return entries


a, b = digests(sys.argv[1]), digests(sys.argv[2])
only_published = sorted(a.keys() - b.keys())
only_rebuilt = sorted(b.keys() - a.keys())
changed = sorted(n for n in a.keys() & b.keys() if a[n] != b[n])
for name in only_published:
    print(f"only in published: {name}")
for name in only_rebuilt:
    print(f"only in rebuilt:   {name}")
for name in changed:
    print(f"differs:           {name}  published {a[name][1][:16]}  rebuilt {b[name][1][:16]}")
if only_published or only_rebuilt or changed:
    sys.exit(f"DIFFERENT: {len(only_published)} only in published, {len(only_rebuilt)} only in rebuilt, {len(changed)} differ")
print(f"SAME: {len(a)} files match")
PY
