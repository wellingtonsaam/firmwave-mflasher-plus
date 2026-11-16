package io.github.wellingtonsaam.mflasherlib;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** The desktop's look (src/ui.rs colors, as in the Figma variables) for the app's views. */
final class Ui {
    static final int BG = 0xFF28221F, PANEL = 0xFF1F1A18, RAISED = 0xFF332C28, FIELD = 0xFF1A1614, LINE = 0xFF3D3531,
            INK = 0xFFE9E4DC, DIM = 0xFF8D837B, LAV = 0xFFB6A2E6, MINT = 0xFF7BE8BB, WARN = 0xFFF2B44C,
            BAD = 0xFFFF6A5A, KEY = 0xFF2C7A70, KEYDARK = 0xFF2A2421, KEYINK = 0xFFD6EFE8, LCD = 0xFFC3D66B,
            LCDINK = 0xFF1D2310;

    private Ui() {}

    static int px(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String s, int color, float size, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    static GradientDrawable box(Context c, int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (stroke != 0) g.setStroke(px(c, 1), stroke);
        g.setCornerRadius(px(c, radius));
        return g;
    }

    static Button key(Context c, String label, boolean primary) {
        Button k = new Button(c);
        k.setText(label);
        k.setAllCaps(true);
        k.setTextColor(primary ? KEYINK : INK);
        k.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        k.setBackground(box(c, primary ? KEY : KEYDARK, primary ? 0 : DIM, 3));
        k.setStateListAnimator(null);
        return k;
    }

    static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.45f);
    }

    static TextView section(Context c, String label) {
        TextView t = text(c, label, LAV, 11, false);
        t.setLetterSpacing(0.25f);
        t.setPadding(0, px(c, 18), 0, px(c, 6));
        return t;
    }
}
