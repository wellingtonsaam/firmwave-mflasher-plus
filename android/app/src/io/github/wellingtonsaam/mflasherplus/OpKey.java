package io.github.wellingtonsaam.mflasherplus;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * FirmWave Operation key (Figma Option A, Android): an icon, the label in Public Sans Black 15, a LED on the
 * right; a card of its own colour (white, the next step yellow, the operations cyan, pink and lilac) with a black
 * outline and a hard shadow. Selected: a thicker outline and the LED on. Disabled at 40 %.
 */
final class OpKey extends LinearLayout {
    private final TextView label;
    private final ImageView icon;
    private final View led;
    private boolean lit;
    private int fill = Ui.WHITE;

    OpKey(Context c, String text, String iconName) {
        super(c);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(Ui.px(c, 14), Ui.px(c, 10), Ui.px(c, 16), Ui.px(c, 10));
        setClickable(true);
        setFocusable(true);
        icon = new ImageView(c);
        setIcon(iconName);
        addView(icon, new LayoutParams(Ui.px(c, 24), Ui.px(c, 24)));
        label = new TextView(c);
        label.setText(text);
        label.setAllCaps(true);
        label.setTypeface(Ui.font(c, Ui.KEYFONT));
        label.setTextSize(15);
        label.setLetterSpacing(0.03f);
        label.setTextColor(Ui.KEYINK);
        LayoutParams lp = new LayoutParams(0, -2, 1);
        lp.setMargins(Ui.px(c, 12), 0, Ui.px(c, 12), 0);
        addView(label, lp);
        led = Ui.led(c);
        addView(led, new LayoutParams(Ui.px(c, 12), Ui.px(c, 12)));
        setLit(false);
    }

    void setIcon(String name) {
        icon.setImageResource(getResources().getIdentifier(name, "drawable", getContext().getPackageName()));
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.BLACK));
    }

    /** The key's own colour. */
    void setFill(int color) {
        fill = color;
        setLit(lit);
    }

    void setText(CharSequence s) {
        label.setText(s);
    }

    CharSequence getText() {
        return label.getText();
    }

    /** Selected: the chosen or next step. */
    void setLit(boolean on) {
        lit = on;
        Context c = getContext();
        setPadding(Ui.px(c, 14), Ui.px(c, 10), Ui.px(c, 16), Ui.px(c, 10));
        Ui.cardBg(this, fill, 12, on ? 4 : 2.5f, 4);
        Ui.setLed(c, led, on ? 1 : 0);
    }

    boolean isLit() {
        return lit;
    }
}
