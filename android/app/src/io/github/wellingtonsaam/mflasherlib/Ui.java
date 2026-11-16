package io.github.wellingtonsaam.mflasherlib;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/** The Figma specs (Components page: Button, Op Button, Section Label, LED, Step, Android Dialog) for the app's views. */
final class Ui {
    static final int BG = 0xFF28221F, PANEL = 0xFF221D1A, RAISED = 0xFF332C28, FIELD = 0xFF1D1917, LINE = 0xFF3C3433,
            INK = 0xFFE9E4DC, DIM = 0xFF8D837B, LAV = 0xFFB6A2E6, LAVKEY = 0xFF8F80B6, LAVLINE = 0xFF5D5172,
            MINT = 0xFF7BE8BB, WARN = 0xFFF2B44C, BAD = 0xFFFF6A5A, NOW = 0xFFFF4A2A,
            KEY = 0xFF23706F, KEYDARK = 0xFF174B4D, KEYLINE = 0xFF2F8888, KEYINK = 0xFFD6EFE8,
            SECLINE = 0xFF5A5060, TERTIARY = 0xFFA9D8E6, STEPIDLE = 0xFF312A26,
            BEZEL = 0xFF141110, BEZELLINE = 0xFF3A322D, LCD = 0xFFB8CF66, LCDLINE = 0xFF77913A, LCDINK = 0xFF1D2710,
            DIALOG = 0xFF312A26, DIALOGINK = 0xFFE0E0E0;

    /** The Figma text styles' families, from res/font. */
    static final String TECH = "share_tech_mono", MONO = "ibm_plex_mono", LCDFONT = "vt323",
            KEYFONT = "barlow_condensed_semibold", HEAD = "barlow_condensed_bold";

    private static final Map<String, Typeface> fonts = new HashMap<>();

    private Ui() {}

    static int px(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** A font from res/font, the system's monospace if missing. */
    static Typeface font(Context c, String name) {
        Typeface t = fonts.get(name);
        if (t == null) {
            int id = c.getResources().getIdentifier(name, "font", c.getPackageName());
            try {
                t = id != 0 ? c.getResources().getFont(id) : null;
            } catch (RuntimeException e) {
                t = null;
            }
            if (t == null) t = Typeface.MONOSPACE;
            fonts.put(name, t);
        }
        return t;
    }

    /** Body/Tech: Share Tech Mono (bold: drawn heavier, the font has one weight). */
    static TextView text(Context c, String s, int color, float size, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setTypeface(font(c, TECH), bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    /** Body/Mono: IBM Plex Mono (the log, details, OR). */
    static TextView mono(Context c, String s, int color, float size) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setTypeface(font(c, MONO));
        return t;
    }

    /** Label/Section: Share Tech Mono 12, capitals, 2.4 px apart. */
    static TextView label(Context c, String s, int color) {
        TextView t = text(c, s, color, 12, false);
        t.setAllCaps(true);
        t.setLetterSpacing(0.2f);
        return t;
    }

    static GradientDrawable box(Context c, int fill, int stroke, float radius) {
        return box(c, fill, stroke, 1, radius);
    }

    static GradientDrawable box(Context c, int fill, int stroke, float strokeWidth, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (stroke != 0) g.setStroke(px(c, strokeWidth), stroke);
        g.setCornerRadius(px(c, radius));
        return g;
    }

    /** Button, Size=Default: Primary or Secondary, Barlow Condensed SemiBold 17. */
    static Button key(Context c, String label, boolean primary) {
        Button k = new Button(c);
        k.setText(label);
        styleKey(c, k, primary ? "primary" : "secondary", 17, 20, 8);
        k.setMinHeight(px(c, 38));
        k.setMinimumHeight(px(c, 38));
        return k;
    }

    /** Disabled: the Figma 40 %. */
    /** When a backup was saved, from its name ("20261116-165332"); its file's time if the name has none. */
    static String savedAt(java.io.File f) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{8}-\\d{6})").matcher(f.getName());
        return m.find() ? m.group(1)
                : new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date(f.lastModified()));
    }

    static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.4f);
    }

    /** Section Label, Tone=Lavender: the label, then a rule to the edge. Its label: {@link #sectionText}. */
    static LinearLayout section(Context c, String label) {
        LinearLayout s = new LinearLayout(c);
        s.setGravity(Gravity.CENTER_VERTICAL);
        s.addView(label(c, label, LAV));
        View rule = new View(c);
        rule.setBackgroundColor(LAVLINE);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, px(c, 1), 1);
        rp.setMargins(px(c, 8), 0, 0, 0);
        s.addView(rule, rp);
        return s;
    }

    static TextView sectionText(View section) {
        return (TextView) ((LinearLayout) section).getChildAt(0);
    }

    /** LED: 10 dp, Off, On (mint) or Warn. */
    static View led(Context c) {
        View v = new View(c);
        setLed(c, v, 0);
        return v;
    }

    /** 0 off, 1 on, 2 warn. */
    static void setLed(Context c, View v, int state) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(state == 1 ? MINT : state == 2 ? WARN : BEZEL);
        g.setStroke(px(c, 1), BEZELLINE);
        v.setBackground(g);
    }

    // ---- dialogs (Figma Android Dialog)

    /** A dialog with a lavender title. */
    static AlertDialog.Builder dialog(Context c) {
        return dialog(c, LAV);
    }

    /**
     * A dialog as the Figma Android Dialog: #312a26, its title Share Tech Mono 18 in capitals in {@code titleColor},
     * its text Share Tech Mono 14, its keys the desktop's Button, Size=Small.
     */
    static AlertDialog.Builder dialog(Context c, int titleColor) {
        return new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert) {
            @Override
            public AlertDialog.Builder setTitle(CharSequence title) {
                TextView t = text(c, title == null ? "" : title.toString(), titleColor, 18, false);
                t.setAllCaps(true);
                t.setPadding(px(c, 24), px(c, 22), px(c, 24), px(c, 6));
                return setCustomTitle(t);
            }

            @Override
            public AlertDialog create() {
                AlertDialog d = super.create();
                if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(box(c, DIALOG, 0, 4));
                d.setOnShowListener(x -> {
                    TextView m = d.findViewById(android.R.id.message);
                    if (m != null) {
                        m.setTypeface(font(c, TECH));
                        m.setTextSize(14);
                        m.setTextColor(DIALOGINK);
                        m.setLineSpacing(px(c, 4), 1f);
                    }
                    keys(d);
                });
                return d;
            }
        };
    }

    /** A key of a dialog's own rows: its label, its Figma kind, what it does once the dialog closes. */
    static final class Key {
        final String label, kind;
        final Runnable run;

        Key(String label, String kind, Runnable run) {
            this.label = label;
            this.kind = kind;
            this.run = run;
        }
    }

    /** Android Dialog, Kind=Two rows: keys in rows from the left, in reading order (Figma 46:476, 46:666). */
    static AlertDialog rows(Context c, AlertDialog.Builder b, Key[][] rows) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(c, 24), px(c, 8), px(c, 24), px(c, 16));
        b.setView(col);
        AlertDialog d = b.create();
        for (Key[] row : rows) {
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            for (Key k : row) {
                Button btn = new Button(c);
                btn.setText(k.label);
                smallKey(c, btn, k.kind);
                btn.setOnClickListener(v -> {
                    d.dismiss();
                    if (k.run != null) k.run.run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMargins(0, 0, px(c, 8), 0);
                r.addView(btn, lp);
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.setMargins(0, px(c, 8), 0, 0);
            col.addView(r, rp);
        }
        return d;
    }

    /** Android Dialog, Kind=List: one Secondary key per item, full width, and CANCEL (Figma 46:531). */
    static AlertDialog list(Context c, AlertDialog.Builder b, String[] items, java.util.function.IntConsumer pick) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(c, 24), px(c, 8), px(c, 24), 0);
        // many backups: the list scrolls, CANCEL stays
        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.addView(col);
        b.setView(scroll).setNegativeButton("CANCEL", null);
        AlertDialog d = b.create();
        for (int i = 0; i < items.length; i++) {
            final int w = i;
            Button btn = new Button(c);
            btn.setText(items[i]);
            smallKey(c, btn, "secondary");
            btn.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            btn.setOnClickListener(v -> {
                d.dismiss();
                pick.accept(w);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, i == 0 ? 0 : px(c, 8), 0, 0);
            col.addView(btn, lp);
        }
        return d;
    }

    /** The Figma kinds by role: the write Primary, alternatives Secondary, CANCEL Tertiary, DELETE Destructive;
     *  an OK alone closes as Primary, beside other actions as Tertiary. */
    static void keys(AlertDialog d) {
        Context c = d.getContext();
        Button pos = d.getButton(DialogInterface.BUTTON_POSITIVE), neg = d.getButton(DialogInterface.BUTTON_NEGATIVE),
                neu = d.getButton(DialogInterface.BUTTON_NEUTRAL);
        boolean others = shown(neg) || shown(neu);
        for (Button b : new Button[] {pos, neg, neu}) {
            if (!shown(b)) continue;
            String label = b.getText().toString().toUpperCase();
            String kind = label.equals("CANCEL") ? "tertiary"
                    : label.equals("DELETE") ? "destructive"
                    : label.equals("OK") ? (others ? "tertiary" : "primary")
                    : b == pos ? "primary" : "secondary";
            smallKey(c, b, kind);
        }
    }

    private static boolean shown(Button b) {
        return b != null && b.getVisibility() == View.VISIBLE && b.getText().length() > 0;
    }

    /** Button, Size=Small: 14 sp, 12 x 6 dp padding, 3 dp corners (Figma 4:28). */
    static void smallKey(Context c, Button b, String kind) {
        styleKey(c, b, kind, 14, 12, 6);
        b.setMinHeight(px(c, 31));
        b.setMinimumHeight(px(c, 31));
        if (b.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) b.getLayoutParams();
            m.setMargins(px(c, 4), px(c, 4), px(c, 4), px(c, 4));
            b.setLayoutParams(m);
        }
    }

    private static void styleKey(Context c, Button b, String kind, float size, float padX, float padY) {
        b.setAllCaps(true);
        b.setTypeface(font(c, KEYFONT));
        b.setTextSize(size);
        b.setLetterSpacing(0.035f);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(px(c, padX), px(c, padY), px(c, padX), px(c, padY));
        switch (kind) {
            case "primary":
                b.setTextColor(KEYINK);
                b.setBackground(box(c, KEY, KEYLINE, 3));
                break;
            case "secondary":
                b.setTextColor(INK);
                b.setBackground(box(c, FIELD, SECLINE, 3));
                break;
            case "destructive":
                b.setTextColor(INK);
                b.setBackground(box(c, NOW, 0, 3));
                break;
            default: // tertiary: a link, no fill
                b.setTextColor(TERTIARY);
                b.setBackground(null);
        }
    }
}
