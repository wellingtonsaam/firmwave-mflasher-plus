package io.github.wellingtonsaam.mflasherlib;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Figma Op Button (7:29): an icon, the label in Barlow Condensed SemiBold 17, a LED on the right.
 * Default #174b4d, Selected #23706f with a 2 dp mint frame and the LED on, Disabled at 40 %.
 */
final class OpKey extends LinearLayout {
    private final TextView label;
    private final ImageView icon;
    private final View led;
    private boolean lit;

    OpKey(Context c, String text, String iconName) {
        super(c);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(Ui.px(c, 16), Ui.px(c, 12), Ui.px(c, 16), Ui.px(c, 12));
        setClickable(true);
        setFocusable(true);
        icon = new ImageView(c);
        setIcon(iconName);
        addView(icon, new LayoutParams(Ui.px(c, 24), Ui.px(c, 24)));
        label = new TextView(c);
        label.setText(text);
        label.setAllCaps(true);
        label.setTypeface(Ui.font(c, Ui.KEYFONT));
        label.setTextSize(17);
        label.setLetterSpacing(0.035f);
        label.setTextColor(Ui.KEYINK);
        LayoutParams lp = new LayoutParams(0, -2, 1);
        lp.setMargins(Ui.px(c, 12), 0, Ui.px(c, 12), 0);
        addView(label, lp);
        led = Ui.led(c);
        addView(led, new LayoutParams(Ui.px(c, 10), Ui.px(c, 10)));
        setLit(false);
    }

    void setIcon(String name) {
        icon.setImageResource(getResources().getIdentifier(name, "drawable", getContext().getPackageName()));
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
        setBackground(on ? Ui.box(c, Ui.KEY, Ui.MINT, 2, 3) : Ui.box(c, Ui.KEYDARK, Ui.KEYLINE, 1, 3));
        Ui.setLed(c, led, on ? 1 : 0);
    }

    boolean isLit() {
        return lit;
    }
}
