//! Files that are only in iCloud (macOS's "Optimize Mac Storage" for Documents): reading one makes the
//! system download it first, and a download that does not move blocks the read for good (21/11: the app
//! never opened its window, waiting on a backup's .json). The app looks before it reads such files, and
//! skips one whose contents are not on this Mac.

use std::path::Path;

/// Whether the file's contents are only in the cloud (SF_DATALESS). Looking at its metadata does not
/// download it; reading it would.
pub fn only_in_cloud(path: &Path) -> bool {
    #[cfg(target_os = "macos")]
    {
        use std::os::macos::fs::MetadataExt;
        const SF_DATALESS: u32 = 0x4000_0000;
        std::fs::symlink_metadata(path).is_ok_and(|m| m.st_flags() & SF_DATALESS != 0)
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = path;
        false
    }
}
