.PHONY: help debug build-release release deploy deploy-debug pub-get

.ONESHELL:
SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c

FLUTTER := fvm flutter
APK_DIR := build/app/outputs/flutter-apk
DEBUG_APK := $(APK_DIR)/app-arm64-v8a-debug.apk
RELEASE_APK := $(APK_DIR)/app-arm64-v8a-release.apk
CURRENT_VERSION := $(shell awk '/^version:/ {print $$2; exit}' pubspec.yaml)
CURRENT_NAME := $(word 1,$(subst +, ,$(CURRENT_VERSION)))
CURRENT_CODE := $(word 2,$(subst +, ,$(CURRENT_VERSION)))

# Reproducible release builds honor SOURCE_DATE_EPOCH (see spec/guidelines/INVARIANTS.md).
SOURCE_DATE_EPOCH ?= $(shell git log -1 --format=%ct 2>/dev/null || date +%s)

ARM64_FLAGS := --split-per-abi --target-platform android-arm64
RED := \033[0;31m
GREEN := \033[0;32m
YELLOW := \033[1;33m
NC := \033[0m

define deploy-apk-cmd
	set -e; \
	apk='$(1)'; \
	if [ ! -f "$$apk" ]; then \
		echo "Missing $$apk. Build it first."; \
		exit 1; \
	fi; \
	devices=$$(adb devices | awk 'NR>1 && $$2=="device" {print $$1}'); \
	if [ -z "$$devices" ]; then \
		echo "No connected Android devices or emulators found."; \
		adb devices; \
		exit 1; \
	fi; \
	if [ -n "$$(printf '%s\n' "$$devices" | sed -n '2p')" ]; then \
		echo "Multiple physical devices connected:"; \
		printf '  %s\n' $$devices; \
		exit 1; \
	fi; \
	serial=$$(printf '%s\n' "$$devices" | sed -n '1p'); \
	echo "Installing $$apk on $$serial..."; \
	adb -s "$$serial" install -r "$$apk"
endef

help:
	@echo "  make debug        $(DEBUG_APK)"
	@echo "  make release      prepare and build a release"
	@echo "  make deploy       prepare, build, and install release APK"
	@echo "  make deploy-debug build and install debug APK"

pub-get:
	$(FLUTTER) pub get

debug: pub-get
	$(FLUTTER) build apk --debug $(ARM64_FLAGS)
	@test -f $(DEBUG_APK)

build-release: pub-get
	SOURCE_DATE_EPOCH=$(SOURCE_DATE_EPOCH) $(FLUTTER) build apk --release $(ARM64_FLAGS)
	@test -f $(RELEASE_APK)

release:
	@if [[ -n "$$(git status --porcelain)" ]]; then \
		printf "$(RED)Error: Working tree is not clean. Commit or stash changes first.$(NC)\n"; \
		exit 1; \
	fi; \
	current_branch="$$(git rev-parse --abbrev-ref HEAD)"; \
	if [[ "$$current_branch" != "master" ]]; then \
		printf "$(YELLOW)Warning: Not on master branch (currently on %s)$(NC)\n" "$$current_branch"; \
		read -r -p "Continue anyway? (y/N) " reply; \
		[[ "$$reply" =~ ^[Yy]$$ ]]; \
	fi; \
	printf "$(GREEN)Fetching latest changes...$(NC)\n"; \
	git fetch origin; \
	printf "\nCurrent version: $(YELLOW)$(CURRENT_NAME)+$(CURRENT_CODE)$(NC)\n"; \
	printf "  Version name: $(CURRENT_NAME)\n"; \
	printf "  Version code: $(CURRENT_CODE)\n"; \
	read -r -p "Enter new version name (e.g. 1.0.7) or press Enter to keep current: " new_name; \
	new_name="$${new_name:-$(CURRENT_NAME)}"; \
	new_code=$$(($(CURRENT_CODE) + 1)); \
	printf "\nNew version will be: $(GREEN)%s+%s$(NC)\n" "$$new_name" "$$new_code"; \
	read -r -p "Proceed? (y/N) " reply; \
	[[ "$$reply" =~ ^[Yy]$$ ]]; \
	printf "\n$(GREEN)Updating version in pubspec.yaml...$(NC)\n"; \
	sed -i.bak "s/^version: .*/version: $$new_name+$$new_code/" pubspec.yaml; \
	rm -f pubspec.yaml.bak; \
	trap 'git checkout -- pubspec.yaml assets/seed.db 2>/dev/null || true' EXIT; \
	printf "\n$(GREEN)Running checks...$(NC)\n"; \
	$(FLUTTER) pub get; \
	$(FLUTTER) analyze; \
	$(FLUTTER) test; \
	printf "\n$(GREEN)Generating seed database (assets/seed.db)...$(NC)\n"; \
	dart run tool/seed_database.dart; \
	printf "\n$(GREEN)Checking CHANGELOG.md...$(NC)\n"; \
	if ! grep -q "## \[$$new_name\]" CHANGELOG.md; then \
		printf "$(YELLOW)Warning: CHANGELOG.md does not contain an entry for [%s]$(NC)\n" "$$new_name"; \
		read -r -p "Continue anyway? (y/N) " reply; \
		[[ "$$reply" =~ ^[Yy]$$ ]]; \
	fi; \
	printf "\n$(GREEN)Building reproducible release APK...$(NC)\n"; \
	export SOURCE_DATE_EPOCH="$(SOURCE_DATE_EPOCH)"; \
	printf "  Using SOURCE_DATE_EPOCH=%s\n" "$$SOURCE_DATE_EPOCH"; \
	$(FLUTTER) build apk --release $(ARM64_FLAGS); \
	test -f "$(RELEASE_APK)"; \
	apk_size="$$(du -h "$(RELEASE_APK)" | cut -f1)"; \
	apk_sha256="$$(shasum -a 256 "$(RELEASE_APK)" | cut -d' ' -f1)"; \
	printf "\n$(GREEN)Build successful!$(NC)\n"; \
	printf "  APK: %s\n  Size: %s\n  SHA256: %s\n" "$(RELEASE_APK)" "$$apk_size" "$$apk_sha256"; \
	printf "\n$(GREEN)Changes to be committed:$(NC)\n"; \
	git diff --stat pubspec.yaml assets/seed.db; \
	git add pubspec.yaml assets/seed.db; \
	git commit -m "Release $$new_name+$$new_code"; \
	trap - EXIT; \
	tag_name="v$$new_name"; \
	printf "\n$(GREEN)Creating git tag: %s$(NC)\n" "$$tag_name"; \
	git tag -a "$$tag_name" -m "Release $$new_name (build $$new_code)"; \
	printf "\n$(GREEN)=== Release Ready ===$(NC)\n"; \
	printf "Version: $(GREEN)%s+%s$(NC)\nTag: $(GREEN)%s$(NC)\nAPK SHA256: $(YELLOW)%s$(NC)\n" "$$new_name" "$$new_code" "$$tag_name" "$$apk_sha256"; \
	printf "\nNext steps:\n"; \
	printf "  1. Review the commit: git show\n"; \
	printf "  2. Push to remote: git push origin master && git push origin %s\n" "$$tag_name"; \
	printf "  3. Create GitHub release with the APK\n"; \
	printf "\nTo rebuild this APK reproducibly:\n"; \
	printf "  git checkout %s\n  export SOURCE_DATE_EPOCH=%s\n  $(FLUTTER) build apk --release $(ARM64_FLAGS)\n" "$$tag_name" "$$SOURCE_DATE_EPOCH"

deploy: release
	@$(call deploy-apk-cmd,$(RELEASE_APK))

deploy-debug: debug
	@$(call deploy-apk-cmd,$(DEBUG_APK))
