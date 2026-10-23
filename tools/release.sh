#!/usr/bin/env bash
# Usage: tools/release.sh 1.1.0
# Bumps the version, commits, tags and pushes. GitHub Actions builds and publishes the release,
# and TVs running the launcher pick it up from the mail button.
set -e
cd "$(dirname "$0")/.."
v="${1:?version, e.g. 1.1.0}"
code=$(( $(grep '^VERSION_CODE=' gradle.properties | cut -d= -f2) + 1 ))
sed -i "s/^VERSION_NAME=.*/VERSION_NAME=$v/; s/^VERSION_CODE=.*/VERSION_CODE=$code/" gradle.properties
./gradlew --no-daemon -q test lintRelease assembleRelease
git add gradle.properties CHANGELOG.md
git commit -m "release: v$v"
git tag "v$v"
git push origin main "v$v"
