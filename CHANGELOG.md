# Changelog

All notable changes are listed here. Format: [Keep a Changelog](https://keepachangelog.com/), versions: [SemVer](https://semver.org/).

## [1.1.0] - 2026-10-22

### Added
- **Hand cursor**: an original pointing hand that glides to the focused channel or button, which is outlined in blue.
- **Folders**: *Add to a folder* creates a folder in the app's place or adds the app to an existing one. A folder tile shows a 2×2 mosaic of its apps. Inside a folder you can reorder apps or take them out; on a folder tile you can rename or ungroup it. Back leaves the folder.
- **SD card button** beside settings: it opens the update menu. That menu shows the installed and GitHub versions and release notes, can check now and install, and switches the theme. The SD card pulses with a red dot when a release is newer.
- **Light and dark themes**: Automatic (follows the system), Light and Dark.
- **Mail button** opens the TV's input picker (HDMI, TV, AirPlay…). On TCL it opens the same panel as the remote's input key; on other TVs it falls back to Settings.

### Changed
- Layout closer to the Wii Menu: four channel columns edge to edge, page arrows on the screen edges, the clock and date (`Lun. 15-03`) inside the bottom bar, and a bar that rises around the round buttons.

## [1.0.3] - 2026-10-22

### Added
- The menu replaces the stock Google TV home whenever that home appears: at boot, on Back out of an app, or from vendor shortcuts. Other stock-launcher screens, such as profiles, stay usable.
- `HomeGuard` turns the Home-key service back on when Android drops it, for example after a force-stop. It runs when the menu opens, at boot and after an update. It needs `WRITE_SECURE_SETTINGS`, which `tools/setup-tv.sh` grants once over ADB.

## [1.0.2] - 2026-10-22

### Added
- The menu opens on its own after the TV boots. The stock Google TV home starts first, and the menu replaces it as soon as the Home-key service connects.

## [1.0.1] - 2026-10-22

### Fixed
- Graphics memory grew without limit (262 MB PSS). The animated shadow radius made a new shadow texture on every frame. The focus glow now uses translucent strokes, banner corners are rounded once at load time, and the glow redraws at about 15 fps. Memory is now stable at about 60 MB PSS (stock Google TV launcher: about 100 MB).

## [1.0.0] - 2026-10-22

### Added
- Channel grid (4×3 per page) with TV banners, app icons, focus glow and page flip at the grid edges.
- Channel preview with zoom animation and **Menu / Start** buttons.
- Bottom bar with clock, date, page dots, a settings button and a mail button that blinks when an update is available.
- Synthesized UI sounds (no audio assets).
- Channel reordering and app info from the options menu (Menu key or long-press OK).
- In-app updater from GitHub Releases, through the system installer.
- Home key routing for Google TV through an accessibility service.
- Resolution-independent rendering: a virtual 1920×1080 canvas scaled to the real surface, sharp at 4K.
- ADB tools: command monitor, debloat with undo, deploy, release and a YouTube playback helper.
