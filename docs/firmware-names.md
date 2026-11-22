# Firmware names: how to make other apps show yours

For makers of FM-1 / MiniFM / SMK-37 firmware. Most apps (M-Flasher, The
Firmware Hub's web installer, backup lists) do not read a name from your
firmware. They find an **identity** in it and look that up. M-Flasher + also
reads a name the firmware gives itself, the `FWNAME=` line (section 2).
When the lookup fails, the user sees `FM-1 902` instead of your firmware's
name, in the device panel and in the backup list. A backup named after the
wrong firmware, or after none, is one the user may not find when they need to
restore it.

This page says what each app reads, and what to do so it shows your name.

## 1. What the apps read

### The identity

Every package carries an identity such as `FM-1_902`: the model, an
underscore, a number. The firmware reports it when it runs, and its text is
inside `app.bin`.

- **M-Flasher and M-Flasher +** read `app.bin` from the flash and look for
  `MODEL_` followed by **three digits**. The models they know are `FM-1`,
  `MiniFM`, `SMK-37 Pro`, `SMK-37 Elite` and `SMK-37+VA` (`find_identities` in
  `src/boards.rs`). The panel then shows `FM-1 902`.
- **The Firmware Hub** compares the identity that the unit reports with
  `product` (exact match) and `ident` (a pattern) in its list,
  [catalog.json](https://thefirmwarehub.com/catalog.json). One match gives
  your name. Two or more give "X or Y", or "one of N firmwares".

### Names written in the code

M-Flasher has only a few names written in its code:

- it shows `SLOOP x.y` if `app.bin` contains `SLOOP ` and a version;
- it shows `Felucca` if `app.bin` contains `Felucca` anywhere;
- it shows `(Baud Girl)` for Baud Girl's layout;
- it adds `(stock)` to an identity whose number is not `900` or `000` when
  only one identity is found.

Every other firmware is shown by its identity.

### The name in M-Flasher +

M-Flasher + knows a firmware by name in two cases:

1. **It installed the firmware from its library.** It keeps a fingerprint of
   the written sectors in `installed.json`, next to the backups. When the
   flash matches, the name comes back.
2. **A package downloaded into its library matches the flash.** The package
   is downloaded from your release, and its SHA-256 is checked against the
   hub's list.

When it knows the name, it puts it into the backup's file name
(`fm1-zp12-2.5-20261116-153448.bin`) and into the `firmware name` field of
the `.json` next to it. The backup list shows `zp12 2.5 · FM-1 975`.

A firmware that is not in the hub's list, or that was installed by another
tool, falls back to the identity: `fm1-20261121-214754.bin`,
`FM-1 902`.

## 2. What to do

### Give your firmware its own name: `FWNAME=`

The best place for your firmware's name is the firmware itself. Put one line in
your code, kept in `app.bin`:

```c
/* the name and the version, NUL-ended, 48 characters at most */
__attribute__((used)) static const char fw_name_tag[] = "FWNAME=" "My Firmware" " " APP_VER;
```

- M-Flasher + reads it from the flash before anything else, whatever tool
  installed the firmware: the panel shows `My Firmware 0.3 · FM-1 9NN`, and a
  backup is called `fm1-my-firmware-0.3-<date>.bin`.
- Printable ASCII only, one `FWNAME=` in `app.bin`, ended by a NUL byte.
- Build it from the same version the firmware reports (here `APP_VER`), so the
  name always says what runs.
- It does not replace the identity: installers still check `MODEL_NNN`.

### Choose an identity no one else uses

- Before you build, look at `firmwares[].product` and `reserved[].ident` in
  the hub's `catalog.json`. Two firmwares with the same identity look like
  one: the hub cannot tell them apart, and neither can a backup list.
  `FM-1_900` alone is reported by about ten firmwares.
- Use **three digits**, preferably `9NN`. M-Flasher reads only three digits.
  `FM-1_9010300` is read as `FM-1 901`, which is another firmware's
  (reserved) number. The web installers also finish an interrupted install
  only for the update loaders they know, which are `FM-1_9NN`.
- Keep the form `MODEL_NNN`. Installers take the model from the part before
  the underscore, so a name in its place (`PocketTutor_902`) makes your
  package refuse to install.
- Put the identity in one place only (for example `PRODUCT` in your build
  script), and build the update loader and the app from it.

### Don't carry another firmware's name

If you started from Felucca or SLOOP, remove the strings `Felucca` and
`SLOOP ` from what goes into `app.bin`: about screens, logs, USB names.
M-Flasher matches them before it looks at the identity, so your fork would be
shown as Felucca or SLOOP. The update loader's own name, such as
"Felucca Update", is outside `app.bin` and does not matter here.

### Get into the hub's list

The hub's list is what gives your identity a name, both on the hub and in
M-Flasher +. To be listed:

- a public repository under GPL-3.0, with `fmhub.json` at its root
  (`what`, `author`, `kinds`, `image`, `color`, `sounds`);
- a **GitHub release with the `.fwsc` attached**;
- the request through the hub's *"Made an open-source firmware?"* card.

The hub checks your repository again every night, so later changes can turn
the request red. In particular:

- **Every tag needs a release with a `.fwsc`.** A tag pushed only as a
  bookmark (`baseline-…`, `wip`, `backup`) can be read as your newest
  release, with no package: "no release with a .fwsc". Keep such bookmarks
  as branches, or delete the tag.
- Keep the release asset named after your firmware and its version, for
  example `my-firmware-0.2.fwsc`.
- After you fix something, press **Check again** on your request's page.

### Keep the USB and MIDI names consistent

Some web tools find the unit by its USB/MIDI port name. Name the port after
your firmware, for example "FM1 Pocket Tutor".

On macOS, CoreMIDI remembers a device by USB vendor and product ID, together
with the first name it saw. A unit that once ran another firmware with the
same IDs can keep that old name (for example "Caravel") until the entry is
renamed or removed in **Audio MIDI Setup**.

## 3. Checklist before a release

- [ ] `FWNAME=<name> <version>` in `app.bin`, NUL-ended.
- [ ] Identity `MODEL_9NN`, not in `catalog.json` (`product`, `reserved`).
- [ ] `app.bin` holds only your own identity: no `Felucca`, no `SLOOP `.
- [ ] GitHub release with the `.fwsc` attached, and no tag without a release.
- [ ] `fmhub.json` complete; the request on the hub is green.
- [ ] After installing, the panel of M-Flasher + shows your name, and a new
      backup is called `fm1-<your-name>-<version>-<date>.bin`.

## 4. For tool makers

To show the same name everywhere, a tool can do what M-Flasher + does: look
for `FWNAME=` in `app.bin` first; else read the hub's `catalog.json` (at most once a day, with a User-Agent that names the
tool), map the identity to `name` through `product` and `ident`, and use that
name in the panel, in the backup file name and in its `.json`. When an
identity has more than one match, show all the names (or the identity), never
just one of them.
