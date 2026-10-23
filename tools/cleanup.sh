#!/usr/bin/env bash
# Disable (never uninstall) ad/recommendation/feedback/demo packages. Undo: backup/undo.sh
cd "$(dirname "$0")/.."
T=tools/tv
PKGS=(
  com.tcl.esticker                       # on-screen promo stickers / showmode
  com.tcl.exhibit                        # store demo mode
  com.tcl.ocean.instructions             # demo/guide popups
  com.tcl.messagebox                     # TCL push messages/promos
  com.tcl.waterfall.overseas             # TCL recommendation feed
  com.tcl.logkit                         # TCL log/telemetry collector
  com.google.android.feedback            # Google feedback
  android.autoinstalls.config.google.gtvpai  # Play Auto Installs (pushes promo apps)
  com.google.android.apps.tv.dreamx      # Ambient screensaver with recommendations
  $EXTRA_PKGS
)
for p in "${PKGS[@]}"; do
  [ -z "$p" ] && continue
  $T shell pm disable-user --user 0 "$p" && echo "$p" >> backup/disabled_by_us.txt
done
$T shell settings put global window_animation_scale 0.5
$T shell settings put global transition_animation_scale 0.5
$T shell settings put global animator_duration_scale 0.5
$T shell settings put global private_dns_mode hostname
$T shell settings put global private_dns_specifier dns.adguard-dns.com
