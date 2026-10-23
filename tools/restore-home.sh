#!/usr/bin/env bash
# Give the Home key back to the stock Google TV launcher.
cd "$(dirname "$0")/.."
T=tools/tv
# Revoke first, or the launcher would turn its service back on.
$T shell pm revoke io.github.cpujp.wiilauncher android.permission.WRITE_SECURE_SETTINGS
$T shell settings delete secure enabled_accessibility_services
$T shell settings put secure accessibility_enabled 0
$T shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
