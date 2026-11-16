//! The JNI side of io.github.wellingtonsaam.mflasherlib.Native.
//!
//! Spike scope: read only. `backup` starts M-Flasher's loader, reads the whole
//! flash (checked against the chip's CRC32), saves a backup with its .json and
//! returns what the unit holds. Nothing is ever written to the flash here.

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

fn backup(fd: i32, iface: u8, ep_in: u8, ep_out: u8, in_packet: usize, dir: &str) -> Result<String, String> {
    PROGRESS.store(0, Ordering::Relaxed);
    let dev = usbfs::Device::new(fd, iface, ep_in, ep_out, in_packet);
    let conn = jl::Connection::start("usb", dev, &mut log).map_err(|e| e.to_string())?;
    let mut session = ops::Session::open(conn, PathBuf::from(dir), &mut log, &mut |done, total| {
        PROGRESS.store((done * 100 / total.max(1)) as u32, Ordering::Relaxed);
    })
    .map_err(|e| e.to_string())?;
    session.show_on_unit();
    let path = session.save_backup(None).map_err(|e| e.to_string())?;
    let mut out: Vec<String> = session.info().into_iter().map(|(k, v)| format!("{k}: {v}")).collect();
    for (_, strong, why) in &session.analysis.evidence {
        out.push(format!("  {} {why}", if *strong { "*" } else { "-" }));
    }
    out.push(format!("backup: {}", path.display()));
    Ok(out.join("\n"))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherlib_Native_backup<'l>(
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
    let r = std::panic::catch_unwind(|| backup(fd, iface as u8, ep_in as u8, ep_out as u8, in_packet as usize, &dir));
    let text = match r {
        Ok(Ok(s)) => format!("OK\n{s}"),
        Ok(Err(e)) => format!("ERROR\n{e}"),
        Err(_) => "ERROR\ninternal error (panic)".into(),
    };
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// The log lines since the last call.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherlib_Native_takeLog<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let lines: Vec<String> = LOG.lock().map(|mut l| l.drain(..).collect()).unwrap_or_default();
    env.new_string(lines.join("\n")).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherlib_Native_progress<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jint {
    PROGRESS.load(Ordering::Relaxed) as jint
}
