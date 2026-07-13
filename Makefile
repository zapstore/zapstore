GRADLE ?= ./gradlew
ANDROID_SDK_ROOT ?= $(ANDROID_HOME)
ADB ?= $(ANDROID_SDK_ROOT)/platform-tools/adb
AVDMANAGER ?= $(ANDROID_SDK_ROOT)/cmdline-tools/latest/bin/avdmanager
EMULATOR ?= $(ANDROID_SDK_ROOT)/emulator/emulator
AVD_NAME ?= Pixel_9
SYSTEM_IMAGE ?= system-images;android-35;google_apis;arm64-v8a
PACKAGE ?= dev.zapstore.beta
ACTIVITY ?= dev.zapstore.app.MainActivity

.PHONY: build install run deploy refresh emulator

build:
	$(GRADLE) assembleDebug

install:
	$(GRADLE) installDebug

run: install
	@while :; do \
		$(ADB) shell am force-stop $(PACKAGE); \
		$(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY); \
		printf "\nPress r to rebuild and reload, or q to quit: "; \
		IFS= read -r -n 1 key; \
		printf "\n"; \
		case "$$key" in \
			q) break ;; \
			r) $(GRADLE) installDebug ;; \
		esac; \
	done

deploy: run

refresh:
	$(ADB) shell am force-stop $(PACKAGE)
	$(ADB) shell am start -n $(PACKAGE)/$(ACTIVITY)

emulator:
	@if ! $(AVDMANAGER) list avd -c | tr -d '\r' | awk '$$0 == "$(AVD_NAME)" { found=1 } END { exit !found }'; then \
		echo "Creating Android Virtual Device $(AVD_NAME)..."; \
		echo no | $(AVDMANAGER) create avd -n "$(AVD_NAME)" -k "$(SYSTEM_IMAGE)" -d pixel_9 --force; \
	fi
	$(EMULATOR) -avd "$(AVD_NAME)"
