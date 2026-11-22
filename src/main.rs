//! FirmWave: back up, restore and reinstall the firmware of the M-VAVE FM-1
//! and SMK-37 Pro / Elite through the chip's USB download mode, and install
//! m-bootloader. Without arguments: the window; with arguments: the
//! command line.

#![cfg_attr(windows, windows_subsystem = "windows")]

mod boards;
mod cli;
mod cloud;
mod migrate;
mod controller;
mod fonts;
mod formats;
mod fw;
mod jl;
mod library;
mod midi;
mod ops;
mod scsi;
mod sectormap;
#[cfg(test)]
mod tests;
mod ui;
mod wordmark;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    // macOS: the root helper the app starts to reach the unit
    #[cfg(target_os = "macos")]
    if args.first().map(String::as_str) == Some(scsi::macos::USB_HELPER) {
        std::process::exit(scsi::macos::usb_helper());
    }
    // Windows: the administrator helper the app starts to reach the unit
    #[cfg(windows)]
    if args.first().map(String::as_str) == Some(scsi::helper::SCSI_HELPER) {
        std::process::exit(scsi::helper::scsi_helper(&args));
    }
    if !args.is_empty() {
        #[cfg(windows)]
        unsafe {
            // a windows-subsystem app has no console: use the one it was started from
            windows_sys::Win32::System::Console::AttachConsole(windows_sys::Win32::System::Console::ATTACH_PARENT_PROCESS);
        }
        std::process::exit(cli::run(args));
    }
    // one window at a time: two would both take the unit as it shows up in download mode
    let _one = match one_window() {
        Ok(lock) => lock,
        Err(()) => {
            rfd::MessageDialog::new()
                .set_title("FirmWave")
                .set_description("FirmWave is already open. Use its window: two at once would both reach for the unit.")
                .set_level(rfd::MessageLevel::Info)
                .show();
            return;
        }
    };
    #[cfg(target_os = "linux")]
    if let Some(problem) = linux_display_problem() {
        eprintln!("cannot open the window: {problem}");
        std::process::exit(1);
    }
    if let Err(e) = ui::run() {
        eprintln!("cannot open the window: {e}");
        #[cfg(windows)]
        window_error(&e.to_string());
        std::process::exit(1);
    }
}

/// The lock one window holds while it is open: Err when another window holds it, Ok(None) when there is
/// no lock file to take (the window opens anyway). A file every user may open (an FirmWave started with
/// sudo runs as root); the system unlocks it when the process ends, however it ends.
fn one_window() -> Result<Option<std::fs::File>, ()> {
    let path = if cfg!(windows) { std::env::temp_dir() } else { std::path::PathBuf::from("/tmp") }.join("firmwave.lock");
    let opened = std::fs::OpenOptions::new().read(true).write(true).create(true).truncate(false).open(&path)
        .or_else(|_| std::fs::File::open(&path));
    let Ok(file) = opened else { return Ok(None) };
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o666));
    }
    match file.try_lock() {
        Ok(()) => Ok(Some(file)),
        Err(std::fs::TryLockError::WouldBlock) => Err(()),
        Err(_) => Ok(None),
    }
}

/// A windows-subsystem app has no console to print to: without this, it just
/// doesn't appear. Rare: Windows draws Direct3D 12 in software without a driver
/// that has it.
#[cfg(windows)]
fn window_error(error: &str) {
    use windows_sys::Win32::UI::WindowsAndMessaging::{MB_ICONERROR, MB_OK, MessageBoxW};
    let wide = |s: &str| s.encode_utf16().chain(std::iter::once(0)).collect::<Vec<u16>>();
    let text = format!(
        "Cannot open the window: {error}\n\nIt is drawn with Direct3D 12, which Windows 10 and 11 provide even without a graphics driver that has it.\n\nThe command line works without it: FirmWave --help"
    );
    // SAFETY: both are valid NUL-terminated strings; no owner window
    unsafe { MessageBoxW(std::ptr::null_mut(), wide(&text).as_ptr(), wide("FirmWave").as_ptr(), MB_ICONERROR | MB_OK) };
}

/// Why the window cannot open on this Linux desktop, if it cannot: checked
/// first, as the windowing library would abort with a less helpful message.
#[cfg(target_os = "linux")]
fn linux_display_problem() -> Option<String> {
    let set = |v| std::env::var_os(v).is_some_and(|s| !s.is_empty());
    let sudo = if set("SUDO_USER") {
        " sudo hides the desktop session: install the udev rule and run FirmWave without sudo (see README.txt)."
    } else {
        ""
    };
    if !set("WAYLAND_DISPLAY") && !set("DISPLAY") {
        return Some(format!("no desktop session (neither WAYLAND_DISPLAY nor DISPLAY is set).{sudo} The command line works without one: firmwave --help"));
    }
    // X11 (no Wayland session): the keyboard library's X11 part, which a
    // Wayland desktop may not have installed
    let loads = |name: &str| {
        let name = std::ffi::CString::new(name).unwrap();
        // SAFETY: dlopen with a valid C string, closed again at once
        unsafe {
            let h = libc::dlopen(name.as_ptr(), libc::RTLD_LAZY);
            !h.is_null() && libc::dlclose(h) == 0
        }
    };
    if !set("WAYLAND_DISPLAY") && !loads("libxkbcommon-x11.so.0") && !loads("libxkbcommon-x11.so") {
        return Some(format!(
            "on X11 it needs libxkbcommon-x11 (Debian, Ubuntu: sudo apt install libxkbcommon-x11-0).{sudo}"
        ));
    }
    None
}
