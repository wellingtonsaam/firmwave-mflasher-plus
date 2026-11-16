package io.github.wellingtonsaam.mflasherlib;

/** The Rust core (android/rust): M-Flasher's own modules over the usbfs file descriptor. */
public final class Native {
    static {
        System.loadLibrary("mflr_android");
    }

    private Native() {}

    /**
     * Starts M-Flasher's loader, reads the whole flash (checked by the chip's CRC32) and saves a
     * backup in dir. Read only. Returns "OK\n..." or "ERROR\n...". Blocks: call off the UI thread.
     */
    public static native String backup(int fd, int iface, int epIn, int epOut, int inPacket, String dir);

    /** The log lines since the last call. */
    public static native String takeLog();

    /** Read progress, in percent. */
    public static native int progress();
}
