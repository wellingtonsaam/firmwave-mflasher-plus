//! Android spike: M-Flasher's own modules, unchanged, over the USB device
//! Android hands the app (a usbfs file descriptor), and a JNI bridge for the
//! Java side.
//!
//! The core is compiled from the desktop sources by path, so the safety rules
//! (two checked reads, sector fences, verify after write) are the same code.
#![allow(dead_code)]

#[path = "../../../src/formats.rs"]
mod formats;
#[path = "../../../src/boards.rs"]
mod boards;
#[path = "../../../src/scsi.rs"]
mod scsi;
#[path = "../../../src/jl.rs"]
mod jl;
#[path = "../../../src/ops.rs"]
mod ops;
#[path = "../../../src/library.rs"]
mod library;

mod bridge;
mod libbridge;
mod usbfs;
