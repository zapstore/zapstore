GRADLE ?= ./gradlew
ADB ?= adb
PACKAGE ?= dev.zapstore.beta

.PHONY: build install run deploy refresh

build:
	cd purplequartz && $(GRADLE) :app:assembleDebug

install:
	cd purplequartz && $(GRADLE) :app:installDebug

run: install
	$(ADB) shell monkey -p $(PACKAGE) 1

deploy: run

refresh:
	$(ADB) shell am force-stop $(PACKAGE)
	$(ADB) shell monkey -p $(PACKAGE) 1
