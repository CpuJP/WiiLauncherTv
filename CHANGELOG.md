# Changelog

All notable changes are listed here. Format: [Keep a Changelog](https://keepachangelog.com/), versions: [SemVer](https://semver.org/).

## [1.3.0] - 2026-10-22

### Added
- **Input list in the Wii settings style**:
  - The mail button lists the TV's physical inputs (TV, HDMI 1, HDMI 2, HDMI 3…) by their names on the TV.
  - The list uses the Wii look: dark striped screen, title tab, white pills with orange selection corners, Back / OK.
  - Picking an input switches to it through the standard `TvContract` passthrough intent.
  - App-provided inputs such as IPTV are left out.
- **Folder management in the same style**:
  - Open, Move, Rename, Take apps out (pick which) and Delete folder, with a confirmation step.
  - These options also work from inside the folder. A Wii-style text screen is used for names.
- **Channel preview**: blue arrows on both sides switch to the previous or next channel without leaving the preview. The buttons sit on the warm gray rounded panel.

### Changed
- Wii Remote hand redrawn: white with a thick black outline, a blue palm and a white "1"; the fist is white with a blue "1".
- While dragging, the other channels fade back and the grabbed channel becomes a small glossy blue box under the fist.
- Channel frames have soft, continuous corners and a white inner rim.
- Clock digits are single hollow glyphs, as on the Wii Menu.
- The left button shows a settings gear.
- Page and list arrows are glossy blue triangles.

### Fixed
- Smooth scrolling with the new frames: the frames and the hand are pre-rendered at screen resolution. The first build of the soft corners dropped about half of the frames; the pre-rendered version drops about 4%.

## [1.2.1] - 2026-10-22

### Fixed
- Fewer dropped frames: the arrow and SD card gradients and the italic font were created on every frame. They are now created once.

## [1.2.0] - 2026-10-22

### Changed
- Layout measured from a 1920×1080 capture of the Wii Menu:
  - 382×208 channels, with the next page peeking in at the right edge.
  - Outlined blue page arrows.
  - A bar with a dip in the middle; the clock sits in the dip and the date sits on the bar.
  - Round **Wii** and mail buttons in recessed wells, and the SD card icon.
- Clock: hollow segmented digits with a blinking colon and AM/PM.
- Hand cursor: Wii Remote style, white with a black outline, pale blue toward the wrist and a blue "1". It closes into a fist while OK is held or a channel is being dragged.
- Channel names no longer pop up on hover, as on the Wii.
- Preview buttons read **Menú Wii / Comenzar**, and the SD button opens the **Menú tarjeta SD**.
- The light theme is the default.

### Added
- Dragging channels: hold OK (or choose *Move* from ☰) to pick up a channel. Move it with the D-pad, across pages too, and drop it with OK.

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
