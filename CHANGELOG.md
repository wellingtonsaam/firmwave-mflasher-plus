# Changelog

## v2.0.0 - FirmWave Rebrand & UX Redesign

M-Flasher + is now **FirmWave**. The project began as a fork of
[M-Flasher](https://github.com/jvitkauskas/m-flasher) by Julius Vitkauskas;
its author keeps M-Flasher on its own course, and the fork has grown its own
scope: the firmware library, a redesigned UX and the Android app. A name of
its own avoids mixing up who supports what.

The last state under the old name is kept as the tag
`v1.0.0-m-flasher-plus-baseline` (the app at 1.0.3, with its pending fixes),
and as the branch `archive/m-flasher-plus-baseline`.

### Identity

- New name, logo and icon: *FirmWave*, with a wave between solder pads (a
  wave and a circuit: firmware + wave). During the transition the logo says
  *formerly M-Flasher+*.
- Neo Brutalism look: cream background, white cards with black outlines and
  hard shadows, a lime LCD, yellow for the recommended next step, cyan for the
  active tab. Fonts: Lexend Mega, Public Sans and Space Mono.
- Dialogs and the operation log readable on light surfaces.
- The repository is `wellingtonsaam/firmwave-mflasher-plus`; the old address
  and the old site's links lead here.

### Names on each system

- Command and package `firmwave`; `FirmWave.app`
  (`io.github.wellingtonsaam.firmwave`) on macOS, `FirmWave.exe` on Windows.
- Linux: the package `firmwave` replaces `m-flasher-plus`; the udev rule is
  `70-firmwave.rules` (the old one still works).
- Android: the same app ID and signing key, so it updates M-Flasher + in
  place, with its backups; it shows as FirmWave. Version code 6.
- Environment variables `FIRMWAVE_*`; the old `MFLASHER_PLUS_*` still work in
  2.x.
- The firmware list is read with the User-Agent `firmwave/2.0.0`; the MIDI
  client is named FirmWave.

### Your files

- At the first start, `Documents/M-Flasher Plus` (backups, the log,
  `installed.json`) is copied to `Documents/FirmWave`. The old folder stays as
  it is. A file that is only in iCloud is not read then (it would wait for the
  download) and is copied at a later start, once it is on the computer.
- The firmware library adopts its old folder (`M-Flasher Plus/library`) the
  same way.

### Fixes

- The app no longer waits at start for backups that are only in iCloud.
- The Android app's Rust core builds again.

## 1.0.3 and earlier

Released as M-Flasher +: see the [releases](../../releases). Versions 1.0.0
and 1.0.1 were withdrawn for bugs.
