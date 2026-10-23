#!/usr/bin/env bash
# Give the Home key back to the stock Google TV launcher.
cd "$(dirname "$0")/.."
T=tools/tv
$T shell settings delete secure enabled_accessibility_services
$T shell settings put secure accessibility_enabled 0
$T shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
