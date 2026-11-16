package io.github.wellingtonsaam.mflasherlib;

import android.content.Context;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Download mode without the keys: the soft key over USB MIDI, as the desktop sends it (src/midi.rs). M-VAVE's
 * firmware and Felucca restart into the chip's download mode on it; other firmwares may not know it, and then
 * {@link #howTo} says which keys do it on this unit.
 */
final class DownloadMode {
    /** The SysEx the firmwares answer by restarting into download mode. */
    static final byte[] SOFT_KEY = {(byte) 0xF0, 0x22, 0x24, 0x35, 0x7D, (byte) 0xF7};
    /** Names under which an FM-1 appears on USB MIDI (src/midi.rs); nothing else is ever sent the soft key. */
    private static final String[] NAMES = {"fm-1", "fm1", "minifm", "felucca", "caravel"};

    /** The soft key's outcome: the unit's MIDI name (null: none found) and what went wrong (null: sent). */
    interface Sent {
        void done(String unit, String error);
    }

    private DownloadMode() {}

    /** Every MIDI device the phone sees, by name: for the log. */
    static List<String> seen(Context c) {
        List<String> names = new ArrayList<>();
        MidiManager m = c.getSystemService(MidiManager.class);
        if (m == null) return names;
        for (MidiDeviceInfo d : devices(m)) names.add(name(d));
        return names;
    }

    /** The name of the FM-1 running on USB MIDI, or null. */
    static String running(Context c) {
        MidiManager m = c.getSystemService(MidiManager.class);
        MidiDeviceInfo d = m == null ? null : unit(m);
        return d == null ? null : name(d);
    }

    private static MidiDeviceInfo unit(MidiManager m) {
        MidiDeviceInfo unit = null;
        for (MidiDeviceInfo d : devices(m)) {
            String n = name(d).toLowerCase(Locale.ROOT);
            for (String known : NAMES) {
                if (n.contains(known) && d.getInputPortCount() > 0) unit = d;
            }
        }
        return unit;
    }

    /** Sends the soft key to the one FM-1 running on USB MIDI; the answer comes on the main thread. */
    static void send(Context c, Sent sent) {
        Handler main = new Handler(Looper.getMainLooper());
        MidiManager m = c.getSystemService(MidiManager.class);
        if (m == null) {
            main.post(() -> sent.done(null, "this phone has no MIDI"));
            return;
        }
        MidiDeviceInfo unit = unit(m);
        if (unit == null) {
            main.post(() -> sent.done(null, null));
            return;
        }
        final String unitName = name(unit);
        m.openDevice(unit, device -> {
            if (device == null) {
                sent.done(unitName, "could not open it on USB MIDI");
                return;
            }
            String error = null;
            try {
                MidiInputPort port = device.openInputPort(0);
                if (port == null) {
                    error = "its MIDI port is in use by another app";
                } else {
                    port.send(SOFT_KEY, 0, SOFT_KEY.length);
                    port.close();
                }
            } catch (Exception e) {
                error = e.toString();
            } finally {
                close(device);
            }
            sent.done(unitName, error);
        }, main);
    }

    /**
     * How to put this FM-1 in download mode by hand, from what the app last read on it: rescue "yes" (m-bootloader),
     * "no" (M-VAVE's stock bootloader) or "" (never read); its firmware as the unit names it; its MIDI name if seen.
     */
    static String howTo(String rescue, String firmware, String midi) {
        String fw = (firmware + " " + (midi == null ? "" : midi)).toLowerCase(Locale.ROOT);
        boolean octKeys = fw.contains("felucca") || fw.contains("caravel") || fw.contains("sloop") || fw.contains("snoop");
        StringBuilder s = new StringBuilder();
        if (rescue.equals("yes")) {
            s.append("1 · Switch it off.\n2 · Hold REC and switch FM-1 on.\n3 · When M-Flasher finds your FM-1, release the REC button.");
            if (octKeys) s.append("\nOr, if your FM-1 is running, hold OCT- and OCT+ buttons together for 5 seconds.");
        } else if (octKeys) {
            s.append("If your FM-1 is running, hold OCT- and OCT+ buttons together for 5 seconds.");
        } else if (rescue.equals("no")) {
            s.append("It has M-VAVE's original bootloader: its buttons can't do it, only the message from M-Flasher. "
                    + "Switch it on, connect it with a data cable (some cables only charge) and tap again.");
        } else {
            s.append("• With m-bootloader: hold REC while switching FM-1 on.\n"
                    + "• With Felucca, SLOOP or Snoop37 running: hold OCT- and OCT+ buttons together for 5 seconds.\n"
                    + "• With M-VAVE's own firmware: switch it on, connect it with a data cable and tap again: "
                    + "M-Flasher switches it.");
        }
        return s.toString();
    }

    @SuppressWarnings("deprecation")
    private static Iterable<MidiDeviceInfo> devices(MidiManager m) {
        List<MidiDeviceInfo> all = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            all.addAll(m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM));
        } else {
            for (MidiDeviceInfo d : m.getDevices()) all.add(d);
        }
        return all;
    }

    private static String name(MidiDeviceInfo d) {
        Bundle p = d.getProperties();
        String n = p.getString(MidiDeviceInfo.PROPERTY_NAME);
        if (n == null) n = p.getString(MidiDeviceInfo.PROPERTY_PRODUCT);
        return n == null ? "MIDI device " + d.getId() : n;
    }

    private static void close(MidiDevice d) {
        try {
            d.close();
        } catch (Exception ignored) {
            // closing only frees the port
        }
    }
}
