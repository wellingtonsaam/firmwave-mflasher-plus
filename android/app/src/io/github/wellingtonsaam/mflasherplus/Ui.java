package io.github.wellingtonsaam.mflasherplus;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * FirmWave · Neo Brutalism, Option A (Figma "Foundations · FirmWave", 283:9836, and the Android sections):
 * cream ground, white cards with a black outline and a hard black shadow, a lime LCD, yellow for the next step.
 * The old names stay (BG, INK, LAV…) so the views keep working: each now holds the Foundations' token for its role.
 */
final class Ui {
    // ---- FirmWave · Primitives
    static final int CREAM_100 = 0xFFFFF4DC, CREAM_50 = 0xFFFFFDF5, WHITE = 0xFFFFFFFF, BLACK = 0xFF000000,
            GRAY_700 = 0xFF4A4A4A, GRAY_500 = 0xFF6B6B6B, GRAY_800 = 0xFF3A3A3A, LIME = 0xFFB8FF6B,
            YELLOW_500 = 0xFFFFE500, LILAC = 0xFFC4A1FF, ORANGE_300 = 0xFFFF9B71, ORANGE_600 = 0xFFFF4911,
            GREEN_300 = 0xFF90EE90, GREEN_LED = 0xFF2FFF2F, GREEN_800 = 0xFF1F7A3A, GREEN_50 = 0xFFF2FFE0,
            CYAN = 0xFF7DF9FF, PINK_200 = 0xFFFFB2EF, CORAL = 0xFFFF6B6B, SAND_200 = 0xFFF3EAD3, SAND_400 = 0xFFB9AE94;

    // ---- FirmWave · Color: what each colour does
    static final int SURFACE_SAVED = GREEN_50, STATUS_OK = GREEN_300, STATUS_WARN = ORANGE_300, BG_LOG = CREAM_50,
            OP_SAVE = CYAN, OP_RESTORE = PINK_200, OP_INSTALL = LILAC, ACCENT_PRIMARY = YELLOW_500;

    // ---- the views' names for them
    static final int BG = CREAM_100, PANEL = WHITE, RAISED = WHITE, FIELD = WHITE, LINE = BLACK,
            INK = BLACK, DIM = GRAY_700, LAV = BLACK, LAVKEY = GRAY_700, LAVLINE = SAND_400,
            MINT = GREEN_800, WARN = ORANGE_600, BAD = CORAL, NOW = ORANGE_600,
            KEY = YELLOW_500, KEYDARK = WHITE, KEYLINE = BLACK, KEYINK = BLACK,
            SECLINE = BLACK, TERTIARY = BLACK, STEPIDLE = WHITE,
            BEZEL = GRAY_800, BEZELLINE = BLACK, LCD = LIME, LCDLINE = BLACK, LCDINK = BLACK,
            DIALOG = WHITE, DIALOGINK = BLACK;

    /** The FirmWave text styles' families, from res/font: Public Sans, Space Mono and Lexend Mega. */
    static final String TECH = "public_sans_medium", BOLD = "public_sans_bold", MONO = "space_mono_regular",
            LCDFONT = "space_mono_bold", KEYFONT = "public_sans_black", HEAD = "lexend_mega_black", LABEL = "lexend_mega_bold";

    private static final Map<String, Typeface> fonts = new HashMap<>();

    private Ui() {}

    static int px(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** A font from res/font, the system's sans if missing. */
    static Typeface font(Context c, String name) {
        Typeface t = fonts.get(name);
        if (t == null) {
            int id = c.getResources().getIdentifier(name, "font", c.getPackageName());
            try {
                t = id != 0 ? c.getResources().getFont(id) : null;
            } catch (RuntimeException e) {
                t = null;
            }
            if (t == null) t = Typeface.SANS_SERIF;
            fonts.put(name, t);
        }
        return t;
    }

    /** FirmWave/Body: Public Sans Medium, or Bold. */
    static TextView text(Context c, String s, int color, float size, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setTypeface(font(c, bold ? BOLD : TECH));
        return t;
    }

    /** FirmWave/Data: Space Mono (the log, details, values). */
    static TextView mono(Context c, String s, int color, float size) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setTypeface(font(c, MONO));
        return t;
    }

    /** FirmWave/Label: Lexend Mega Bold, capitals, spaced (section labels). */
    static TextView label(Context c, String s, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(10);
        t.setTypeface(font(c, LABEL));
        t.setAllCaps(true);
        t.setLetterSpacing(0.12f);
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

    /** FirmWave card: a fill, a black outline, rounded corners and a hard black shadow (no blur) down and right. */
    static Drawable card(Context c, int fill, float radius, float stroke, float shadow) {
        GradientDrawable body = box(c, fill, BLACK, stroke, radius);
        if (shadow <= 0) return body;
        LayerDrawable l = new LayerDrawable(new Drawable[] {box(c, BLACK, 0, 0, radius), body});
        int s = px(c, shadow);
        l.setLayerInset(0, s, s, 0, 0);
        l.setLayerInset(1, 0, 0, s, s);
        return l;
    }

    /** Sets a card as v's background, its padding kept and the shadow's room added at the right and bottom. */
    static void cardBg(View v, int fill, float radius, float stroke, float shadow) {
        Context c = v.getContext();
        int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
        v.setBackground(card(c, fill, radius, stroke, shadow));
        int s = shadow > 0 ? px(c, shadow) : 0;
        v.setPadding(l, t, r + s, b + s);
    }

    /** A pill: a text on its status colour, black outline (Figma DS · Tag). */
    static TextView pill(Context c, String s, int fill) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(BLACK);
        t.setTextSize(11);
        t.setTypeface(font(c, KEYFONT));
        t.setAllCaps(true);
        t.setPadding(px(c, 10), px(c, 3), px(c, 10), px(c, 3));
        t.setBackground(box(c, fill, BLACK, 2, 12));
        return t;
    }

    /** Button, Size=Default: Primary (yellow) or Secondary (white), Public Sans Black 15. */
    static Button key(Context c, String label, boolean primary) {
        Button k = new Button(c);
        k.setText(label);
        styleKey(c, k, primary ? "primary" : "secondary", 15, 18, 6);
        k.setMinHeight(px(c, 38));
        k.setMinimumHeight(px(c, 38));
        return k;
    }

    /** When a backup was saved, from its name ("20261116-165332"); its file's time if the name has none. */
    static String savedAt(java.io.File f) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{8}-\\d{6})").matcher(f.getName());
        return m.find() ? m.group(1)
                : new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date(f.lastModified()));
    }

    /** Disabled: the Figma 40 %. */
    static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.4f);
    }

    /** Section Label: the label alone, black (Figma Option A has no rule). Its label: {@link #sectionText}. */
    static LinearLayout section(Context c, String label) {
        LinearLayout s = new LinearLayout(c);
        s.setGravity(Gravity.CENTER_VERTICAL);
        s.addView(label(c, label, LAV));
        View rule = new View(c);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, px(c, 1), 1);
        rp.setMargins(px(c, 8), 0, 0, 0);
        s.addView(rule, rp);
        return s;
    }

    static TextView sectionText(View section) {
        return (TextView) ((LinearLayout) section).getChildAt(0);
    }

    /** LED (Figma DS · LED): green on, dark gray off, orange warn; a black ring. */
    static View led(Context c) {
        View v = new View(c);
        setLed(c, v, 0);
        return v;
    }

    /** 0 off, 1 on, 2 warn. */
    static void setLed(Context c, View v, int state) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(state == 1 ? GREEN_LED : state == 2 ? ORANGE_300 : GRAY_800);
        g.setStroke(px(c, 2), BLACK);
        v.setBackground(g);
    }

    // ---- dialogs (Figma: Diálogos e notificações · Android, Option A)

    /** A dialog with a cyan title band. */
    static AlertDialog.Builder dialog(Context c) {
        return dialog(c, LAV);
    }

    /** The title band's colour for the views' title colours: done green, warnings orange, errors coral, else cyan. */
    private static int band(int titleColor) {
        return titleColor == MINT ? STATUS_OK : titleColor == NOW ? STATUS_WARN : titleColor == BAD ? CORAL : CYAN;
    }

    /**
     * A dialog as the Figma Android Dialog: a white card with a 3 dp black outline; its title in Lexend Mega, in a
     * band of {@code titleColor}'s status colour; its text Public Sans 15; its keys Size=Small.
     */
    static AlertDialog.Builder dialog(Context c, int titleColor) {
        return new AlertDialog.Builder(c, android.R.style.Theme_Material_Light_Dialog_Alert) {
            @Override
            public AlertDialog.Builder setTitle(CharSequence title) {
                LinearLayout wrap = new LinearLayout(c);
                wrap.setPadding(px(c, 20), px(c, 20), px(c, 20), px(c, 4));
                TextView t = new TextView(c);
                t.setText(title == null ? "" : title.toString());
                t.setTextColor(BLACK);
                t.setTextSize(14);
                t.setTypeface(font(c, LABEL));
                t.setAllCaps(true);
                t.setLetterSpacing(0.08f);
                t.setPadding(px(c, 12), px(c, 8), px(c, 12), px(c, 8));
                t.setBackground(box(c, band(titleColor), BLACK, 2.5f, 8));
                wrap.addView(t, new LinearLayout.LayoutParams(-1, -2));
                return setCustomTitle(wrap);
            }

            @Override
            public AlertDialog create() {
                AlertDialog d = super.create();
                if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(card(c, DIALOG, 18, 3, 6));
                d.setOnShowListener(x -> {
                    TextView m = d.findViewById(android.R.id.message);
                    if (m != null) {
                        m.setTypeface(font(c, TECH));
                        m.setTextSize(15);
                        m.setTextColor(DIALOGINK);
                        m.setLineSpacing(px(c, 3), 1f);
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
        // the keys' bar clear of the card's shadow, with room around the keys' own shadows
        for (Button b : new Button[] {pos, neg, neu}) {
            if (shown(b) && b.getParent() instanceof View) {
                View bar = (View) b.getParent();
                if (bar.getTag() == null) {
                    bar.setTag("fw");
                    bar.setPadding(bar.getPaddingLeft(), bar.getPaddingTop() + px(c, 4), bar.getPaddingRight() + px(c, 14),
                            bar.getPaddingBottom() + px(c, 18));
                }
                break;
            }
        }
    }

    private static boolean shown(Button b) {
        return b != null && b.getVisibility() == View.VISIBLE && b.getText().length() > 0;
    }

    /** Button, Size=Small: Public Sans Black 13, 12 x 5 dp padding (Figma 4:28). */
    static void smallKey(Context c, Button b, String kind) {
        styleKey(c, b, kind, 13, 12, 5);
        b.setMinHeight(px(c, 34));
        b.setMinimumHeight(px(c, 34));
        if (b.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) b.getLayoutParams();
            m.setMargins(px(c, 4), px(c, 4), px(c, 4), px(c, 4));
            b.setLayoutParams(m);
        }
    }

    /** FirmWave Key: Primary yellow, Secondary white, Destructive coral, all with the black outline and hard
     *  shadow; Tertiary a black word with no box. */
    private static void styleKey(Context c, Button b, String kind, float size, float padX, float padY) {
        b.setAllCaps(true);
        b.setTypeface(font(c, KEYFONT));
        b.setTextSize(size);
        b.setLetterSpacing(0.03f);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setTextColor(BLACK);
        b.setPadding(px(c, padX), px(c, padY), px(c, padX), px(c, padY));
        switch (kind) {
            case "primary":
                cardBg(b, YELLOW_500, 10, 2.5f, 3);
                break;
            case "secondary":
                cardBg(b, WHITE, 10, 2.5f, 3);
                break;
            case "destructive":
                cardBg(b, CORAL, 10, 2.5f, 3);
                break;
            default: // tertiary: a word, no box
                b.setBackground(null);
        }
    }
}
