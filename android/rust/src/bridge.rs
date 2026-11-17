//! The JNI side of io.github.wellingtonsaam.mflasherplus.Native.
//!
//! Read only so far. `diagnose` starts the flash loader, reads the whole
//! flash (checked against the chip's CRC32) and returns what the unit holds
//! and its safety checks; `backup` does the same and saves a backup with its
//! .json; `planInstall` plans installing a saved firmware for review;
//! `install` writes the reviewed plan, as the desktop does.

use crate::boards::{BootKind, CHIP_KEY, flash_tested, flash_text};
use crate::formats::AppStatus;
use crate::library::{Http, InstallOptions, Library, bootloader_step, unit_chip};
use crate::jl::Flash;
use crate::{jl, ops, usbfs};
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use std::path::PathBuf;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU32, Ordering};

static LOG: Mutex<Vec<String>> = Mutex::new(Vec::new());
/// Read progress, in percent.
static PROGRESS: AtomicU32 = AtomicU32::new(0);

fn log(s: String) {
    if let Ok(mut l) = LOG.lock() {
        l.push(s);
    }
}

type Unit = ops::Session<jl::Connection<usbfs::Device>>;

/// The unit read on this connection (its file descriptor), kept as the desktop
/// keeps its session: planning and writing start from it instead of starting
/// the loader and reading the whole flash again. Dropped when the unit goes
/// (`close`), after a failed write, and on READ UNIT.
static UNIT: Mutex<Option<(i32, Unit)>> = Mutex::new(None);

/// Runs `f` on the unit of this connection, read first if it is not kept yet.
fn with_unit<R>(fd: i32, iface: u8, ep_in: u8, ep_out: u8, in_packet: usize, dir: &str, fresh: bool,
                f: impl FnOnce(&mut Unit) -> Result<R, String>) -> Result<R, String> {
    let mut kept = UNIT.lock().map_err(|_| "the unit is busy".to_string())?;
    if fresh || !matches!(&*kept, Some((f0, _)) if *f0 == fd) {
        *kept = None;
        *kept = Some((fd, open(fd, iface, ep_in, ep_out, in_packet, dir)?));
    }
    let r = f(&mut kept.as_mut().unwrap().1);
    if r.is_err() {
        // what the unit holds is no longer certain: read it again next time
        *kept = None;
    }
    r
}

fn open(fd: i32, iface: u8, ep_in: u8, ep_out: u8, in_packet: usize, dir: &str)
        -> Result<ops::Session<jl::Connection<usbfs::Device>>, String> {
    PROGRESS.store(0, Ordering::Relaxed);
    let dev = usbfs::Device::new(fd, iface, ep_in, ep_out, in_packet);
    let conn = jl::Connection::start("usb", dev, &mut log).map_err(|e| e.to_string())?;
    let session = ops::Session::open(conn, PathBuf::from(dir), &mut log, &mut |done, total| {
        PROGRESS.store((done * 100 / total.max(1)) as u32, Ordering::Relaxed);
    })
    .map_err(|e| e.to_string())?;
    Ok(session)
}

/// What the unit holds, one fact per line for the Java side:
/// `INFO|key|value`, `CHECK|name|1 or 0|detail|action`, `EVIDENCE|* or -|why`.
/// The checks are the desktop's SAFETY CHECK (REQ-BOOT-01 first); BACKUP is
/// the app's own (it knows which units it backed up).
fn report<F: jl::Flash>(s: &ops::Session<F>) -> Vec<String> {
    let clean = |t: &str| t.replace(['|', '\n'], " ");
    let mut out: Vec<String> = s.info().into_iter().map(|(k, v)| format!("INFO|{k}|{}", clean(&v))).collect();
    let smk = s.board().is_some_and(|b| b.name.contains("SMK"));
    let (rec, menu) = if smk { ("SEQ REC", "SEQ PLAY") } else { ("REC", "PLAY") };
    let (recovery_ok, recovery, action) = match s.analysis.bootloader {
        BootKind::Open => (true, format!("m-bootloader · {rec}: download mode · {menu}: menu"), ""),
        BootKind::Stock => (false, "stock bootloader: no recovery from the keys".to_string(),
                            if s.bootloader_suggestion().is_some() { "INSTALL" } else { "" }),
        _ => (false, format!("bootloader {}", s.analysis.bootloader_text), ""),
    };
    out.push(format!("CHECK|RECOVERY|{}|{}|{action}", recovery_ok as u8, clean(&recovery)));
    let app_ok = s.analysis.app_status == AppStatus::Ok;
    let fw = if app_ok { format!("{} · checks OK", s.analysis.firmware_text) }
             else { format!("{}: reinstall it from LIBRARY", s.analysis.app_status.text()) };
    out.push(format!("CHECK|FIRMWARE|{}|{}|", app_ok as u8, clean(&fw)));
    let (key, id) = (s.dev.chip_key(), s.dev.flash_id());
    let tested = flash_tested(id);
    let chip = format!("chip key 0x{key:04X}{} · flash {}{}", if key == CHIP_KEY { "" } else { " damaged" },
                       flash_text(id), if tested { "" } else { ": untested with m-bootloader" });
    out.push(format!("CHECK|CHIP|{}|{}|", (key == CHIP_KEY && tested) as u8, clean(&chip)));
    // the library's "This unit only": "old" firmwares never go on the newer chip
    out.push(format!("UNIT|chip|{}", crate::library::unit_chip(id).unwrap_or("")));
    for (_, strong, why) in &s.analysis.evidence {
        out.push(format!("EVIDENCE|{}|{}", if *strong { "*" } else { "-" }, clean(why)));
    }
    out
}

/// Reads the unit and says what it holds and how safe it is. Read only.
fn diagnose(fd: i32, iface: u8, ep_in: u8, ep_out: u8, in_packet: usize, dir: &str) -> Result<String, String> {
    with_unit(fd, iface, ep_in, ep_out, in_packet, dir, true, |s| {
        s.show_on_unit();
        Ok(report(s).join("\n"))
    })
}

fn backup(fd: i32, iface: u8, ep_in: u8, ep_out: u8, in_packet: usize, dir: &str) -> Result<String, String> {
    with_unit(fd, iface, ep_in, ep_out, in_packet, dir, false, |s| {
        let path = s.save_backup(None).map_err(|e| e.to_string())?;
        let mut out = report(s);
        out.push(format!("BACKUP|{}", path.display()));
        Ok(out.join("\n"))
    })
}

/// Sectors' span, as the desktop shows it: "0x04000-0x82FFF".
fn span(p: &ops::Plan) -> String {
    match (p.sectors.iter().min(), p.sectors.iter().max()) {
        (Some(a), Some(b)) => format!("0x{a:05X}-0x{:05X}", b + 0xFFF),
        _ => "nothing to write".into(),
    }
}

/// Reads the unit and plans installing a saved firmware, as the desktop does:
/// the chip is checked, m-bootloader comes first on a stock unit (REQ-BOOT-01).
/// Adds to diagnose's lines `BOOT|sectors|span|why` (step 1, if any),
/// `PLAN|name|version|sectors|span|title`, `PNOTE|..`, `PWARN|..`. Nothing is written.
fn plan_install(fd: i32, iface: u8, ep_in: u8, ep_out: u8, in_packet: usize, dir: &str, lib_dir: &str, id: &str)
        -> Result<String, String> {
    let lib = Library::new(PathBuf::from(lib_dir));
    let inst = lib.installable(id, &Http::new())?;
    with_unit(fd, iface, ep_in, ep_out, in_packet, dir, false, |session| {
    if !inst.fits_chip(unit_chip(session.dev.flash_id())) {
        return Err(format!("{} is marked for the FM-1's older flash chip (0x856014) only; this unit has the newer one",
                           inst.name));
    }
    let mut out = report(session);
    if let Some((boot, why)) = bootloader_step(session, &inst, &InstallOptions::default()).map_err(|e| e.to_string())? {
        let version = ops::bundled_bootloader(crate::boards::BOARDS[1]).map(|(_, v)| v).unwrap_or_default();
        out.push(format!("BOOT|{}|{}|{}|{version}", boot.sectors.len(), span(&boot), why.replace(['|', '\n'], " ")));
    }
    let fw = session.plan_firmware(&inst.data).map_err(|e| e.to_string())?;
    let clean = |t: &str| t.replace(['|', '\n'], " ");
    out.push(format!("PLAN|{}|{}|{}|{}|{}", clean(&inst.name), clean(&inst.version), fw.sectors.len(), span(&fw),
                     clean(&fw.title)));
    out.extend(fw.notes.iter().map(|n| format!("PNOTE|{}", clean(n))));
    out.extend(fw.warnings.iter().map(|w| format!("PWARN|{}", clean(w))));
    Ok(out.join("\n"))
    })
}

/// Installs a saved firmware, or with `boot` the m-bootloader step before it
/// (REQ-BOOT-01): the plan the user reviewed, made again on what the unit holds
/// now, then written by the core as on the desktop: a full backup first, every
/// run verified by the chip's CRC32 (one retry), stopped with the backup's
/// path if it still differs. Returns diagnose's lines, `DONE|sectors|span`
/// and `BACKUP|path`.
fn install(fd: i32, iface: u8, ep_in: u8, ep_out: u8, in_packet: usize, dir: &str, lib_dir: &str, id: &str, boot: bool)
        -> Result<String, String> {
    let lib = Library::new(PathBuf::from(lib_dir));
    let inst = lib.installable(id, &Http::new())?;
    with_unit(fd, iface, ep_in, ep_out, in_packet, dir, false, |session| {
    if !inst.fits_chip(unit_chip(session.dev.flash_id())) {
        return Err(format!("{} is marked for the FM-1's older flash chip (0x856014) only; this unit has the newer one",
                           inst.name));
    }
    let plan = if boot {
        bootloader_step(session, &inst, &InstallOptions::default()).map_err(|e| e.to_string())?
            .ok_or("this unit needs no m-bootloader step")?.0
    } else {
        session.plan_firmware(&inst.data).map_err(|e| e.to_string())?
    };
    let out = write(session, &plan)?;
    if !boot {
        remember(session, &plan, dir, &format!("{} {}", inst.name, inst.version));
    }
    Ok(out)
    })
}

/// Plans putting back a backup saved on this phone, as the desktop does: the
/// file is checked against the SHA-256 in its .json first. `PLAN|file|saved|sectors|span|title`,
/// `PNOTE|..`, `PWARN|..`, and `RBOOT|the backup's bootloader`. Nothing is written.
fn plan_restore(fd: i32, iface: u8, ep_in: u8, ep_out: u8, in_packet: usize, dir: &str, file: &str) -> Result<String, String> {
    with_unit(fd, iface, ep_in, ep_out, in_packet, dir, false, |s| {
        let (plan, saved) = restore_plan(s, file)?;
        let clean = |t: &str| t.replace(['|', '\n'], " ");
        let name = std::path::Path::new(file).file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let mut out = report(s);
        out.push(format!("PLAN|{}|{}|{}|{}|{}", clean(&name), clean(&saved), plan.sectors.len(), span(&plan), clean(&plan.title)));
        out.extend(plan.notes.iter().map(|n| format!("PNOTE|{}", clean(n))));
        out.extend(plan.warnings.iter().map(|w| format!("PWARN|{}", clean(w))));
        let backup = crate::boards::Analysis::new(&std::fs::read(file).map_err(|e| e.to_string())?, None, CHIP_KEY);
        out.push(format!("RBOOT|{}", clean(&backup.bootloader_text)));
        Ok(out.join("\n"))
    })
}

/// The backup's restore plan on what the unit holds, and when it was saved.
fn restore_plan(s: &Unit, file: &str) -> Result<(ops::Plan, String), String> {
    let path = std::path::Path::new(file);
    let data = std::fs::read(path).map_err(|e| format!("cannot read {file}: {e}"))?;
    ops::check_backup_file(path, &data).map_err(|e| e.to_string())?;
    let saved = std::fs::read_to_string(path.with_extension("json")).ok()
        .and_then(|j| j.split("\"saved\": \"").nth(1).and_then(|r| r.split('"').next()).map(str::to_string))
        .unwrap_or_default();
    let plan = s.plan_restore(&data, ops::backup_unit_id(path).as_deref(), false).map_err(|e| e.to_string())?;
    Ok((plan, saved))
}

/// Puts back a backup after the user reviewed it: a backup of what the unit
/// holds now first, every run verified by the chip's CRC32.
fn restore(fd: i32, iface: u8, ep_in: u8, ep_out: u8, in_packet: usize, dir: &str, file: &str) -> Result<String, String> {
    with_unit(fd, iface, ep_in, ep_out, in_packet, dir, false, |session| {
        let (plan, _) = restore_plan(session, file)?;
        write(session, &plan)
    })
}

/// Writes a reviewed plan: a full backup first, then the core's apply.
fn write(session: &mut Unit, plan: &ops::Plan) -> Result<String, String> {
    let mut sectors = plan.sectors.clone();
    sectors.sort_unstable();
    let total = sectors.len().max(1);
    PROGRESS.store(0, Ordering::Relaxed);
    // every write gets its own backup, taken from the image just read
    session.backup_path = None;
    session.apply(plan, &mut |e| {
        if let ops::Event::Write(at) = e {
            let done = sectors.partition_point(|&s| s <= at);
            PROGRESS.store((done * 100 / total) as u32, Ordering::Relaxed);
        }
    }, &mut log).map_err(|e| e.to_string())?;
    PROGRESS.store(100, Ordering::Relaxed);
    let mut out = report(session);
    out.push(format!("DONE|{}|{}", plan.sectors.len(), span(plan)));
    if let Some(p) = &session.backup_path {
        out.push(format!("BACKUP|{}", p.display()));
    }
    Ok(out.join("\n"))
}

fn reply(r: std::thread::Result<Result<String, String>>) -> String {
    match r {
        Ok(Ok(s)) => format!("OK\n{s}"),
        Ok(Err(e)) => format!("ERROR\n{e}"),
        Err(_) => "ERROR\ninternal error (panic)".into(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_backup<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    iface: jint,
    ep_in: jint,
    ep_out: jint,
    in_packet: jint,
    dir: JString<'l>,
) -> jstring {
    let dir: String = env.get_string(&dir).map(|s| s.into()).unwrap_or_default();
    let text = reply(std::panic::catch_unwind(|| backup(fd, iface as u8, ep_in as u8, ep_out as u8, in_packet as usize, &dir)));
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_diagnose<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    iface: jint,
    ep_in: jint,
    ep_out: jint,
    in_packet: jint,
    dir: JString<'l>,
) -> jstring {
    let dir: String = env.get_string(&dir).map(|s| s.into()).unwrap_or_default();
    let text = reply(std::panic::catch_unwind(|| diagnose(fd, iface as u8, ep_in as u8, ep_out as u8, in_packet as usize, &dir)));
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_planInstall<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    iface: jint,
    ep_in: jint,
    ep_out: jint,
    in_packet: jint,
    dir: JString<'l>,
    lib_dir: JString<'l>,
    id: JString<'l>,
) -> jstring {
    let dir: String = env.get_string(&dir).map(|s| s.into()).unwrap_or_default();
    let lib_dir: String = env.get_string(&lib_dir).map(|s| s.into()).unwrap_or_default();
    let id: String = env.get_string(&id).map(|s| s.into()).unwrap_or_default();
    let text = reply(std::panic::catch_unwind(|| {
        plan_install(fd, iface as u8, ep_in as u8, ep_out as u8, in_packet as usize, &dir, &lib_dir, &id)
    }));
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_install<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    iface: jint,
    ep_in: jint,
    ep_out: jint,
    in_packet: jint,
    dir: JString<'l>,
    lib_dir: JString<'l>,
    id: JString<'l>,
    boot: jni::sys::jboolean,
) -> jstring {
    let dir: String = env.get_string(&dir).map(|s| s.into()).unwrap_or_default();
    let lib_dir: String = env.get_string(&lib_dir).map(|s| s.into()).unwrap_or_default();
    let id: String = env.get_string(&id).map(|s| s.into()).unwrap_or_default();
    let text = reply(std::panic::catch_unwind(|| {
        install(fd, iface as u8, ep_in as u8, ep_out as u8, in_packet as usize, &dir, &lib_dir, &id, boot != 0)
    }));
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// The m-bootloader step on this unit (REQ-BOOT-01): only on M-VAVE's stock
/// bootloader, the bundled m-bootloader for the identified model.
fn boot_step(session: &Unit) -> Result<Option<ops::Plan>, String> {
    if session.bootloader_suggestion().is_none() {
        return Ok(None);
    }
    session.plan_bootloader(None, None).map(Some).map_err(|e| e.to_string())
}

/// Plans what the Device tab's operations install, as the desktop does: a
/// firmware package chosen on the phone (`kind` "file", REQ-BOOT-01's step
/// first on a stock unit: `BOOT|..`), or m-bootloader alone ("boot", from
/// INSTALL M-BOOTLOADER). `PLAN|name|version|sectors|span|title`, `PNOTE`,
/// `PWARN`. Nothing is written.
fn plan_package(fd: i32, iface: u8, ep_in: u8, ep_out: u8, in_packet: usize, dir: &str, file: &str, kind: &str)
        -> Result<String, String> {
    with_unit(fd, iface, ep_in, ep_out, in_packet, dir, false, |session| {
        let clean = |t: &str| t.replace(['|', '\n'], " ");
        let mut out = report(session);
        let (plan, name, version) = if kind == "boot" {
            let plan = session.plan_bootloader(None, None).map_err(|e| e.to_string())?;
            let version = ops::bundled_bootloader(crate::boards::BOARDS[1]).map(|(_, v)| v).unwrap_or_default();
            (plan, "m-bootloader".to_string(), version)
        } else {
            let data = std::fs::read(file).map_err(|e| format!("cannot read {file}: {e}"))?;
            if let Some(boot) = boot_step(session)? {
                let version = ops::bundled_bootloader(crate::boards::BOARDS[1]).map(|(_, v)| v).unwrap_or_default();
                out.push(format!("BOOT|{}|{}|m-bootloader goes first|{version}", boot.sectors.len(), span(&boot)));
            }
            let name = std::path::Path::new(file).file_stem().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            (session.plan_firmware(&data).map_err(|e| e.to_string())?, name, String::new())
        };
        out.push(format!("PLAN|{}|{}|{}|{}|{}", clean(&name), clean(&version), plan.sectors.len(), span(&plan), clean(&plan.title)));
        out.extend(plan.notes.iter().map(|n| format!("PNOTE|{}", clean(n))));
        out.extend(plan.warnings.iter().map(|w| format!("PWARN|{}", clean(w))));
        Ok(out.join("\n"))
    })
}

/// Writes what plan_package planned, made again on what the unit holds now
/// (with `boot`, the file's m-bootloader step), as install does.
fn install_package(fd: i32, iface: u8, ep_in: u8, ep_out: u8, in_packet: usize, dir: &str, file: &str, kind: &str, boot: bool)
        -> Result<String, String> {
    with_unit(fd, iface, ep_in, ep_out, in_packet, dir, false, |session| {
        let plan = if kind == "boot" {
            session.plan_bootloader(None, None).map_err(|e| e.to_string())?
        } else if boot {
            boot_step(session)?.ok_or("this unit needs no m-bootloader step")?
        } else {
            let data = std::fs::read(file).map_err(|e| format!("cannot read {file}: {e}"))?;
            session.plan_firmware(&data).map_err(|e| e.to_string())?
        };
        let out = write(session, &plan)?;
        if kind != "boot" && !boot {
            let name = std::path::Path::new(file).file_stem().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            remember(session, &plan, dir, &name);
        }
        Ok(out)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_planPackage<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    iface: jint,
    ep_in: jint,
    ep_out: jint,
    in_packet: jint,
    dir: JString<'l>,
    file: JString<'l>,
    kind: JString<'l>,
) -> jstring {
    let dir: String = env.get_string(&dir).map(|s| s.into()).unwrap_or_default();
    let file: String = env.get_string(&file).map(|s| s.into()).unwrap_or_default();
    let kind: String = env.get_string(&kind).map(|s| s.into()).unwrap_or_default();
    let text = reply(std::panic::catch_unwind(|| {
        plan_package(fd, iface as u8, ep_in as u8, ep_out as u8, in_packet as usize, &dir, &file, &kind)
    }));
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_installPackage<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    iface: jint,
    ep_in: jint,
    ep_out: jint,
    in_packet: jint,
    dir: JString<'l>,
    file: JString<'l>,
    kind: JString<'l>,
    boot: jni::sys::jboolean,
) -> jstring {
    let dir: String = env.get_string(&dir).map(|s| s.into()).unwrap_or_default();
    let file: String = env.get_string(&file).map(|s| s.into()).unwrap_or_default();
    let kind: String = env.get_string(&kind).map(|s| s.into()).unwrap_or_default();
    let text = reply(std::panic::catch_unwind(|| {
        install_package(fd, iface as u8, ep_in as u8, ep_out as u8, in_packet as usize, &dir, &file, &kind, boot != 0)
    }));
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// After a firmware is written: its fingerprint with the name it was chosen by, next to the backups
/// (installed.json), so that the next read names it so.
fn remember(session: &Unit, plan: &ops::Plan, dir: &str, name: &str) {
    let sectors = crate::library::firmware_sectors(&plan.target, session.dev.chip_key(), session.board());
    match crate::library::remember_installed(std::path::Path::new(dir), &plan.target, &sectors, name) {
        Ok(()) => log(format!("Remembered: this code is {name}")),
        Err(e) => log(format!("Could not remember the firmware's name: {e}")),
    }
}

/// Which firmware the unit holds by name: the one installed with that code (installed.json in `dir`, next
/// to the backups), else the saved one whose install would change nothing. "id|name|version", or "" when
/// neither (the unit's own name for it is then all there is). Reads only what was read.
fn identify(lib_dir: &str, dir: &str) -> String {
    let Ok(mut kept) = UNIT.lock() else { return String::new() };
    let Some((_, s)) = kept.as_mut() else { return String::new() };
    if let Some(n) = crate::library::installed_name(std::path::Path::new(dir), &s.flash) {
        // the unit's display too: the name it was installed by
        s.named = Some(n.clone());
        if s.display {
            s.show_on_unit();
        }
        return format!("|{}|", n.replace('|', " "));
    }
    let lib = Library::new(PathBuf::from(lib_dir));
    let Ok(Some(cat)) = lib.catalog() else { return String::new() };
    for e in cat.entries.iter().filter(|e| lib.downloaded(e)) {
        let Ok(data) = std::fs::read(lib.package_path(e)) else { continue };
        if s.plan_firmware(&data).is_ok_and(|p| p.sectors.is_empty()) {
            s.named = Some(format!("{} {}", e.name, e.version));
            if s.display {
                s.show_on_unit();
            }
            return format!("{}|{}|{}", e.id, e.name, e.version);
        }
    }
    String::new()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_identify<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    lib_dir: JString<'l>,
    dir: JString<'l>,
) -> jstring {
    let lib_dir: String = env.get_string(&lib_dir).map(|s| s.into()).unwrap_or_default();
    let dir: String = env.get_string(&dir).map(|s| s.into()).unwrap_or_default();
    let text = std::panic::catch_unwind(|| identify(&lib_dir, &dir)).unwrap_or_default();
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_planRestore<'l>(
    mut env: JNIEnv<'l>, _class: JClass<'l>, fd: jint, iface: jint, ep_in: jint, ep_out: jint, in_packet: jint,
    dir: JString<'l>, file: JString<'l>,
) -> jstring {
    let dir: String = env.get_string(&dir).map(|s| s.into()).unwrap_or_default();
    let file: String = env.get_string(&file).map(|s| s.into()).unwrap_or_default();
    let text = reply(std::panic::catch_unwind(|| {
        plan_restore(fd, iface as u8, ep_in as u8, ep_out as u8, in_packet as usize, &dir, &file)
    }));
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_restore<'l>(
    mut env: JNIEnv<'l>, _class: JClass<'l>, fd: jint, iface: jint, ep_in: jint, ep_out: jint, in_packet: jint,
    dir: JString<'l>, file: JString<'l>,
) -> jstring {
    let dir: String = env.get_string(&dir).map(|s| s.into()).unwrap_or_default();
    let file: String = env.get_string(&file).map(|s| s.into()).unwrap_or_default();
    let text = reply(std::panic::catch_unwind(|| {
        restore(fd, iface as u8, ep_in as u8, ep_out as u8, in_packet as usize, &dir, &file)
    }));
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// The unit went (or its connection is closed): forget what was read.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_close<'l>(_env: JNIEnv<'l>, _class: JClass<'l>) {
    if let Ok(mut kept) = UNIT.lock() {
        *kept = None;
    }
}

/// The log lines since the last call.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_takeLog<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let lines: Vec<String> = LOG.lock().map(|mut l| l.drain(..).collect()).unwrap_or_default();
    env.new_string(lines.join("\n")).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_progress<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jint {
    PROGRESS.load(Ordering::Relaxed) as jint
}

/// After a write: the unit leaves download mode and starts what was written
/// (the desktop's restart; the firmware is not running yet, so MIDI can't do
/// it). The unit kept is forgotten: it goes. `OK|address` or `ERR|why`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_restart<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let r = match UNIT.lock() {
        Ok(mut kept) => match kept.take() {
            Some((_, mut u)) => match u.dev.restart() {
                Ok(addr) => {
                    log("Restarting the FM-1: it leaves download mode and starts what was written".into());
                    format!("OK|{addr:08X}")
                }
                Err(e) => format!("ERR|{e}"),
            },
            None => "ERR|no unit read".into(),
        },
        Err(_) => "ERR|the unit is busy".into(),
    };
    env.new_string(r).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
