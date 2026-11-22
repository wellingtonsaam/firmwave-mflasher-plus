package io.github.wellingtonsaam.mflasherplus;

/** The Rust core (android/rust): FirmWave's own modules over the usbfs file descriptor. */
public final class Native {
    static {
        System.loadLibrary("mflr_android");
    }

    private Native() {}

    /**
     * Starts the flash loader, reads the whole flash (checked by the chip's CRC32) and saves a
     * backup in dir. Read only. Returns diagnose's lines and BACKUP|path, or "ERROR\n...". Blocks: call
     * off the UI thread.
     */
    public static native String backup(int fd, int iface, int epIn, int epOut, int inPacket, String dir);

    /**
     * Reads the unit (checked by the chip's CRC32) and returns "OK\n" and one fact per line:
     * INFO|key|value, CHECK|name|1 or 0|detail|action, EVIDENCE|* or -|why. Read only; blocks.
     */
    public static native String diagnose(int fd, int iface, int epIn, int epOut, int inPacket, String dir);

    /**
     * The firmware library (the desktop's src/library.rs): reads the hub's list at most once a day.
     * Returns "OK\nNOTE|..." and one FW|id|name|version|byline|kinds|chip|beta|saved|elsewhere|older|favorite
     * line per firmware. Blocks (network): call off the UI thread.
     */
    public static native String librarySync(String dir);

    /** Downloads a firmware from its author's release, kept only if its SHA-256 matches. "OK\npath". */
    public static native String libraryGet(String dir, String id);

    public static native String libraryFavorite(String dir, String id, boolean on);

    /** Deletes every saved version of a firmware from this phone. */
    public static native String libraryDelete(String dir, String id);

    /**
     * Reads the unit and plans installing a saved firmware (id from the library in libDir), as the
     * desktop does. Returns diagnose's lines plus BOOT|sectors|span|why (m-bootloader first, if the
     * unit has the stock bootloader), PLAN|name|version|sectors|span|title, PNOTE|.., PWARN|.. Nothing
     * is written. Blocks.
     */
    public static native String planInstall(int fd, int iface, int epIn, int epOut, int inPacket, String dir,
            String libDir, String id);

    /**
     * Writes a saved firmware (or, with boot, the m-bootloader step before it) after the user reviewed
     * the plan: a full backup first, every run verified by the chip's CRC32. Returns diagnose's lines,
     * DONE|sectors|span and BACKUP|path, or "ERROR\n..." (with the backup's path). Blocks.
     */
    public static native String install(int fd, int iface, int epIn, int epOut, int inPacket, String dir,
            String libDir, String id, boolean boot);

    /**
     * Which firmware saved on this phone the unit holds (its install would change nothing):
     * "id|name|version", or "" if none matches. Uses what was read; quick.
     */
    public static native String identify(String libDir, String dir);

    /**
     * Plans putting back a backup saved on this phone (checked against its .json's SHA-256), as the
     * desktop does: PLAN|file|saved|sectors|span|title, PNOTE|.., PWARN|.., RBOOT|the backup's
     * bootloader. Nothing is written. Blocks.
     */
    public static native String planRestore(int fd, int iface, int epIn, int epOut, int inPacket, String dir, String file);

    /** Puts back a reviewed backup: a backup of the unit first, every run verified. DONE|.., BACKUP|.. */
    public static native String restore(int fd, int iface, int epIn, int epOut, int inPacket, String dir, String file);

    /** Forgets the unit read on this connection (it went, or the connection closes). */
    public static native void close();

    /**
     * Plans the Device tab's operations: kind "file" (a firmware package on the phone, m-bootloader first on a stock
     * unit: BOOT|..) or "boot" (m-bootloader alone). PLAN|name|version|sectors|span|title, PNOTE, PWARN.
     */
    public static native String planPackage(int fd, int iface, int epIn, int epOut, int inPacket, String dir, String file,
            String kind);

    /** Writes what planPackage planned (boot: the file's m-bootloader step). DONE|sectors|span, BACKUP|path. */
    public static native String installPackage(int fd, int iface, int epIn, int epOut, int inPacket, String dir, String file,
            String kind, boolean boot);

    /** After a write: the unit leaves download mode and starts what was written. OK|address or ERR|why. */
    public static native String restart();

    /** The log lines since the last call. */
    public static native String takeLog();

    /** Read progress, in percent. */
    public static native int progress();
}
