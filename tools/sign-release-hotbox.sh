#!/usr/bin/env bash
# Build two release APKs and sign both with hotbox.
# The bundled APK is for a new install. The other is the Zapstore update.
# Same version code, so neither is an update of the other.
# Does not run zsp publish or touch the relay.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

GRADLE=${GRADLE:-./gradlew}
CATALOG_RELAY=${CATALOG_RELAY:-wss://relay.zapstore.dev}
out=build/outputs/apk/release
bundle_apk=$out/zapstore-release-bundle.apk
update_apk=$out/zapstore-release.apk

if ! command -v hotbox >/dev/null 2>&1; then
	echo "hotbox not found in PATH" >&2
	exit 1
fi
if ! command -v zsp >/dev/null 2>&1; then
	echo "zsp not found in PATH (needed for find-unsigned)" >&2
	exit 1
fi

# make release copies CATALOG_BUNDLE into assets before this runs.
if [ -z "${CATALOG_BUNDLE:-}" ]; then
	make fetch-catalog-bundle CATALOG_RELAY="$CATALOG_RELAY"
fi
if [ ! -f src/main/assets/bundle-0-1.tar.zst ]; then
	echo "no catalog bundle in src/main/assets/bundle-0-1.tar.zst" >&2
	exit 1
fi

gradle_args=(-PCATALOG_RELAY="$CATALOG_RELAY")
if [ -n "${CATALOG_PUBKEY:-}" ]; then
	gradle_args+=(-PCATALOG_PUBKEY="$CATALOG_PUBKEY")
fi

find_unsigned() {
	local found
	found=$(
		zsp utils find-unsigned . | grep -m1 arm64-v8a ||
			zsp utils find-unsigned . | grep -m1 -E 'universal|unsigned|app-release\.apk' ||
			true
	)
	if [ -z "$found" ]; then
		echo "no unsigned release APK; remove release signing config or delete $out/*-release.apk and rebuild" >&2
		exit 1
	fi
	printf '%s\n' "$found"
}

# hotbox writes zapstore-release.apk next to zapstore-release-unsigned.apk.
sign_release() {
	local dest=$1 unsigned signed
	unsigned=$(find_unsigned)
	signed=$(hotbox sign "$unsigned" | sed -n '1p')
	if [ ! -f "$signed" ]; then
		echo "hotbox did not write an APK (got '$signed')" >&2
		exit 1
	fi
	if [ "$signed" != "$dest" ]; then
		mv -f "$signed" "$dest"
		if [ -f "$signed.idsig" ]; then
			mv -f "$signed.idsig" "$dest.idsig"
		fi
	fi
}

has_bundle() {
	python3 - "$1" <<'PY'
import sys, zipfile
path, = sys.argv[1:]
with zipfile.ZipFile(path) as apk:
    names = apk.namelist()
sys.exit(0 if "assets/bundle-0-1.tar.zst" in names else 1)
PY
}

"$GRADLE" assembleRelease "${gradle_args[@]}"
sign_release "$bundle_apk"
has_bundle "$bundle_apk"

# packageRelease clears this directory, so the bundled APK cannot stay here
# while the update APK is built.
park=$(mktemp -d)
cp -f "$bundle_apk" "$park/zapstore-release-bundle.apk"
if [ -f "$bundle_apk.idsig" ]; then
	cp -f "$bundle_apk.idsig" "$park/zapstore-release-bundle.apk.idsig"
fi

"$GRADLE" assembleRelease "${gradle_args[@]}" -POMIT_CATALOG_BUNDLE=true
sign_release "$update_apk"
cp -f "$park/zapstore-release-bundle.apk" "$bundle_apk"
if [ -f "$park/zapstore-release-bundle.apk.idsig" ]; then
	cp -f "$park/zapstore-release-bundle.apk.idsig" "$bundle_apk.idsig"
fi
rm -rf "$park"

has_bundle "$bundle_apk"
if has_bundle "$update_apk"; then
	echo "update APK still contains the catalog bundle" >&2
	exit 1
fi

echo "$bundle_apk"
echo "$update_apk"
