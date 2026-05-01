#!/usr/bin/env bash
set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== Zapstore Release Script ===${NC}\n"

# 1. Check working tree is clean
if [[ -n $(git status --porcelain) ]]; then
  echo -e "${RED}Error: Working tree is not clean. Commit or stash changes first.${NC}"
  exit 1
fi

# 2. Ensure we're on master and up to date
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [[ "$CURRENT_BRANCH" != "master" ]]; then
  echo -e "${YELLOW}Warning: Not on master branch (currently on $CURRENT_BRANCH)${NC}"
  read -p "Continue anyway? (y/N) " -n 1 -r
  echo
  if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    exit 1
  fi
fi

echo -e "${GREEN}Fetching latest changes...${NC}"
git fetch origin

# 3. Parse current version from pubspec.yaml
CURRENT_VERSION=$(grep '^version:' pubspec.yaml | sed 's/version: //')
CURRENT_NAME=$(echo "$CURRENT_VERSION" | cut -d'+' -f1)
CURRENT_CODE=$(echo "$CURRENT_VERSION" | cut -d'+' -f2)

echo -e "\nCurrent version: ${YELLOW}$CURRENT_NAME+$CURRENT_CODE${NC}"
echo -e "  Version name: $CURRENT_NAME"
echo -e "  Version code: $CURRENT_CODE"

# 4. Prompt for new version
read -p "Enter new version name (e.g. 1.0.7) or press Enter to keep current: " NEW_NAME
NEW_NAME=${NEW_NAME:-$CURRENT_NAME}

NEW_CODE=$((CURRENT_CODE + 1))
echo -e "\nNew version will be: ${GREEN}$NEW_NAME+$NEW_CODE${NC}"
read -p "Proceed? (y/N) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
  exit 1
fi

# 5. Update version in pubspec.yaml
echo -e "\n${GREEN}Updating version in pubspec.yaml...${NC}"
sed -i.bak "s/^version: .*/version: $NEW_NAME+$NEW_CODE/" pubspec.yaml
rm pubspec.yaml.bak

# 6. Run checks
echo -e "\n${GREEN}Running checks...${NC}"

echo "  - fvm flutter pub get"
fvm flutter pub get

echo "  - fvm flutter analyze"
if ! fvm flutter analyze; then
  echo -e "${RED}Error: Lint/analysis errors found. Fix them first.${NC}"
  git checkout pubspec.yaml
  exit 1
fi

echo "  - fvm flutter test"
if ! fvm flutter test; then
  echo -e "${RED}Error: Tests failed. Fix them first.${NC}"
  git checkout pubspec.yaml
  exit 1
fi

# 7. Pull latest apps into seed.db
echo -e "\n${GREEN}Generating seed database (assets/seed.db)...${NC}"
if ! dart run tool/seed_database.dart; then
  echo -e "${RED}Error: Failed to generate seed database.${NC}"
  git checkout pubspec.yaml
  exit 1
fi

# 8. Check that CHANGELOG.md was updated
echo -e "\n${GREEN}Checking CHANGELOG.md...${NC}"
if ! grep -q "## \[$NEW_NAME\]" CHANGELOG.md; then
  echo -e "${YELLOW}Warning: CHANGELOG.md does not contain an entry for [$NEW_NAME]${NC}"
  echo "Please update CHANGELOG.md before proceeding."
  read -p "Continue anyway? (y/N) " -n 1 -r
  echo
  if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    git checkout pubspec.yaml assets/seed.db
    exit 1
  fi
fi

# 9. Build reproducible APK
echo -e "\n${GREEN}Building reproducible release APK...${NC}"

# Set SOURCE_DATE_EPOCH for reproducibility (use git commit timestamp)
export SOURCE_DATE_EPOCH=$(git log -1 --format=%ct)
echo "  Using SOURCE_DATE_EPOCH=$SOURCE_DATE_EPOCH"

# Build unsigned APK (reproducible)
fvm flutter build apk --release --split-per-abi

APK_PATH="build/app/outputs/flutter-apk/app-arm64-v8a-release.apk"
if [[ ! -f "$APK_PATH" ]]; then
  echo -e "${RED}Error: APK not found at $APK_PATH${NC}"
  git checkout pubspec.yaml assets/seed.db
  exit 1
fi

APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
APK_SHA256=$(shasum -a 256 "$APK_PATH" | cut -d' ' -f1)

echo -e "\n${GREEN}Build successful!${NC}"
echo "  APK: $APK_PATH"
echo "  Size: $APK_SIZE"
echo "  SHA256: $APK_SHA256"

# 10. Show what will be committed
echo -e "\n${GREEN}Changes to be committed:${NC}"
git diff --stat pubspec.yaml assets/seed.db

# 11. Commit and tag
echo -e "\n${GREEN}Creating release commit...${NC}"
git add pubspec.yaml assets/seed.db

COMMIT_MSG="Release $NEW_NAME+$NEW_CODE"
git commit -m "$COMMIT_MSG"

TAG_NAME="v$NEW_NAME"
echo -e "\n${GREEN}Creating git tag: $TAG_NAME${NC}"
git tag -a "$TAG_NAME" -m "Release $NEW_NAME (build $NEW_CODE)"

# 12. Summary
echo -e "\n${GREEN}=== Release Ready ===${NC}"
echo -e "Version: ${GREEN}$NEW_NAME+$NEW_CODE${NC}"
echo -e "Tag: ${GREEN}$TAG_NAME${NC}"
echo -e "APK SHA256: ${YELLOW}$APK_SHA256${NC}"
echo -e "\nNext steps:"
echo "  1. Review the commit: git show"
echo "  2. Push to remote: git push origin master && git push origin $TAG_NAME"
echo "  3. Create GitHub release with the APK"
echo -e "\nTo rebuild this APK reproducibly, anyone can:"
echo "  git checkout $TAG_NAME"
echo "  export SOURCE_DATE_EPOCH=$SOURCE_DATE_EPOCH"
echo "  fvm flutter build apk --release --split-per-abi"
