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
    private static final int BG = Ui.BG, PANEL = Ui.PANEL, INK = Ui.INK, DIM = Ui.DIM, LAV = Ui.LAV, MINT = Ui.MINT,
            WARN = Ui.WARN, BAD = Ui.BAD, NOW = Ui.NOW, LCD = Ui.LCD, LCDINK = Ui.LCDINK;

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
    private OpKey read;
    /** The App Bar's LED, the LCD's progress bar (Figma LCD Display, State=Writing). */
    private View unitLed;
    private LinearLayout lcdBar;
    private View lcdBarFill, lcdBarRest;
    private ScrollView device;
    private LibraryView library;
    private BackupsView backups;
    /** The backup being put back (PLAN_RESTORE, WRITE_RESTORE). */
    private File restoreFile;
    /** Device tab (Figma A1–A12): the LCD's last line, the bootloader key, the warning, what shows once checked. */
    private TextView lcdPrompt, formLabel, field;
    private View warn;
    private OpKey bootBtn;
    private Button formChoose, formPrimary, formOther;
    private OpKey[] opKeys;
    /** The last write: its sectors, its backup's path, the sectors it plans; the bundled m-bootloader's version. */
    private int lastSectors, writeTotal, bootSectors;
    private String lastBackupPath = "", bootVersion = "", flashId = "";
    private LinearLayout checkedPart, form;
    private final TextView[] steps = new TextView[4];
    /** A unit read on this connection, and whether it has m-bootloader. */
    private boolean checked, bootOpen;
    /** The operation whose form is open (FW_OP, RESTORE_OP), -1 none: chosen only once the unit is checked. */
    private int opChosen = -1;
    private static final int FW_OP = 1, RESTORE_OP = 2;
    /** The plan the form's key reviews; its firmware from a file on the phone (else the library). */
    private String planResult;
    private boolean fromFile;
    private File chosenFile;
    /** The library's firmware chosen ("Hortator 1.0.1"), null for a file. */
    private String libraryChoice;
    /** The plan is step 2 of 2: its review opens as soon as it is made. */
    private boolean reviewAfterPlan;
    private static final int PICK_FILE = 7;
    private TextView[] tabs;
    private volatile boolean running, writing;
    /** What reading the unit is for: its diagnosis, a backup, or a plan to review. */
    private static final int DIAGNOSE = 0, BACKUP = 1, PLAN = 2, WRITE = 3, WRITE_BOOT = 4, PLAN_RESTORE = 5, WRITE_RESTORE = 6,
            SWITCH = 7, RESTART = 8, PLAN_FILE = 9, PLAN_BOOT = 10, WRITE_BOOT_ONLY = 11;
    /** What to do once the user grants the USB permission. */
    private int pendingMode;
    /** The USB permission was asked for pendingMode: it may come by the system's broadcast or with the unit's attach. */
    private boolean awaitingPermission;
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
                if (!awaitingPermission || running) return;
                awaitingPermission = false;
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
                if (writing) {
                    setSteps(p == 0 ? "DAII" : p < 100 ? "DDAI" : "DDDA");
                    lcdStatus.setText(writeTotal > 0 ? writeTotal * p / 100 + " / " + writeTotal + " · " + p + " %" : p + " %");
                    lcdDetail.setText(p == 0 ? "SAVING A BACKUP FIRST" : "BACKUP SAVED FIRST");
                    setLcdBar(p);
                }
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
        say("M-Flasher + " + appVersion() + " on " + Build.MODEL + ", Android " + Build.VERSION.RELEASE);
        showWaiting();
        library.load();
        readUnit(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction()) || running) return;
        if (awaitingPermission) {
            // the permission came with the attach (the app is the unit's default): go on with what asked for it
            awaitingPermission = false;
            run(pendingMode);
        } else if (afterSwitch < 0) {
            // a unit in download mode was plugged in: read it, unless INSTALL or CHECK is waiting for it
            readUnit(false);
        }
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
        return Ui.text(this, s, color, size, bold);
    }

    /** LCD/Large (40) and LCD/Body (24): VT323, capitals. */
    private TextView lcdText(float size) {
        TextView t = new TextView(this);
        t.setTextColor(LCDINK);
        t.setTextSize(size);
        t.setTypeface(Ui.font(this, Ui.LCDFONT));
        t.setAllCaps(true);
        t.setLetterSpacing(1f / size);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** A fill with a line on one edge (the App Bar's bottom, the Bottom Nav's top, a Safety Row's bottom). */
    private android.graphics.drawable.Drawable edge(int fill, int line, boolean top) {
        GradientDrawable l = new GradientDrawable();
        l.setColor(line);
        android.graphics.drawable.LayerDrawable d = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[] {new android.graphics.drawable.ColorDrawable(fill), l});
        d.setLayerHeight(1, px(1));
        d.setLayerGravity(1, top ? Gravity.TOP : Gravity.BOTTOM);
        return d;
    }

    private static LinearLayout.LayoutParams below(int w, int h, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(0, top, 0, 0);
        return lp;
    }

    private GradientDrawable box(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (stroke != 0) g.setStroke(px(1), stroke);
        g.setCornerRadius(px(radius));
        return g;
    }

    private Button key(String label, boolean primary) {
        return Ui.key(this, label, primary);
    }

    private View section(String label) {
        return Ui.section(this, label);
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // App Bar (Figma 15:222): the name, the unit, its LED
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(12), px(6), px(12), px(6));
        bar.setBackground(edge(BG, Ui.LINE, false));
        TextView name = text("M-FLASHER +", MINT, 24, false);
        name.setTypeface(Ui.font(this, Ui.HEAD));
        name.setLetterSpacing(1f / 24);
        name.setOnClickListener(v -> about());
        bar.addView(name);
        // About: the ⓘ beside the name, its touch area 40 dp
        android.widget.ImageView about = new android.widget.ImageView(this);
        about.setImageResource(getResources().getIdentifier("ic_info", "drawable", getPackageName()));
        about.setPadding(px(10), px(10), px(10), px(10));
        about.setContentDescription("About M-Flasher +");
        about.setOnClickListener(v -> about());
        bar.addView(about, new LinearLayout.LayoutParams(px(40), px(40)));
        unit = text("NO UNIT", DIM, 14, false);
        unit.setGravity(Gravity.END);
        unit.setSingleLine(true);
        unit.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(0, -2, 1);
        up.setMargins(px(6), 0, px(6), 0);
        bar.addView(unit, up);
        unitLed = Ui.led(this);
        bar.addView(unitLed, new LinearLayout.LayoutParams(px(10), px(10)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, px(56)));

        FrameLayout content = new FrameLayout(this);
        device = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(px(12), px(12), px(12), px(16));
        device.addView(body);
        content.addView(device);
        library = new LibraryView(this, new File(getFilesDir(), "library"), this::install);
        library.setVisibility(View.GONE);
        content.addView(library);
        backups = new BackupsView(this, new File(getExternalFilesDir(null), "backups"), f -> {
            restoreFile = f;
            showTab(0);
            chooseOp(RESTORE_OP);
            run(PLAN_RESTORE);
        });
        backups.setVisibility(View.GONE);
        content.addView(backups);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        // LCD Display (Figma 7:51): a dark bezel around the green screen, VT323
        LinearLayout bezel = new LinearLayout(this);
        bezel.setPadding(px(8), px(8), px(8), px(8));
        bezel.setBackground(Ui.box(this, Ui.BEZEL, Ui.BEZELLINE, 1, 0));
        LinearLayout lcd = new LinearLayout(this);
        lcd.setOrientation(LinearLayout.VERTICAL);
        lcd.setPadding(px(16), px(12), px(16), px(12));
        lcd.setBackground(Ui.box(this, LCD, Ui.LCDLINE, 2, 0));
        lcdTitle = lcdText(40);
        lcdStatus = lcdText(24);
        lcdDetail = lcdText(24);
        lcd.addView(lcdTitle);
        lcd.addView(lcdStatus, below(-2, -2, px(4)));
        lcd.addView(lcdDetail, below(-2, -2, px(4)));
        // State=Writing: the bar, framed in ink
        lcdBar = new LinearLayout(this);
        lcdBar.setPadding(px(3), px(3), px(3), px(3));
        lcdBar.setBackground(Ui.box(this, LCD, LCDINK, 2, 0));
        lcdBarFill = new View(this);
        lcdBarFill.setBackgroundColor(LCDINK);
        lcdBar.addView(lcdBarFill, new LinearLayout.LayoutParams(0, px(10), 0));
        lcdBarRest = new View(this);
        lcdBar.addView(lcdBarRest, new LinearLayout.LayoutParams(0, px(10), 100));
        lcdBar.setVisibility(View.GONE);
        lcd.addView(lcdBar, below(-1, -2, px(4)));
        View rule = new View(this);
        rule.setBackgroundColor(LCDINK);
        lcd.addView(rule, below(-1, px(2), px(4)));
        lcdPrompt = lcdText(24);
        lcd.addView(lcdPrompt, below(-2, -2, px(4)));
        bezel.addView(lcd, new LinearLayout.LayoutParams(-1, -2));
        body.addView(bezel);

        // check, then protect, then operate: CHECK FM1 STATUS switches a running unit to download mode and reads it
        read = new OpKey(this, "CHECK FM1 STATUS", "ic_firmware");
        read.setOnClickListener(v -> switchThen(DIAGNOSE));
        body.addView(read, keyParams(12));
        bootBtn = new OpKey(this, "INSTALL M-BOOTLOADER", "ic_bootloader");
        bootBtn.setOnClickListener(v -> run(PLAN_BOOT));
        body.addView(bootBtn, keyParams(6));
        LinearLayout w = new LinearLayout(this);
        android.widget.ImageView wi = new android.widget.ImageView(this);
        wi.setImageResource(getResources().getIdentifier("ic_check_attention", "drawable", getPackageName()));
        w.addView(wi, new LinearLayout.LayoutParams(px(18), px(18)));
        TextView wt = text("You don't have m-bootloader · It's a safeguard to prevent bricks", WARN, 14, false);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1);
        wp.setMargins(px(8), 0, 0, 0);
        w.addView(wt, wp);
        warn = w;
        body.addView(warn, below(-1, -2, px(12)));

        checkedPart = new LinearLayout(this);
        checkedPart.setOrientation(LinearLayout.VERTICAL);
        body.addView(checkedPart);
        checkedPart.addView(section("DEVICE INFO"), below(-1, px(18), px(12)));
        info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        checkedPart.addView(info, below(-1, -2, px(12)));
        checkedPart.addView(section("OPERATIONS"), below(-1, px(18), px(12)));
        OpKey bk = new OpKey(this, "BACKUP FULL FLASH", "ic_backup"), rs = new OpKey(this, "RESTORE FULL FLASH", "ic_restore"),
                fwk = new OpKey(this, "FLASH FIRMWARE", "ic_firmware");
        bk.setOnClickListener(v -> {
            chooseOp(-1);
            readUnit(true);
        });
        rs.setOnClickListener(v -> chooseOp(RESTORE_OP));
        fwk.setOnClickListener(v -> chooseOp(FW_OP));
        opKeys = new OpKey[] {bk, rs, fwk};
        for (int i = 0; i < opKeys.length; i++) checkedPart.addView(opKeys[i], keyParams(i == 0 ? 12 : 6));

        // the chosen operation's form (Figma A7, A8): a file of its own, its review, OR the other source
        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        View formHead = section("");
        formLabel = Ui.sectionText(formHead);
        form.addView(formHead, new LinearLayout.LayoutParams(-1, px(18)));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        formChoose = key("CHOOSE FILE", false);
        row.addView(formChoose, new LinearLayout.LayoutParams(-2, px(38)));
        field = text("no file chosen", DIM, 14, false);
        field.setPadding(px(8), 0, px(8), 0);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setSingleLine(true);
        field.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        field.setBackground(Ui.box(this, Ui.FIELD, Ui.SECLINE, 1, 0));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, px(38), 1);
        fp.setMargins(px(6), 0, 0, 0);
        row.addView(field, fp);
        form.addView(row, below(-1, -2, px(8)));
        formPrimary = key("FLASH FIRMWARE", true);
        form.addView(formPrimary, below(-1, px(38), px(8)));
        TextView or = Ui.mono(this, "OR", DIM, 12);
        or.setGravity(Gravity.CENTER);
        form.addView(or, below(-1, -2, px(8)));
        formOther = key("INSTALL FROM LIBRARY", true);
        form.addView(formOther, below(-1, px(38), px(8)));
        checkedPart.addView(form, below(-1, -2, px(12)));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(section("SAFETY CHECK"), new LinearLayout.LayoutParams(0, px(18), 1));
        summary = Ui.label(this, "", DIM);
        summary.setPadding(px(8), 0, 0, 0);
        head.addView(summary);
        checkedPart.addView(head, below(-1, -2, px(12)));
        checks = new LinearLayout(this);
        checks.setOrientation(LinearLayout.VERTICAL);
        checkedPart.addView(checks, below(-1, -2, px(12)));

        // progress and log: always
        body.addView(section("TRANSFER PROGRESS"), below(-1, px(18), px(12)));
        LinearLayout st = new LinearLayout(this);
        String[] sn = {"READ", "BACKUP", "WRITE", "VERIFY"};
        for (int i = 0; i < 4; i++) {
            steps[i] = Ui.label(this, sn[i], DIM);
            steps[i].setGravity(Gravity.CENTER);
            steps[i].setSingleLine(true);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, px(28), 1);
            sp.setMargins(i == 0 ? 0 : px(4), 0, 0, 0);
            st.addView(steps[i], sp);
        }
        body.addView(st, below(-1, -2, px(12)));
        body.addView(section("LOG"), below(-1, px(18), px(12)));
        log = Ui.mono(this, "", DIM, 12);
        log.setTextIsSelectable(true);
        body.addView(log, below(-1, -2, px(12)));

        // Bottom Nav (Figma 15:211): DEVICE, LIBRARY, BACKUPS
        LinearLayout nav = new LinearLayout(this);
        nav.setBackground(edge(PANEL, Ui.LINE, true));
        String[] names = {"DEVICE", "LIBRARY", "BACKUPS"};
        tabs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int tab = i;
            TextView t = text(names[i], DIM, 14, false);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> showTab(tab));
            tabs[i] = t;
            nav.addView(t, new LinearLayout.LayoutParams(0, px(64), 1));
        }
        root.addView(nav);
        showTab(0);
        return root;
    }


    private void showTab(int tab) {
        device.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
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

    private LinearLayout.LayoutParams keyParams(float top) {
        return below(-1, px(52), px(top));
    }

    private void lit(OpKey k, boolean on) {
        k.setLit(on);
    }

    /** The progress steps: D done, A active, I idle (READ, BACKUP, WRITE, VERIFY). */
    private void setSteps(String states) {
        for (int i = 0; i < 4; i++) {
            char c = states.charAt(i);
            // Figma Step: Current, Done, Pending
            steps[i].setBackground(box(c == 'A' ? Ui.KEY : c == 'D' ? Ui.KEYDARK : Ui.STEPIDLE, 0, 2));
            steps[i].setTextColor(c == 'A' ? Ui.KEYINK : c == 'D' ? MINT : DIM);
            String n = steps[i].getText().toString().replace("✓ ", "");
            steps[i].setText(c == 'D' ? "✓ " + n : n);
        }
    }

    /** The LCD's last line: what to do next. */
    private void updatePrompt() {
        int n = planCount(planResult);
        lcdPrompt.setText(!checked ? "CONNECT IT AND TAP CHECK"
                : opChosen < 0 && !bootOpen ? "INSTALL M-BOOTLOADER FIRST"
                : opChosen < 0 ? "CHECKED · CHOOSE AN OPERATION"
                : n > 0 ? "PLAN READY: " + n + " SECTORS"
                : n == 0 ? "NOTHING TO WRITE · ALREADY ON THE UNIT"
                : opChosen == FW_OP ? "CHOOSE A FIRMWARE" : "CHOOSE A BACKUP");
    }

    /** Sectors the plan writes, or -1 without a plan. */
    private static int planCount(String r) {
        if (r == null || !r.startsWith("OK")) return -1;
        for (String l : r.split("\n")) {
            String[] p = l.split("\\|", -1);
            if (p[0].equals("PLAN") && p.length >= 6) return Integer.parseInt(p[3]);
        }
        return -1;
    }

    /** Opens an operation's form (-1: none), its choices cleared. */
    private void chooseOp(int op) {
        opChosen = op;
        planResult = null;
        if (op != FW_OP) {
            libraryChoice = null;
            chosenFile = null;
        }
        if (op != RESTORE_OP) restoreFile = null;
        refreshForm();
    }

    private void refreshForm() {
        if (opKeys != null) {
            lit(opKeys[1], opChosen == RESTORE_OP);
            lit(opKeys[2], opChosen == FW_OP);
        }
        form.setVisibility(opChosen < 0 ? View.GONE : View.VISIBLE);
        int n = planCount(planResult);
        boolean fw = opChosen == FW_OP;
        formLabel.setText(fw ? "FIRMWARE FILE" : "BACKUP TO RESTORE");
        formChoose.setText(fw ? "CHOOSE FILE" : "CHOOSE BACKUP");
        formChoose.setOnClickListener(v -> {
            if (fw) pickFile(); else chooseBackup();
        });
        String chosen = fw ? (libraryChoice != null ? libraryChoice + " · library · SHA-256 OK"
                : chosenFile != null ? chosenFile.getName() : null)
                : restoreFile != null ? restoreFile.getName() : null;
        field.setText(chosen == null ? "no file chosen" : chosen);
        field.setTextColor(chosen == null ? DIM : INK);
        String verb = fw ? "FLASH FIRMWARE" : "RESTORE BACKUP";
        formPrimary.setText(n > 0 ? verb + " · REVIEW " + n + " SECTORS" : n == 0 ? "NOTHING TO WRITE" : verb);
        Ui.enable(formPrimary, n > 0);
        formPrimary.setOnClickListener(v -> {
            if (fw) review(planResult); else reviewRestore(planResult);
        });
        formOther.setText(fw ? "INSTALL FROM LIBRARY" : "FROM BACKUPS TAB");
        formOther.setOnClickListener(v -> showTab(fw ? 1 : 2));
        updatePrompt();
    }

    /** CHOOSE FILE: a firmware package from the phone, copied into the app before it is planned. */
    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(i, PICK_FILE);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FILE || result != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        String name = "package.fwsc";
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        }
        File dir = new File(getCacheDir(), "chosen");
        dir.mkdirs();
        File out = new File(dir, name.replaceAll("[/\\\\]", "_"));
        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
             java.io.OutputStream o = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } catch (Exception e) {
            say("Could not read " + name + ": " + e.getMessage());
            return;
        }
        say(name + " chosen");
        chosenFile = out;
        libraryChoice = null;
        fromFile = true;
        planResult = null;
        refreshForm();
        run(PLAN_FILE);
    }

    /** CHOOSE BACKUP: the backups on this phone, newest first. */
    private void chooseBackup() {
        File dir = new File(getExternalFilesDir(null), "backups");
        File[] all = dir.listFiles((d, n) -> n.endsWith(".bin"));
        if (all == null || all.length == 0) {
            dialog("No backups yet", "Save one with BACKUP FULL FLASH first.").setPositiveButton("OK", null).show();
            return;
        }
        // newest first by when each was saved (its name holds the firmware too: "fm1-zp12-2.5-20261116-165332.bin")
        java.util.Arrays.sort(all, (x, y) -> Ui.savedAt(y).compareTo(Ui.savedAt(x)));
        String[] names = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            // fm1-20261116-102922.bin: saved 16/11/2026 10:29
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})").matcher(all[i].getName());
            names[i] = m.find() ? m.group(3) + "/" + m.group(2) + "/" + m.group(1) + " " + m.group(4) + ":" + m.group(5)
                    : all[i].getName();
        }
        Ui.list(this, Ui.dialog(this).setTitle("Choose a backup"), names, w -> {
            restoreFile = all[w];
            planResult = null;
            refreshForm();
            run(PLAN_RESTORE);
        }).show();
    }

    /** The bundled m-bootloader on its own (INSTALL M-BOOTLOADER), reviewed as any write. */
    private void reviewBoot(String result) {
        String[] lines = result.split("\n");
        if (!lines[0].equals("OK")) {
            dialog("Can't install m-bootloader", result.substring(Math.min(result.length(), 6))).setPositiveButton("OK", null).show();
            return;
        }
        String[] plan = null;
        for (String l : lines) {
            String[] p = l.split("\\|", -1);
            if (p[0].equals("PLAN") && p.length >= 6) plan = p;
        }
        if (plan == null) return;
        int n = Integer.parseInt(plan[3]);
        // its release ("2.4"), not the build ("2.4-395e6cc")
        bootVersion = plan[2].split("-")[0];
        bootSectors = n;
        showReview(dialog("Install m-bootloader " + bootVersion + "?",
                "Your FM-1 gets its rescue mode: holding REC while switching on always reaches download mode, whatever "
                + "the firmware does.\n\n"
                + "✓  Your FM-1 is backed up on this phone first.\n"
                + "✓  Only the start-up area is written (" + n * 4 + " KiB); the firmware and your settings stay.\n"
                + "✓  Everything written is checked by the FM-1 itself.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("INSTALL", (x, w) -> run(WRITE_BOOT_ONLY)).create());
    }

    /** The flash chip of the unit last checked, by name ("UCUN UC25HQ80"). */
    private String flashName() {
        return flashId.equalsIgnoreCase("0x856014") ? "Puya P25Q80H" : flashId.equalsIgnoreCase("0xB36014") ? "UCUN UC25HQ80"
                : flashId.isEmpty() ? "FLASH" : flashId;
    }

    /** The flash chip as owners know it: "Puya P25Q80H · 0x856014 (old)". */
    private static String chipName(String id) {
        if (id.equalsIgnoreCase("0x856014")) return "Puya P25Q80H · 0x856014 (old)";
        if (id.equalsIgnoreCase("0xB36014")) return "UCUN UC25HQ80 · 0xB36014 (new)";
        return id;
    }

    /** This build's version ("1.0.1"), named in the log and in About. */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** About: what this app is, and whose work it builds on. */
    private void about() {
        Ui.dialog(this)
                .setTitle("M-Flasher + " + appVersion())
                .setMessage("Back up, restore and install the firmware of an M-VAVE FM-1 from this phone, through the "
                        + "chip's USB download mode.\n\n"
                        + "M-Flasher + by Wellington Saam. Based on M-Flasher by Julius Vitkauskas (MIT).")
                .setPositiveButton("CLOSE", null).show();
    }

    // ---- state shown

    private void say(String s) {
        for (String l : s.split("\n")) {
            Log.i(TAG, l);
            logLines.add(l);
            logToFile(l);
        }
        while (logLines.size() > 14) logLines.remove(0);
        if (log != null) log.setText(String.join("\n", logLines));
    }

    /**
     * The log kept on the phone too (Android/data/…/files/m-flasher-plus.log, in the app's own folder): the screen shows only
     * its last lines. Past 1 MiB the file starts again, the last one kept as m-flasher-plus.log.1.
     */
    private void logToFile(String line) {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        File f = new File(dir, "m-flasher-plus.log");
        if (f.length() > (1 << 20)) f.renameTo(new File(dir, "m-flasher-plus.log.1"));
        String when = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date());
        try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
            w.write(when + " " + line + "\n");
        } catch (java.io.IOException ignored) {
            // the screen's log still has it
        }
    }

    private void showWaiting() {
        unit.setText("NO UNIT");
        Ui.setLed(this, unitLed, 0);
        lcdTitle.setText("NO UNIT");
        lcdStatus.setText("WAITING FOR THE FM-1");
        lcdDetail.setText("USB OTG");
        summary.setText("");
        checks.removeAllViews();
        info.removeAllViews();
        library.setUnit(null);
        if (backups != null) backups.setUnit(false);
        // nothing but CHECK until the unit is checked
        checked = false;
        bootOpen = false;
        opChosen = -1;
        planResult = null;
        checkedPart.setVisibility(View.GONE);
        warn.setVisibility(View.GONE);
        lit(read, true);
        bootBtn.setText("INSTALL M-BOOTLOADER");
        lit(bootBtn, false);
        Ui.enable(bootBtn, false);
        setSteps("IIII");
        updatePrompt();
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
        for (Check c : found) if (c.name.equals("RECOVERY")) bootOpen = c.ok;
        checked = true;
        checkedPart.setVisibility(View.VISIBLE);
        warn.setVisibility(bootOpen ? View.GONE : View.VISIBLE);
        lit(read, false);
        String bl = facts.getOrDefault("bootloader", "");
        String ver = bl.startsWith("m-bootloader") && bl.split(" ").length > 1 ? bl.split(" ")[1].split("-")[0] : "";
        bootBtn.setText(bootOpen ? "M-BOOTLOADER " + ver + " INSTALLED ✓" : "INSTALL M-BOOTLOADER");
        lit(bootBtn, !bootOpen);
        Ui.enable(bootBtn, !bootOpen);
        if (!writing) setSteps("DIII");
        unit.setText(model + " · DOWNLOAD MODE");
        Ui.setLed(this, unitLed, 1);
        lcdTitle.setText(model);
        lcdStatus.setText("USB OTG · DOWNLOAD MODE");
        lcdDetail.setText("FLASH 1 MiB");

        int need = 0;
        checks.removeAllViews();
        for (Check c : found) {
            if (!c.ok) need++;
            checks.addView(checkRow(c));
        }
        summary.setText(need == 0 ? "ALL CLEAR" : need + (need == 1 ? " NEEDS" : " NEED") + " ATTENTION");
        summary.setTextColor(need == 0 ? MINT : WARN);

        info.removeAllViews();
        String[][] rows = {{"MODEL", facts.getOrDefault("model", "")}, {"FIRMWARE", facts.getOrDefault("firmware", "")},
                {"FIRMWARE CHECK", facts.getOrDefault("app", "")}, {"BOOTLOADER", bl},
                {"FLASH CHIP", chipName(flashId = facts.getOrDefault("flash", ""))}, {"CHIP KEY", facts.getOrDefault("chip key", "")}};
        // Figma Info Row: the key in lavender capitals, the value (Ok mint, Warn amber)
        for (String[] e : rows) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setMinimumHeight(px(24));
            r.setPadding(0, px(1), 0, px(1));
            r.addView(Ui.label(this, e[0], Ui.LAVKEY), new LinearLayout.LayoutParams(px(150), -2));
            int col = e[0].equals("FIRMWARE CHECK") ? (e[1].equals("OK") ? MINT : BAD)
                    : e[0].equals("BOOTLOADER") && !bootOpen ? WARN : INK;
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, -2, 1);
            vp.setMargins(px(12), 0, 0, 0);
            r.addView(text(e[1], col, 14, false), vp);
            info.addView(r);
        }
        refreshForm();
    }

    /** Figma Safety Row: the check's icon, its name and detail, its action; a line under it. */
    private View checkRow(Check c) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(2), px(4), px(2), px(4));
        r.setBackground(edge(BG, Ui.LINE, false));
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(getResources().getIdentifier(c.ok ? "ic_check_ok" : "ic_check_attention", "drawable",
                getPackageName()));
        r.addView(icon, new LinearLayout.LayoutParams(px(18), px(18)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(c.name, LAV, 14, false));
        col.addView(Ui.mono(this, c.detail, c.ok ? INK : WARN, 12));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        cp.setMargins(px(6), px(2), px(6), px(2));
        r.addView(col, cp);
        if (!c.action.isEmpty()) {
            Button k = key(c.action, false);
            if (c.action.equals("SAVE")) {
                k.setOnClickListener(v -> readUnit(true));
            } else {
                // INSTALL: m-bootloader, reviewed as any write
                k.setOnClickListener(v -> run(PLAN_BOOT));
            }
            r.addView(k, new LinearLayout.LayoutParams(-2, px(38)));
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
            awaitingPermission = true;
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
        writing = mode == WRITE || mode == WRITE_BOOT || mode == WRITE_RESTORE || mode == WRITE_BOOT_ONLY;
        writeTotal = mode == WRITE_BOOT || mode == WRITE_BOOT_ONLY ? bootSectors : Math.max(0, planCount(planResult));
        if (mode == DIAGNOSE) setSteps("AIII");
        setRunningUi(mode);
        final String lib = new File(getFilesDir(), "library").getAbsolutePath();
        final String id = planId;
        final String file = restoreFile == null ? "" : restoreFile.getAbsolutePath();
        final String pkg = chosenFile == null ? "" : chosenFile.getAbsolutePath();
        final boolean ownFile = fromFile;
        ui.post(poll);
        new Thread(() -> {
            int fd = c.getFileDescriptor();
            int epIn = fIn.getAddress(), epOut = fOut.getAddress(), packet = fIn.getMaxPacketSize();
            String r = mode == BACKUP ? Native.backup(fd, fIntf.getId(), epIn, epOut, packet, dir.getAbsolutePath())
                    : mode == PLAN ? Native.planInstall(fd, fIntf.getId(), epIn, epOut, packet, dir.getAbsolutePath(), lib, id)
                    : mode == PLAN_FILE ? Native.planPackage(fd, fIntf.getId(), epIn, epOut, packet, dir.getAbsolutePath(), pkg, "file")
                    : mode == PLAN_BOOT ? Native.planPackage(fd, fIntf.getId(), epIn, epOut, packet, dir.getAbsolutePath(), "", "boot")
                    : mode == WRITE_BOOT_ONLY ? Native.installPackage(fd, fIntf.getId(), epIn, epOut, packet, dir.getAbsolutePath(), "", "boot", false)
                    : writing && mode != WRITE_RESTORE && ownFile
                            ? Native.installPackage(fd, fIntf.getId(), epIn, epOut, packet, dir.getAbsolutePath(), pkg, "file", mode == WRITE_BOOT)
                    : mode == PLAN_RESTORE ? Native.planRestore(fd, fIntf.getId(), epIn, epOut, packet, dir.getAbsolutePath(), file)
                    : mode == WRITE_RESTORE ? Native.restore(fd, fIntf.getId(), epIn, epOut, packet, dir.getAbsolutePath(), file)
                    : writing ? Native.install(fd, fIntf.getId(), epIn, epOut, packet, dir.getAbsolutePath(), lib, id, mode == WRITE_BOOT)
                    : Native.diagnose(fd, fIntf.getId(), epIn, epOut, packet, dir.getAbsolutePath());
            // the firmware's library name, before the review opens
            String named = r.startsWith("OK") ? Native.identify(lib, dir.getAbsolutePath()) : "";
            ui.post(() -> {
                running = false;
                hideBusy();
                String lines = Native.takeLog();
                if (!lines.isEmpty()) say(lines);
                Log.i(TAG, "RESULT " + r.replace('\n', ';'));
                setRunningUi(-1);
                show(r);
                name(named);
                if (mode == PLAN && opChosen != FW_OP) {
                    opChosen = FW_OP;
                }
                if (mode == PLAN || mode == PLAN_FILE || mode == PLAN_RESTORE) {
                    // the form's key shows the plan; its review opens when tapped (step 2 of 2: at once)
                    planResult = r;
                    if (mode == PLAN) {
                        for (String l : r.split("\n")) {
                            String[] p = l.split("\\|", -1);
                            if (p[0].equals("PLAN") && p.length >= 6) libraryChoice = p[1] + " " + p[2];
                        }
                    }
                    refreshForm();
                    if (!r.startsWith("OK")) say("Can't plan this: " + r.substring(Math.min(r.length(), 6)));
                    if (reviewAfterPlan) {
                        reviewAfterPlan = false;
                        review(r);
                    }
                }
                if (mode == PLAN_BOOT) reviewBoot(r);
                if (writing) {
                    planResult = null;
                    written(r, mode == WRITE_BOOT, mode == WRITE_RESTORE);
                    if (r.startsWith("OK")) setSteps("DDDD");
                }
                writing = false;
            });
        }, mode == BACKUP ? "mflr-backup" : mode == PLAN || mode == PLAN_RESTORE || mode == PLAN_FILE || mode == PLAN_BOOT ? "mflr-plan" : writing ? "mflr-write"
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
                String named = (p[1] + " " + p[2]).trim();
                currentFirmware = named;
                say("Firmware: " + named + (internal.isEmpty() ? "" : " (the unit calls it " + internal + ")"));
                for (int i = 0; i < info.getChildCount(); i++) {
                    View row = info.getChildAt(i);
                    if (!(row instanceof LinearLayout) || ((LinearLayout) row).getChildCount() < 2) continue;
                    TextView k = (TextView) ((LinearLayout) row).getChildAt(0), v = (TextView) ((LinearLayout) row).getChildAt(1);
                    // the name it was chosen by first, then what it calls itself (Figma: name flow, 79:3143)
                    if (k.getText().toString().equals("FIRMWARE")) v.setText(named + (internal.isEmpty() || internal.equals(named) ? "" : " · " + internal));
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
    /**
     * The LIBRARY tab's INSTALL: the unit is checked (switched to download mode first when running) and the
     * firmware planned, and its review opens right there, over the LIBRARY tab. The Device tab's form follows.
     */
    private void install(String id) {
        planId = id;
        fromFile = false;
        chosenFile = null;
        opChosen = FW_OP;
        planResult = null;
        if (checked) refreshForm();
        reviewAfterPlan = true;
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
        // the unit by its model: the name its firmware gives the MIDI port ("Caravel") is metadata, kept for the log
        String why = seenRunning ? "The M-Vave FM-1 did not switch automatically to Download Mode. Switch it manually "
                + "following these steps:\n"
                : "M-Flasher can't see your M-Vave FM-1 running. Check it is connected and switched on, or switch it "
                + "manually following these steps:\n";
        String last = recalled("firmware");
        howTo = dialog("Manual Action: put FM-1 in Download Mode for checking", why
                + DownloadMode.howTo(recalled("rescue"), last, midiName))
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
        busyText = text(mode == WRITE_BOOT_ONLY ? "Backing up your FM-1 on this phone…"
                : mode == PLAN_FILE || mode == PLAN_BOOT ? "Reading your FM-1 and preparing the installation…"
                : mode == RESTART ? lastSectors + " sectors written, read back and verified. Restarting the FM-1…"
                : mode == SWITCH ? "Asking your FM-1 to switch to download mode…"
                : mode == PLAN ? "Reading your FM-1 and preparing the installation…"
                : mode == PLAN_RESTORE ? "Checking the backup and comparing it with your FM-1…"
                : mode == BACKUP ? "Copying everything on your FM-1 to this phone…"
                : mode == WRITE || mode == WRITE_BOOT || mode == WRITE_RESTORE ? "Backing up your FM-1 on this phone…"
                : "Reading your FM-1…", INK, 14, false);
        box.addView(busyText);
        box.addView(busyBar);
        String title = mode == WRITE_BOOT_ONLY ? "Installing m-bootloader" : mode == PLAN_FILE || mode == PLAN_BOOT ? "Preparing"
                : mode == RESTART ? "RESTARTING FM-1" : mode == SWITCH ? "CHECKING FM-1" : mode == PLAN || mode == PLAN_RESTORE ? "Preparing"
                : mode == BACKUP ? "Saving a backup"
                : mode == WRITE_RESTORE ? "Restoring"
                : mode == WRITE_BOOT ? "Installing m-bootloader" : mode == WRITE ? "Installing" : "Reading";
        busy = Ui.dialog(this, mode == RESTART ? MINT : LAV)
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
            dialog("Installation stopped", NOW, why + "\n\nDon't switch the FM-1 off. Try again, with another cable if it "
                    + "happens again: the backup taken first is on this phone, and the rescue mode (REC while switching "
                    + "on) is still there.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String done = "", backup = "";
        for (String l : lines) {
            String[] p = l.split("\\|", -1);
            if (p[0].equals("DONE") && p.length >= 3) {
                done = p[1] + " sectors (" + p[2] + ")";
                lastSectors = Integer.parseInt(p[1]);
            }
            if (p[0].equals("BACKUP") && p.length >= 2) {
                backup = new File(p[1]).getName();
                lastBackupPath = p[1];
            }
        }
        say("Written and verified: " + done + " · backup " + backup);
        if (restored) {
            restartThenSay("Restored", "Your FM-1 holds again what this backup saved, and everything written was "
                    + "checked.", "What was there just before is saved on this phone (" + backup + ").");
        } else if (step1) {
            dialog("m-bootloader " + bootVersion + " INSTALLED", MINT, "FM-1 bootloader is now protected with m-bootloader "
                    + bootVersion + ". It was written and verified (" + lastSectors + " sectors). Next Step: Confirm "
                    + "firmware install.")
                    .setPositiveButton("CONTINUE", (d, w) -> {
                        reviewAfterPlan = true;
                        run(fromFile ? PLAN_FILE : PLAN);
                    }).setCancelable(false).show();
        } else {
            restartThenSay("FIRMWARE RUNNING", "Everything was written and checked.",
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
            // the MIDI devices before: what shows up after the restart is the unit, whatever its firmware calls it
            final List<String> before = DownloadMode.seen(MainActivity.this);
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
                    /** The result's title: the write's, or what to do when it can't be confirmed. */
                    // Figma scenario 11: the firmware by the name it was chosen by
                    String shown = title.equals("FIRMWARE RUNNING") && !fw.isEmpty() ? fw + " running" : title;
                    int color = title.equals("FIRMWARE RUNNING") ? MINT : LAV;

                    @Override
                    public void run() {
                        long t = System.currentTimeMillis() - started;
                        String name = DownloadMode.running(MainActivity.this);
                        if (name == null) {
                            for (String n : DownloadMode.seen(MainActivity.this)) if (!before.contains(n)) name = n;
                        }
                        boolean inDownload = find() != null;
                        if (!inDownload) gone = true;
                        String end = null;
                        if (name != null) {
                            say("Running: " + name + " on USB MIDI");
                            end = done + "\n\nYour FM-1 restarted and is running" + (fw.isEmpty() ? "" : " " + fw) + ".";
                            showDone(fw);
                        } else if (gone && inDownload) {
                            say("The FM-1 came back in download mode: what was written did not start");
                            hideBusy();
                            firmwareError();
                            return;
                        } else if (!gone && t > 5000) {
                            say("The FM-1 did not leave download mode");
                            end = done + "\n\nThe FM-1 did not restart: switch it off and on to start it.";
                        } else if (t > 60000) {
                            say("Restarted; not on USB MIDI within 60 s");
                            shown = "Check FM-1 display";
                            color = LAV;
                            end = done + "\n\nYour FM-1 restarted, but M-Flasher couldn't confirm it's running: it didn't "
                                    + "show up on USB within 60 seconds. Look at the FM-1 display: if the new firmware "
                                    + "shows up, it's ready. If not, switch the FM-1 off and on.";
                        }
                        if (end == null) {
                            ui.postDelayed(this, 500);
                            return;
                        }
                        hideBusy();
                        dialog(shown, color, end + "\n\n" + backupLine).setPositiveButton("OK", null).show();
                    }
                }, 500);
            });
        }, "mflr-restart").start();
    }

    // ---- review (Figma A03 and the desktop's REVIEW): nothing is written yet

    private AlertDialog.Builder dialog(String title, String message) {
        return dialog(title, LAV, message);
    }

    /** A dialog whose title has the Figma color of its kind: lavender, mint when done, red when refused or failed. */
    private AlertDialog.Builder dialog(String title, int color, String message) {
        return Ui.dialog(this, color).setTitle(title).setMessage(message);
    }

    /** FIRMWARE ERROR (Figma 46:666): written and verified, but the FM-1 came back in download mode. */
    private void firmwareError() {
        final File saved = lastBackupPath.isEmpty() ? null : new File(lastBackupPath);
        Ui.rows(this, dialog("FIRMWARE ERROR", BAD, lastSectors + " sectors written, read back and verified. But the "
                + "unit came back in download mode: what was written did not start. Its backup is saved: restore it, or "
                + "install another firmware."), new Ui.Key[][] {
                {new Ui.Key("RESTORE BACKUP", "primary", () -> {
                    if (saved == null) {
                        showTab(2);
                        return;
                    }
                    restoreFile = saved;
                    showTab(0);
                    chooseOp(RESTORE_OP);
                    restoreFile = saved;
                    run(PLAN_RESTORE);
                })},
                {new Ui.Key("INSTALL FIRMWARE", "secondary", () -> showTab(1)), new Ui.Key("OK", "tertiary", null)}})
                .show();
    }

    /** Figma A10: the FM-1 restarted and runs what was written. */
    private void showDone(String fw) {
        lcdTitle.setText("DONE");
        lcdStatus.setText(lastSectors + " SECTORS VERIFIED");
        lcdDetail.setText("RESTARTED" + (fw.isEmpty() ? "" : " · " + fw + " RUNNING"));
        lcdPrompt.setText("TAP CHECK TO READ IT AGAIN");
        unit.setText("FM-1 RUNNING");
        Ui.setLed(this, unitLed, 0);
        setSteps("DDDD");
    }

    /** While the app works: the keys keep their labels, disabled; the LCD and App Bar say what happens (A3, A9). */
    private void setRunningUi(int mode) {
        boolean on = mode >= 0;
        Ui.enable(read, !on);
        Ui.enable(bootBtn, !on && checked && !bootOpen);
        if (opKeys != null) for (OpKey k : opKeys) Ui.enable(k, !on || k.isLit());
        Ui.enable(formChoose, !on);
        Ui.enable(formOther, !on);
        lcdBar.setVisibility(on && writing ? View.VISIBLE : View.GONE);
        if (!on) {
            refreshForm();
            return;
        }
        Ui.enable(formPrimary, false);
        if (writing) {
            formPrimary.setText("WRITING…");
            lcdTitle.setText("WRITING");
            lcdStatus.setText(writeTotal > 0 ? "0 / " + writeTotal + " · 0 %" : "0 %");
            lcdDetail.setText("SAVING A BACKUP FIRST");
            lcdPrompt.setText("DO NOT UNPLUG");
            setLcdBar(0);
            unit.setText("WRITING");
            Ui.setLed(this, unitLed, 1);
        } else {
            lcdTitle.setText(mode == BACKUP ? "SAVING…" : mode == DIAGNOSE ? "CHECKING…" : "PREPARING…");
            lcdStatus.setText(mode == BACKUP ? "COPYING THE FLASH" : "READING THE FLASH");
            lcdDetail.setText(mode == BACKUP ? "TO THIS PHONE" : mode == DIAGNOSE ? "AND CHECKING IT" : "PLANNING THE WRITE");
            lcdPrompt.setText("DO NOT UNPLUG");
            unit.setText(mode == BACKUP ? "SAVING…" : mode == DIAGNOSE ? "CHECKING…" : "PREPARING…");
            Ui.setLed(this, unitLed, 0);
        }
    }

    private void setLcdBar(int percent) {
        ((LinearLayout.LayoutParams) lcdBarFill.getLayoutParams()).weight = Math.max(0, Math.min(100, percent));
        ((LinearLayout.LayoutParams) lcdBarRest.getLayoutParams()).weight = 100 - Math.max(0, Math.min(100, percent));
        lcdBar.requestLayout();
    }

    /** The review is the confirmation, as on the desktop: its write key is the Primary one (Ui.keys). */
    private void showReview(AlertDialog d) {
        d.show();
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
                + warnings.toString().trim())
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
            String why = result.substring(Math.min(result.length(), 6));
            if (why.contains("older flash chip")) {
                dialog("WARNING: NEW " + flashName() + " CHIP", BAD, "Refused: " + why.replace("; this unit has the newer one",
                        ". This FM-1 has a new chip (" + flashName() + ")")).setPositiveButton("OK", null).show();
            } else {
                dialog("Can't install this", BAD, why).setPositiveButton("OK", null).show();
            }
            return;
        }
        String[] plan = null, boot = null;
        StringBuilder notes = new StringBuilder();
        for (String l : lines) {
            String[] p = l.split("\\|", -1);
            if (p[0].equals("PLAN") && p.length >= 6) plan = p;
            if (p[0].equals("BOOT") && p.length >= 4) {
                boot = p;
                bootSectors = Integer.parseInt(p[1]);
                if (p.length >= 5 && !p[4].isEmpty()) bootVersion = p[4].split("-")[0];
            }
            if ((p[0].equals("PNOTE") || p[0].equals("PWARN")) && p.length >= 2) notes.append(p[1]).append("\n");
        }
        if (plan == null) return;
        final String[] fw = plan;
        int n = Integer.parseInt(fw[3]);
        say("Plan: " + fw[5] + " · " + n + " sectors · " + fw[4]);
        if (n == 0) {
            dialog(fw[1] + " " + fw[2] + " is already installed", NOW, "Nothing to write: your FM-1 already holds this firmware.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        if (boot != null) {
            String target = (fw[1] + " " + fw[2]).trim();
            Ui.rows(this, dialog("STOCK BOOTLOADER DETECTED", "Replace the stock bootloader with m-bootloader for FM-1 "
                    + "safeguards.\n\nStep 1: Install m-bootloader. After that, it's easier to access FM-1 safeguards. "
                    + "Before switching on the FM-1, hold PLAY button for Recovery Mode.\nStep 2: App shows the " + target
                    + " confirmation box. App only installs the firmware with your authorization."), new Ui.Key[][] {
                    {new Ui.Key("CANCEL", "tertiary", null)},
                    {new Ui.Key("SKIP M-BOOTLOADER", "secondary", () -> reviewFirmware(fw, notes.toString()))},
                    {new Ui.Key(("INSTALL M-BOOTLOADER " + bootVersion).trim(), "primary", () -> run(WRITE_BOOT))}})
                    .show();
        } else {
            reviewFirmware(fw, notes.toString());
        }
    }

    private void reviewFirmware(String[] fw, String notes) {
        int n = Integer.parseInt(fw[3]);
        String replaces = currentFirmware.isEmpty() ? "" : "This replaces " + currentFirmware + " on your FM-1.\n\n";
        AlertDialog d = dialog("Install " + (fw[1] + " " + fw[2]).trim() + "?", replaces
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
