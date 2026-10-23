package io.github.cpujp.wiilauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

/**
 * Full-screen Wii-style text entry (dark striped screen, title tab, white pill field, Back / OK),
 * laid out in the same virtual 1920x1080 space as the menu and scaled to the screen.
 */
final class WiiPrompt extends FrameLayout {
    interface Callback {
        void onText(String text);
    }

    private static final float VW = 1920f, VH = 1080f;
    private final WiiDraw wii = new WiiDraw();
    private final EditText field;
    private final PillButton back, ok;
    private String title = "";
    private Callback callback;
    private Runnable onClose;

    WiiPrompt(Context ctx) {
        super(ctx);
        setWillNotDraw(false);
        setVisibility(GONE);
        setClipChildren(false);

        field = new EditText(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(44f);
        bg.setStroke(5, WiiDraw.PILL_BORDER);
        field.setBackground(bg);
        field.setTextColor(WiiDraw.TEXT);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, 52f);
        field.setGravity(Gravity.CENTER);
        field.setPadding(40, 0, 40, 0);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setOnEditorActionListener((v, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                v.focusSearch(View.FOCUS_DOWN).requestFocus();
                return true;
            }
            return false;
        });
        addView(field, at(462, 380, 993, 120));

        back = new PillButton(ctx, R.string.back, WiiDraw.PILL_WHITE, this::cancel);
        ok = new PillButton(ctx, R.string.ok, WiiDraw.PILL_LAVENDER, this::confirm);
        addView(back, at(337, 886, 604, 137));
        addView(ok, at(984, 886, 604, 137));
        field.setNextFocusDownId(generateIds(field, ok, back));
        back.setNextFocusRightId(ok.getId());
        ok.setNextFocusLeftId(back.getId());
        back.setNextFocusUpId(field.getId());
        ok.setNextFocusUpId(field.getId());
    }

    private static int generateIds(View... views) {
        for (View v : views) v.setId(View.generateViewId());
        return views[1].getId();
    }

    private static LayoutParams at(int l, int t, int w, int h) {
        LayoutParams p = new LayoutParams(w, h);
        p.leftMargin = l;
        p.topMargin = t;
        return p;
    }

    /** Lays children out in virtual units and scales the whole overlay to the screen. */
    @Override protected void onMeasure(int wSpec, int hSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec((int) VW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((int) VH, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        View parent = (View) getParent();
        if (parent != null && parent.getWidth() > 0) {
            setPivotX(0);
            setPivotY(0);
            setScaleX(parent.getWidth() / VW);
            setScaleY(parent.getHeight() / VH);
        }
    }

    void show(String title, String initial, Callback cb, Runnable onClose) {
        this.title = title;
        this.callback = cb;
        this.onClose = onClose;
        field.setText(initial);
        field.selectAll();
        setVisibility(VISIBLE);
        bringToFront();
        field.requestFocus();
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        post(() -> imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT));
        invalidate();
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    void cancel() {
        hide();
    }

    private void confirm() {
        String text = field.getText().toString().trim();
        Callback cb = callback;
        hide();
        if (cb != null) cb.onText(text);
    }

    private void hide() {
        getContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(field.getWindowToken(), 0);
        setVisibility(GONE);
        callback = null;
        if (onClose != null) onClose.run();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (isShowing() && e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP && !field.hasFocus()) {
            cancel();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override protected void onDraw(Canvas c) {
        wii.darkStripes(c, 0, VH, VW);
        Paint p = wii.paint;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(0x9A, 0x9A, 0x9C));
        c.drawRoundRect(316f, 92f, 420f, 170f, 14f, 14f, p);
        Path tab = new Path();
        tab.moveTo(385f, 154f);
        tab.lineTo(385f, 92f);
        tab.quadTo(385f, 77f, 400f, 77f);
        tab.lineTo(1290f, 77f);
        tab.quadTo(1335f, 77f, 1335f, 122f);
        tab.lineTo(1335f, 154f);
        tab.close();
        p.setColor(Color.WHITE);
        c.drawPath(tab, p);
        c.drawRect(0, 152f, VW, 156f, p);
        c.drawRect(0, 867f, VW, 871f, p);
        wii.text.setTextAlign(Paint.Align.LEFT);
        wii.text.setTypeface(wii.regular);
        wii.text.setTextSize(48f);
        wii.text.setColor(WiiDraw.TEXT);
        c.drawText(title, 407f, 133f, wii.text);
        if (field.hasFocus()) wii.brackets(c, new RectF(462f, 380f, 1455f, 500f));
    }

    /** Back / OK pill drawn with the shared Wii pill renderer. */
    private static final class PillButton extends View {
        private final WiiDraw wii = new WiiDraw();
        private final String label;
        private final int style;

        PillButton(Context ctx, int label, int style, Runnable action) {
            super(ctx);
            this.label = ctx.getString(label);
            this.style = style;
            setFocusable(true);
            setOnClickListener(v -> action.run());
        }

        @Override protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect previous) {
            super.onFocusChanged(gained, direction, previous);
            invalidate();
            ((View) getParent()).invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            wii.pill(c, new RectF(6, 6, getWidth() - 6, getHeight() - 6), label, style, isFocused(), 255, 55f);
        }
    }
}
