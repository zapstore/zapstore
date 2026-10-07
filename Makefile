GRADLE ?= ./gradlew
ADB := ./tools/adb
PACKAGE ?= dev.zapstore.app
ACTIVITY ?= dev.zapstore.app.MainActivity
KEYSTORE ?= release.keystore
KEYSTORE_PASSWORD ?=
KEY_ALIAS ?=
KEY_PASSWORD ?= $(KEYSTORE_PASSWORD)
DEBUG_KEYSTORE ?= $(abspath debug.keystore)

ASSETS_CATALOG_BUNDLE := src/main/assets/bundle-0-1.tar.zst
RELAY_PORT := 3334
# Baked into the APK. Example: CATALOG_RELAY=ws://127.0.0.1:3334 make run
CATALOG_RELAY ?= wss://brelay.zapstore.dev
GRADLE_RELAY := -PCATALOG_RELAY="$(CATALOG_RELAY)"

.PHONY: build release install run run-release deploy refresh vendor unbundle-catalog reverse link-adb

# Drop leftover seeds so a build without CATALOG_BUNDLE does not package them.
unbundle-catalog:
	rm -f "$(ASSETS_CATALOG_BUNDLE)" src/main/assets/catalog-delta.tar.zst

ifeq ($(CATALOG_BUNDLE),)
build install release: unbundle-catalog
else
.PHONY: $(ASSETS_CATALOG_BUNDLE)
$(ASSETS_CATALOG_BUNDLE):
	@test -f "$(CATALOG_BUNDLE)" || { echo "CATALOG_BUNDLE not found: $(CATALOG_BUNDLE)" >&2; exit 1; }
	@mkdir -p src/main/assets
	cp "$(CATALOG_BUNDLE)" "$@"

build install release: $(ASSETS_CATALOG_BUNDLE)
endif

# Rebuild libarti_android.so into src/main/jniLibs/arm64-v8a.
# Needs rustup, the cargo-ndk / NDK versions pinned under tools/arti-build/,
# and a later commit of the produced .so files.
vendor:
	./tools/arti-build/build-arti.sh
	$(GRADLE) verifyArtiAbis

build:
	$(GRADLE) assembleDebug $(GRADLE_RELAY)

release:
	@if [ -f "$(KEYSTORE)" ]; then \
		store="$(abspath $(KEYSTORE))"; \
		pass="$(KEYSTORE_PASSWORD)"; \
		alias="$(KEY_ALIAS)"; \
		keypass="$(KEY_PASSWORD)"; \
		if [ -z "$$pass" ] || [ -z "$$alias" ]; then \
			echo "Found $$store; set KEYSTORE_PASSWORD and KEY_ALIAS to sign the release build."; \
			exit 1; \
		fi; \
	else \
		echo "No $(KEYSTORE); signing release with the Android debug certificate"; \
		store="$(DEBUG_KEYSTORE)"; \
		if [ ! -f "$$store" ]; then \
			mkdir -p "$$(dirname "$$store")"; \
			keytool -genkeypair -keystore "$$store" \
				-storepass android -keypass android \
				-alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
				-dname "CN=Android Debug,O=Android,C=US"; \
		fi; \
		pass=android; \
		alias=androiddebugkey; \
		keypass=android; \
	fi; \
	$(GRADLE) assembleRelease $(GRADLE_RELAY) \
		-Pandroid.injected.signing.store.file="$$store" \
		-Pandroid.injected.signing.store.password="$$pass" \
		-Pandroid.injected.signing.key.alias="$$alias" \
		-Pandroid.injected.signing.key.password="$$keypass"

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

refresh:
	$(ADB) shell am force-stop $(PACKAGE)
	$(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY)
