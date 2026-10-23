package io.github.cpujp.wiilauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The whole menu is drawn on one canvas in a virtual 1920x1080 space scaled to the real surface,
 * so it stays sharp at 1080p and 4K without bitmaps for the chrome.
 */
final class WiiMenuView extends View {

    interface Callbacks {
        void onLaunch(AppEntry app);
        void onOptions(AppEntry app, int index);
        void onSettings();
        void onMail();
    }

    // Layout in virtual units (1920x1080).
    private static final float VW = 1920f, VH = 1080f;
    private static final float TILE_W = 400f, TILE_H = 225f, GAP_X = 30f, GAP_Y = 32f;
    private static final float GRID_LEFT = (VW - (Grid.COLS * TILE_W + (Grid.COLS - 1) * GAP_X)) / 2f;
    private static final float GRID_TOP = 42f;
    private static final float RADIUS = 30f;
    private static final float BTN_R = 92f;
    private static final float BTN_Y = 962f, BTN_SETTINGS_X = 196f, BTN_MAIL_X = VW - 196f;

    private static final int BLUE = Color.rgb(0x34, 0xBE, 0xED);
    private static final int TEXT_GRAY = Color.rgb(0x8C, 0x91, 0x9B);

    private final Callbacks callbacks;
    private final Sfx sfx;
    private final PackageManager pm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> banners = new LruCache<>(Grid.PER_PAGE * 2);
    private final Set<String> loading = new HashSet<>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF tile = new RectF();
    private final Path path = new Path();
    private float[] stripes = new float[0];
    private Shader barShader, tileShader;

    private List<AppEntry> apps = new ArrayList<>();
    private Grid.Focus focus = new Grid.Focus(0, 0);
    private float shownPage;          // animated page position for the slide transition
    private ValueAnimator pageAnim;
    private float pulse;              // 0..1 focus glow
    private final ValueAnimator pulseAnim;
    private boolean mailBlink;
    private String status;

    // Channel preview ("zoomed in" channel with Menu / Start), Wii style.
    private AppEntry previewApp;
    private Bitmap previewBanner;
    private float previewT;           // 0 = grid, 1 = fully zoomed
    private RectF previewFrom;
    private boolean previewStartFocused = true;
    private ValueAnimator previewAnim;
    private boolean longPressHandled;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            invalidate();
            main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    WiiMenuView(Context ctx, Callbacks callbacks, Sfx sfx) {
        super(ctx);
        this.callbacks = callbacks;
        this.sfx = sfx;
        this.pm = ctx.getPackageManager();
        setFocusable(true);
        setFocusableInTouchMode(true);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        pulseAnim = ValueAnimator.ofFloat(0f, 1f);
        pulseAnim.setDuration(1400);
        pulseAnim.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnim.addUpdateListener(a -> {
            pulse = (float) a.getAnimatedValue();
            invalidate();
        });
    }

    void setApps(List<AppEntry> list) {
        apps = list;
        int pages = Grid.pageCount(apps.size());
        if (focus.page >= pages) focus = new Grid.Focus(pages - 1, 0);
        shownPage = focus.page;
        banners.evictAll();
        invalidate();
    }

    List<AppEntry> apps() {
        return apps;
    }

    void focusIndex(int index) {
        focus = new Grid.Focus(index / Grid.PER_PAGE, index % Grid.PER_PAGE);
        shownPage = focus.page;
        invalidate();
    }

    void setMailBlink(boolean on) {
        mailBlink = on;
        invalidate();
    }

    void setStatus(String s) {
        status = s;
        invalidate();
    }

    /** Returns to the grid when Home is pressed while a channel preview is open. */
    boolean closePreview() {
        if (previewApp == null) return false;
        animatePreview(false);
        return true;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        pulseAnim.start();
        clockTick.run();
    }

    @Override protected void onDetachedFromWindow() {
        pulseAnim.cancel();
        main.removeCallbacks(clockTick);
        loader.shutdownNow();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // No animation work while another app is in front.
        if (visibility == VISIBLE) {
            if (!pulseAnim.isStarted()) pulseAnim.start();
        } else {
            pulseAnim.cancel();
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        int n = (int) (VH / 6f);
        stripes = new float[n * 4];
        for (int i = 0; i < n; i++) {
            float y = i * 6f + 3f;
            stripes[i * 4] = 0;
            stripes[i * 4 + 1] = y;
            stripes[i * 4 + 2] = VW;
            stripes[i * 4 + 3] = y;
        }
        barShader = new LinearGradient(0, 840, 0, VH, Color.rgb(0xE4, 0xE6, 0xEA), Color.rgb(0xC4, 0xC8, 0xD0), Shader.TileMode.CLAMP);
        tileShader = new LinearGradient(0, 0, 0, TILE_H, Color.WHITE, Color.rgb(0xE6, 0xE8, 0xEC), Shader.TileMode.CLAMP);
        banners.evictAll(); // banner bitmaps are sized for the surface
    }

    // ---------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas c) {
        float s = getWidth() / VW;
        c.save();
        c.scale(s, getHeight() / VH);

        c.drawColor(Color.rgb(0xF4, 0xF4, 0xF6));
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(Color.rgb(0xEA, 0xEA, 0xEE));
        c.drawLines(stripes, paint);

        int pages = Grid.pageCount(apps.size());
        float offset = (shownPage - (int) shownPage);
        int base = (int) shownPage;
        drawPage(c, base, -offset * VW);
        if (offset > 0.001f) drawPage(c, base + 1, (1 - offset) * VW);

        drawArrows(c, pages);
        drawBottomBar(c, pages);
        if (previewApp != null) drawPreview(c);
        c.restore();
    }

    private void drawPage(Canvas c, int page, float dx) {
        AppEntry focused = null;
        RectF focusedRect = null;
        for (int slot = 0; slot < Grid.PER_PAGE; slot++) {
            int idx = page * Grid.PER_PAGE + slot;
            tileRect(slot, dx, tile);
            boolean isFocus = previewApp == null && focus.page == page && focus.slot == slot;
            AppEntry app = idx < apps.size() ? apps.get(idx) : null;
            drawTile(c, tile, app, isFocus);
            if (isFocus) {
                focused = app;
                focusedRect = new RectF(tile);
            }
        }
        if (focused != null) drawLabel(c, focusedRect, focused.label);
    }

    private static void tileRect(int slot, float dx, RectF out) {
        int col = slot % Grid.COLS, row = slot / Grid.COLS;
        float l = GRID_LEFT + col * (TILE_W + GAP_X) + dx, t = GRID_TOP + row * (TILE_H + GAP_Y);
        out.set(l, t, l + TILE_W, t + TILE_H);
    }

    private void drawTile(Canvas c, RectF r, AppEntry app, boolean focused) {
        paint.setStyle(Paint.Style.FILL);
        if (app == null) {
            paint.setShader(null);
            paint.setColor(Color.rgb(0xE3, 0xE4, 0xE8));
            c.drawRoundRect(r, RADIUS, RADIUS, paint);
        } else {
            c.save();
            c.translate(r.left, r.top);
            paint.setShader(tileShader);
            c.drawRoundRect(0, 0, TILE_W, TILE_H, RADIUS, RADIUS, paint);
            paint.setShader(null);
            c.restore();
            Bitmap b = banner(app);
            if (b != null) {
                c.save();
                path.reset();
                path.addRoundRect(r.left + 8, r.top + 8, r.right - 8, r.bottom - 8, RADIUS - 8, RADIUS - 8, Path.Direction.CW);
                c.clipPath(path);
                fitInto(c, b, r.left + 8, r.top + 8, r.right - 8, r.bottom - 8, app);
                c.restore();
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        if (focused) {
            paint.setColor(BLUE);
            paint.setStrokeWidth(7f);
            paint.setShadowLayer(14f + 10f * pulse, 0, 0, Color.argb(200, 0x34, 0xBE, 0xED));
            c.drawRoundRect(r, RADIUS, RADIUS, paint);
            paint.clearShadowLayer();
        } else {
            paint.setColor(Color.rgb(0xC8, 0xCB, 0xD2));
            paint.setStrokeWidth(3f);
            c.drawRoundRect(r, RADIUS, RADIUS, paint);
        }
    }

    /** Banners fill the tile; square launcher icons sit centered on the white channel face. */
    private void fitInto(Canvas c, Bitmap b, float l, float t, float r, float btm, AppEntry app) {
        float w = r - l, h = btm - t;
        if (app.leanback && b.getWidth() > b.getHeight()) {
            rect.set(l, t, r, btm);
        } else {
            float size = h * 0.7f;
            rect.set(l + (w - size) / 2, t + (h - size) / 2, l + (w + size) / 2, t + (h + size) / 2);
        }
        c.drawBitmap(b, null, rect, paint);
    }

    private void drawLabel(Canvas c, RectF r, String label) {
        text.setTextSize(40f);
        text.setColor(Color.rgb(0x4A, 0x4F, 0x58));
        String l = TextUtils.ellipsize(label, new android.text.TextPaint(text), 560f, TextUtils.TruncateAt.END).toString();
        float w = text.measureText(l) + 60f;
        float cx = Math.max(w / 2 + 20, Math.min(VW - w / 2 - 20, r.centerX()));
        float top = r.bottom - 30f;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setShadowLayer(10f, 0, 3f, Color.argb(90, 0, 0, 0));
        c.drawRoundRect(cx - w / 2, top, cx + w / 2, top + 66f, 33f, 33f, paint);
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(BLUE);
        c.drawRoundRect(cx - w / 2, top, cx + w / 2, top + 66f, 33f, 33f, paint);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(l, cx, top + 47f, text);
    }

    private void drawArrows(Canvas c, int pages) {
        if (previewApp != null) return;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        float cy = GRID_TOP + (Grid.ROWS * TILE_H + (Grid.ROWS - 1) * GAP_Y) / 2f;
        if (focus.page > 0) arrow(c, 52f, cy, -1);
        if (focus.page < pages - 1) arrow(c, VW - 52f, cy, 1);
    }

    private void arrow(Canvas c, float cx, float cy, int dir) {
        paint.setColor(Color.WHITE);
        paint.setShadowLayer(8f, 0, 2f, Color.argb(80, 0, 0, 0));
        c.drawCircle(cx, cy, 34f, paint);
        paint.clearShadowLayer();
        paint.setColor(BLUE);
        path.reset();
        path.moveTo(cx + dir * 14f, cy);
        path.lineTo(cx - dir * 10f, cy - 18f);
        path.lineTo(cx - dir * 10f, cy + 18f);
        path.close();
        c.drawPath(path, paint);
    }

    private void drawBottomBar(Canvas c, int pages) {
        // Panel whose top edge dips in the middle and rises into two bubbles at the ends.
        path.reset();
        path.moveTo(0, VH);
        path.lineTo(0, 860);
        path.cubicTo(260, 850, 360, 905, 520, 905);
        path.lineTo(VW - 520, 905);
        path.cubicTo(VW - 360, 905, VW - 260, 850, VW, 860);
        path.lineTo(VW, VH);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(barShader);
        paint.setShadowLayer(12f, 0, -2f, Color.argb(60, 0, 0, 0));
        c.drawPath(path, paint);
        paint.clearShadowLayer();
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(BLUE);
        path.reset();
        path.moveTo(0, 860);
        path.cubicTo(260, 850, 360, 905, 520, 905);
        path.lineTo(VW - 520, 905);
        path.cubicTo(VW - 360, 905, VW - 260, 850, VW, 860);
        c.drawPath(path, paint);

        // Clock above the dip, date inside the panel.
        Calendar now = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(getContext());
        String time = DateFormat.format(h24 ? "H:mm" : "h:mm", now).toString();
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(TEXT_GRAY);
        text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        text.setTextSize(96f);
        float tw = text.measureText(time);
        c.drawText(time, VW / 2, 880f, text);
        if (!h24) {
            text.setTextSize(36f);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(now.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM", VW / 2 + tw / 2 + 12f, 880f, text);
            text.setTextAlign(Paint.Align.CENTER);
        }
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setTextSize(46f);
        text.setColor(Color.rgb(0x6E, 0x73, 0x7D));
        c.drawText(DateFormat.format("EEE d/M", now).toString(), VW / 2, 1000f, text);

        if (pages > 1) drawPageDots(c, pages);
        if (status != null) {
            text.setTextSize(30f);
            c.drawText(status, VW / 2, 1055f, text);
        }

        boolean onSettings = previewApp == null && focus.slot == Grid.BTN_SETTINGS;
        boolean onMail = previewApp == null && focus.slot == Grid.BTN_MAIL;
        roundButton(c, BTN_SETTINGS_X, onSettings, false);
        gear(c, BTN_SETTINGS_X, BTN_Y);
        roundButton(c, BTN_MAIL_X, onMail, mailBlink);
        envelope(c, BTN_MAIL_X, BTN_Y, mailBlink);
    }

    private void drawPageDots(Canvas c, int pages) {
        float spacing = 26f, start = VW / 2 - (pages - 1) * spacing / 2;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < pages; i++) {
            paint.setColor(i == focus.page ? BLUE : Color.rgb(0xB4, 0xB8, 0xC0));
            c.drawCircle(start + i * spacing, 1022f, 7f, paint);
        }
    }

    private void roundButton(Canvas c, float cx, boolean focused, boolean glow) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setShadowLayer(focused ? 22f : 10f, 0, 3f, focused ? Color.argb(200, 0x34, 0xBE, 0xED) : Color.argb(90, 0, 0, 0));
        c.drawCircle(cx, BTN_Y, BTN_R * (focused ? 1.06f : 1f), paint);
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(focused ? 7f : 4f);
        paint.setColor(focused || glow ? BLUE : Color.rgb(0xB9, 0xBD, 0xC5));
        if (glow && !focused) paint.setAlpha((int) (110 + 145 * pulse));
        c.drawCircle(cx, BTN_Y, BTN_R * (focused ? 1.06f : 1f), paint);
        paint.setAlpha(255);
    }

    private void gear(Canvas c, float cx, float cy) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(TEXT_GRAY);
        for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45f, cx, cy);
            c.drawRoundRect(cx - 9f, cy - 46f, cx + 9f, cy - 24f, 4f, 4f, paint);
            c.restore();
        }
        c.drawCircle(cx, cy, 32f, paint);
        paint.setColor(Color.WHITE);
        c.drawCircle(cx, cy, 13f, paint);
    }

    private void envelope(Canvas c, float cx, float cy, boolean active) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(active ? BLUE : TEXT_GRAY);
        c.drawRoundRect(cx - 44f, cy - 30f, cx + 44f, cy + 30f, 6f, 6f, paint);
        path.reset();
        path.moveTo(cx - 44f, cy - 28f);
        path.lineTo(cx, cy + 6f);
        path.lineTo(cx + 44f, cy - 28f);
        c.drawPath(path, paint);
    }

    private void drawPreview(Canvas c) {
        float t = previewT;
        // Grow from the channel's tile to full screen.
        float l = lerp(previewFrom.left, 0, t), top = lerp(previewFrom.top, 0, t);
        float r = lerp(previewFrom.right, VW, t), b = lerp(previewFrom.bottom, VH, t);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(0x16, 0x18, 0x1C));
        c.drawRoundRect(l, top, r, b, RADIUS * (1 - t), RADIUS * (1 - t), paint);
        if (t < 0.6f) return;
        int alpha = (int) (255 * (t - 0.6f) / 0.4f);

        if (previewBanner != null) {
            paint.setAlpha(alpha);
            float bw = 1152f, bh = 648f;
            if (!(previewApp.leanback && previewBanner.getWidth() > previewBanner.getHeight())) bw = bh = 440f;
            rect.set((VW - bw) / 2, 400f - bh / 2, (VW + bw) / 2, 400f + bh / 2);
            c.save();
            path.reset();
            path.addRoundRect(rect, 24f, 24f, Path.Direction.CW);
            c.clipPath(path);
            c.drawBitmap(previewBanner, null, rect, paint);
            c.restore();
            paint.setAlpha(255);
        }
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(52f);
        text.setColor(Color.argb(alpha, 0xF0, 0xF0, 0xF2));
        c.drawText(previewApp.label, VW / 2, 820f, text);

        // Bottom strip with the two Wii-style buttons.
        paint.setColor(Color.argb(alpha, 0x2A, 0x2D, 0x33));
        c.drawRect(0, 880f, VW, VH, paint);
        pillButton(c, VW / 2 - 330f, getContext().getString(R.string.menu), !previewStartFocused, alpha);
        pillButton(c, VW / 2 + 330f, getContext().getString(R.string.start), previewStartFocused, alpha);
    }

    private void pillButton(Canvas c, float cx, String label, boolean focused, int alpha) {
        rect.set(cx - 250f, 920f, cx + 250f, 1040f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(alpha, 0xFA, 0xFA, 0xFB));
        if (focused) paint.setShadowLayer(18f + 8f * pulse, 0, 0, Color.argb(alpha, 0x34, 0xBE, 0xED));
        c.drawRoundRect(rect, 60f, 60f, paint);
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(focused ? 7f : 3f);
        paint.setColor(focused ? BLUE : Color.rgb(0xB9, 0xBD, 0xC5));
        paint.setAlpha(alpha);
        c.drawRoundRect(rect, 60f, 60f, paint);
        paint.setAlpha(255);
        text.setTextSize(50f);
        text.setColor(Color.argb(alpha, 0x4A, 0x4F, 0x58));
        c.drawText(label, cx, 998f, text);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ---------------------------------------------------------------- banners

    private Bitmap banner(AppEntry app) {
        Bitmap b = banners.get(app.pkg);
        if (b != null || loading.contains(app.pkg)) return b;
        loading.add(app.pkg);
        float s = getWidth() / VW;
        int w = Math.max(1, Math.round((TILE_W - 16) * s)), h = Math.max(1, Math.round((TILE_H - 16) * s));
        loader.execute(() -> {
            Bitmap bmp = render(app, w, h);
            main.post(() -> {
                loading.remove(app.pkg);
                if (bmp != null) banners.put(app.pkg, bmp);
                invalidate();
            });
        });
        return null;
    }

    /** Loads the TV banner when the app has one, otherwise its icon, rendered at the final pixel size. */
    private Bitmap render(AppEntry app, int w, int h) {
        try {
            Drawable d = app.leanback ? app.activity.loadBanner(pm) : null;
            boolean isBanner = d != null;
            if (d == null) d = app.activity.loadIcon(pm);
            if (!isBanner) w = h = Math.round(h * 0.7f);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            return bmp;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- input

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (isSelect(keyCode)) {
            if (e.getRepeatCount() == 0) {
                e.startTracking();
                longPressHandled = false;
            }
            return true;
        }
        if (previewApp != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    previewStartFocused = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
                    sfx.tick();
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    animatePreview(false);
                    return true;
                default:
                    return true; // swallow other keys while zoomed in
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return step(Grid.Dir.LEFT);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return step(Grid.Dir.RIGHT);
            case KeyEvent.KEYCODE_DPAD_UP: return step(Grid.Dir.UP);
            case KeyEvent.KEYCODE_DPAD_DOWN: return step(Grid.Dir.DOWN);
            case KeyEvent.KEYCODE_MENU:
                openOptions();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (focus.page != 0 || focus.slot != 0) {
                    animatePage(0);
                    focus = new Grid.Focus(0, 0);
                    invalidate();
                }
                return true; // a launcher has nowhere to go back to
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override public boolean onKeyLongPress(int keyCode, KeyEvent e) {
        if (isSelect(keyCode) && previewApp == null) {
            longPressHandled = true;
            openOptions();
            return true;
        }
        return super.onKeyLongPress(keyCode, e);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (!isSelect(keyCode)) return super.onKeyUp(keyCode, e);
        if (longPressHandled || e.isCanceled()) return true;
        if (previewApp != null) {
            if (previewT < 1f) return true;
            sfx.select();
            if (previewStartFocused) callbacks.onLaunch(previewApp);
            animatePreview(false);
            return true;
        }
        if (focus.slot == Grid.BTN_SETTINGS) {
            sfx.select();
            callbacks.onSettings();
        } else if (focus.slot == Grid.BTN_MAIL) {
            sfx.select();
            callbacks.onMail();
        } else if (focus.index() < apps.size()) {
            sfx.select();
            openPreview(apps.get(focus.index()));
        }
        return true;
    }

    private static boolean isSelect(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    private boolean step(Grid.Dir d) {
        Grid.Focus next = Grid.move(focus, d, Grid.pageCount(apps.size()));
        if (next.equals(focus)) return true;
        if (next.page != focus.page) animatePage(next.page);
        focus = next;
        sfx.tick();
        invalidate();
        return true;
    }

    private void openOptions() {
        int idx = focus.index();
        if (idx >= 0 && idx < apps.size()) callbacks.onOptions(apps.get(idx), idx);
    }

    private void animatePage(int target) {
        if (pageAnim != null) pageAnim.cancel();
        pageAnim = ValueAnimator.ofFloat(shownPage, target);
        pageAnim.setDuration(260);
        pageAnim.setInterpolator(new DecelerateInterpolator());
        pageAnim.addUpdateListener(a -> {
            shownPage = (float) a.getAnimatedValue();
            invalidate();
        });
        pageAnim.start();
    }

    private void openPreview(AppEntry app) {
        tileRect(focus.slot, 0, rect);
        previewFrom = new RectF(rect);
        previewApp = app;
        previewStartFocused = true;
        Bitmap big = render(app, 1280, 720);
        previewBanner = big != null ? big : banners.get(app.pkg);
        animatePreview(true);
    }

    private void animatePreview(boolean open) {
        if (previewAnim != null) previewAnim.cancel();
        previewAnim = ValueAnimator.ofFloat(previewT, open ? 1f : 0f);
        previewAnim.setDuration(open ? 320 : 220);
        previewAnim.setInterpolator(new DecelerateInterpolator());
        previewAnim.addUpdateListener(a -> {
            previewT = (float) a.getAnimatedValue();
            if (!open && previewT <= 0f) {
                previewApp = null;
                previewBanner = null;
            }
            invalidate();
        });
        previewAnim.start();
    }
}
