# How it works

The JieLi AC791N's mask ROM has a USB download mode (UBOOT1.00): the chip
shows up as a USB mass-storage device that takes vendor SCSI commands to
write its RAM and jump into it. M-Flasher writes a flash loader into RAM and
starts it; the loader then reads, erases and writes the SPI flash. The loader
comes from [m-bootloader](https://github.com/jvitkauskas/m-bootloader), which
also specifies its commands. If another tool's loader is already running,
M-Flasher asks for the unit to be switched off and on. The protocol follows
[jl-uboot-tool](https://github.com/kagaimiq/jl-uboot-tool) by kagaimiq.

The loader takes the flash's size from its ID once, when it starts, and without
one refuses every read. M-Flasher reuses a loader that is already running, so
it checks for that with a small read, and starts a new one in the other slot
once before giving up with the ID the chip now gives.

How the unit is reached differs by system:

- **Windows:** SCSI pass-through to the disk (`SCSI_PASS_THROUGH_DIRECT`),
  which needs administrator rights. The app runs as the user and starts a
  helper as administrator (UAC) once it finds a unit, passing it each
  command over a named pipe, as on macOS (`src/windows_helper.rs`).
- **Linux:** the SCSI generic driver (`SG_IO`), or libusb with
  `--features usb`.
- **macOS:** it does not pass such commands through, so the app takes the
  device from the disk driver and speaks USB mass storage (Bulk-Only
  Transport) itself, through libusb. That needs root: the window runs as the
  user and starts a small root helper through the system's password dialog
  (`src/macos.rs`), which does the USB transfers for it.

A unit that is running normally is switched to download mode with the soft
key, the SysEx `F0 22 24 35 7D F7` on its first MIDI port, which M-VAVE's
firmware and Felucca answer by restarting into the ROM's download mode. Linux
sends it through the kernel's sequencer interface (`/dev/snd/seq`), which
shares the port with whatever else uses it (PipeWire's MIDI bridge, browsers,
DAWs), with libc only, so the program still needs no ALSA library. Without a
sequencer it writes to the raw-MIDI device (port 0, as `amidi -p hw:N,0,0`),
which another program may hold: then it says so. Windows and macOS use
[midir](https://github.com/Boddlnagg/midir) (WinMM, CoreMIDI).

It is one native executable: the window (egui), the command line, the
flash loader, the m-bootloader builds and the fonts are all inside it.

## Source

| | |
| --- | --- |
| `src/formats.rs` | flash and package formats: CRCs, ENC, file tables, boot area, `.fwsc` |
| `src/boards.rs` | the models and how a flash image identifies them |
| `src/sectormap.rs` | the flash map: a region for each of the 256 sectors |
| `src/scsi.rs` | SCSI pass-through: Linux `SG_IO`, Windows `SCSI_PASS_THROUGH_DIRECT` |
| `src/usb.rs` | USB mass storage through libusb (macOS; Linux with `--features usb`) |
| `src/macos.rs` | macOS: the root helper, started through the system's password dialog |
| `src/jl.rs` | the download-mode protocol and starting the loader |
| `src/midi.rs` | the soft key: download mode over USB MIDI |
| `src/ops.rs` | the actions and their safety rules |
| `src/controller.rs` | the window's logic, in background threads |
| `src/ui.rs` | the window: the panel and the flash map |
| `src/wordmark.rs` | the striped "M-FLASHER" lettering |
| `src/cli.rs` | the command line, including `run-ram` |
| `data/` | the bundled flash loader and m-bootloader builds, from its [releases](https://github.com/jvitkauskas/m-bootloader/releases) ([NOTICE](../data/NOTICE.md)) |
| `assets/` | fonts and the window icon ([README](../assets/README.md)) |
| `linux/`, `windows/`, `macos/` | each system's package: icons, instructions, the udev rule, the disk image |
| `linux/package/` | the `.deb` and `.rpm`: nFPM's configuration, the launcher, AppStream metadata, the man page |

## Building

```sh
cargo build --release          # target/release/m-flasher-plus
cargo test --release           # FLASHER_TEST_DATA=DIR adds the tests with real flash images
```

The optional test data includes `fm1-016.fwsc`, the vendor's
[FM-1 016 package](https://yms-file-store.oss-cn-hongkong.aliyuncs.com/software/firmware/FM-1.fwsc)
(SHA-256 `d017fb65e70aca97ee29d944470c3515a74581df5c0576bf70de917c7b2b61d5`).
The URL may change with later releases. The tests check its three app files
(`app.bin`, `cfg_tool.bin`, `DX7.bin`), upgrades, reinstalls and downgrades,
and stock/m-bootloader replacement against simulated NOR flash. Synthetic
tests cover the new layout and boot configuration placement without this file.

FM-1 016's image ends at `0xA9000`; VM is at `0xBA000..0xDA000`, SEQ64 at
`0xDA000..0xEC000`, and BTIF at `0xEC000..0xED000`. Its larger stock bootloader
moves `isd_config.ini` from `0x38D0` to `0x3938`. Bootloader replacement reads
the configuration's address and length from the file table, retaining its
bytes and moving it within the boot area only when needed to fit a larger
bootloader. It refuses to move it over other data.

GitHub Actions (`.github/workflows/build.yml`) builds and tests the Linux,
Windows and macOS packages for every push, and publishes them as a release
for a `v*` tag; the `.deb` and `.rpm` are installed and run in clean
containers of the distributions they are for (`linux/package/test-install.sh`).
`linux/package/build.sh` makes them with [nFPM](https://nfpm.goreleaser.com). On a Mac, `macos/package.sh` makes the app and the disk image,
and `macos/render.sh` renders the icons and the disk image's background from
their SVGs.

### The Linux packages

Building the `.deb` and `.rpm` needs one tool beyond the Rust toolchain:
[nFPM](https://nfpm.goreleaser.com), a single binary with no dependencies of
its own. It is not needed to build or run M-Flasher itself, only to package it.

```sh
# the latest release into ~/.local/bin, no root needed
ver=$(curl -sS https://api.github.com/repos/goreleaser/nfpm/releases/latest \
    | grep -oP '"tag_name":\s*"\Kv[0-9.]+')
curl -sSL "https://github.com/goreleaser/nfpm/releases/download/$ver/nfpm_${ver#v}_Linux_x86_64.tar.gz" \
    | tar xz -C ~/.local/bin nfpm
```

Then, from a release build:

```sh
cargo build --release
sh linux/package/build.sh 1.0.0 amd64      # target/package/*.deb, *.rpm
```

`build.sh` takes the version and the architecture (`amd64` or `arm64`), and
`NFPM=/path/to/nfpm` overrides where it looks for the tool. The packages carry
the udev rule, the SCSI generic driver's `modules-load.d` file, the launcher
and its icon, so an installed package needs nothing else set up. To check one
on a machine or in a container, `linux/package/test-install.sh` installs it and
names every library that is missing.

### The Android app

The Android app (`android/`) is built without Gradle by `android/build.sh`: the
Rust core with [cargo-ndk](https://github.com/bbqsrc/cargo-ndk) into a shared
library, then the Java app with the SDK's own tools (aapt2, javac, d8,
zipalign, apksigner). It runs on Android 9 or newer (API 28), arm64 phones.

What it needs, as tested on macOS:

- [rustup](https://rustup.rs)'s Rust in `~/.cargo/bin`, with the Android target
  and cargo-ndk:
  ```sh
  rustup target add aarch64-linux-android
  cargo install cargo-ndk
  ```
- The [Android SDK](https://developer.android.com/studio#command-tools) at
  `~/Library/Android/sdk` (or `ANDROID_HOME`), with **build-tools 36.0.0**,
  **platforms;android-37.0** and an **NDK** (28 here):
  ```sh
  sdkmanager "build-tools;36.0.0" "platforms;android-37.0" "ndk;28.2.13676358"
  ```
- A JDK (`javac`, 17 or newer).

Then:

```sh
sh android/build.sh            # android/build/m-flasher-library.apk, signed with the debug key
adb install -r android/build/m-flasher-library.apk
```

The core is the same source as the desktop's: `android/rust/src/lib.rs` takes
`src/formats.rs`, `boards.rs`, `scsi.rs`, `jl.rs`, `ops.rs`, `library.rs` and
`sectormap.rs` by path, and `android/rust/src/bridge.rs` is their JNI side for
`android/app/src/.../Native.java`. The app reaches the unit through Android's
USB host API (`android/rust/src/usbfs.rs`), so it needs no root.

**Signing.** Without settings, the APK is signed with the debug key
(`~/.android/debug.keystore`, made if missing): for testing. `RELEASE=1 sh
android/build.sh` signs it with the release key instead, from
`~/Documents/M-Flasher + signing` (or `RELEASE_KEY_DIR`):
`m-flasher-plus-release.jks` (alias `m-flasher-plus`) and its password in
`m-flasher-plus-release.password`, read from the file and never put on the
command line. The release key is not in this repository. A phone with an APK
signed by one key refuses an APK signed by the other on top of it: to change
keys, the app has to be uninstalled first, and that deletes what it keeps
(backups included), so copy those off the phone before.

## Tested

FM-1s sold from late 2026 have a UCUN UC25HQ80 flash (`0xB36014`) in place of the
Puya P25Q80H (`0x856014`). Owners report backups and restores with M-Flasher on
it, and many report m-bootloader working on it, so M-Flasher no longer warns about
it. Early on, one unit did not start after m-bootloader 1.1 or 1.2 was installed,
and why is not known ([#13](https://github.com/jvitkauskas/m-flasher/issues/13)).

m-bootloader 1.3 and flash loader 1.3, on an SMK-37 Pro from Linux: installing
it, booting its firmware and stock 016, swapping with the older loader in both
directions, and installing stock firmware and restoring, ending byte-identical.
On an FM-1, the build just before it boots with both the 015 and 016 boot
configurations. Not yet tried on Windows or macOS, or with M-UPGRADE.

m-bootloader 2.0 and newer do not read the boot configuration, so installing them
keeps only the 34-byte chip-key record, and they may use the room up to there. 2.1 adds
the five settings 1.3 and older require, at `0x3FA0`, so that those start if installed
later; 2.0 had only the record, at `0x3FDC`. The ROM loads a full-size image on an FM-1
and an SMK-37. Build `2.4-395e6cc`, on an FM-1 and an SMK-37 Pro: installing it with M-Flasher, firmware with a flipped bit, an erased sector or no app at all caught each time with MIDI mode opening by itself, **Boot anyway** starting it once, and download mode asked for from MIDI mode. Build `2.3-6bc8f45`, on an SMK-37 Pro: its package from the stock bootloader through the firmware's update route, MIDI mode opening by itself and a web install from it, and installing with M-Flasher; on an FM-1, the same bootloader code at `0c24142`. Build `2.2-64f7c9c`, on the same two units: installing it, an official 016 update, its MIDI mode and RAM stage, and on the FM-1 M-UPGRADE. Build `2.1-861e43d`, on the same two units: 1.2 and 1.3 installed over it
start firmware, and it gives the same boot area over 2.0's configuration as over
stock's, and through M-UPGRADE. Build `2.0-c43a32f`, the same bootloader code: installing with M-Flasher over 1.3 and with
M-UPGRADE over 1.2, stock firmware, official 016 updates and Baud Girl's installer,
which restore the full configuration, and browser installers from running firmware
and from MIDI mode, which leave the boot area alone. On the FM-1 also M-UPGRADE from
the stock bootloader, giving the same boot area as over 1.2, and on macOS and Windows,
putting stock back and installing it again. An SMK-37 on firmware 900 refuses
M-VAVE's update at `FAILED -5`, and M-UPGRADE refuses an equal version, so its 016
update there used a copy of the package labelled `099`. The stock bootloader reads the
keys, so it goes back with its package's whole boot area. Older M-Flasher versions
keep the unit's configuration when putting it back, so do not use them for that
on such a unit.

On an SMK-37 Pro, from Linux, Windows and macOS (an M3 Mac): identifying
stock firmware, Baud Girl and Snoop37; backups (identical to jl-uboot-tool's
reads); installing m-bootloader; installing stock firmware over Baud
Girl and over Snoop37; restoring a backup (the flash afterwards identical to
it). On an FM-1, from Linux: backups (two reads identical), installing m-bootloader and putting
the stock bootloader back, restoring, and the unit's screen during operations.

Download mode over USB MIDI, from Linux: on an SMK-37 Pro with the stock
bootloader and firmware 016 (switched, then read and checked), and on an
FM-1. The soft key itself was seen to work, with `amidi`, on the stock
firmware of the FM-1 (015), the SMK-37 Pro and the SMK-37 Elite (016).
Windows and macOS not yet tried.

FM-1 016 compatibility has been checked with the vendor package and simulated
flash, and installing 016 with M-Flasher has been tested on a unit.

Fame MiniFM support comes from comparing its
[`MiniFM_016` package](https://images.musicstore.de/assets/downloads/fame/Fame-Mini-FM/MiniFM.zip)
(`MiniFM_016.fwsc`, SHA-256
`f10b297b3f0e1789110ba1832b12865b7fc39d5d6e0e7c9a375461339268ca77`) with FM-1 016:
the same chip key, region addresses and boot area, and the soft key handled by
the same code. Cross-flashing has been tested with both packages against
simulated flash only, **not yet on a MiniFM**, and the soft key on its firmware
015 is unconfirmed. The optional test data can include it as `minifm-016.fwsc`.
