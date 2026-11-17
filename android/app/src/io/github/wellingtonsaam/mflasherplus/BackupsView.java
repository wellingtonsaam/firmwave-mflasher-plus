package io.github.wellingtonsaam.mflasherplus;

import android.app.AlertDialog;
import android.content.Context;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The BACKUPS tab: every full backup saved on this phone, newest first, to put back on the unit
 * (reviewed first, as any write) or to delete.
 */
final class BackupsView extends LinearLayout {
    private static final String TAG = "MFLR";

    /** Plans putting back a backup on the unit connected, for review. */
    interface Restorer { void restore(File backup); }

    private final File dir;
    private final Restorer restorer;
    private final LinearLayout list;
    private final TextView count;
    private boolean unit;

    BackupsView(Context c, File dir, Restorer restorer) {
        super(c);
        this.dir = dir;
        this.restorer = restorer;
        setOrientation(VERTICAL);
        int p = Ui.px(c, 16);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(VERTICAL);
        body.setPadding(p, Ui.px(c, 4), p, p);
        ScrollView scroll = new ScrollView(c);
        scroll.addView(body);
        addView(scroll, new LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.BOTTOM);
        head.addView(Ui.section(c, "BACKUPS ON THIS PHONE"), new LayoutParams(0, -2, 1));
        count = Ui.text(c, "", Ui.DIM, 11, false);
        count.setPadding(0, 0, 0, Ui.px(c, 6));
        head.addView(count);
        body.addView(head);
        body.addView(Ui.text(c, "Each one is everything your FM-1 held when it was saved. The app saves one before "
                + "every write.", Ui.DIM, 12, false));
        list = new LinearLayout(c);
        list.setOrientation(VERTICAL);
        list.setPadding(0, Ui.px(c, 10), 0, 0);
        body.addView(list);
    }

    /** Whether a unit in download mode is connected: RESTORE needs one. */
    void setUnit(boolean connected) {
        unit = connected;
        load();
    }

    private static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\":\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    /** "20261116-005636" → "16/11/2026 00:56". */
    private static String when(String saved) {
        if (!saved.matches("\\d{8}-\\d{6}")) return saved;
        return saved.substring(6, 8) + "/" + saved.substring(4, 6) + "/" + saved.substring(0, 4) + " "
                + saved.substring(9, 11) + ":" + saved.substring(11, 13);
    }

    void load() {
        list.removeAllViews();
        File[] bins = dir.listFiles((d, n) -> n.endsWith(".bin"));
        if (bins == null) bins = new File[0];
        // newest first by when each was saved: the name holds the firmware too ("fm1-zp12-2.5-20261116-165332.bin")
        Arrays.sort(bins, (a, b) -> Ui.savedAt(b).compareTo(Ui.savedAt(a)));
        count.setText(bins.length + (bins.length == 1 ? " BACKUP" : " BACKUPS"));
        if (bins.length == 0) {
            list.addView(Ui.text(getContext(), "No backup yet: SAVE on the DEVICE tab makes one.", Ui.DIM, 12, false));
        }
        for (File f : bins) list.addView(row(f));
    }

    private View row(File bin) {
        Context c = getContext();
        String json = "";
        File jf = new File(bin.getParent(), bin.getName().replaceAll("\\.bin$", ".json"));
        try {
            json = new String(Files.readAllBytes(jf.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // a backup without its .json: no SHA-256 to check it against, shown as such
        }
        String saved = field(json, "saved");
        if (saved.isEmpty()) saved = bin.getName().replaceAll("^[a-z0-9]+-|\\.bin$", "");
        String firmware = field(json, "firmware"), boot = field(json, "bootloader"), model = field(json, "model");
        // the name it was installed by first, then what the firmware calls itself
        String named = field(json, "firmware name");
        if (!named.isEmpty() && !named.equals(firmware)) firmware = named + " · " + firmware;
        final String date = when(saved);

        LinearLayout r = new LinearLayout(c);
        r.setOrientation(VERTICAL);
        r.setPadding(Ui.px(c, 12), Ui.px(c, 10), Ui.px(c, 12), Ui.px(c, 10));
        r.setBackground(Ui.box(c, Ui.RAISED, Ui.LINE, 3));
        LayoutParams lp = new LayoutParams(-1, -2);
        lp.bottomMargin = Ui.px(c, 8);
        r.setLayoutParams(lp);
        r.addView(Ui.text(c, date, Ui.INK, 15, true));
        String what = (model.isEmpty() ? "" : model + " · ") + (firmware.isEmpty() ? "firmware unknown" : firmware);
        r.addView(Ui.text(c, what, Ui.DIM, 12, false));
        boolean rescue = boot.startsWith("m-bootloader");
        r.addView(Ui.text(c, boot.isEmpty() ? "bootloader unknown" : rescue ? "with rescue mode (m-bootloader)"
                : "M-VAVE's original start-up program: no rescue mode", rescue || boot.isEmpty() ? Ui.DIM : Ui.WARN, 11, false));

        LinearLayout foot = new LinearLayout(c);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, Ui.px(c, 6), 0, 0);
        foot.addView(Ui.text(c, json.contains("\"sha256\"") ? "CHECKED BEFORE RESTORING (SHA-256)" : "NO .JSON: CANNOT BE CHECKED",
                json.contains("\"sha256\"") ? Ui.MINT : Ui.WARN, 10, false), new LayoutParams(0, -2, 1));
        android.widget.ImageView trash = new android.widget.ImageView(c);
        trash.setImageResource(c.getResources().getIdentifier("ic_delete", "drawable", c.getPackageName()));
        trash.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.DIM));
        int pad = Ui.px(c, 8);
        trash.setPadding(pad, pad, pad, pad);
        trash.setContentDescription("Delete this backup");
        trash.setOnClickListener(v -> confirmDelete(bin, jf, date));
        foot.addView(trash, new LayoutParams(Ui.px(c, 38), Ui.px(c, 38)));
        Button k = Ui.key(c, "RESTORE", true);
        k.setTextSize(12);
        Ui.enable(k, unit);
        k.setOnClickListener(v -> restorer.restore(bin));
        foot.addView(k, new LayoutParams(-2, Ui.px(c, 40)));
        r.addView(foot);
        return r;
    }

    private void confirmDelete(File bin, File json, String when) {
        Ui.dialog(getContext(), Ui.NOW)
                .setTitle("Delete this backup?")
                .setMessage("The backup of " + when + " is removed from this phone. It can't be brought back.\n\n"
                        + "Nothing on the FM-1 changes.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE", (d, w) -> {
                    boolean ok = bin.delete();
                    json.delete();
                    Log.i(TAG, "DELETE backup " + bin.getName() + ": " + ok);
                    load();
                })
                .show();
    }
}
