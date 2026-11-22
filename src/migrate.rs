//! FirmWave was called M-Flasher + until 2.0.0. What it kept under the old name
//! is carried over here: its folders (Documents/M-Flasher Plus, the library's) and
//! its environment variables (MFLASHER_PLUS_*).

use std::ffi::OsString;
use std::path::Path;

/// FIRMWAVE_<name>, or else the old MFLASHER_PLUS_<name>, which still works in 2.x.
pub fn env(name: &str) -> Option<OsString> {
    std::env::var_os(format!("FIRMWAVE_{name}")).or_else(|| std::env::var_os(format!("MFLASHER_PLUS_{name}")))
}

/// [`env`] as text (None when unset or not Unicode).
pub fn env_str(name: &str) -> Option<String> {
    env(name).and_then(|v| v.into_string().ok())
}

/// Copies what `old` holds into `new`, at the same places, leaving `old` as it is: every file that
/// `new` doesn't have yet. A file that is only in iCloud is not read (reading it waits for the download,
/// which may never come) and is left for the next start, when it may be here. Returns how many were
/// left that way.
pub fn adopt_folder(new: &Path, old: &Path) -> usize {
    if !old.is_dir() {
        return 0;
    }
    let mut left = 0;
    let _ = std::fs::create_dir_all(new);
    for e in std::fs::read_dir(old).into_iter().flatten().flatten() {
        let (src, dst) = (e.path(), new.join(e.file_name()));
        match e.file_type() {
            Ok(t) if t.is_dir() => left += adopt_folder(&dst, &src),
            Ok(t) if t.is_file() && !dst.exists() => {
                if crate::cloud::only_in_cloud(&src) {
                    left += 1;
                } else {
                    // through a temporary name, so that a copy cut short is never taken for the file
                    let part = dst.with_file_name(format!(".{}.part", e.file_name().to_string_lossy()));
                    if std::fs::copy(&src, &part).is_ok() {
                        let _ = std::fs::rename(&part, &dst);
                    } else {
                        let _ = std::fs::remove_file(&part);
                    }
                }
            }
            _ => {}
        }
    }
    left
}
