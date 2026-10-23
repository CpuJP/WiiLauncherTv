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
| Accessibility service (`HomeKeyService`) | Google TV ignores the default-home setting, so the Home key is intercepted to open this launcher. The service only filters key events: it requests no window content and stores nothing. |
