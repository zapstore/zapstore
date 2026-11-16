#!/usr/bin/env bash
# Build one unsigned release APK. This is the whole recipe: infra/misc/cut-mobile-release.sh signs what it builds
# and tools/release/verify.sh rebuilds it, so a verifier gets the same bytes.
#
#   tools/release/build-apk.sh bundle|update [OUT_FILE]
#
#   bundle     ships the catalog bundle pinned by catalog-bundle.lock, so a new install opens with the catalog
#   update  ships no bundle; the app downloads the catalog on first open
#
# Environment:
#   GRADLE             gradle launcher (default ./gradlew)
#   GRADLE_FLAGS       extra gradle flags, such as --no-daemon
#   CATALOG_RELAY      default wss://relay.zapstore.dev
#   CATALOG_PUBKEY     empty means the default for that relay
#   CATALOG_BUNDLE_URL fetch the pinned bundle from here (https or file:) instead of the lock's URL; its
#                      sha256 must still match. Default: build/release/<name>, where cut-mobile-release.sh saved it
#   ALLOW_DIRTY=1      build with uncommitted changes
set -euo pipefail

die() { echo "build-apk.sh: $*" >&2; exit 1; }
sha256_stdin() { if command -v sha256sum >/dev/null 2>&1; then sha256sum | cut -d' ' -f1; else shasum -a 256 | cut -d' ' -f1; fi; }
lock_get() { sed -n "s/^$1=//p" catalog-bundle.lock 2>/dev/null | head -n 1; }
# The listing is captured first: with pipefail, grep -q closing the pipe early would fail the pipeline.
apk_has() { local entries; entries=$(unzip -Z1 "$1") && grep -qxF "$2" <<<"$entries"; }

variant=${1:-}
out_file=${2:-}
case "$variant" in
bundle) mode=pinned ;;
update) mode=none ;;
*) echo "usage: $0 bundle|update [OUT_FILE]" >&2; exit 2 ;;
esac

cd "$(dirname "$0")/../.."
command -v unzip >/dev/null || die "unzip not found in PATH"

# The APK records HEAD (AGP writes it to META-INF/version-control-info.textproto), so a dirty tree would
# name a commit the APK does not contain.
if [ -d .git ] && [ -z "${ALLOW_DIRTY:-}" ] && [ -n "$(git status --porcelain --untracked-files=no)" ]; then
	die "uncommitted changes: the APK would record HEAD but not contain it (ALLOW_DIRTY=1 to build anyway)"
fi

GRADLE=${GRADLE:-./gradlew}
unsigned=build/outputs/apk/release/zapstore-release-unsigned.apk
asset=bundle-0-1.tar.zst

args=(-PCATALOG_RELAY="${CATALOG_RELAY:-wss://relay.zapstore.dev}" -PcatalogBundle="$mode")
[ -z "${CATALOG_PUBKEY:-}" ] || args+=(-PCATALOG_PUBKEY="$CATALOG_PUBKEY")
[ -z "${CATALOG_BUNDLE_URL:-}" ] || args+=(-PcatalogBundleUrl="$CATALOG_BUNDLE_URL")
if [ "$mode" = pinned ]; then
	[ -n "$(lock_get sha256)" ] || die "no catalog-bundle.lock; cut-mobile-release.sh writes it"
	# Before the release is published the pinned URL is not live; cut-mobile-release.sh saved the file.
	saved=build/release/$(basename "$(lock_get url)")
	if [ -z "${CATALOG_BUNDLE_URL:-}" ] && [ -f "$saved" ]; then
		args+=(-PcatalogBundleUrl="file://$PWD/$saved")
	fi
fi

rm -f "$unsigned"
# shellcheck disable=SC2086 # GRADLE_FLAGS is a deliberate word list
"$GRADLE" ${GRADLE_FLAGS:-} --console=plain assembleRelease "${args[@]}"
[ -f "$unsigned" ] || die "no $unsigned after the build"

if [ "$mode" = pinned ]; then
	apk_has "$unsigned" "assets/$asset" || die "bundle APK has no catalog bundle"
	got=$(unzip -p "$unsigned" "assets/$asset" | sha256_stdin)
	[ "$got" = "$(lock_get sha256)" ] || die "bundle APK carries catalog $got, the lock pins $(lock_get sha256)"
else
	! apk_has "$unsigned" "assets/$asset" || die "update APK carries a catalog bundle"
fi

if [ -n "$out_file" ]; then
	cp -f "$unsigned" "$out_file"
	echo "$out_file"
else
	echo "$PWD/$unsigned"
fi
