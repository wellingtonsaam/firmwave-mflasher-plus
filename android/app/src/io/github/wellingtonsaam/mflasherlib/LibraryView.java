package io.github.wellingtonsaam.mflasherlib;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The LIBRARY tab (Figma A02): the FirmwareHub list, search, favorites, This unit only and Betas
 * filters, GET / UPDATE and the trash. Downloads only: installing comes with the write review.
 */
final class LibraryView extends LinearLayout {
    private static final String TAG = "MFLR";

    /** One firmware of the list. */
    private static final class Item {
        String id, name, version, byline, kinds, chip, older;
        boolean beta, saved, elsewhere, favorite;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final File dir;
    private final List<Item> items = new ArrayList<>();
    private final Set<String> busy = new HashSet<>();
    /** The row shown for each firmware: changed in place, so the list keeps its scroll. */
    private final Map<String, View> rows = new HashMap<>();
    private final LinearLayout list;
    private final ScrollView scroll;
    private final TextView count, hidden, note;
    private final android.widget.ImageView star;
    private String query = "";
    private boolean favoritesOnly, unitOnly = true, betas = true, loading;
    /** The unit's flash chip ("old", "new", "") while one is connected, else null. */
    private String unitChip;

    LibraryView(Context c, File dir, Installer installer) {
        super(c);
        this.dir = dir;
        this.installer = installer;
        setOrientation(VERTICAL);
        int p = Ui.px(c, 16);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(VERTICAL);
        body.setPadding(p, Ui.px(c, 4), p, p);
        scroll = new ScrollView(c);
        scroll.addView(body);
        addView(scroll, new LayoutParams(-1, -1));

        body.addView(Ui.section(c, "FIRMWARE LIBRARY"));
        LinearLayout search = new LinearLayout(c);
        search.setGravity(Gravity.CENTER_VERTICAL);
        EditText field = new EditText(c);
        field.setHint("Search name, author, kind");
        field.setHintTextColor(Ui.DIM);
        field.setTextColor(Ui.INK);
        field.setTextSize(13);
        field.setSingleLine(true);
        field.setTypeface(android.graphics.Typeface.MONOSPACE);
        field.setBackground(Ui.box(c, Ui.FIELD, Ui.LINE, 3));
        field.setPadding(Ui.px(c, 12), Ui.px(c, 8), Ui.px(c, 12), Ui.px(c, 8));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                render();
            }
        });
        search.addView(field, new LayoutParams(0, Ui.px(c, 44), 1));
        star = icon("ic_star_outline", Ui.DIM, 24);
        star.setContentDescription("Favorites only");
        star.setOnClickListener(v -> {
            favoritesOnly = !favoritesOnly;
            star.setImageResource(c.getResources().getIdentifier(favoritesOnly ? "ic_star" : "ic_star_outline", "drawable", c.getPackageName()));
            star.setImageTintList(android.content.res.ColorStateList.valueOf(favoritesOnly ? Ui.WARN : Ui.DIM));
            render();
        });
        search.addView(star);
        body.addView(search);

        LinearLayout filters = new LinearLayout(c);
        filters.setGravity(Gravity.CENTER_VERTICAL);
        filters.setPadding(0, Ui.px(c, 6), 0, 0);
        filters.addView(check(c, "This unit only", true, on -> unitOnly = on));
        filters.addView(check(c, "Betas", true, on -> betas = on));
        count = Ui.text(c, "", Ui.DIM, 11, false);
        count.setGravity(Gravity.END);
        filters.addView(count, new LayoutParams(0, -2, 1));
        body.addView(filters);
        hidden = Ui.text(c, "", Ui.WARN, 11, false);
        body.addView(hidden);

        list = new LinearLayout(c);
        list.setOrientation(VERTICAL);
        // a rebuilt list must not pull the scroll to a focused row
        list.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        list.setPadding(0, Ui.px(c, 8), 0, 0);
        body.addView(list);
        note = Ui.text(c, "", Ui.DIM, 11, false);
        note.setPadding(0, Ui.px(c, 12), 0, 0);
        body.addView(note);
    }

    private interface OnCheck { void set(boolean on); }

    /** Plans installing a saved firmware on the unit connected, for review. */
    interface Installer { void install(String id); }

    private final Installer installer;

    private CheckBox check(Context c, String label, boolean on, OnCheck f) {
        CheckBox b = new CheckBox(c);
        b.setText(label);
        b.setTextColor(Ui.INK);
        b.setTextSize(12);
        b.setTypeface(android.graphics.Typeface.MONOSPACE);
        b.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.MINT));
        b.setChecked(on);
        b.setOnCheckedChangeListener((v, checked) -> { f.set(checked); render(); });
        return b;
    }

    /** The unit connected (its chip, "" if unknown) or none (null): This unit only follows it. */
    void setUnit(String chip) {
        unitChip = chip;
        render();
    }

    /** Reads the list (the hub at most once a day; offline, the one on this phone). */
    void load() {
        if (loading) return;
        loading = true;
        note.setText("Reading the firmware list…");
        new Thread(() -> {
            String r = Native.librarySync(dir.getAbsolutePath());
            ui.post(() -> {
                loading = false;
                String[] lines = r.split("\n");
                if (!lines[0].equals("OK")) {
                    note.setText(r.substring(Math.min(r.length(), 6)));
                    return;
                }
                items.clear();
                String n = "";
                for (int i = 1; i < lines.length; i++) {
                    String[] p = lines[i].split("\\|", -1);
                    if (p[0].equals("NOTE")) n = p[1];
                    if (!p[0].equals("FW") || p.length < 12) continue;
                    Item it = new Item();
                    it.id = p[1]; it.name = p[2]; it.version = p[3]; it.byline = p[4]; it.kinds = p[5]; it.chip = p[6];
                    it.beta = p[7].equals("1"); it.saved = p[8].equals("1"); it.elsewhere = p[9].equals("1");
                    it.older = p[10]; it.favorite = p[11].equals("1");
                    items.add(it);
                }
                Log.i(TAG, "Library: " + items.size() + " firmwares · " + n);
                note.setText("GET downloads for later · INSTALL switches the FM-1 to download mode first.\n" + n
                        + "\nFirmware list: The Firmware Hub · thefirmwarehub.com (CC BY 4.0)");
                render();
            });
        }, "mflr-library").start();
    }

    private boolean matches(Item it) {
        if (query.isEmpty()) return true;
        for (String s : new String[] {it.name, it.id, it.byline, it.kinds}) {
            if (s.toLowerCase(Locale.ROOT).contains(query)) return true;
        }
        return false;
    }

    private boolean fits(Item it) {
        return !("new".equals(unitChip) && "old".equals(it.chip));
    }

    private void render() {
        if (list == null) return;
        list.removeAllViews();
        rows.clear();
        int shown = 0, byUnit = 0, byBeta = 0;
        for (Item it : items) {
            if (!matches(it) || (favoritesOnly && !it.favorite)) continue;
            // as on the desktop: also the firmwares installed with their authors' own tools
            if (unitOnly && (it.elsewhere || !fits(it))) { byUnit++; continue; }
            if (!betas && it.beta) { byBeta++; continue; }
            shown++;
            View r = row(it);
            rows.put(it.id, r);
            list.addView(r);
        }
        count.setText(shown + " FOUND");
        StringBuilder h = new StringBuilder();
        if (byUnit > 0) h.append(byUnit).append(" MORE HIDDEN BY THIS UNIT ONLY");
        if (byBeta > 0) h.append(h.length() > 0 ? " · " : "").append(byBeta).append(" MORE HIDDEN BY BETAS");
        hidden.setText(h);
        hidden.setVisibility(h.length() > 0 ? VISIBLE : GONE);
        scroll.post(() -> scroll.scrollTo(0, 0));
        if (shown == 0 && !loading) list.addView(Ui.text(getContext(), items.isEmpty() ? "No list yet." : "Nothing found.", Ui.DIM, 12, false));
    }

    /** Redraws one firmware's row where it is. */
    private void refresh(Item it) {
        View old = rows.get(it.id);
        int i = old == null ? -1 : list.indexOfChild(old);
        if (i < 0) { render(); return; }
        View r = row(it);
        list.removeViewAt(i);
        list.addView(r, i);
        rows.put(it.id, r);
    }

    private android.widget.ImageView icon(String name, int color, float size) {
        Context c = getContext();
        android.widget.ImageView v = new android.widget.ImageView(c);
        v.setImageResource(c.getResources().getIdentifier(name, "drawable", c.getPackageName()));
        v.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        int pad = Ui.px(c, 8);
        v.setPadding(pad, pad, pad, pad);
        v.setLayoutParams(new LayoutParams(Ui.px(c, size) + 2 * pad, Ui.px(c, size) + 2 * pad));
        return v;
    }

    private View row(Item it) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(VERTICAL);
        r.setPadding(Ui.px(c, 12), Ui.px(c, 10), Ui.px(c, 12), Ui.px(c, 10));
        r.setBackground(Ui.box(c, Ui.RAISED, Ui.LINE, 3));
        LayoutParams lp = new LayoutParams(-1, -2);
        lp.bottomMargin = Ui.px(c, 8);
        r.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView fav = icon(it.favorite ? "ic_star" : "ic_star_outline", it.favorite ? Ui.WARN : Ui.DIM, 20);
        fav.setContentDescription(it.favorite ? "Favorite" : "Not a favorite");
        fav.setOnClickListener(v -> toggleFavorite(it));
        head.addView(fav);
        head.addView(Ui.text(c, it.name, Ui.INK, 15, true));
        TextView ver = Ui.text(c, "  " + it.version, Ui.DIM, 12, false);
        head.addView(ver);
        r.addView(head);
        String meta = it.byline + (it.kinds.isEmpty() ? "" : " · " + it.kinds.toUpperCase(Locale.ROOT)) + (it.beta ? " · BETA" : "");
        r.addView(Ui.text(c, meta, Ui.DIM, 11, false));

        LinearLayout foot = new LinearLayout(c);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, Ui.px(c, 6), 0, 0);
        boolean getting = busy.contains(it.id);
        String status;
        int color;
        if (getting) { status = "DOWNLOADING…"; color = Ui.DIM; }
        else if (!fits(it)) { status = "OLDER CHIP ONLY · NOT FOR THIS UNIT"; color = Ui.BAD; }
        else if (it.saved) { status = "SAVED · SHA-256 OK"; color = Ui.MINT; }
        else if (!it.older.isEmpty()) { status = it.older + " SAVED · " + it.version + " OUT"; color = Ui.WARN; }
        else if (it.elsewhere) { status = "INSTALLED WITH THE AUTHOR'S OWN TOOL"; color = Ui.DIM; }
        else { status = "NOT ON THIS PHONE"; color = Ui.DIM; }
        foot.addView(Ui.text(c, status, color, 11, false), new LayoutParams(0, -2, 1));
        if (it.saved || !it.older.isEmpty()) {
            android.widget.ImageView trash = icon("ic_delete", Ui.DIM, 22);
            trash.setContentDescription("Delete from this phone");
            trash.setOnClickListener(v -> confirmDelete(it));
            foot.addView(trash);
        }
        if (!it.elsewhere || it.saved) {
            String label = it.saved ? "INSTALL" : it.older.isEmpty() ? "GET" : "UPDATE";
            Button k = Ui.key(c, label, it.saved);
            k.setTextSize(12);
            if (it.saved) {
                // switched to download mode first when needed; the plan is reviewed before anything is written
                Ui.enable(k, fits(it) && !it.elsewhere);
                k.setOnClickListener(v -> installer.install(it.id));
            } else {
                Ui.enable(k, !getting);
                k.setOnClickListener(v -> get(it));
            }
            foot.addView(k, new LayoutParams(-2, Ui.px(c, 40)));
        }
        r.addView(foot);
        return r;
    }

    private void toggleFavorite(Item it) {
        it.favorite = !it.favorite;
        if (favoritesOnly) render(); else refresh(it);
        new Thread(() -> {
            String r = Native.libraryFavorite(dir.getAbsolutePath(), it.id, it.favorite);
            Log.i(TAG, "FAVORITE " + it.id + " " + it.favorite + ": " + r.replace('\n', ' '));
            if (!r.startsWith("OK")) ui.post(() -> Toast.makeText(getContext(), r, Toast.LENGTH_LONG).show());
        }, "mflr-favorite").start();
    }

    private void get(Item it) {
        busy.add(it.id);
        refresh(it);
        new Thread(() -> {
            String r = Native.libraryGet(dir.getAbsolutePath(), it.id);
            ui.post(() -> {
                busy.remove(it.id);
                Log.i(TAG, "GET " + it.id + ": " + r.replace('\n', ' '));
                if (r.startsWith("OK")) {
                    it.saved = true;
                    Toast.makeText(getContext(), it.name + " " + it.version + " saved · SHA-256 OK", Toast.LENGTH_SHORT).show();
                } else {
                    new AlertDialog.Builder(getContext(), android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("COULD NOT GET " + it.name.toUpperCase(Locale.ROOT))
                            .setMessage(r.substring(Math.min(r.length(), 6)))
                            .setPositiveButton("OK", null).show();
                }
                refresh(it);
            });
        }, "mflr-get").start();
    }

    /** Figma A04: asked first; the unit is never touched. */
    private void confirmDelete(Item it) {
        String versions = it.saved ? it.version + (it.older.isEmpty() ? "" : ", " + it.older) : it.older;
        new AlertDialog.Builder(getContext(), android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("DELETE FROM THIS PHONE?")
                .setMessage(it.name + ": " + versions + " saved on this phone.\n\nGET downloads it again later. Nothing on the unit changes.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE", (d, w) -> new Thread(() -> {
                    String r = Native.libraryDelete(dir.getAbsolutePath(), it.id);
                    ui.post(() -> {
                        Log.i(TAG, "DELETE " + it.id + ": " + r.replace('\n', ' '));
                        if (r.startsWith("OK")) { it.saved = false; it.older = ""; }
                        else Toast.makeText(getContext(), r, Toast.LENGTH_LONG).show();
                        refresh(it);
                    });
                }, "mflr-delete").start())
                .show();
    }
}
