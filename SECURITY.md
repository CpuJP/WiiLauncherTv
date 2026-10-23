# Security

## Reporting a vulnerability

Open a [GitHub security advisory](https://github.com/CpuJP/WiiLauncherTv/security/advisories/new) rather than a public issue.

## Update channel

- The launcher checks `https://api.github.com/repos/CpuJP/WiiLauncherTv/releases/latest` at most every 6 hours.
- It only accepts APK assets whose URL starts with `https://github.com/CpuJP/WiiLauncherTv/releases/download/`.
- Installation goes through the Android system installer, which asks for confirmation on the TV and refuses any APK not signed with the same key as the installed app.

## Signing key

Releases are signed with `signing/sideload.jks`. **This key is public on purpose**, so anyone can build an APK that updates their own install. Because the key is public, the signature only shows that an APK comes from *some* build of this project. Trust comes from the download source (this repository's releases), not from the key.

To use a private key, set the `WII_KEYSTORE`, `WII_KEYSTORE_PASSWORD`, `WII_KEY_ALIAS` and `WII_KEY_PASSWORD` environment variables when building (for example as GitHub Actions secrets). Switching keys requires uninstalling the old build once.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Checks this repository for updates. |
| `REQUEST_INSTALL_PACKAGES` | Hands a downloaded update to the system installer. |
| `WRITE_SECURE_SETTINGS` (granted only over ADB) | Lets `HomeGuard` add `HomeKeyService` back to the enabled accessibility services if Android removes it. It writes only that setting and `accessibility_enabled`. Revoke it with `adb shell pm revoke io.github.cpujp.wiilauncher android.permission.WRITE_SECURE_SETTINGS`. |
| `RECEIVE_BOOT_COMPLETED` | Runs `HomeGuard` at boot. |
| Accessibility service (`HomeKeyService`) | Google TV ignores the default-home setting, so the Home key is intercepted to open this launcher. The service filters key events and receives only window-change events from the stock launcher (`packageNames` filter), so it can replace the stock home when that home appears. It cannot read window content (`canRetrieveWindowContent=false`) and stores nothing. |
