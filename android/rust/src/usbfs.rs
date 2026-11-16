//! USB mass storage Bulk-Only Transport over Linux usbfs, for the file
//! descriptor Android's UsbDeviceConnection gives the app. The Java side
//! finds the unit, gets the user's permission and claims the interface
//! (taking it from the system's disk driver); this does the transfers with
//! usbfs ioctls, as src/usb.rs does with libusb on macOS.

use crate::scsi::{Data, Scsi, ScsiError};
use std::os::raw::{c_int, c_void};

const TIMEOUT_MS: u32 = 10_000;
const DRAIN_TIMEOUT_MS: u32 = 100;
const CBW_SIGNATURE: u32 = 0x4342_5355; // "USBC"
const CSW_SIGNATURE: u32 = 0x5342_5355; // "USBS"

// <linux/usbdevice_fs.h>, 64-bit layouts
const USBDEVFS_CONTROL: u32 = 0xC018_5500;
const USBDEVFS_BULK: u32 = 0xC018_5502;
const USBDEVFS_CLEAR_HALT: u32 = 0x8004_5515;

#[repr(C)]
struct BulkTransfer {
    ep: u32,
    len: u32,
    timeout: u32,
    data: *mut c_void,
}

#[repr(C)]
struct CtrlTransfer {
    request_type: u8,
    request: u8,
    value: u16,
    index: u16,
    length: u16,
    timeout: u32,
    data: *mut c_void,
}

#[derive(Debug, Clone, Copy, PartialEq)]
enum UsbError {
    /// The endpoint stalled.
    Pipe,
    Timeout,
    /// The device sent more than the buffer holds.
    Overflow,
    Other(c_int),
}

impl std::fmt::Display for UsbError {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        match self {
            UsbError::Pipe => f.write_str("endpoint stalled"),
            UsbError::Timeout => f.write_str("timed out"),
            UsbError::Overflow => f.write_str("overflow"),
            UsbError::Other(e) => write!(f, "{}", std::io::Error::from_raw_os_error(*e)),
        }
    }
}

fn last_error() -> UsbError {
    match std::io::Error::last_os_error().raw_os_error().unwrap_or(0) {
        libc::EPIPE => UsbError::Pipe,
        libc::ETIMEDOUT => UsbError::Timeout,
        libc::EOVERFLOW => UsbError::Overflow,
        e => UsbError::Other(e),
    }
}

fn failed(what: &str, e: UsbError) -> ScsiError {
    ScsiError::Failed(format!("{what}: {e}"))
}

fn cmd_name(cdb: &[u8]) -> String {
    match cdb {
        [a, b, ..] => format!("0x{a:02x}{b:02x}"),
        [a] => format!("0x{a:02x}"),
        [] => "(empty)".into(),
    }
}

/// The tag of a status block (CSW), if `b` starts with one.
fn csw_tag(b: &[u8]) -> Option<u32> {
    (b.len() >= 13 && b[0..4] == CSW_SIGNATURE.to_le_bytes()).then(|| u32::from_le_bytes(b[4..8].try_into().unwrap()))
}

pub struct Device {
    fd: c_int,
    iface: u8,
    ep_in: u8,
    ep_out: u8,
    in_packet: usize,
    tag: u32,
}

impl Device {
    /// The interface must already be claimed by the Java side.
    pub fn new(fd: c_int, iface: u8, ep_in: u8, ep_out: u8, in_packet: usize) -> Device {
        let tag = {
            use std::hash::{BuildHasher, Hasher};
            std::collections::hash_map::RandomState::new().build_hasher().finish() as u32
        };
        let mut d = Device { fd, iface, ep_in, ep_out, in_packet, tag };
        d.drain();
        d
    }

    fn bulk(&self, ep: u8, buf: *mut c_void, len: usize, timeout: u32) -> Result<usize, UsbError> {
        let mut t = BulkTransfer { ep: ep as u32, len: len as u32, timeout, data: buf };
        // SAFETY: t points at `len` bytes the caller owns for the call
        let r = unsafe { libc::ioctl(self.fd, USBDEVFS_BULK as _, &mut t as *mut BulkTransfer) };
        if r < 0 { Err(last_error()) } else { Ok(r as usize) }
    }

    fn read_bulk(&self, buf: &mut [u8], timeout: u32) -> Result<usize, UsbError> {
        self.bulk(self.ep_in, buf.as_mut_ptr() as *mut c_void, buf.len(), timeout)
    }

    fn write_bulk(&self, buf: &[u8], timeout: u32) -> Result<usize, UsbError> {
        self.bulk(self.ep_out, buf.as_ptr() as *mut c_void, buf.len(), timeout)
    }

    fn clear_halt(&self, ep: u8) {
        let mut e = ep as u32;
        // SAFETY: a pointer to a u32 for the call
        unsafe { libc::ioctl(self.fd, USBDEVFS_CLEAR_HALT as _, &mut e as *mut u32) };
    }

    /// Bulk-Only Mass Storage Reset, then clear both endpoints' halts.
    fn reset_recovery(&mut self) {
        let mut c = CtrlTransfer {
            request_type: 0x21,
            request: 0xFF,
            value: 0,
            index: self.iface as u16,
            length: 0,
            timeout: TIMEOUT_MS,
            data: std::ptr::null_mut(),
        };
        // SAFETY: no data stage
        unsafe { libc::ioctl(self.fd, USBDEVFS_CONTROL as _, &mut c as *mut CtrlTransfer) };
        self.clear_halt(self.ep_in);
        self.clear_halt(self.ep_out);
    }

    /// Reads away what the unit still has to send from before the app took it
    /// (the disk driver's last status, say).
    fn drain(&mut self) {
        let mut buf = vec![0u8; self.in_packet.max(64) * 8];
        for _ in 0..4 {
            match self.read_bulk(&mut buf, DRAIN_TIMEOUT_MS) {
                Ok(_) | Err(UsbError::Overflow) => {}
                Err(UsbError::Pipe) => self.clear_halt(self.ep_in),
                Err(_) => break,
            }
        }
    }

    /// One read of the data phase, in whole packets.
    fn read_data(&self, buf: &mut [u8]) -> Result<usize, UsbError> {
        let packet = self.in_packet.max(1);
        if buf.len() % packet == 0 {
            return self.read_bulk(buf, TIMEOUT_MS);
        }
        let mut whole = vec![0u8; buf.len().next_multiple_of(packet)];
        let n = self.read_bulk(&mut whole, TIMEOUT_MS)?;
        if n > buf.len() {
            return Err(UsbError::Overflow);
        }
        buf[..n].copy_from_slice(&whole[..n]);
        Ok(n)
    }

    fn read_csw(&mut self) -> Result<[u8; 13], UsbError> {
        let mut buf = [0u8; 512];
        for _ in 0..3 {
            match self.read_bulk(&mut buf, TIMEOUT_MS) {
                Ok(13) if csw_tag(&buf) == Some(self.tag) => return Ok(buf[..13].try_into().unwrap()),
                Ok(_) => {}
                Err(UsbError::Pipe) => self.clear_halt(self.ep_in),
                Err(e) => return Err(e),
            }
        }
        Err(UsbError::Other(libc::EIO))
    }

    fn transfer(&mut self, cdb: &[u8], data: Data) -> Result<usize, ScsiError> {
        if cdb.is_empty() || cdb.len() > 16 {
            return Err(ScsiError::Refused(format!("a command of {} bytes: 1 to 16 fit", cdb.len())));
        }
        self.tag = self.tag.wrapping_add(1);
        let (len, flags) = match &data {
            Data::None => (0, 0),
            Data::In(b) => (b.len(), 0x80),
            Data::Out(b) => (b.len(), 0),
        };
        let mut cbw = [0u8; 31];
        cbw[0..4].copy_from_slice(&CBW_SIGNATURE.to_le_bytes());
        cbw[4..8].copy_from_slice(&self.tag.to_le_bytes());
        cbw[8..12].copy_from_slice(&(len as u32).to_le_bytes());
        cbw[12] = flags;
        cbw[14] = cdb.len() as u8;
        cbw[15..15 + cdb.len()].copy_from_slice(cdb);
        let name = cmd_name(cdb);
        self.write_bulk(&cbw, TIMEOUT_MS).map_err(|e| failed(&format!("sending command {name}"), e))?;
        let mut early = None;
        let phase = match data {
            Data::None => Ok(0),
            Data::In(b) if b.is_empty() => Ok(0),
            Data::Out(b) if b.is_empty() => Ok(0),
            Data::In(buf) => {
                let status = |b: &[u8]| if b.len() > 13 { csw_tag(b) } else { None };
                let mut skipped = 0;
                loop {
                    match self.read_data(buf) {
                        Ok(13) if status(buf) == Some(self.tag) => {
                            early = Some(buf[..13].try_into().unwrap());
                            break Ok(0);
                        }
                        Ok(13) if skipped < 2 && status(buf).is_some() => skipped += 1,
                        Ok(n) => break Ok(n),
                        Err(UsbError::Pipe) => break Err(self.ep_in),
                        Err(e) => return Err(failed(&format!("command {name}: reading its data"), e)),
                    }
                }
            }
            Data::Out(buf) => match self.write_bulk(buf, TIMEOUT_MS) {
                Ok(n) if n == buf.len() => Ok(n),
                Ok(n) => return Err(ScsiError::Failed(format!("command {name}: {n} of {} bytes sent", buf.len()))),
                Err(UsbError::Pipe) => Err(self.ep_out),
                Err(e) => return Err(failed(&format!("command {name}: sending its data"), e)),
            },
        };
        let moved = phase.unwrap_or_else(|ep| {
            self.clear_halt(ep);
            0
        });
        let csw = match early {
            Some(csw) => csw,
            None => self.read_csw().map_err(|e| failed(&format!("command {name}: reading its status"), e))?,
        };
        if u32::from_le_bytes(csw[0..4].try_into().unwrap()) != CSW_SIGNATURE
            || u32::from_le_bytes(csw[4..8].try_into().unwrap()) != self.tag
        {
            return Err(ScsiError::Failed(format!("command {name}: an invalid status block")));
        }
        match csw[12] {
            0 => Ok(moved),
            1 => Err(ScsiError::Refused(format!("command {name} failed (status 1)"))),
            s => Err(ScsiError::Failed(format!("command {name}: phase error (status {s})"))),
        }
    }
}

impl Scsi for Device {
    fn execute(&mut self, cdb: &[u8], data: Data) -> Result<usize, ScsiError> {
        let r = self.transfer(cdb, data);
        if matches!(&r, Err(ScsiError::Failed(_))) {
            self.reset_recovery();
        }
        r
    }
}
