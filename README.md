# M-Flasher +

**Free for Windows, macOS, Linux and Android.** Download it from the
[releases](../../releases).

Back up, restore and reinstall the firmware of an **M-VAVE FM-1** (or its
rebranded twin, the **Fame MiniFM**) or **SMK-37 Pro / Elite** over USB, with
a **built-in firmware library** and a safer way to install. It talks to the
chip's built-in USB download mode, so it also works when the firmware on the
unit is broken.

M-Flasher + is [M-Flasher](https://github.com/jvitkauskas/m-flasher) by Julius
Vitkauskas, extended by [Wellington Saam](https://github.com/wellingtonsaam).

> Unofficial: not affiliated with M-VAVE or JieLi. It saves a backup before it
> writes anything and checks every write, but use it at your own risk.

## Why M-Flasher +

M-Flasher is ingenious and reliable: it reaches the chip's download mode, reads
and writes the flash safely, and verifies everything. Two things were missing:

- **A firmware library inside the app.** Finding a firmware meant searching
  forums and releases, downloading a package and choosing the file by hand.
  M-Flasher + lists the community firmwares, downloads them from their
  authors' releases, checks each one by SHA-256 and keeps it on your computer
  or phone, so it installs **offline** too.
- **A design that guides the user.** The window puts every action on one
  screen, for every situation at once. As a Product Designer, I mapped the use
  cases of the app (no unit, a unit to check, one to protect, one to flash)
  and am rebuilding the Device tab around them: **check the FM-1 first,
  protect it, then operate**.

## REQ-BOOT-01: m-bootloader before a firmware

**M-Flasher + never installs a firmware on an FM-1 that has no way back.**

An FM-1 with M-VAVE's original bootloader has one weakness: if a new firmware
does not start, its keys can't reach download mode any more, and only a
USB_KEY dongle can rescue it. [m-bootloader](https://github.com/jvitkauskas/m-bootloader)
fixes that: with it, holding **REC** while switching on always opens download
mode, whatever the firmware does.

So, on a unit with the original bootloader, installing a firmware (from a file
or from the library) happens in **two reviewed steps**: m-bootloader first,
then the firmware. Each step is backed up first and verified. The first step
can be skipped, with a clear warning.

![REQ-BOOT-01: install a firmware, check the FM-1, then by its bootloader: the original one gets m-bootloader first in two steps, m-bootloader goes straight to the firmware; then the firmware is written and the FM-1 restarts](docs/img/req-boot-01-flow.png)

## What's new in M-Flasher +

- **Firmware library** (LIBRARY tab): the community firmwares listed by
  [The Firmware Hub](https://thefirmwarehub.com), with search, favorites,
  betas and **This unit only** (hides what can't go on your FM-1, such as a
  firmware for the older flash chip). **GET** downloads and checks by
  SHA-256; **INSTALL** plans it and opens its review.
- **REQ-BOOT-01**: m-bootloader before a firmware, as above.
- **SAFETY CHECK**: recovery, backup, firmware and chip, at a glance.
- **No keys to remember**: INSTALL (and READ UNIT on Android) switches a
  running unit to download mode by a MIDI message. If its firmware does not
  know it, the app says which keys do it on *that* unit, and carries on when
  it shows up.
- **Restart after writing**: the FM-1 leaves download mode by itself and the
  app tells you when the new firmware is running.
- **Android app**: the same core on a phone, over a USB OTG cable: read the
  unit, back up, install from the library, restore.
- **In development**: the redesigned Device tab, with **CHECK FM1 STATUS**
  and **INSTALL M-BOOTLOADER** first, and each operation's form shown only
  when it is chosen.

## Getting started

1. Download M-Flasher + from the [releases](../../releases): the desktop app
   for your system, or the Android APK. The apps are not signed, so the first
   start needs one extra confirmation.
2. Connect the FM-1 with a USB **data** cable (on a phone: a USB OTG adapter)
   and switch it on.
3. Pick a firmware in the **LIBRARY** tab and press **INSTALL**: M-Flasher +
   switches the FM-1 to download mode, reads it and opens the review. Nothing
   is written until you confirm. To start with a backup, press **DOWNLOAD
   MODE** (desktop) or **READ UNIT** (Android), then save one.

## More

- [Using M-Flasher](docs/usage.md): each system, download mode, the actions,
  the firmware library, the command line
- [Safety](docs/safety.md): what is checked before and after every write,
  REQ-BOOT-01
- [How it works](docs/development.md): the protocol, the source, building

## Privacy

M-Flasher + talks to the unit over USB. It goes online only for the firmware
library: it reads The Firmware Hub's public list (at most once a day) and
downloads the firmware you choose from its author's release. Nothing about you
or your unit is sent.

## Credits and license

MIT ([LICENSE](LICENSE)).

- **M-Flasher** by [Julius Vitkauskas](https://github.com/jvitkauskas): the
  app this is built on. If it helped you, support his work:
  [GitHub Sponsors](https://github.com/sponsors/jvitkauskas) ·
  [Ko-fi](https://ko-fi.com/jvitkauskas).
- **M-Flasher +** by [Wellington Saam](https://github.com/wellingtonsaam): the
  firmware library, REQ-BOOT-01, the use cases of the redesign and the
  Android app.
- [m-bootloader](https://github.com/jvitkauskas/m-bootloader) (MIT): the
  bootloader and the flash loader.
- Firmware list: [The Firmware Hub](https://thefirmwarehub.com) (CC BY 4.0);
  each firmware belongs to its author, under its own license.
- Bundled files and their licenses: [data/NOTICE.md](data/NOTICE.md) and
  [assets/README.md](assets/README.md). The protocol follows
  [jl-uboot-tool](https://github.com/kagaimiq/jl-uboot-tool) by kagaimiq, and
  the safety rules those of
  [MvaveFM1Unbricker](https://github.com/Quixotic7/MvaveFM1Unbricker) and
  [FM-1 Transporter](https://github.com/kurogedelic/FM-1-transporter).
