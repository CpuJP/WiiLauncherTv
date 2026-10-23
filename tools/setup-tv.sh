#!/usr/bin/env bash
# Make the launcher the Home screen. Undo: tools/restore-home.sh
set -e
cd "$(dirname "$0")/.."
T=tools/tv
P=io.github.cpujp.wiilauncher
$T shell cmd package set-home-activity $P/.MainActivity
# Google TV keeps its own launcher as HOME, so the Home key is routed by an accessibility service.
$T shell settings put secure enabled_accessibility_services $P/.HomeKeyService
$T shell settings put secure accessibility_enabled 1
# Lets the launcher re-enable that service if Android drops it (e.g. after a force-stop).
$T shell pm grant $P android.permission.WRITE_SECURE_SETTINGS
# Lets the built-in updater hand new releases to the system installer.
$T shell appops set $P REQUEST_INSTALL_PACKAGES allow
