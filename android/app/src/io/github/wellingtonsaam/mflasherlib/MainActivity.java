package io.github.wellingtonsaam.mflasherlib;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Device screen (Figma A01): finds an FM-1 in download mode on USB, reads it with M-Flasher's core
 * and shows what it holds and its SAFETY CHECK. Read only: diagnose and backup. Nothing is written.
 */
public class MainActivity extends Activity {
    private static final String TAG = "MFLR";
    private static final String ACTION_PERMISSION = "io.github.wellingtonsaam.mflasherlib.USB_PERMISSION";
    private static final int VID = 0x4C4A;
    private static final int PID = 0x8057;

    // the desktop's colors (src/ui.rs), as in the Figma variables
    private static final int BG = 0xFF28221F, PANEL = 0xFF1F1A18, INK = 0xFFE9E4DC, DIM = 0xFF8D837B,
            LAV = 0xFFB6A2E6, MINT = 0xFF7BE8BB, WARN = 0xFFF2B44C, BAD = 0xFFFF6A5A,
            KEY = 0xFF2C7A70, KEYDARK = 0xFF2A2421, KEYINK = 0xFFD6EFE8, LCD = 0xFFC3D66B, LCDINK = 0xFF1D2310;

    /** A safety check: name, passed, what it says, the action that fixes it ("" for none). */
    private static final class Check {
        final String name, detail, action;
        final boolean ok;

        Check(String name, boolean ok, String detail, String action) {
            this.name = name;
            this.ok = ok;
            this.detail = detail;
            this.action = action;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private UsbManager usb;
    private float dp;
    private TextView unit, lcdTitle, lcdStatus, lcdDetail, summary, log;
    private LinearLayout checks, info;
    private Button read;
    private ScrollView device;
    private LibraryView library;
    private BackupsView backups;
    /** The backup being put back (PLAN_RESTORE, WRITE_RESTORE). */
    private File restoreFile;
    private LinearLayout foot;
    private TextView[] tabs;
    private volatile boolean running, writing;
    /** What reading the unit is for: its diagnosis, a backup, or a plan to review. */
    private static final int DIAGNOSE = 0, BACKUP = 1, PLAN = 2, WRITE = 3, WRITE_BOOT = 4, PLAN_RESTORE = 5, WRITE_RESTORE = 6,
            SWITCH = 7, RESTART = 8;
    /** What to do once the user grants the USB permission. */
    private int pendingMode;
    /** The firmware being planned (PLAN). */
    private String planId;
    /** The unit's USB connection, kept open while it is plugged in (the core keeps what it read). */
    private UsbDeviceConnection conn;
    private UsbInterface claimed;
    private UsbEndpoint epInUsed, epOutUsed;
    /** The firmware on the unit, as last read. */
    private String currentFirmware = "";
    /** Shown from the tap until the work is done: what is happening, and how far. */
    private AlertDialog busy;
    private TextView busyText;
    private android.widget.ProgressBar busyBar;
    /** The backup of the unit now connected, if saved (the FM-1s seen share one unit id: issue #61). */
    private String backupSaved;
    private final List<String> logLines = new ArrayList<>();
    /** READ UNIT or INSTALL tapped with no unit in download mode: what to do once it is (DIAGNOSE, PLAN), -1 none. */
    private int afterSwitch = -1;
    /** The soft key was sent: give up waiting at this time; 0 while the user switches it by hand. */
    private long switchDeadline;
    /** The unit's name on USB MIDI, when it was found running. */
    private String midiName;
    /** How to switch it by hand, while shown. */
    private AlertDialog howTo;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                backupSaved = null;
                closeUnit();
                say("Unit disconnected");
                showWaiting();
                return;
            }
            if (!ACTION_PERMISSION.equals(intent.getAction())) return;
            UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) {
                say("USB permission granted");
                start(d, pendingMode);
            } else {
                say("USB permission refused");
            }
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            String lines = Native.takeLog();
            if (!lines.isEmpty()) say(lines);
            if (running) {
                int p = Native.progress();
                read.setText((writing ? "WRITING… " : "READING… ") + p + "%");
                if (busy != null && writing) {
                    busyText.setText(p == 0 ? "Backing up your FM-1 on this phone…" : p < 100 ? "Installing… " + p + "%"
                            : "Checking what was written…");
                    busyBar.setIndeterminate(p == 0);
                    busyBar.setProgress(p);
                }
                ui.postDelayed(this, 200);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(PANEL);
        usb = getSystemService(UsbManager.class);
        dp = getResources().getDisplayMetrics().density;
        setContentView(build());

        IntentFilter f = new IntentFilter(ACTION_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
        say("M-Flasher + on " + Build.MODEL + ", Android " + Build.VERSION.RELEASE);
        showWaiting();
        library.load();
        readUnit(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // a unit in download mode was plugged in: read it, unless INSTALL is waiting for it
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction()) && afterSwitch < 0) readUnit(false);
    }

    @Override
    protected void onDestroy() {
        closeUnit();
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    // ---- layout

    private int px(float v) {
        return Math.round(v * dp);
    }

    private TextView text(String s, int color, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private GradientDrawable box(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (stroke != 0) g.setStroke(px(1), stroke);
        g.setCornerRadius(px(radius));
        return g;
    }

    private Button key(String label, boolean primary) {
        Button k = new Button(this);
        k.setText(label);
        k.setAllCaps(true);
        k.setTextColor(primary ? KEYINK : INK);
        k.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        k.setBackground(box(primary ? KEY : KEYDARK, primary ? 0 : DIM, 3));
        k.setStateListAnimator(null);
        return k;
    }

    private View section(String label) {
        TextView t = text(label, LAV, 11, false);
        t.setLetterSpacing(0.25f);
        t.setPadding(0, px(18), 0, px(6));
        return t;
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(16), px(12), px(16), px(12));
        TextView name = text("M-FLASHER +", MINT, 20, true);
        name.setOnClickListener(v -> about());
        name.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        bar.addView(name);
        unit = text("NO UNIT", DIM, 11, false);
        unit.setGravity(Gravity.END);
        bar.addView(unit, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar);

        FrameLayout content = new FrameLayout(this);
        device = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(px(16), px(4), px(16), px(16));
        device.addView(body);
        content.addView(device);
        library = new LibraryView(this, new File(getFilesDir(), "library"), this::install);
        library.setVisibility(View.GONE);
        content.addView(library);
        backups = new BackupsView(this, new File(getExternalFilesDir(null), "backups"), f -> {
            restoreFile = f;
            showTab(0);
            run(PLAN_RESTORE);
        });
        backups.setVisibility(View.GONE);
        content.addView(backups);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout lcd = new LinearLayout(this);
        lcd.setOrientation(LinearLayout.VERTICAL);
        lcd.setPadding(px(14), px(12), px(14), px(12));
        lcd.setBackground(box(LCD, 0xFF111111, 2));
        lcdTitle = text("NO UNIT", LCDINK, 26, true);
        lcdStatus = text("", LCDINK, 13, false);
        lcdDetail = text("", LCDINK, 13, false);
        lcd.addView(lcdTitle);
        lcd.addView(lcdStatus);
        lcd.addView(lcdDetail);
        body.addView(lcd);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.BOTTOM);
        head.addView(section("SAFETY CHECK"), new LinearLayout.LayoutParams(0, -2, 1));
        summary = text("", DIM, 11, true);
        summary.setPadding(0, 0, 0, px(6));
        head.addView(summary);
        body.addView(head);
        checks = new LinearLayout(this);
        checks.setOrientation(LinearLayout.VERTICAL);
        body.addView(checks);

        body.addView(section("DEVICE INFO"));
        info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        body.addView(info);

        body.addView(section("LOG"));
        log = text("", DIM, 10, false);
        log.setTextIsSelectable(true);
        body.addView(log);

        foot = new LinearLayout(this);
        foot.setPadding(px(16), px(10), px(16), px(10));
        foot.setBackgroundColor(PANEL);
        read = key("READ UNIT", true);
        // as the desktop's DOWNLOAD MODE: a running FM-1 is switched first
        read.setOnClickListener(v -> switchThen(DIAGNOSE));
        foot.addView(read, new LinearLayout.LayoutParams(-1, px(48)));
        root.addView(foot);

        // bottom navigation (Figma Bottom Nav): DEVICE, LIBRARY
        LinearLayout nav = new LinearLayout(this);
        nav.setBackgroundColor(PANEL);
        String[] names = {"DEVICE", "LIBRARY", "BACKUPS"};
        tabs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int tab = i;
            TextView t = text(names[i], DIM, 12, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, px(14), 0, px(14));
            t.setOnClickListener(v -> showTab(tab));
            tabs[i] = t;
            nav.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(nav);
        showTab(0);
        return root;
    }

    private void showTab(int tab) {
        device.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        foot.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        library.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        backups.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        if (tab == 2) backups.load();
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(i == tab ? MINT : DIM);
            tabs[i].setBackground(i == tab ? topLine() : null);
        }
    }

    /** The active tab's mint line on top. */
    private android.graphics.drawable.Drawable topLine() {
        GradientDrawable line = new GradientDrawable();
        line.setColor(MINT);
        android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[] {new android.graphics.drawable.ColorDrawable(PANEL), line});
        l.setLayerHeight(1, px(2));
        return l;
    }

    /** About: what this app is, and whose work it builds on. */
    private void about() {
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "";
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("M-Flasher + " + version)
                .setMessage("Back up, restore and install the firmware of an M-VAVE FM-1 from this phone, through the "
                        + "chip's USB download mode.\n\n"
                        + "M-Flasher + by Wellington Saam: the firmware library, m-bootloader before a firmware "
                        + "(REQ-BOOT-01), the Device tab's checks and this Android app. Based on M-Flasher by Julius "
                        + "Vitkauskas (MIT).\n\n"
                        + "Bootloader and flash loader: m-bootloader (MIT). Firmware list: The Firmware Hub "
                        + "(CC BY 4.0).\n\nUnofficial: not affiliated with M-VAVE or JieLi.")
                .setPositiveButton("OK", null).show();
    }

    // ---- state shown

    private void say(String s) {
        for (String l : s.split("\n")) {
            Log.i(TAG, l);
            logLines.add(l);
        }
        while (logLines.size() > 14) logLines.remove(0);
        if (log != null) log.setText(String.join("\n", logLines));
    }

    private void showWaiting() {
        unit.setText("NO UNIT");
        unit.setTextColor(DIM);
        lcdTitle.setText("NO UNIT");
        lcdStatus.setText("WAITING FOR DOWNLOAD MODE");
        lcdDetail.setText("HOLD REC WHILE SWITCHING ON");
        summary.setText("");
        checks.removeAllViews();
        info.removeAllViews();
        library.setUnit(null);
        if (backups != null) backups.setUnit(false);
        checks.addView(text("Connect an FM-1 in download mode with an OTG cable: m-bootloader, hold REC while "
                + "switching on; Felucca and others, hold OCT- and OCT+ for 5 s.", DIM, 12, false));
    }

    private void show(String result) {
        String[] lines = result.split("\n");
        if (!lines[0].equals("OK")) {
            say("ERROR: " + result.substring(Math.min(result.length(), 6)));
            return;
        }
        Map<String, String> facts = new LinkedHashMap<>();
        List<Check> found = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] p = lines[i].split("\\|", -1);
            if (p[0].equals("INFO") && p.length >= 3) facts.put(p[1], p[2]);
            if (p[0].equals("INFO") && p.length >= 3 && p[1].equals("firmware")) currentFirmware = p[2];
            if (p[0].equals("CHECK") && p.length >= 5) found.add(new Check(p[1], p[2].equals("1"), p[3], p[4]));
            if (p[0].equals("CHECK") && p.length >= 5 && p[1].equals("RECOVERY"))
                remember("rescue", p[2].equals("1") ? "yes" : p[3].startsWith("stock") ? "no" : "");
            if (p[0].equals("UNIT") && p.length >= 3 && p[1].equals("chip")) {
                library.setUnit(p[2]);
                backups.setUnit(true);
            }
            if (p[0].equals("BACKUP") && p.length >= 2) {
                backupSaved = new File(p[1]).getName();
                say("Backup saved: " + p[1]);
            }
        }
        // BACKUP is the app's own check, second as on the desktop
        Check backup = backupSaved != null ? new Check("BACKUP", true, "saved · " + backupSaved, "")
                : new Check("BACKUP", false, "none of this connection yet", "SAVE");
        found.add(Math.min(1, found.size()), backup);

        if (facts.containsKey("firmware")) remember("firmware", facts.get("firmware"));
        String model = facts.containsKey("model") ? facts.get("model") : "UNIT";
        unit.setText(model + " · DOWNLOAD MODE");
        unit.setTextColor(MINT);
        lcdTitle.setText(model);
        lcdStatus.setText("USB OTG · DOWNLOAD MODE");
        lcdDetail.setText(facts.getOrDefault("firmware", "") + " · FLASH 1 MiB");

        int need = 0;
        checks.removeAllViews();
        for (Check c : found) {
            if (!c.ok) need++;
            checks.addView(checkRow(c));
        }
        summary.setText(need == 0 ? "ALL CLEAR" : need + (need == 1 ? " NEEDS" : " NEED") + " ATTENTION");
        summary.setTextColor(need == 0 ? MINT : WARN);

        info.removeAllViews();
        for (Map.Entry<String, String> e : facts.entrySet()) {
            LinearLayout r = new LinearLayout(this);
            r.setPadding(0, px(3), 0, px(3));
            r.addView(text(e.getKey().toUpperCase(), LAV, 11, false), new LinearLayout.LayoutParams(px(118), -2));
            boolean app = e.getKey().equals("app");
            r.addView(text(e.getValue(), app ? (e.getValue().equals("OK") ? MINT : BAD) : INK, 12, false),
                    new LinearLayout.LayoutParams(0, -2, 1));
            info.addView(r);
        }
    }

    private View checkRow(Check c) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(8), 0, px(8));
        r.addView(text(c.ok ? "✓" : "⚠", c.ok ? MINT : WARN, 16, true), new LinearLayout.LayoutParams(px(26), -2));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(c.name, LAV, 11, false));
        col.addView(text(c.detail, c.ok ? INK : WARN, 12, false));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        if (!c.action.isEmpty()) {
            Button k = key(c.action, false);
            k.setTextSize(12);
            if (c.action.equals("SAVE")) {
                k.setOnClickListener(v -> readUnit(true));
            } else {
                // installing m-bootloader writes the unit: it comes with the write review (T15.5)
                k.setEnabled(false);
                k.setAlpha(0.45f);
            }
            r.addView(k, new LinearLayout.LayoutParams(-2, px(40)));
        }
        return r;
    }

    // ---- USB

    private UsbDevice find() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == VID && d.getProductId() == PID) return d;
        }
        return null;
    }

    /** Reads the unit: its diagnosis, and a backup too when asked. Read only. */
    private void readUnit(boolean backup) {
        run(backup ? BACKUP : DIAGNOSE);
    }

    private void run(int mode) {
        if (running) return;
        showBusy(mode);
        UsbDevice d = find();
        if (d == null) {
            say("No FM-1 in download mode (USB 4C4A:8057)");
            hideBusy();
            showWaiting();
            return;
        }
        if (!usb.hasPermission(d)) {
            pendingMode = mode;
            Intent i = new Intent(ACTION_PERMISSION).setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
            say("Asking for USB permission…");
            hideBusy();
            return;
        }
        start(d, mode);
    }

    private void start(UsbDevice d, int mode) {
        if (conn == null && !connect(d)) {
            hideBusy();
            return;
        }
        final UsbDeviceConnection c = conn;
        final UsbInterface fIntf = claimed;
        final UsbEndpoint fIn = epInUsed, fOut = epOutUsed;
        final File dir = new File(getExternalFilesDir(null), "backups");
        running = true;
        read.setEnabled(false);
        writing = mode == WRITE || mode == WRITE_BOOT || mode == WRITE_RESTORE;
        read.setText(mode == BACKUP ? "SAVING BACKUP…" : mode == PLAN ? "PLANNING…" : writing ? "WRITING…" : "READING…");
        if (writing) {
            lcdStatus.setText("WRITING · DO NOT UNPLUG");
            lcdStatus.setTextColor(0xFF6B2A00);
        }
        final String lib = new File(getFilesDir(), "library").getAbsolutePath();
        final String id = planId;
        final String file = restoreFile == null ? "" : restoreFile.getAbsolutePath();
        ui.post(poll);
        new Thread(() -> {
            int fd = c.getFileDescriptor();
            int epIn = fIn.getAddress(), epOut = fOut.getAddress(), packet = fIn.getMaxPacketSize();
            String r = mode == BACKUP ? Native.backup(fd, fIntf.getId(), epIn, epOut, packet, dir.getAbsolutePath())
                    : mode == PLAN ? Native.planInstall(fd, fIntf.getId(), epIn, epOut, packet, dir.getAbsolutePath(), lib, id)
                    : mode == PLAN_RESTORE ? Native.planRestore(fd, fIntf.getId(), epIn, epOut, packet, dir.getAbsolutePath(), file)
                    : mode == WRITE_RESTORE ? Native.restore(fd, fIntf.getId(), epIn, epOut, packet, dir.getAbsolutePath(), file)
                    : writing ? Native.install(fd, fIntf.getId(), epIn, epOut, packet, dir.getAbsolutePath(), lib, id, mode == WRITE_BOOT)
                    : Native.diagnose(fd, fIntf.getId(), epIn, epOut, packet, dir.getAbsolutePath());
            // the firmware's library name, before the review opens
            String named = r.startsWith("OK") ? Native.identify(lib) : "";
            ui.post(() -> {
                running = false;
                hideBusy();
                String lines = Native.takeLog();
                if (!lines.isEmpty()) say(lines);
                Log.i(TAG, "RESULT " + r.replace('\n', ';'));
                show(r);
                name(named);
                read.setEnabled(true);
                read.setText("READ UNIT");
                lcdStatus.setTextColor(LCDINK);
                if (mode == PLAN) review(r);
                if (mode == PLAN_RESTORE) reviewRestore(r);
                if (writing) written(r, mode == WRITE_BOOT, mode == WRITE_RESTORE);
                writing = false;
            });
        }, mode == BACKUP ? "mflr-backup" : mode == PLAN || mode == PLAN_RESTORE ? "mflr-plan" : writing ? "mflr-write"
                : "mflr-diagnose").start();
    }

    /**
     * Names the firmware as the library does ("Hortator 1.0.1") when it is one saved on this phone;
     * otherwise the unit's own name for it stays.
     */
    private void name(String r) {
        final String internal = currentFirmware;
        {
            {
                String[] p = r.split("\\|", -1);
                if (p.length < 3) return;
                String named = p[1] + " " + p[2];
                currentFirmware = named;
                lcdDetail.setText(named + " · FLASH 1 MiB");
                say("Firmware: " + named + (internal.isEmpty() ? "" : " (the unit calls it " + internal + ")"));
                for (int i = 0; i < info.getChildCount(); i++) {
                    View row = info.getChildAt(i);
                    if (!(row instanceof LinearLayout) || ((LinearLayout) row).getChildCount() < 2) continue;
                    TextView k = (TextView) ((LinearLayout) row).getChildAt(0), v = (TextView) ((LinearLayout) row).getChildAt(1);
                    if (k.getText().toString().equals("FIRMWARE")) v.setText(named + (internal.isEmpty() ? "" : "  (" + internal + ")"));
                }
                for (int i = 0; i < checks.getChildCount(); i++) {
                    View row = checks.getChildAt(i);
                    if (!(row instanceof LinearLayout) || ((LinearLayout) row).getChildCount() < 2) continue;
                    View col = ((LinearLayout) row).getChildAt(1);
                    if (!(col instanceof LinearLayout) || ((LinearLayout) col).getChildCount() < 2) continue;
                    TextView name = (TextView) ((LinearLayout) col).getChildAt(0), detail = (TextView) ((LinearLayout) col).getChildAt(1);
                    if (name.getText().toString().equals("FIRMWARE") && !internal.isEmpty())
                        detail.setText(detail.getText().toString().replace(internal, named));
                }
            }
        }
    }

    /** Opens the unit's mass storage interface, kept until it goes. */
    private boolean connect(UsbDevice d) {
        UsbInterface intf = null;
        UsbEndpoint in = null, outEp = null;
        for (int i = 0; i < d.getInterfaceCount() && intf == null; i++) {
            UsbInterface c = d.getInterface(i);
            if (c.getInterfaceClass() != UsbConstants.USB_CLASS_MASS_STORAGE) continue;
            UsbEndpoint ei = null, eo = null;
            for (int e = 0; e < c.getEndpointCount(); e++) {
                UsbEndpoint ep = c.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) ei = ep; else eo = ep;
            }
            if (ei != null && eo != null) {
                intf = c;
                in = ei;
                outEp = eo;
            }
        }
        if (intf == null) {
            say("The device has no mass storage interface");
            return false;
        }
        UsbDeviceConnection c = usb.openDevice(d);
        if (c == null) {
            say("Could not open the USB device");
            return false;
        }
        if (!c.claimInterface(intf, true)) {
            c.close();
            say("Could not claim the USB interface");
            return false;
        }
        conn = c;
        claimed = intf;
        epInUsed = in;
        epOutUsed = outEp;
        return true;
    }

    private void closeUnit() {
        Native.close();
        if (conn != null) {
            conn.releaseInterface(claimed);
            conn.close();
            conn = null;
        }
    }

    // ---- INSTALL from the library: download mode first, by the soft key or by hand; then the review

    /** Plans installing a saved firmware, switching the FM-1 to download mode first when it is running. */
    private void install(String id) {
        planId = id;
        showTab(0);
        switchThen(PLAN);
    }

    /** Runs `mode` on the unit in download mode, switching a running FM-1 to it first (the soft key, or by hand). */
    private void switchThen(int mode) {
        if (running) return;
        if (find() != null) {
            run(mode);
            return;
        }
        afterSwitch = mode;
        midiName = null;
        switchDeadline = 0;
        showBusy(SWITCH);
        DownloadMode.send(this, (unitName, error) -> {
            if (afterSwitch < 0) return;
            midiName = unitName;
            if (unitName == null) {
                say("No FM-1 in download mode or running on USB MIDI (MIDI devices: " + DownloadMode.seen(this) + ")");
                hideBusy();
                showHowTo(false);
            } else if (error != null) {
                say("Could not send " + unitName + " the download-mode message: " + error);
                hideBusy();
                showHowTo(true);
            } else {
                say("Sent " + unitName + " the download-mode message over USB MIDI; waiting for it");
                switchDeadline = System.currentTimeMillis() + 20000;
            }
            ui.post(waitDownloadMode);
        });
    }

    /** Waits for the unit in download mode: then plans, and the review opens. */
    private final Runnable waitDownloadMode = new Runnable() {
        @Override
        public void run() {
            if (afterSwitch < 0) return;
            if (find() != null) {
                int mode = afterSwitch;
                afterSwitch = -1;
                if (howTo != null) howTo.dismiss();
                howTo = null;
                hideBusy();
                say("In download mode");
                MainActivity.this.run(mode);
                return;
            }
            if (switchDeadline != 0 && System.currentTimeMillis() > switchDeadline) {
                switchDeadline = 0;
                say("The " + midiName + " did not come back in download mode within 20 s: its firmware may not know "
                        + "the message");
                hideBusy();
                showHowTo(true);
            }
            ui.postDelayed(this, 500);
        }
    };

    /** The keys that switch this FM-1, from what was last read on it; the app keeps waiting and carries on by itself. */
    private void showHowTo(boolean seenRunning) {
        String why = seenRunning ? "Your FM-1 (" + midiName + ") didn't switch by itself: its firmware may not know "
                + "the message from this phone. Switch it by hand:\n\n"
                : "This phone can't see your FM-1 running. Check it is connected and switched on, or switch it by hand:\n\n";
        String last = recalled("firmware");
        howTo = dialog("Put your FM-1 in download mode", why
                + DownloadMode.howTo(recalled("rescue"), last, midiName)
                + (afterSwitch == PLAN ? "\n\nThis app is waiting: once the FM-1 is in download mode it reads it and "
                        + "shows what it will install. Nothing is written until you tap INSTALL there."
                        : "\n\nThis app is waiting: once the FM-1 is in download mode it reads it."))
                .setNegativeButton("CANCEL", (d, w) -> {
                    say(afterSwitch == PLAN ? "Install cancelled" : "Cancelled");
                    afterSwitch = -1;
                    howTo = null;
                })
                .setCancelable(false).create();
        howTo.show();
    }

    private void remember(String key, String value) {
        getSharedPreferences("unit", MODE_PRIVATE).edit().putString(key, value).apply();
    }

    private String recalled(String key) {
        return getSharedPreferences("unit", MODE_PRIVATE).getString(key, "");
    }

    // ---- progress: from the tap, so the app answers at once

    private void showBusy(int mode) {
        hideBusy();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(24), px(8), px(24), px(8));
        busyBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        busyBar.setIndeterminate(true);
        busyBar.setMax(100);
        busyText = text(mode == RESTART ? "Starting your FM-1…"
                : mode == SWITCH ? "Asking your FM-1 to switch to download mode…"
                : mode == PLAN ? "Reading your FM-1 and preparing the installation…"
                : mode == PLAN_RESTORE ? "Checking the backup and comparing it with your FM-1…"
                : mode == BACKUP ? "Copying everything on your FM-1 to this phone…"
                : mode == WRITE || mode == WRITE_BOOT || mode == WRITE_RESTORE ? "Backing up your FM-1 on this phone…"
                : "Reading your FM-1…", INK, 14, false);
        box.addView(busyText);
        box.addView(busyBar);
        String title = mode == RESTART ? "Starting" : mode == SWITCH ? "Download mode" : mode == PLAN || mode == PLAN_RESTORE ? "Preparing"
                : mode == BACKUP ? "Saving a backup"
                : mode == WRITE_RESTORE ? "Restoring"
                : mode == WRITE_BOOT ? "Installing m-bootloader" : mode == WRITE ? "Installing" : "Reading";
        busy = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title).setView(box).setCancelable(false).create();
        busy.show();
    }

    private void hideBusy() {
        if (busy != null) busy.dismiss();
        busy = null;
    }

    @Override
    public void onBackPressed() {
        if (writing) {
            Toast.makeText(this, "Writing: do not leave or unplug the unit", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    /** After a write: done and checked, or what went wrong and where the backup is. Step 1 of 2 opens step 2's review. */
    private void written(String result, boolean step1, boolean restored) {
        String[] lines = result.split("\n");
        if (!lines[0].equals("OK")) {
            String why = result.substring(Math.min(result.length(), 6));
            say("WRITE FAILED: " + why);
            dialog("Installation stopped", why + "\n\nDon't switch the FM-1 off. Try again, with another cable if it "
                    + "happens again: the backup taken first is on this phone, and the rescue mode (REC while switching "
                    + "on) is still there.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String done = "", backup = "";
        for (String l : lines) {
            String[] p = l.split("\\|", -1);
            if (p[0].equals("DONE") && p.length >= 3) done = p[1] + " sectors (" + p[2] + ")";
            if (p[0].equals("BACKUP") && p.length >= 2) backup = new File(p[1]).getName();
        }
        say("Written and verified: " + done + " · backup " + backup);
        lcdDetail.setText("DONE · CHECKED");
        if (restored) {
            restartThenSay("Restored", "Your FM-1 holds again what this backup saved, and everything written was "
                    + "checked.", "What was there just before is saved on this phone (" + backup + ").");
        } else if (step1) {
            dialog("Step 1 of 2 done", "m-bootloader is installed and checked. Your FM-1 now has its rescue mode."
                    + "\n\nNext: the firmware.")
                    .setPositiveButton("CONTINUE", (d, w) -> run(PLAN)).setCancelable(false).show();
        } else {
            restartThenSay("Installed", "Everything was written and checked.",
                    "What was there before is saved on this phone (" + backup + ").");
        }
    }

    /**
     * After a write: the FM-1 leaves download mode and starts what was written (no MIDI yet: its firmware isn't
     * running), then the app waits up to 60 s for it on USB MIDI and says whether it started.
     */
    private void restartThenSay(String title, String done, String backupLine) {
        showBusy(RESTART);
        final String fw = currentFirmware;
        new Thread(() -> {
            String r = Native.restart();
            ui.post(() -> {
                String lines = Native.takeLog();
                if (!lines.isEmpty()) say(lines);
                if (!r.startsWith("OK")) {
                    hideBusy();
                    say("Could not restart the FM-1: " + r.substring(Math.min(r.length(), 4)));
                    dialog(title, done + "\n\nSwitch the FM-1 off and on to start it.\n\n" + backupLine)
                            .setPositiveButton("OK", null).show();
                    return;
                }
                final long started = System.currentTimeMillis();
                ui.postDelayed(new Runnable() {
                    boolean gone;

                    @Override
                    public void run() {
                        long t = System.currentTimeMillis() - started;
                        String name = DownloadMode.running(MainActivity.this);
                        boolean inDownload = find() != null;
                        if (!inDownload) gone = true;
                        String end = null;
                        if (name != null) {
                            say("Running: " + name + " on USB MIDI");
                            end = done + "\n\nYour FM-1 restarted and is running" + (fw.isEmpty() ? "" : " " + fw) + ".";
                        } else if (gone && inDownload) {
                            say("The FM-1 came back in download mode: what was written did not start");
                            end = done + "\n\n⚠  But the FM-1 came back in download mode: what was written did not "
                                    + "start. Restore the backup from BACKUPS, or install another firmware.";
                        } else if (!gone && t > 5000) {
                            say("The FM-1 did not leave download mode");
                            end = done + "\n\nThe FM-1 did not restart: switch it off and on to start it.";
                        } else if (t > 60000) {
                            say("Restarted; not on USB MIDI within 60 s");
                            end = done + "\n\nYour FM-1 restarted; it hasn't shown up on USB within 60 s (not every "
                                    + "firmware has USB MIDI): look at its screen.";
                        }
                        if (end == null) {
                            ui.postDelayed(this, 500);
                            return;
                        }
                        hideBusy();
                        dialog(title, end + "\n\n" + backupLine).setPositiveButton("OK", null).show();
                    }
                }, 500);
            });
        }, "mflr-restart").start();
    }

    // ---- review (Figma A03 and the desktop's REVIEW): nothing is written yet

    private AlertDialog.Builder dialog(String title, String message) {
        return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(title).setMessage(message);
    }

    /** The write key in mint, the others in ink: the review is the confirmation, as on the desktop. */
    private void showReview(AlertDialog d) {
        d.show();
        android.widget.Button w = d.getButton(DialogInterface.BUTTON_POSITIVE);
        if (w != null) w.setTextColor(MINT);
    }

    /** The review of putting back a backup, in plain words; DETAILS has the plan. */
    private void reviewRestore(String result) {
        String[] lines = result.split("\n");
        if (!lines[0].equals("OK")) {
            dialog("Can't restore this backup", result.substring(Math.min(result.length(), 6))).setPositiveButton("OK", null).show();
            return;
        }
        String[] plan = null;
        String rboot = "";
        StringBuilder notes = new StringBuilder(), warnings = new StringBuilder();
        for (String l : lines) {
            String[] p = l.split("\\|", -1);
            if (p[0].equals("PLAN") && p.length >= 6) plan = p;
            if (p[0].equals("RBOOT") && p.length >= 2) rboot = p[1];
            if (p[0].equals("PNOTE") && p.length >= 2) notes.append(p[1]).append("\n");
            if (p[0].equals("PWARN") && p.length >= 2) warnings.append("⚠  ").append(p[1]).append("\n");
        }
        if (plan == null) return;
        final String[] fw = plan;
        int n = Integer.parseInt(fw[3]);
        String saved = fw[2].matches("\\d{8}-\\d{6}") ? fw[2].substring(6, 8) + "/" + fw[2].substring(4, 6) + " "
                + fw[2].substring(9, 11) + ":" + fw[2].substring(11, 13) : fw[1];
        if (n == 0) {
            dialog("Already the same", "Your FM-1 already holds exactly what the backup of " + saved + " saved.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        boolean rescue = rboot.startsWith("m-bootloader");
        AlertDialog d = dialog("Restore the backup of " + saved + "?",
                "Puts back everything your FM-1 held then" + (currentFirmware.isEmpty() ? "" : ", replacing " + currentFirmware)
                + ".\n\n✓  What is on your FM-1 now is backed up first.\n"
                + "✓  The backup was checked: it is the file that was saved.\n"
                + "✓  Everything written is checked by the FM-1 itself.\n"
                + (rescue ? "✓  Its rescue mode (m-bootloader) stays.\n"
                          : "⚠  This backup has M-VAVE's original start-up program: restoring it removes the rescue mode.\n")
                + warnings + "\nTakes about " + seconds(n) + " seconds. Keep the FM-1 connected and this screen open.")
                .setNegativeButton("CANCEL", null)
                .setNeutralButton("DETAILS", null)
                .setPositiveButton("RESTORE", (x, w) -> run(WRITE_RESTORE)).create();
        showReview(d);
        d.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> dialog("Details", fw[5] + "\nWrites " + n
                + " sectors (" + n * 4 + " KiB): " + fw[4] + (notes.length() == 0 ? "" : "\n" + notes.toString().trim())
                + "\nBackup file: " + fw[1]).setPositiveButton("OK", null).show());
    }

    /** Seconds a write takes on this phone, measured: about 0.05 s a sector, plus the backup. */
    private static int seconds(int sectors) {
        return Math.max(3, Math.round(sectors * 0.05f) + 2);
    }

    private void review(String result) {
        String[] lines = result.split("\n");
        if (!lines[0].equals("OK")) {
            dialog("Can't install this", result.substring(Math.min(result.length(), 6))).setPositiveButton("OK", null).show();
            return;
        }
        String[] plan = null, boot = null;
        StringBuilder notes = new StringBuilder();
        for (String l : lines) {
            String[] p = l.split("\\|", -1);
            if (p[0].equals("PLAN") && p.length >= 6) plan = p;
            if (p[0].equals("BOOT") && p.length >= 4) boot = p;
            if ((p[0].equals("PNOTE") || p[0].equals("PWARN")) && p.length >= 2) notes.append(p[1]).append("\n");
        }
        if (plan == null) return;
        final String[] fw = plan;
        int n = Integer.parseInt(fw[3]);
        lcdDetail.setText("INSTALL " + fw[1] + " " + fw[2] + " · " + n + " SECTORS");
        say("Plan: " + fw[5] + " · " + n + " sectors · " + fw[4]);
        if (n == 0) {
            dialog(fw[1] + " " + fw[2] + " is already installed", "Nothing to write: your FM-1 already holds this firmware.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        if (boot != null) {
            final String[] b = boot;
            showReview(dialog("First, protect your FM-1",
                    "Your FM-1 has M-VAVE's original start-up program. If a new firmware fails to start, only a special "
                    + "USB dongle can rescue it."
                    + "\n\nStep 1 · m-bootloader (about " + seconds(Integer.parseInt(b[1])) + " s). Afterwards, holding REC "
                    + "while switching on always opens the rescue mode."
                    + "\nStep 2 · " + fw[1] + " " + fw[2] + "."
                    + "\n\nEach step backs up your FM-1 first and checks what it writes.")
                    .setNegativeButton("CANCEL", null)
                    .setNeutralButton("SKIP STEP 1", (d, w) -> reviewFirmware(fw, notes.toString()))
                    .setPositiveButton("START STEP 1", (d, w) -> run(WRITE_BOOT)).create());
        } else {
            reviewFirmware(fw, notes.toString());
        }
    }

    private void reviewFirmware(String[] fw, String notes) {
        int n = Integer.parseInt(fw[3]);
        String replaces = currentFirmware.isEmpty() ? "" : "This replaces " + currentFirmware + " on your FM-1.\n\n";
        AlertDialog d = dialog("Install " + fw[1] + " " + fw[2] + "?", replaces
                + "✓  Your FM-1 is backed up on this phone first.\n"
                + "✓  Everything written is checked by the FM-1 itself.\n"
                + "✓  Its rescue mode (m-bootloader) stays as it is.\n\n"
                + "Takes about " + seconds(n) + " seconds. Keep the FM-1 connected and this screen open.")
                .setNegativeButton("CANCEL", null)
                .setNeutralButton("DETAILS", null)
                .setPositiveButton("INSTALL", (x, w) -> run(WRITE)).create();
        showReview(d);
        // DETAILS: the plan as the desktop shows it, without closing the review
        d.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> dialog("Details", fw[5] + "\nWrites " + n
                + " sectors (" + n * 4 + " KiB): " + fw[4] + (notes.isEmpty() ? "" : "\n" + notes.trim())
                + "\nA full backup is saved first; every run written is verified by the chip's CRC32.")
                .setPositiveButton("OK", null).show());
    }
}
