GRADLE ?= ./gradlew
ADB := ./tools/adb
PACKAGE ?= dev.zapstore.app
ACTIVITY ?= dev.zapstore.app.MainActivity
ASSETS_CATALOG_BUNDLE := src/main/assets/bundle-0-1.tar.zst
RELAY_PORT := 3334
# Baked into the APK. Example: CATALOG_RELAY=ws://127.0.0.1:3334 make run
CATALOG_RELAY ?= wss://relay.zapstore.dev
GRADLE_FILE := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))build.gradle.kts
PRODUCTION_CATALOG_PUBKEY := $(shell sed -n 's/^[[:space:]]*val productionCatalogPubkey = "\([0-9a-f]*\)".*/\1/p' "$(GRADLE_FILE)" | head -n 1)
# The key the bundled snapshot and the default catalog must be signed by. A local relay needs its own:
# CATALOG_RELAY=ws://127.0.0.1:3334 CATALOG_PUBKEY=<hex> make run. Empty skips the check (debug only).
CATALOG_PUBKEY ?= $(if $(filter wss://relay.zapstore.dev wss://relay.zapstore.dev/,$(CATALOG_RELAY)),$(PRODUCTION_CATALOG_PUBKEY),)
GRADLE_RELAY := -PCATALOG_RELAY="$(CATALOG_RELAY)" -PCATALOG_PUBKEY="$(CATALOG_PUBKEY)"
# IoliteConfig.maxCompressedBytes. Import treats a larger file as unreadable.
CATALOG_BUNDLE_MAX_BYTES := 268435456
APP_VERSION := $(shell sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' "$(GRADLE_FILE)" | head -n 1)

# $(call bundle_signer_ok,<file>): exits 1 unless the bundle's manifest is signed by CATALOG_PUBKEY.
# The app verifies the signature itself; this stops a build from shipping someone else's catalog.
bundle_signer_ok = if [ -n "$(CATALOG_PUBKEY)" ]; then \
		command -v zstd >/dev/null || { echo "zstd is needed to check the catalog bundle" >&2; exit 1; }; \
		signer=$$(zstd -dc "$(1)" | tar -xOf - manifest.json 2>/dev/null | sed -n 's/.*"pubkey"[[:space:]]*:[[:space:]]*"\([0-9a-f]\{64\}\)".*/\1/p' | head -n 1); \
		if [ "$$signer" != "$(CATALOG_PUBKEY)" ]; then \
			echo "catalog bundle manifest is signed by '$$signer', not $(CATALOG_PUBKEY)" >&2; \
			exit 1; \
		fi; \
	fi

.PHONY: build release install run run-release deploy reload watch refresh vendor unbundle-catalog fetch-catalog-bundle reverse link-adb

# Drop leftover seeds so a debug build without CATALOG_BUNDLE does not package them.
unbundle-catalog:
	@rm -f "$(ASSETS_CATALOG_BUNDLE)" src/main/assets/catalog-delta.tar.zst

# The asset name stays bundle-0-1.tar.zst. The manifest's from and to are the epochs.
fetch-catalog-bundle:
	@test -n "$(APP_VERSION)" || { echo "versionName missing from build.gradle.kts" >&2; exit 1; }
	@origin=$$(printf '%s\n' "$(CATALOG_RELAY)" | sed -E 's|^wss://|https://|; s|^ws://|http://|; s|[?].*||; s|(https?://[^/]+).*|\1|'); \
	case "$$origin" in \
		http://*|https://*) ;; \
		*) echo "CATALOG_RELAY must be ws:// or wss://: $(CATALOG_RELAY)" >&2; exit 1 ;; \
	esac; \
	url="$$origin/v1/bundle?from=0&v=$(APP_VERSION)"; \
	tmp="$(ASSETS_CATALOG_BUNDLE).tmp"; \
	mkdir -p src/main/assets; \
	rm -f src/main/assets/catalog-delta.tar.zst; \
	echo "Fetching $$url"; \
	curl -fL --retry 3 --retry-delay 2 --connect-timeout 30 --max-time 600 -o "$$tmp" "$$url" || { rm -f "$$tmp"; exit 1; }; \
	bytes=$$(wc -c < "$$tmp" | tr -d ' '); \
	sig=$$(od -An -tx1 -N4 "$$tmp" | tr -d ' \n'); \
	if [ "$$bytes" -eq 0 ] || [ "$$bytes" -gt $(CATALOG_BUNDLE_MAX_BYTES) ] || [ "$$sig" != "28b52ffd" ]; then \
		rm -f "$$tmp"; \
		echo "catalog bundle is $$bytes bytes ($$sig); it must be a zstd frame of at most $(CATALOG_BUNDLE_MAX_BYTES) bytes" >&2; \
		exit 1; \
	fi; \
	( $(call bundle_signer_ok,$$tmp) ) || { rm -f "$$tmp"; exit 1; }; \
	mv "$$tmp" "$(ASSETS_CATALOG_BUNDLE)"

ifeq ($(CATALOG_BUNDLE),)
build install reload watch: unbundle-catalog
else
.PHONY: $(ASSETS_CATALOG_BUNDLE)
$(ASSETS_CATALOG_BUNDLE):
	@test -f "$(CATALOG_BUNDLE)" || { echo "CATALOG_BUNDLE not found: $(CATALOG_BUNDLE)" >&2; exit 1; }
	@bytes=$$(wc -c < "$(CATALOG_BUNDLE)" | tr -d ' '); \
	sig=$$(od -An -tx1 -N4 "$(CATALOG_BUNDLE)" | tr -d ' \n'); \
	if [ "$$bytes" -eq 0 ] || [ "$$bytes" -gt $(CATALOG_BUNDLE_MAX_BYTES) ] || [ "$$sig" != "28b52ffd" ]; then \
		echo "catalog bundle is $$bytes bytes ($$sig); it must be a zstd frame of at most $(CATALOG_BUNDLE_MAX_BYTES) bytes" >&2; \
		exit 1; \
	fi
	@$(call bundle_signer_ok,$(CATALOG_BUNDLE))
	@mkdir -p src/main/assets
	rm -f src/main/assets/catalog-delta.tar.zst
	cp "$(CATALOG_BUNDLE)" "$@"

build install release reload watch: $(ASSETS_CATALOG_BUNDLE)
endif

# Rebuild libarti_android.so into src/main/jniLibs/arm64-v8a.
# Needs rustup, the cargo-ndk / NDK versions pinned under tools/arti-build/,
# and a later commit of the produced .so files.
vendor:
	./tools/arti-build/build-arti.sh
	$(GRADLE) verifyArtiAbis

build:
	$(GRADLE) assembleDebug $(GRADLE_RELAY)

# Two hotbox-signed APKs of this version: with the catalog, and without it.
# Never calls zsp publish.
release:
	./tools/sign-release-hotbox.sh

# Forward this machine to the device. A local CATALOG_RELAY port is included.
reverse:
	@$(ADB) reverse tcp:$(RELAY_PORT) tcp:$(RELAY_PORT) >/dev/null || true
	@port=$$(printf '%s\n' "$(CATALOG_RELAY)" | sed -nE 's#^wss?://(127\.0\.0\.1|localhost):([0-9]+).*#\2#p'); \
	if [ -n "$$port" ] && [ "$$port" != "$(RELAY_PORT)" ]; then \
		$(ADB) reverse tcp:$$port tcp:$$port >/dev/null || true; \
	fi

run-release: release reverse
	$(ADB) install -r --no-incremental build/outputs/apk/release/zapstore-release.apk
	$(ADB) shell am force-stop $(PACKAGE)
	$(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY)

# Gradle runs .android-sdk/platform-tools/adb. Keep that path on tools/adb.
link-adb:
	@sdk="$(abspath .android-sdk)/platform-tools"; \
	if [ -L "$$sdk/adb" ] && [ "$$(readlink "$$sdk/adb")" = "../../tools/adb" ]; then \
		exit 0; \
	fi; \
	real=""; \
	for candidate in "$${ANDROID_SDK_ROOT:-}/platform-tools" "$${ANDROID_HOME:-}/platform-tools" "/opt/homebrew/share/android-commandlinetools/platform-tools"; do \
		if [ -x "$$candidate/adb" ] && [ ! -L "$$candidate/adb" ]; then real=$$candidate; break; fi; \
	done; \
	test -n "$$real" || { echo "platform-tools adb not found" >&2; exit 1; }; \
	if [ -L "$$sdk" ]; then rm "$$sdk"; fi; \
	mkdir -p "$$sdk"; \
	for entry in "$$real"/*; do \
		name=$$(basename "$$entry"); \
		if [ "$$name" = "adb" ]; then ln -sfn ../../tools/adb "$$sdk/adb"; \
		else ln -sfn "$$entry" "$$sdk/$$name"; fi; \
	done

install: link-adb
	$(GRADLE) installDebug $(GRADLE_RELAY)
	$(ADB) shell pm clear $(PACKAGE)

run: install reverse
	@$(ADB) shell am force-stop $(PACKAGE)
	@$(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY)
	@if [ -t 0 ]; then \
		while :; do \
		printf "\nPress r to rebuild and reload, or q to quit: "; \
		IFS= read -r -n 1 key; \
		printf "\n"; \
		case "$$key" in \
			q) break ;; \
			r) $(GRADLE) installDebug $(GRADLE_RELAY) && $(ADB) shell am force-stop $(PACKAGE) && $(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY) ;; \
		esac; \
		done; \
	fi

deploy: run

# One-shot, non-interactive build + install + relaunch for scripts and agents. Keeps app data.
# Silent on success except the last line. Prints only compiler errors on a failed build, and the
# crash log if the app is not running after launch. Exit code is non-zero on either failure.
# Run `make run` once first so the app is installed with a clean state.
reload: link-adb
	@$(ADB) reverse tcp:$(RELAY_PORT) tcp:$(RELAY_PORT) >/dev/null 2>&1 || true
	@$(ADB) logcat -b crash -c >/dev/null 2>&1 || true
	@start=$$(date +%s); \
	$(GRADLE) -q --console=plain runDebug $(GRADLE_RELAY) >/tmp/zapstore-reload.log 2>&1 || { \
		{ grep -E '^e: ' /tmp/zapstore-reload.log || grep -E -A3 'What went wrong' /tmp/zapstore-reload.log; } | head -40; \
		echo "reload: build failed (full log: /tmp/zapstore-reload.log)" >&2; exit 1; }; \
	sleep 2; \
	if $(ADB) shell pidof $(PACKAGE) >/dev/null 2>&1; then \
		echo "reload: ok ($$(( $$(date +%s) - start ))s)"; \
	else \
		$(ADB) logcat -b crash -d -t 40; \
		echo "reload: app is not running after launch" >&2; exit 1; \
	fi

# Interactive: rebuild, reinstall and relaunch on every saved change. Keeps app data.
watch: link-adb reverse
	$(GRADLE) -t runDebug $(GRADLE_RELAY)

refresh:
	$(ADB) shell am force-stop $(PACKAGE)
	$(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY)
