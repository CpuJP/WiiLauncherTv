package io.github.cpujp.wiilauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
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
import java.util.Locale;
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
        void onOptions(Channel channel, int index, Channel inFolder);
        void onSettings();
        void onInputs();
        void onCheckUpdates();
        void onInstallUpdate();
        void onCycleTheme();
    }

    // Layout in virtual units (1920x1080), proportioned after the Wii Menu: four columns edge to edge.
    private static final float VW = 1920f, VH = 1080f;
    private static final float TILE_W = 452f, TILE_H = 242f, GAP_X = 18f, GAP_Y = 16f;
    private static final float GRID_LEFT = (VW - (Grid.COLS * TILE_W + (Grid.COLS - 1) * GAP_X)) / 2f;
    private static final float GRID_TOP = 26f;
    private static final float RADIUS = 26f;
    private static final float BTN_R = 96f, BTN_Y = 970f;
    private static final float BTN_SETTINGS_X = 150f, BTN_MAIL_X = VW - 150f;
    private static final float SD_X = 330f, SD_Y = 985f;

    // SD (updates) menu actions.
    private static final int SD_CHECK = 0, SD_INSTALL = 1, SD_THEME = 2, SD_BACK = 3;

    private final Callbacks callbacks;
    private final Sfx sfx;
    private final PackageManager pm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> banners = new LruCache<>(Grid.PER_PAGE * 3);
    private final Set<String> loading = new HashSet<>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint();
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final RectF rect = new RectF();
    private final RectF tile = new RectF();
    private final Path path = new Path();
    private final Path barEdge = new Path();
    private final Path barFill = new Path();
    private final Path hand;
    private float[] stripes = new float[0];
    private Shader barShader, tileShader;
    private Palette pal = Palette.LIGHT;

    private List<Channel> channels = new ArrayList<>();
    private Channel openFolder;                 // non-null while browsing inside a folder
    private List<Channel> folderChannels = new ArrayList<>();
    private Grid.Focus focus = new Grid.Focus(0, 0);
    private Grid.Focus focusBeforeFolder;
    private float shownPage;
    private ValueAnimator pageAnim;
    private float pulse;
    private boolean pulsing;
    private boolean updateAvailable;
    private String status;

    // Hand cursor: eases toward the focused element.
    private float handX = VW / 2, handY = VH / 2, handFromX, handFromY, handToX, handToY;
    private ValueAnimator handAnim;

    // Channel preview ("zoomed in" channel with Menu / Start).
    private AppEntry previewApp;
    private Bitmap previewBanner;
    private float previewT;
    private RectF previewFrom;
    private boolean previewStartFocused = true;
    private ValueAnimator previewAnim;
    private boolean longPressHandled;

    // SD card menu, used here for updates from GitHub and the theme.
    private boolean sdOpen;
    private int sdFocus;
    private String installedVersion = "", latestVersion = "", releaseNotes = "", themeLabel = "";

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            invalidate();
            main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    // ~15 fps is plenty for a slow glow and keeps the GPU mostly idle on a 2 GB TV.
    private final Runnable pulseTick = new Runnable() {
        @Override public void run() {
            pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() * (2 * Math.PI / 2800.0)));
            invalidate();
            main.postDelayed(this, 66);
        }
    };

    WiiMenuView(Context ctx, Callbacks callbacks, Sfx sfx) {
        super(ctx);
        this.callbacks = callbacks;
        this.sfx = sfx;
        this.pm = ctx.getPackageManager();
        setFocusable(true);
        setFocusableInTouchMode(true);
        text.setTypeface(regular);
        hand = buildHand();
    }

    // ---------------------------------------------------------------- state from the activity

    void setChannels(List<Channel> list) {
        channels = list;
        if (openFolder != null) {
            Channel again = null;
            for (Channel ch : channels) if (ch.key.equals(openFolder.key)) again = ch;
            if (again == null) exitFolder(); else enterFolderList(again);
        }
        clampFocus();
        shownPage = focus.page;
        banners.evictAll();
        retargetHand(false);
        invalidate();
    }

    List<Channel> visible() {
        return openFolder != null ? folderChannels : channels;
    }

    List<Channel> channelsTopLevel() {
        return channels;
    }

    void focusIndex(int index) {
        focus = new Grid.Focus(index / Grid.PER_PAGE, index % Grid.PER_PAGE);
        shownPage = focus.page;
        retargetHand(true);
        invalidate();
    }

    void setPalette(Palette p) {
        pal = p;
        rebuildShaders();
        invalidate();
    }

    void setUpdateInfo(String installed, String latest, boolean newer, String notes) {
        installedVersion = installed;
        latestVersion = latest;
        updateAvailable = newer;
        releaseNotes = notes == null ? "" : notes;
        if (sdOpen && sdFocus >= sdItems().size()) sdFocus = 0;
        retargetHand(true);
        invalidate();
    }

    void setThemeLabel(String label) {
        themeLabel = label;
        invalidate();
    }

    void setStatus(String s) {
        status = s;
        invalidate();
    }

    /** Home pressed while already home: back to the top of the menu. */
    void resetToMenu() {
        if (previewApp != null) animatePreview(false);
        sdOpen = false;
        if (openFolder != null) exitFolder();
        retargetHand(true);
        invalidate();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setPulsing(true);
        clockTick.run();
    }

    @Override protected void onDetachedFromWindow() {
        setPulsing(false);
        main.removeCallbacks(clockTick);
        loader.shutdownNow();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        setPulsing(visibility == VISIBLE); // no animation work while another app is in front
    }

    private void setPulsing(boolean on) {
        if (on == pulsing) return;
        pulsing = on;
        main.removeCallbacks(pulseTick);
        if (on) pulseTick.run();
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
        // Bar edge: high at both ends around the round buttons, low and flat in the middle.
        barEdge.reset();
        barEdge.moveTo(0, 806);
        barEdge.cubicTo(250, 802, 330, 884, 540, 884);
        barEdge.lineTo(VW - 540, 884);
        barEdge.cubicTo(VW - 330, 884, VW - 250, 802, VW, 806);
        barFill.reset();
        barFill.addPath(barEdge);
        barFill.lineTo(VW, VH);
        barFill.lineTo(0, VH);
        barFill.close();
        rebuildShaders();
        banners.evictAll(); // banner bitmaps are sized for the surface
        retargetHand(false);
    }

    private void rebuildShaders() {
        barShader = new LinearGradient(0, 806, 0, VH, pal.barTop, pal.barBottom, Shader.TileMode.CLAMP);
        tileShader = new LinearGradient(0, 0, 0, TILE_H, pal.tileTop, pal.tileBottom, Shader.TileMode.CLAMP);
    }

    // ---------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas c) {
        c.save();
        c.scale(getWidth() / VW, getHeight() / VH);

        c.drawColor(pal.bg);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(pal.stripe);
        c.drawLines(stripes, paint);

        if (sdOpen) {
            drawSdMenu(c);
        } else {
            float offset = shownPage - (int) shownPage;
            int base = (int) shownPage;
            drawPage(c, base, -offset * VW);
            if (offset > 0.001f) drawPage(c, base + 1, (1 - offset) * VW);
            drawArrows(c);
        }
        drawBottomBar(c);
        if (previewApp != null) drawPreview(c);
        drawHand(c);
        c.restore();
    }

    private void drawPage(Canvas c, int page, float dx) {
        List<Channel> list = visible();
        Channel focused = null;
        RectF focusedRect = null;
        for (int slot = 0; slot < Grid.PER_PAGE; slot++) {
            int idx = page * Grid.PER_PAGE + slot;
            tileRect(slot, dx, tile);
            boolean isFocus = previewApp == null && focus.page == page && focus.slot == slot;
            Channel ch = idx < list.size() ? list.get(idx) : null;
            drawTile(c, tile, ch, isFocus);
            if (isFocus && ch != null) {
                focused = ch;
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

    private void drawTile(Canvas c, RectF r, Channel ch, boolean focused) {
        paint.setStyle(Paint.Style.FILL);
        if (ch == null) {
            paint.setShader(null);
            paint.setColor(pal.tileEmpty);
            c.drawRoundRect(r, RADIUS, RADIUS, paint);
        } else {
            c.save();
            c.translate(r.left, r.top);
            paint.setShader(tileShader);
            c.drawRoundRect(0, 0, TILE_W, TILE_H, RADIUS, RADIUS, paint);
            paint.setShader(null);
            c.restore();
            if (ch.isFolder()) {
                drawFolderFace(c, r, ch);
            } else {
                Bitmap b = banner(ch.app);
                if (b != null) fitInto(c, b, r.left + 8, r.top + 8, r.right - 8, r.bottom - 8, ch.app);
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        if (focused) {
            glow(c, r, RADIUS, 18f, (int) (90 + 130 * pulse));
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(pal.blue);
            paint.setStrokeWidth(8f);
            c.drawRoundRect(r, RADIUS, RADIUS, paint);
        } else {
            paint.setColor(pal.tileBorder);
            paint.setStrokeWidth(3f);
            c.drawRoundRect(r, RADIUS, RADIUS, paint);
        }
    }

    /** A folder shows its first four channels as a 2x2 mosaic plus a folder badge. */
    private void drawFolderFace(Canvas c, RectF r, Channel ch) {
        float pad = 22f, gap = 12f;
        float w = (TILE_W - 2 * pad - gap) / 2f, h = (TILE_H - 2 * pad - gap) / 2f;
        for (int i = 0; i < 4; i++) {
            float l = r.left + pad + (i % 2) * (w + gap), t = r.top + pad + (i / 2) * (h + gap);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(pal.tileEmpty);
            c.drawRoundRect(l, t, l + w, t + h, 12f, 12f, paint);
            if (i < ch.folderApps.size()) {
                Bitmap b = banner(ch.folderApps.get(i));
                if (b != null) fitInto(c, b, l, t, l + w, t + h, ch.folderApps.get(i));
            }
        }
        float bx = r.right - 54f, by = r.bottom - 48f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.blue);
        path.reset();
        path.addRoundRect(bx - 30f, by - 16f, bx + 30f, by + 24f, 6f, 6f, Path.Direction.CW);
        path.addRoundRect(bx - 30f, by - 24f, bx - 4f, by - 10f, 4f, 4f, Path.Direction.CW);
        c.drawPath(path, paint);
    }

    /** Banners fill the tile; square launcher icons sit centered on the channel face. */
    private void fitInto(Canvas c, Bitmap b, float l, float t, float r, float btm, AppEntry app) {
        float w = r - l, h = btm - t;
        if (app.leanback && b.getWidth() > b.getHeight()) {
            rect.set(l, t, r, btm);
        } else {
            float size = Math.min(w, h) * 0.7f;
            rect.set(l + (w - size) / 2, t + (h - size) / 2, l + (w + size) / 2, t + (h + size) / 2);
        }
        c.drawBitmap(b, null, rect, paint);
    }

    /** Soft glow from stacked translucent strokes; avoids per-frame shadow textures. */
    private void glow(Canvas c, RectF r, float radius, float spread, int alpha) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 3; i >= 1; i--) {
            float d = spread * i / 3f;
            paint.setStrokeWidth(spread / 3f + 2f);
            paint.setColor(pal.blue);
            paint.setAlpha(alpha / (i + 1));
            c.drawRoundRect(r.left - d, r.top - d, r.right + d, r.bottom + d, radius + d, radius + d, paint);
        }
        paint.setAlpha(255);
    }

    private void drawLabel(Canvas c, RectF r, String label) {
        text.setTypeface(regular);
        text.setTextSize(40f);
        labelPaint.set(text);
        String l = TextUtils.ellipsize(label, labelPaint, 560f, TextUtils.TruncateAt.END).toString();
        float w = text.measureText(l) + 60f;
        float cx = Math.max(w / 2 + 20, Math.min(VW - w / 2 - 20, r.centerX()));
        float top = r.bottom - 34f;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.shadow);
        c.drawRoundRect(cx - w / 2, top + 4f, cx + w / 2, top + 70f, 33f, 33f, paint);
        paint.setColor(pal.labelFace);
        c.drawRoundRect(cx - w / 2, top, cx + w / 2, top + 66f, 33f, 33f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(pal.blue);
        c.drawRoundRect(cx - w / 2, top, cx + w / 2, top + 66f, 33f, 33f, paint);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(pal.labelText);
        c.drawText(l, cx, top + 47f, text);
    }

    private void drawArrows(Canvas c) {
        if (previewApp != null) return;
        int pages = Grid.pageCount(visible().size());
        float cy = GRID_TOP + (Grid.ROWS * TILE_H + (Grid.ROWS - 1) * GAP_Y) / 2f;
        if (focus.page > 0) arrow(c, 26f, cy, -1);
        if (focus.page < pages - 1) arrow(c, VW - 26f, cy, 1);
    }

    /** Small blue triangle with a white rim, as on the Wii Menu's page edges. */
    private void arrow(Canvas c, float cx, float cy, int dir) {
        path.reset();
        path.moveTo(cx + dir * 18f, cy);
        path.lineTo(cx - dir * 12f, cy - 30f);
        path.lineTo(cx - dir * 12f, cy + 30f);
        path.close();
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(10f);
        paint.setColor(Color.WHITE);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.blue);
        c.drawPath(path, paint);
    }

    private void drawBottomBar(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(pal.shadow);
        c.save();
        c.translate(0, -5f);
        c.drawPath(barFill, paint);
        c.restore();
        paint.setShader(barShader);
        c.drawPath(barFill, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(pal.line);
        c.drawPath(barEdge, paint);

        text.setTextAlign(Paint.Align.CENTER);
        if (sdOpen) {
            text.setTypeface(regular);
            text.setTextSize(50f);
            text.setColor(pal.date);
            c.drawText(getContext().getString(R.string.sd_menu_title), VW / 2, 1000f, text);
        } else {
            drawClock(c);
        }

        boolean grid = previewApp == null && !sdOpen;
        roundButton(c, BTN_SETTINGS_X, grid && focus.slot == Grid.BTN_SETTINGS);
        gear(c, BTN_SETTINGS_X, BTN_Y);
        roundButton(c, BTN_MAIL_X, grid && focus.slot == Grid.BTN_MAIL);
        envelope(c, BTN_MAIL_X, BTN_Y);
        sdCard(c, grid && focus.slot == Grid.BTN_SD);
    }

    private void drawClock(Canvas c) {
        Calendar now = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(getContext());
        String time = DateFormat.format(h24 ? "H:mm" : "h:mm", now).toString();
        text.setColor(pal.clock);
        text.setTypeface(light);
        text.setTextSize(104f);
        float tw = text.measureText(time);
        c.drawText(time, VW / 2, 978f, text);
        if (!h24) {
            text.setTextSize(36f);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(now.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM", VW / 2 + tw / 2 + 12f, 978f, text);
            text.setTextAlign(Paint.Align.CENTER);
        }
        text.setTypeface(regular);
        text.setTextSize(50f);
        text.setColor(pal.date);
        String line = openFolder != null ? openFolder.label : dateLine(now);
        c.drawText(line, VW / 2, 1050f, text);
    }

    /** "Lun. 15-03" style. */
    private static String dateLine(Calendar now) {
        String day = DateFormat.format("EEE", now).toString().replace(".", "");
        if (!day.isEmpty()) day = day.substring(0, 1).toUpperCase(Locale.getDefault()) + day.substring(1);
        return day + ". " + DateFormat.format("dd-MM", now);
    }

    private void roundButton(Canvas c, float cx, boolean focused) {
        float r = BTN_R * (focused ? 1.05f : 1f);
        rect.set(cx - r, BTN_Y - r, cx + r, BTN_Y + r);
        if (focused) {
            glow(c, rect, r, 20f, (int) (90 + 130 * pulse));
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.shadow);
        c.drawCircle(cx, BTN_Y + 4f, r, paint);
        paint.setColor(pal.buttonFace);
        c.drawCircle(cx, BTN_Y, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(focused ? 7f : 4f);
        paint.setColor(focused ? pal.blue : pal.buttonRing);
        c.drawCircle(cx, BTN_Y, r, paint);
    }

    private void gear(Canvas c, float cx, float cy) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.icon);
        for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45f, cx, cy);
            c.drawRoundRect(cx - 9f, cy - 46f, cx + 9f, cy - 24f, 4f, 4f, paint);
            c.restore();
        }
        c.drawCircle(cx, cy, 32f, paint);
        paint.setColor(pal.buttonFace);
        c.drawCircle(cx, cy, 13f, paint);
    }

    private void envelope(Canvas c, float cx, float cy) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(pal.icon);
        c.drawRoundRect(cx - 44f, cy - 30f, cx + 44f, cy + 30f, 6f, 6f, paint);
        path.reset();
        path.moveTo(cx - 44f, cy - 28f);
        path.lineTo(cx, cy + 6f);
        path.lineTo(cx + 44f, cy - 28f);
        c.drawPath(path, paint);
    }

    /** SD card icon beside the settings button; it pulses when GitHub has a newer release. */
    private void sdCard(Canvas c, boolean focused) {
        float w = 46f, h = 60f, l = SD_X - w / 2, t = SD_Y - h / 2;
        if (focused) {
            rect.set(l - 6f, t - 6f, l + w + 6f, t + h + 6f);
            glow(c, rect, 10f, 16f, (int) (90 + 130 * pulse));
        }
        path.reset();
        path.moveTo(l + 14f, t);
        path.lineTo(l + w - 4f, t);
        path.quadTo(l + w, t, l + w, t + 4f);
        path.lineTo(l + w, t + h - 4f);
        path.quadTo(l + w, t + h, l + w - 4f, t + h);
        path.lineTo(l + 4f, t + h);
        path.quadTo(l, t + h, l, t + h - 4f);
        path.lineTo(l, t + 14f);
        path.close();
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.blue);
        if (updateAvailable && !focused) paint.setAlpha((int) (120 + 135 * pulse));
        c.drawPath(path, paint);
        paint.setAlpha(255);
        paint.setColor(Color.WHITE);
        for (int i = 0; i < 4; i++) c.drawRect(l + 12f + i * 8f, t + 6f, l + 16f + i * 8f, t + 18f, paint);
        if (updateAvailable) {
            paint.setColor(Color.rgb(0xF5, 0x5A, 0x4E));
            c.drawCircle(l + w + 2f, t + 2f, 9f, paint);
        }
    }

    private void drawPreview(Canvas c) {
        float t = previewT;
        float l = lerp(previewFrom.left, 0, t), top = lerp(previewFrom.top, 0, t);
        float r = lerp(previewFrom.right, VW, t), b = lerp(previewFrom.bottom, VH, t);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.previewBg);
        c.drawRoundRect(l, top, r, b, RADIUS * (1 - t), RADIUS * (1 - t), paint);
        if (t < 0.6f) return;
        int alpha = (int) (255 * (t - 0.6f) / 0.4f);

        if (previewBanner != null) {
            paint.setAlpha(alpha);
            float bw = 1152f, bh = 648f;
            if (!(previewApp.leanback && previewBanner.getWidth() > previewBanner.getHeight())) bw = bh = 440f;
            rect.set((VW - bw) / 2, 380f - bh / 2, (VW + bw) / 2, 380f + bh / 2);
            c.drawBitmap(previewBanner, null, rect, paint);
            paint.setAlpha(255);
        }
        text.setTypeface(regular);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(52f);
        text.setColor(pal.date);
        text.setAlpha(alpha);
        c.drawText(previewApp.label, VW / 2, 790f, text);
        text.setAlpha(255);

        // Bottom strip with the two Wii-style buttons, in the bar's colors.
        paint.setShader(barShader);
        paint.setAlpha(alpha);
        c.drawRect(0, 860f, VW, VH, paint);
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(pal.line);
        paint.setAlpha(alpha);
        c.drawLine(0, 860f, VW, 860f, paint);
        paint.setAlpha(255);
        pillButton(c, previewPill(false), getContext().getString(R.string.menu), !previewStartFocused, alpha);
        pillButton(c, previewPill(true), getContext().getString(R.string.start), previewStartFocused, alpha);
    }

    private static RectF previewPill(boolean start) {
        float cx = VW / 2 + (start ? 330f : -330f);
        return new RectF(cx - 250f, 912f, cx + 250f, 1032f);
    }

    private void pillButton(Canvas c, RectF r, String label, boolean focused, int alpha) {
        if (focused) glow(c, r, 60f, 18f, (int) ((90 + 130 * pulse) * alpha / 255));
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.buttonFace);
        paint.setAlpha(alpha);
        c.drawRoundRect(r, 60f, 60f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(focused ? 7f : 3f);
        paint.setColor(focused ? pal.blue : pal.buttonRing);
        paint.setAlpha(alpha);
        c.drawRoundRect(r, 60f, 60f, paint);
        paint.setAlpha(255);
        text.setTypeface(regular);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(48f);
        text.setColor(pal.labelText);
        text.setAlpha(alpha);
        c.drawText(label, r.centerX(), r.centerY() + 17f, text);
        text.setAlpha(255);
    }

    // ---------------------------------------------------------------- SD (updates) menu

    private List<Integer> sdItems() {
        List<Integer> items = new ArrayList<>();
        items.add(SD_CHECK);
        if (updateAvailable) items.add(SD_INSTALL);
        items.add(SD_THEME);
        items.add(SD_BACK);
        return items;
    }

    private String sdLabel(int item) {
        Context ctx = getContext();
        switch (item) {
            case SD_CHECK: return ctx.getString(R.string.sd_check);
            case SD_INSTALL: return ctx.getString(R.string.sd_install, latestVersion);
            case SD_THEME: return ctx.getString(R.string.sd_theme, themeLabel);
            default: return ctx.getString(R.string.sd_back);
        }
    }

    private static RectF sdItemRect(int i) {
        return new RectF(VW / 2 - 420f, 400f + i * 112f, VW / 2 + 420f, 486f + i * 112f);
    }

    private void drawSdMenu(Canvas c) {
        rect.set(GRID_LEFT, GRID_TOP, VW - GRID_LEFT, 790f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.tileEmpty);
        c.drawRoundRect(rect, RADIUS, RADIUS, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(pal.tileBorder);
        c.drawRoundRect(rect, RADIUS, RADIUS, paint);

        Context ctx = getContext();
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(regular);
        text.setTextSize(48f);
        text.setColor(pal.labelText);
        c.drawText(ctx.getString(R.string.sd_versions, installedVersion,
                latestVersion.isEmpty() ? "—" : latestVersion), VW / 2, 140f, text);
        text.setTextSize(36f);
        text.setColor(pal.clock);
        String info = status != null ? status
                : updateAvailable ? ctx.getString(R.string.sd_new_available)
                : latestVersion.isEmpty() ? ctx.getString(R.string.sd_not_checked) : ctx.getString(R.string.sd_up_to_date);
        c.drawText(info, VW / 2, 205f, text);
        if (updateAvailable && !releaseNotes.isEmpty()) {
            text.setTextSize(30f);
            String[] lines = releaseNotes.replace("\r", "").split("\n");
            int y = 260;
            for (int i = 0; i < lines.length && y <= 350; i++) {
                String ln = lines[i].replaceAll("^[#*\\- ]+", "").trim();
                if (ln.isEmpty()) continue;
                labelPaint.set(text);
                c.drawText(TextUtils.ellipsize(ln, labelPaint, 1500f, TextUtils.TruncateAt.END).toString(), VW / 2, y, text);
                y += 42;
            }
        }
        List<Integer> items = sdItems();
        for (int i = 0; i < items.size(); i++) pillButton(c, sdItemRect(i), sdLabel(items.get(i)), sdFocus == i, 255);
    }

    // ---------------------------------------------------------------- hand cursor

    /** An original pointing-hand outline: index finger up, fingertip at (34, 0). */
    private static Path buildHand() {
        Path p = new Path();
        p.addRoundRect(23, 0, 45, 60, 11, 11, Path.Direction.CW);
        Path palm = new Path();
        palm.addRoundRect(12, 40, 72, 100, 22, 22, Path.Direction.CW);
        p.op(palm, Path.Op.UNION);
        for (float[] k : new float[][]{{40, 36, 56, 66}, {53, 42, 68, 70}, {62, 50, 76, 76}}) {
            Path knuckle = new Path();
            knuckle.addRoundRect(k[0], k[1], k[2], k[3], 8, 8, Path.Direction.CW);
            p.op(knuckle, Path.Op.UNION);
        }
        Path thumb = new Path();
        thumb.addRoundRect(-4, 52, 26, 70, 9, 9, Path.Direction.CW);
        Matrix m = new Matrix();
        m.setRotate(-35, 20, 62);
        thumb.transform(m);
        p.op(thumb, Path.Op.UNION);
        return p;
    }

    private void drawHand(Canvas c) {
        float bob = 3f * (pulse - 0.5f);
        c.save();
        c.translate(handX, handY + bob);
        c.rotate(-16f);
        c.translate(-34f, 0f);
        c.scale(1.25f, 1.25f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(60, 0, 0, 0));
        c.save();
        c.translate(5f, 6f);
        c.drawPath(hand, paint);
        c.restore();
        paint.setColor(Color.WHITE);
        c.drawPath(hand, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(4f);
        paint.setColor(Color.rgb(0x3C, 0x40, 0x48));
        c.drawPath(hand, paint);
        paint.setStrokeWidth(2.5f);
        paint.setColor(pal.blue);
        c.drawLine(30, 14, 30, 40, paint); // finger crease, the cursor's only accent
        c.restore();
    }

    /** Where the fingertip should rest for the current focus. */
    private float[] handTarget() {
        if (previewApp != null) {
            RectF r = previewPill(previewStartFocused);
            return new float[]{r.centerX() + 150f, r.centerY() + 4f};
        }
        if (sdOpen) {
            RectF r = sdItemRect(sdFocus);
            return new float[]{r.right - 110f, r.centerY() + 4f};
        }
        switch (focus.slot) {
            case Grid.BTN_SETTINGS: return new float[]{BTN_SETTINGS_X + 30f, BTN_Y + 10f};
            case Grid.BTN_MAIL: return new float[]{BTN_MAIL_X + 30f, BTN_Y + 10f};
            case Grid.BTN_SD: return new float[]{SD_X + 16f, SD_Y + 8f};
            default:
                tileRect(focus.slot, 0, rect);
                return new float[]{rect.centerX() + 150f, rect.centerY() - 40f};
        }
    }

    private void retargetHand(boolean animate) {
        float[] t = handTarget();
        if (handAnim != null) handAnim.cancel();
        if (!animate) {
            handX = t[0];
            handY = t[1];
            return;
        }
        handFromX = handX;
        handFromY = handY;
        handToX = t[0];
        handToY = t[1];
        handAnim = ValueAnimator.ofFloat(0f, 1f);
        handAnim.setDuration(170);
        handAnim.setInterpolator(new DecelerateInterpolator());
        handAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            handX = lerp(handFromX, handToX, f);
            handY = lerp(handFromY, handToY, f);
            invalidate();
        });
        handAnim.start();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ---------------------------------------------------------------- banners

    private Bitmap banner(AppEntry app) {
        Bitmap b = banners.get(app.pkg);
        if (b != null || loading.contains(app.pkg) || getWidth() == 0) return b;
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
            if (isBanner) {
                // Round the corners once here instead of clipping on every frame.
                float r = h * 0.08f;
                Path clip = new Path();
                clip.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
                c.clipPath(clip);
            }
            d.setBounds(0, 0, w, h);
            d.draw(c);
            bmp.prepareToDraw();
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
                    boolean start = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
                    if (start != previewStartFocused) {
                        previewStartFocused = start;
                        sfx.tick();
                        retargetHand(true);
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    animatePreview(false);
                    return true;
                default:
                    return true; // swallow other keys while zoomed in
            }
        }
        if (sdOpen) {
            int n = sdItems().size();
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    int next = Math.max(0, Math.min(n - 1, sdFocus + (keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
                    if (next != sdFocus) {
                        sdFocus = next;
                        sfx.tick();
                        retargetHand(true);
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    closeSd();
                    return true;
                default:
                    return true;
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
                if (openFolder != null) {
                    sfx.select();
                    exitFolder();
                } else if (focus.page != 0 || focus.slot != 0) {
                    animatePage(0);
                    focus = new Grid.Focus(0, 0);
                    retargetHand(true);
                }
                invalidate();
                return true; // a launcher has nowhere to go back to
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override public boolean onKeyLongPress(int keyCode, KeyEvent e) {
        if (isSelect(keyCode) && previewApp == null && !sdOpen) {
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
        if (sdOpen) {
            sfx.select();
            switch (sdItems().get(sdFocus)) {
                case SD_CHECK: callbacks.onCheckUpdates(); break;
                case SD_INSTALL: callbacks.onInstallUpdate(); break;
                case SD_THEME: callbacks.onCycleTheme(); break;
                default: closeSd();
            }
            return true;
        }
        List<Channel> list = visible();
        if (focus.slot == Grid.BTN_SETTINGS) {
            sfx.select();
            callbacks.onSettings();
        } else if (focus.slot == Grid.BTN_MAIL) {
            sfx.select();
            callbacks.onInputs();
        } else if (focus.slot == Grid.BTN_SD) {
            sfx.select();
            openSd();
        } else if (focus.index() < list.size()) {
            sfx.select();
            Channel ch = list.get(focus.index());
            if (ch.isFolder()) enterFolder(ch);
            else openPreview(ch.app);
        }
        return true;
    }

    private static boolean isSelect(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    private boolean step(Grid.Dir d) {
        Grid.Focus next = Grid.move(focus, d, Grid.pageCount(visible().size()));
        if (next.equals(focus)) return true;
        if (next.page != focus.page) animatePage(next.page);
        focus = next;
        sfx.tick();
        retargetHand(true);
        invalidate();
        return true;
    }

    private void openOptions() {
        int idx = focus.index();
        List<Channel> list = visible();
        if (idx >= 0 && idx < list.size()) callbacks.onOptions(list.get(idx), idx, openFolder);
    }

    private void clampFocus() {
        int pages = Grid.pageCount(visible().size());
        if (focus.page >= pages) focus = new Grid.Focus(pages - 1, 0);
    }

    private void enterFolder(Channel folder) {
        focusBeforeFolder = focus;
        enterFolderList(folder);
        focus = new Grid.Focus(0, 0);
        shownPage = 0;
        retargetHand(true);
        invalidate();
    }

    private void enterFolderList(Channel folder) {
        openFolder = folder;
        folderChannels = new ArrayList<>();
        for (AppEntry a : folder.folderApps) folderChannels.add(Channel.of(a));
    }

    private void exitFolder() {
        openFolder = null;
        folderChannels = new ArrayList<>();
        focus = focusBeforeFolder != null ? focusBeforeFolder : new Grid.Focus(0, 0);
        focusBeforeFolder = null;
        clampFocus();
        shownPage = focus.page;
        retargetHand(true);
    }

    private void openSd() {
        sdOpen = true;
        sdFocus = 0;
        status = null;
        retargetHand(true);
        invalidate();
    }

    private void closeSd() {
        sdOpen = false;
        retargetHand(true);
        invalidate();
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
        Bitmap big = render(app, 1152, 648);
        previewBanner = big != null ? big : banners.get(app.pkg);
        animatePreview(true);
        retargetHand(true);
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
                retargetHand(true);
            }
            invalidate();
        });
        previewAnim.start();
    }
}
