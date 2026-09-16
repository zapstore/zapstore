GRADLE ?= ./gradlew
ANDROID_SDK_ROOT ?= $(ANDROID_HOME)
ADB ?= $(ANDROID_SDK_ROOT)/platform-tools/adb
AVDMANAGER ?= $(ANDROID_SDK_ROOT)/cmdline-tools/latest/bin/avdmanager
EMULATOR ?= $(ANDROID_SDK_ROOT)/emulator/emulator
AVD_NAME ?= Pixel_9
SYSTEM_IMAGE ?= system-images;android-35;google_apis;arm64-v8a
AVD_HOME ?= $(HOME)/.android/avd
AVD_CONFIG ?= $(AVD_HOME)/$(AVD_NAME).avd/config.ini
PACKAGE ?= dev.zapstore.beta
ACTIVITY ?= dev.zapstore.app.MainActivity
KEYSTORE ?= release.keystore
KEYSTORE_PASSWORD ?=
KEY_ALIAS ?=
KEY_PASSWORD ?= $(KEYSTORE_PASSWORD)
DEBUG_KEYSTORE ?= $(HOME)/.android/debug.keystore

ZSP_CATALOG_DB ?= ../zsp/testdata/catalog/catalog.db
ASSETS_CATALOG_DB := src/main/assets/catalog.db

.PHONY: build release install run deploy refresh emulator bundle-catalog

bundle-catalog: $(ASSETS_CATALOG_DB)

$(ASSETS_CATALOG_DB): $(ZSP_CATALOG_DB)
	@mkdir -p src/main/assets
	cp "$(ZSP_CATALOG_DB)" "$@"

build:
	$(GRADLE) assembleDebug

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
	$(GRADLE) assembleRelease \
		-Pandroid.injected.signing.store.file="$$store" \
		-Pandroid.injected.signing.store.password="$$pass" \
		-Pandroid.injected.signing.key.alias="$$alias" \
		-Pandroid.injected.signing.key.password="$$keypass"

install:
	$(GRADLE) installDebug

run: install
	@$(ADB) reverse tcp:3334 tcp:3334 || true
	@$(ADB) reverse tcp:3336 tcp:3336 || true
	@$(ADB) shell am force-stop $(PACKAGE)
	@$(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY)
	@if [ -t 0 ]; then \
		while :; do \
		printf "\nPress r to rebuild and reload, or q to quit: "; \
		IFS= read -r -n 1 key; \
		printf "\n"; \
		case "$$key" in \
			q) break ;; \
			r) $(GRADLE) installDebug && $(ADB) shell am force-stop $(PACKAGE) && $(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY) ;; \
		esac; \
		done; \
	fi

deploy: run

refresh:
	$(ADB) shell am force-stop $(PACKAGE)
	$(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY)

emulator:
	@if ! $(AVDMANAGER) list avd -c | tr -d '\r' | awk '$$0 == "$(AVD_NAME)" { found=1 } END { exit !found }'; then \
		echo "Creating Android Virtual Device $(AVD_NAME)..."; \
		echo no | $(AVDMANAGER) create avd -n "$(AVD_NAME)" -k "$(SYSTEM_IMAGE)" -d pixel_9 --force; \
	fi
	@if [ -f "$(AVD_CONFIG)" ]; then \
		if grep -qE '^hw\.keyboard[[:space:]]*=[[:space:]]*no' "$(AVD_CONFIG)"; then \
			sed -i '' 's/^hw\.keyboard[[:space:]]*=[[:space:]]*no/hw.keyboard = yes/' "$(AVD_CONFIG)"; \
		elif ! grep -qE '^hw\.keyboard' "$(AVD_CONFIG)"; then \
			echo 'hw.keyboard = yes' >> "$(AVD_CONFIG)"; \
		fi; \
	fi
	$(EMULATOR) -avd "$(AVD_NAME)"
