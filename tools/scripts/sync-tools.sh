#!/usr/bin/env bash
set -e

UPSTREAM_URL="https://github.com/purplebase/purplestack.git"
UPSTREAM_BRANCH="main"
UPSTREAM_LOCAL_DIR=/tmp/purplestack-tmp
FOLDER="tools"

# 1. shallow clone upstream into a hidden mirror
if [ ! -d "$UPSTREAM_LOCAL_DIR" ]; then
    git clone --depth 1 --branch "$UPSTREAM_BRANCH" "$UPSTREAM_URL" "$UPSTREAM_LOCAL_DIR"
fi

# 2. fetch latest
git -C "$UPSTREAM_LOCAL_DIR" fetch --depth 1 origin "$UPSTREAM_BRANCH"
git -C "$UPSTREAM_LOCAL_DIR" reset --hard "origin/$UPSTREAM_BRANCH"

# 3. copy only the folder we care about
rm -rf "$FOLDER"
cp -a "$UPSTREAM_LOCAL_DIR/$FOLDER" "$(dirname "$FOLDER")"

fvm flutter pub upgrade || flutter pub upgrade