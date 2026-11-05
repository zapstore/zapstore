#!/usr/bin/env bash
# Build an unsigned release APK, sign it with hotbox, print the signed path.
# Does not run zsp publish or touch the relay.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

GRADLE=${GRADLE:-./gradlew}
CATALOG_RELAY=${CATALOG_RELAY:-wss://relay.zapstore.dev}

if ! command -v hotbox >/dev/null 2>&1; then
	echo "hotbox not found in PATH" >&2
	exit 1
fi
if ! command -v zsp >/dev/null 2>&1; then
	echo "zsp not found in PATH (needed for find-unsigned)" >&2
	exit 1
fi

make fetch-catalog-bundle CATALOG_RELAY="$CATALOG_RELAY"

"$GRADLE" assembleRelease -PCATALOG_RELAY="$CATALOG_RELAY"

unsigned=$(
	zsp utils find-unsigned . | grep -m1 arm64-v8a ||
		zsp utils find-unsigned . | grep -m1 -E 'universal|unsigned|app-release\.apk' ||
		true
)
if [ -z "$unsigned" ]; then
	echo "no unsigned release APK; remove release signing config or delete build/outputs/apk/release/*-release.apk and rebuild" >&2
	exit 1
fi

hotbox sign "$unsigned"
