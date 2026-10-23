#!/usr/bin/env bash
# Build the release APK and install it on the TV for quick iteration.
set -e
cd "$(dirname "$0")/.."
./gradlew --no-daemon -q test assembleRelease
tools/tv install -r app/build/outputs/apk/release/app-release.apk
tools/tv shell am start -n io.github.cpujp.wiilauncher/.MainActivity
