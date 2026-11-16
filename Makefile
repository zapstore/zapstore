GRADLE ?= ./gradlew
ADB := ./tools/adb
PACKAGE ?= dev.zapstore.app
ACTIVITY ?= dev.zapstore.app.MainActivity
RELAY_PORT := 3334

# Inputs baked into a debug build. Release builds never read these: tools/release/build-apk.sh owns them.
#   CATALOG_RELAY=ws://127.0.0.1:3334 CATALOG_PUBKEY=<hex> make run     a local relay and its signing key
#   CATALOG_BUNDLE=tests/out/bundle-0-1.tar.zst make run                import this bundle on first open
# CATALOG_PUBKEY left unset is the production key for the production relay, and empty (trust the first
# bundle's signer) for any other relay; set it to an empty value to force that for the production relay.
CATALOG_RELAY ?= wss://relay.zapstore.dev
GRADLE_ARGS := -PCATALOG_RELAY="$(CATALOG_RELAY)" \
	$(if $(filter undefined,$(origin CATALOG_PUBKEY)),,-PCATALOG_PUBKEY="$(CATALOG_PUBKEY)") \
	$(if $(CATALOG_BUNDLE),-PcatalogBundle="$(abspath $(CATALOG_BUNDLE))")

.DEFAULT_GOAL := help
.PHONY: help build install run reload watch refresh reverse link-adb vendor

help: ## This list
	@awk 'BEGIN { FS = ":.*## " } /^[a-z-]+:.*## / { printf "  %-16s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

# --- Develop ------------------------------------------------------------------------------------

build: ## Debug APK
	$(GRADLE) assembleDebug $(GRADLE_ARGS)

install: link-adb ## Install the debug APK and clear app data
	$(GRADLE) installDebug $(GRADLE_ARGS)
	$(ADB) shell pm clear $(PACKAGE)

run: install reverse ## Install, launch, then press r to rebuild and reload
	@$(ADB) shell am force-stop $(PACKAGE)
	@$(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY)
	@if [ -t 0 ]; then \
		while :; do \
		printf "\nPress r to rebuild and reload, or q to quit: "; \
		IFS= read -r -n 1 key; \
		printf "\n"; \
		case "$$key" in \
			q) break ;; \
			r) $(GRADLE) installDebug $(GRADLE_ARGS) && $(ADB) shell am force-stop $(PACKAGE) && $(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY) ;; \
		esac; \
		done; \
	fi

# For scripts and agents.
# Silent on success except the last line. Prints only compiler errors on a failed build, and the
# crash log if the app is not running after launch. Exit code is non-zero on either failure.
# Run `make run` once first so the app is installed with a clean state.
reload: link-adb ## One-shot build, install and relaunch, keeping app data
	@$(ADB) reverse tcp:$(RELAY_PORT) tcp:$(RELAY_PORT) >/dev/null 2>&1 || true
	@$(ADB) logcat -b crash -c >/dev/null 2>&1 || true
	@start=$$(date +%s); \
	$(GRADLE) -q --console=plain runDebug $(GRADLE_ARGS) >/tmp/zapstore-reload.log 2>&1 || { \
		{ grep -E '^e: ' /tmp/zapstore-reload.log || grep -E -A3 'What went wrong' /tmp/zapstore-reload.log; } | head -40; \
		echo "reload: build failed (full log: /tmp/zapstore-reload.log)" >&2; exit 1; }; \
	sleep 2; \
	if $(ADB) shell pidof $(PACKAGE) >/dev/null 2>&1; then \
		echo "reload: ok ($$(( $$(date +%s) - start ))s)"; \
	else \
		$(ADB) logcat -b crash -d -t 40; \
		echo "reload: app is not running after launch" >&2; exit 1; \
	fi

watch: link-adb reverse ## Rebuild, reinstall and relaunch on every saved change
	$(GRADLE) -t runDebug $(GRADLE_ARGS)

refresh: ## Restart the app
	$(ADB) shell am force-stop $(PACKAGE)
	$(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY)

# A local CATALOG_RELAY port is included.
reverse: ## Forward the relay port to the device
	@$(ADB) reverse tcp:$(RELAY_PORT) tcp:$(RELAY_PORT) >/dev/null || true
	@port=$$(printf '%s\n' "$(CATALOG_RELAY)" | sed -nE 's#^wss?://(127\.0\.0\.1|localhost):([0-9]+).*#\2#p'); \
	if [ -n "$$port" ] && [ "$$port" != "$(RELAY_PORT)" ]; then \
		$(ADB) reverse tcp:$$port tcp:$$port >/dev/null || true; \
	fi

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

# --- Native library -----------------------------------------------------------------------------

vendor: ## Rebuild libarti_android.so into src/main/jniLibs (commit the result)
	./tools/arti-build/build-arti.sh
	$(GRADLE) verifyArtiAbis
