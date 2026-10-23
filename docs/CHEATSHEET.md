# Cheat sheet / Hoja de trucos

Every script uses `tools/tv`, which prints each ADB command with a timestamp and captures `logcat` only while that command runs. Watch it live in a second terminal:

```bash
tail -f tools/monitor.log
```

Set your TV once per shell: `export TV=192.168.1.50:5555` (the default is in `tools/tv`).

## 1. Enable ADB on the TV (remote control)

1. **Settings → System → About** → highlight **Android TV OS build** → press **OK 7 times**. A message says "You are now a developer".
2. **Back → System → Developer options** → turn on **USB debugging**. If **Wireless debugging** exists, turn it on too.
3. **Settings → Network & Internet** → select your network → write down the **IP address**.
4. Run `adb connect $TV`. On the TV choose **Always allow from this computer** → **Allow**.
5. If the connection is refused, go to **Developer options → Wireless debugging → Pair device with code**, then run `adb pair <ip>:<pair-port> <code>` and `adb connect <ip>:<port>`.

## 2. Everyday commands

| What | Command |
|---|---|
| Connect | `adb connect $TV` |
| Run any ADB command with logging | `tools/tv shell <cmd>` |
| Screenshot | `adb -s $TV exec-out screencap -p > shot.png` |
| UI dump (what is on screen) | `adb -s $TV exec-out uiautomator dump /dev/tty` |
| Press a key | `tools/tv shell input keyevent KEYCODE_DPAD_CENTER` (`HOME`, `BACK`, `DPAD_UP/DOWN/LEFT/RIGHT`) |
| Play a YouTube video by search | `tools/yt-play.sh "feid"` |
| Open the launcher | `tools/tv shell am start -n io.github.cpujp.wiilauncher/.MainActivity` |
| Build + install the launcher | `tools/deploy.sh` |
| Publish a new version | `tools/release.sh 1.1.0` |
| What is playing | `adb -s $TV shell dumpsys media_session \| grep -E "package=\|state="` (`state=3` = playing) |

`uiautomator dump` and `uiautomator events` pause other accessibility services while they run, so the Home redirect does not work during them.

Screenshots of a playing video come out black because of DRM. Use `dumpsys media_session` to check playback.

## 3. Cleanup (debloat)

`tools/cleanup.sh` **disables** packages and never uninstalls them. It also sets the animation scales to 0.5 and private DNS to `dns.adguard-dns.com`. Each disabled package is appended to `backup/disabled_by_us.txt`. Extra packages: `EXTRA_PKGS="pkg1 pkg2" tools/cleanup.sh`.

Default list: `com.tcl.esticker`, `com.tcl.exhibit`, `com.tcl.ocean.instructions`, `com.tcl.messagebox`, `com.tcl.waterfall.overseas`, `com.tcl.logkit`, `com.google.android.feedback`, `android.autoinstalls.config.google.gtvpai`, `com.google.android.apps.tv.dreamx`.

The script never touches Live TV, launchers, Play Services, keyboards and input services, vendor system services, or unknown packages.

## 4. Undo

| Change | Undo |
|---|---|
| Disabled package | `tools/tv shell pm enable --user 0 <pkg>` |
| Animations 0.5 | `tools/tv shell settings put global window_animation_scale 1.0` (same for `transition_animation_scale`; `settings delete global animator_duration_scale`) |
| Private DNS | `tools/tv shell settings delete global private_dns_mode && tools/tv shell settings delete global private_dns_specifier` |
| Launcher as Home (also revokes `WRITE_SECURE_SETTINGS`) | `tools/restore-home.sh` |
| Launcher installed | `tools/tv shell pm uninstall io.github.cpujp.wiilauncher` |
| TV Bro installed | `tools/tv shell pm uninstall com.phlox.tvwebbrowser` |
| **Everything** | `backup/undo.sh` (generated locally on first run, not in the repository) |
