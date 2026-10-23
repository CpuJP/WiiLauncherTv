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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The whole menu is drawn on one canvas in a virtual 1920x1080 space scaled to the real surface,
 * so it stays sharp at 1080p and 4K without bitmaps for the chrome. Geometry is measured from a
 * 1920x1080 capture of the Wii Menu.
 */
final class WiiMenuView extends View {

    interface Callbacks {
        void onLaunch(AppEntry app);
        void onOptions(Channel channel, int index, Channel inFolder);
        void onMoved(List<Channel> order, Channel inFolder);
        void onSettings();
        void onInputs();
        void onCheckUpdates();
        void onInstallUpdate();
        void onCycleTheme();
    }

    // Layout in virtual units (1920x1080).
    private static final float VW = 1920f, VH = 1080f;
    private static final float TILE_W = 382f, TILE_H = 208f, GAP_X = 22f, GAP_Y = 20f;
    private static final float GRID_LEFT = 163f, GRID_TOP = 92f;
    private static final float PAGE_W = Grid.COLS * (TILE_W + GAP_X); // next page peeks in at the right edge
    private static final float RADIUS = 30f;
    private static final float BAR_TOP = 783f, BAR_DIP = 895f;
    private static final float BTN_R = 86f, WELL_R = 118f, BTN_Y = 912f;
    private static final float BTN_SETTINGS_X = 178f, BTN_MAIL_X = 1745f;
    private static final float SD_X = 395f, SD_Y = 945f;
    private static final float ARROW_Y = 412f;

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
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final RectF rect = new RectF();
    private final RectF tile = new RectF();
    private final Path path = new Path();
    private final Path barEdge = new Path();
    private final Path barFill = new Path();
    private final Path handPoint, handFist;
    private final Shader handShader, arrowShader, sdShader;
    private final Typeface boldItalic = Typeface.create("sans-serif", Typeface.BOLD_ITALIC);
    private float[] stripes = new float[0];
    private Shader barShader, tileShader, emptyShader, buttonShader, wellShader;
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

    // Hand cursor: eases toward the focused element and closes into a fist while pressing or dragging.
    private float handX = VW / 2, handY = VH / 2, handFromX, handFromY, handToX, handToY;
    private ValueAnimator handAnim;
    private boolean pressing;
    private boolean grabbing;

    // Channel preview ("zoomed in" channel with Wii Menu / Start).
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
            main.postDelayed(this, 1000 - System.currentTimeMillis() % 1000); // the colon blinks every second
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
        handPoint = buildHand(true);
        handFist = buildHand(false);
        handShader = new LinearGradient(0, 36, 0, 104, Color.WHITE, Color.rgb(0xC4, 0xE6, 0xF8), Shader.TileMode.CLAMP);
        arrowShader = new LinearGradient(0, ARROW_Y - 46f, 0, ARROW_Y + 46f, Color.WHITE, Color.rgb(0xCF, 0xEC, 0xFA), Shader.TileMode.CLAMP);
        sdShader = new LinearGradient(0, SD_Y - 48f, 0, SD_Y + 48f, Color.WHITE, Color.rgb(0xD2, 0xEC, 0xFA), Shader.TileMode.CLAMP);
    }

    // ---------------------------------------------------------------- state from the activity

    void setChannels(List<Channel> list) {
        grabbing = false;
        channels = new ArrayList<>(list);
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

    /** Picks up the focused channel; the D-pad then drags it and OK drops it, as with A+B on the Wii. */
    void startGrab() {
        int idx = focus.index();
        if (idx < 0 || idx >= visible().size()) return;
        grabbing = true;
        sfx.select();
        retargetHand(true);
        invalidate();
    }

    /** Home pressed while already home: back to the top of the menu. */
    void resetToMenu() {
        if (grabbing) drop();
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
        int n = (int) (VH / 5f);
        stripes = new float[n * 4];
        for (int i = 0; i < n; i++) {
            float y = i * 5f + 2f;
            stripes[i * 4] = 0;
            stripes[i * 4 + 1] = y;
            stripes[i * 4 + 2] = VW;
            stripes[i * 4 + 3] = y;
        }
        // Bar edge: flat at both ends, dipping in the middle where the clock sits on the background.
        barEdge.reset();
        barEdge.moveTo(0, BAR_TOP);
        barEdge.lineTo(330, BAR_TOP);
        barEdge.cubicTo(470, BAR_TOP, 520, BAR_DIP, 650, BAR_DIP);
        barEdge.lineTo(VW - 650, BAR_DIP);
        barEdge.cubicTo(VW - 520, BAR_DIP, VW - 470, BAR_TOP, VW - 330, BAR_TOP);
        barEdge.lineTo(VW, BAR_TOP);
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
        barShader = new LinearGradient(0, BAR_TOP, 0, VH, pal.barTop, pal.barBottom, Shader.TileMode.CLAMP);
        tileShader = new LinearGradient(0, 0, 0, TILE_H, pal.tileTop, pal.tileBottom, Shader.TileMode.CLAMP);
        emptyShader = new LinearGradient(0, 0, 0, TILE_H, pal.emptyTop, pal.emptyBottom, Shader.TileMode.CLAMP);
        buttonShader = new LinearGradient(0, -BTN_R, 0, BTN_R, pal.buttonTop, pal.buttonBottom, Shader.TileMode.CLAMP);
        wellShader = new LinearGradient(0, -WELL_R, 0, WELL_R, pal.well, pal.barTop, Shader.TileMode.CLAMP);
    }

    // ---------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas c) {
        c.save();
        c.scale(getWidth() / VW, getHeight() / VH);

        c.drawColor(pal.bg);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(pal.stripe);
        c.drawLines(stripes, paint);

        if (sdOpen) {
            drawSdMenu(c);
        } else {
            int base = (int) Math.floor(shownPage);
            for (int p = Math.max(0, base - 1); p <= base + 2; p++) drawPage(c, p, (p - shownPage) * PAGE_W);
            drawArrows(c);
            if (grabbing) drawGrabbed(c);
        }
        drawBottomBar(c);
        if (previewApp != null) drawPreview(c);
        drawHand(c);
        c.restore();
    }

    private void drawPage(Canvas c, int page, float dx) {
        List<Channel> list = visible();
        for (int slot = 0; slot < Grid.PER_PAGE; slot++) {
            tileRect(slot, dx, tile);
            if (tile.right < 0 || tile.left > VW) continue;
            int idx = page * Grid.PER_PAGE + slot;
            boolean isFocus = previewApp == null && focus.page == page && focus.slot == slot;
            Channel ch = idx < list.size() ? list.get(idx) : null;
            if (grabbing && isFocus) ch = null; // the grabbed channel is drawn under the hand
            drawTile(c, tile, ch, isFocus && !grabbing);
        }
    }

    private static void tileRect(int slot, float dx, RectF out) {
        int col = slot % Grid.COLS, row = slot / Grid.COLS;
        float l = GRID_LEFT + col * (TILE_W + GAP_X) + dx, t = GRID_TOP + row * (TILE_H + GAP_Y);
        out.set(l, t, l + TILE_W, t + TILE_H);
    }

    private void drawTile(Canvas c, RectF r, Channel ch, boolean focused) {
        if (focused) glow(c, r, RADIUS, 16f, (int) (110 + 120 * pulse));
        paint.setStyle(Paint.Style.FILL);
        c.save();
        c.translate(r.left, r.top);
        paint.setShader(ch == null ? emptyShader : tileShader);
        c.drawRoundRect(0, 0, r.width(), r.height(), RADIUS, RADIUS, paint);
        paint.setShader(null);
        c.restore();
        if (ch != null) {
            if (ch.isFolder()) {
                drawFolderFace(c, r, ch);
            } else {
                Bitmap b = banner(ch.app);
                if (b != null) fitInto(c, b, r.left + 7, r.top + 7, r.right - 7, r.bottom - 7, ch.app);
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        if (focused) {
            paint.setColor(pal.focus);
            paint.setStrokeWidth(7f);
        } else {
            paint.setColor(pal.tileBorder);
            paint.setStrokeWidth(3f);
        }
        c.drawRoundRect(r, RADIUS, RADIUS, paint);
    }

    /** The grabbed channel floats with the fist, slightly lifted. */
    private void drawGrabbed(Canvas c) {
        int idx = focus.index();
        if (idx < 0 || idx >= visible().size()) return;
        float w = TILE_W * 1.06f, h = TILE_H * 1.06f;
        float cx = handX - 40f, cy = handY + 10f;
        RectF r = new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.shadow);
        c.drawRoundRect(r.left + 10f, r.top + 14f, r.right + 10f, r.bottom + 14f, RADIUS, RADIUS, paint);
        drawTile(c, r, visible().get(idx), true);
    }

    /** A folder shows its first four channels as a 2x2 mosaic plus a folder badge. */
    private void drawFolderFace(Canvas c, RectF r, Channel ch) {
        float pad = 18f, gap = 10f;
        float w = (r.width() - 2 * pad - gap) / 2f, h = (r.height() - 2 * pad - gap) / 2f;
        for (int i = 0; i < 4; i++) {
            float l = r.left + pad + (i % 2) * (w + gap), t = r.top + pad + (i / 2) * (h + gap);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(pal.emptyBottom);
            c.drawRoundRect(l, t, l + w, t + h, 12f, 12f, paint);
            if (i < ch.folderApps.size()) {
                Bitmap b = banner(ch.folderApps.get(i));
                if (b != null) fitInto(c, b, l, t, l + w, t + h, ch.folderApps.get(i));
            }
        }
        float bx = r.right - 50f, by = r.bottom - 44f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.focus);
        path.reset();
        path.addRoundRect(bx - 28f, by - 14f, bx + 28f, by + 22f, 6f, 6f, Path.Direction.CW);
        path.addRoundRect(bx - 28f, by - 22f, bx - 4f, by - 8f, 4f, 4f, Path.Direction.CW);
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
            paint.setColor(pal.focus);
            paint.setAlpha(alpha / (i + 1));
            c.drawRoundRect(r.left - d, r.top - d, r.right + d, r.bottom + d, radius + d, radius + d, paint);
        }
        paint.setAlpha(255);
    }

    private void drawArrows(Canvas c) {
        if (previewApp != null) return;
        int pages = Grid.pageCount(visible().size());
        if (focus.page > 0) arrow(c, 98f, -1);
        if (focus.page < pages - 1) arrow(c, 1822f, 1);
    }

    /** Blue-outlined triangle with a pale fill, as on the Wii Menu's page edges. */
    private void arrow(Canvas c, float cx, int dir) {
        path.reset();
        path.moveTo(cx + dir * 26f, ARROW_Y);
        path.lineTo(cx - dir * 22f, ARROW_Y - 46f);
        path.lineTo(cx - dir * 22f, ARROW_Y + 46f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setShader(arrowShader);
        c.drawPath(path, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(5f);
        paint.setColor(Color.rgb(0x2E, 0x8E, 0xD6));
        c.drawPath(path, paint);
    }

    private void drawBottomBar(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(barShader);
        c.drawPath(barFill, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(pal.buttonTop);
        c.save();
        c.translate(0, 5f);
        c.drawPath(barEdge, paint); // pale highlight under the blue line
        c.restore();
        paint.setStrokeWidth(5f);
        paint.setColor(pal.line);
        c.drawPath(barEdge, paint);

        if (sdOpen) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(bold);
            text.setTextSize(64f);
            text.setColor(pal.date);
            c.drawText(getContext().getString(R.string.sd_menu_title), VW / 2, 985f, text);
        } else {
            drawClock(c);
        }

        boolean grid = previewApp == null && !sdOpen && !grabbing;
        roundButton(c, BTN_SETTINGS_X, grid && focus.slot == Grid.BTN_SETTINGS);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(bold);
        text.setTextSize(66f);
        text.setColor(pal.icon);
        c.drawText("Wii", BTN_SETTINGS_X, BTN_Y + 23f, text);
        roundButton(c, BTN_MAIL_X, grid && focus.slot == Grid.BTN_MAIL);
        envelope(c, BTN_MAIL_X, BTN_Y);
        sdCard(c, grid && focus.slot == Grid.BTN_SD);
    }

    // ---------------------------------------------------------------- clock

    private static final float DIGIT_W = 48f, DIGIT_H = 76f, CLOCK_TOP = 790f;

    /** Hollow segmented digits with a blinking colon centered on screen, then AM/PM. */
    private void drawClock(Canvas c) {
        Calendar now = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(getContext());
        int hour = now.get(h24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        if (!h24 && hour == 0) hour = 12;
        int min = now.get(Calendar.MINUTE);
        String hh = String.valueOf(hour), mm = String.format(Locale.ROOT, "%02d", min);

        if (hh.length() == 2) digit(c, hh.charAt(0), 823f);
        digit(c, hh.charAt(hh.length() - 1), 887f);
        digit(c, mm.charAt(0), 985f);
        digit(c, mm.charAt(1), 1049f);
        if (now.get(Calendar.SECOND) % 2 == 0) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(pal.clock);
            c.drawCircle(VW / 2, CLOCK_TOP + 22f, 7.5f, paint);
            c.drawCircle(VW / 2, CLOCK_TOP + 56f, 7.5f, paint);
        }
        if (!h24) {
            text.setTextAlign(Paint.Align.LEFT);
            text.setTypeface(bold);
            text.setTextSize(46f);
            text.setColor(pal.clock);
            c.drawText(now.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM", 1138f, CLOCK_TOP + DIGIT_H, text);
        }

        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(bold);
        text.setTextSize(78f);
        text.setColor(pal.date);
        String line = openFolder != null ? openFolder.label : dateLine(now);
        labelPaint.set(text);
        c.drawText(TextUtils.ellipsize(line, labelPaint, 900f, TextUtils.TruncateAt.END).toString(), VW / 2, 985f, text);
    }

    private void digit(Canvas c, char ch, float x) {
        int m = SevenSegment.mask(ch);
        float y = CLOCK_TOP, w = DIGIT_W, h = DIGIT_H, mid = y + h / 2, inset = 9f;
        float[][] seg = {
                {x + inset, y, x + w - inset, y},                 // a
                {x + w, y + inset, x + w, mid - inset},           // b
                {x + w, mid + inset, x + w, y + h - inset},       // c
                {x + inset, y + h, x + w - inset, y + h},         // d
                {x, mid + inset, x, y + h - inset},               // e
                {x, y + inset, x, mid - inset},                   // f
                {x + inset, mid, x + w - inset, mid},             // g
        };
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int pass = 0; pass < 2; pass++) {
            paint.setStrokeWidth(pass == 0 ? 17f : 6f);
            paint.setColor(pass == 0 ? pal.clock : pal.clockInner);
            for (int i = 0; i < 7; i++) if ((m & (1 << i)) != 0) c.drawLine(seg[i][0], seg[i][1], seg[i][2], seg[i][3], paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** "Lun. 15-03" style, as on a Spanish Wii. */
    private static String dateLine(Calendar now) {
        String day = DateFormat.format("EEE", now).toString().replace(".", "");
        if (!day.isEmpty()) day = day.substring(0, 1).toUpperCase(Locale.getDefault()) + day.substring(1);
        return day + ". " + DateFormat.format("dd-MM", now);
    }

    // ---------------------------------------------------------------- bottom bar buttons

    /** Round button sitting in a recessed well, with the Wii Menu's blue rim. */
    private void roundButton(Canvas c, float cx, boolean focused) {
        c.save();
        c.translate(cx, BTN_Y);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(wellShader);
        c.drawCircle(0, 0, WELL_R, paint);
        paint.setShader(null);
        float r = BTN_R * (focused ? 1.06f : 1f);
        if (focused) {
            rect.set(-r, -r, r, r);
            glow(c, rect, r, 18f, (int) (110 + 120 * pulse));
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.shadow);
        c.drawCircle(0, 5f, r, paint);
        paint.setShader(buttonShader);
        c.drawCircle(0, 0, r, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(focused ? 7f : 5f);
        paint.setColor(pal.ring);
        c.drawCircle(0, 0, r - 2f, paint);
        c.restore();
    }

    private void envelope(Canvas c, float cx, float cy) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.icon);
        c.drawRoundRect(cx - 50f, cy - 36f, cx + 50f, cy + 32f, 7f, 7f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(pal.buttonTop);
        path.reset();
        path.moveTo(cx - 46f, cy - 30f);
        path.lineTo(cx, cy + 2f);
        path.lineTo(cx + 46f, cy - 30f);
        c.drawPath(path, paint);
    }

    /** SD card icon beside the Wii button; it pulses when GitHub has a newer release. */
    private void sdCard(Canvas c, boolean focused) {
        float w = 74f, h = 96f, l = SD_X - w / 2, t = SD_Y - h / 2;
        if (focused) {
            rect.set(l - 4f, t - 4f, l + w + 4f, t + h + 4f);
            glow(c, rect, 10f, 16f, (int) (110 + 120 * pulse));
        }
        path.reset();
        path.moveTo(l + 20f, t);
        path.lineTo(l + w - 6f, t);
        path.quadTo(l + w, t, l + w, t + 6f);
        path.lineTo(l + w, t + h - 6f);
        path.quadTo(l + w, t + h, l + w - 6f, t + h);
        path.lineTo(l + 6f, t + h);
        path.quadTo(l, t + h, l, t + h - 6f);
        path.lineTo(l, t + 20f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.shadow);
        c.save();
        c.translate(4f, 5f);
        c.drawPath(path, paint);
        c.restore();
        paint.setColor(Color.WHITE);
        paint.setShader(sdShader);
        if (updateAvailable && !focused) paint.setAlpha((int) (140 + 115 * pulse));
        c.drawPath(path, paint);
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(Color.rgb(0x3F, 0xA9, 0xE0));
        c.drawPath(path, paint);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(boldItalic);
        text.setTextSize(34f);
        text.setColor(Color.rgb(0x3F, 0xA9, 0xE0));
        c.drawText("SD", SD_X + 2f, SD_Y + 12f, text);
        if (updateAvailable) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(0xF5, 0x5A, 0x4E));
            c.drawCircle(l + w, t + 2f, 11f, paint);
        }
    }

    // ---------------------------------------------------------------- channel preview

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
            float bw = 1280f, bh = 697f;
            if (!(previewApp.leanback && previewBanner.getWidth() > previewBanner.getHeight())) bw = bh = 440f;
            rect.set((VW - bw) / 2, 410f - bh / 2, (VW + bw) / 2, 410f + bh / 2);
            c.drawBitmap(previewBanner, null, rect, paint);
            paint.setAlpha(255);
        }

        // Bottom strip with the two Wii-style buttons, in the bar's colors.
        paint.setShader(barShader);
        paint.setAlpha(alpha);
        c.drawRect(0, 860f, VW, VH, paint);
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5f);
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
        if (focused) glow(c, r, 60f, 18f, (int) ((110 + 120 * pulse) * alpha / 255));
        c.save();
        c.translate(0, r.centerY());
        paint.setShader(buttonShader);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(alpha);
        c.drawRoundRect(r.left, -r.height() / 2, r.right, r.height() / 2, 60f, 60f, paint);
        c.restore();
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(focused ? 7f : 4f);
        paint.setColor(focused ? pal.focus : pal.tileBorder);
        paint.setAlpha(alpha);
        c.drawRoundRect(r, 60f, 60f, paint);
        paint.setAlpha(255);
        text.setTypeface(bold);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(50f);
        text.setColor(pal.icon);
        text.setAlpha(alpha);
        c.drawText(label, r.centerX(), r.centerY() + 18f, text);
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
        return new RectF(VW / 2 - 420f, 380f + i * 100f, VW / 2 + 420f, 462f + i * 100f);
    }

    private void drawSdMenu(Canvas c) {
        rect.set(GRID_LEFT, GRID_TOP, VW - GRID_LEFT, 756f);
        c.save();
        c.translate(0, rect.top);
        paint.setShader(emptyShader);
        paint.setStyle(Paint.Style.FILL);
        c.drawRoundRect(rect.left, 0, rect.right, rect.height(), RADIUS, RADIUS, paint);
        c.restore();
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(pal.tileBorder);
        c.drawRoundRect(rect, RADIUS, RADIUS, paint);

        Context ctx = getContext();
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(bold);
        text.setTextSize(48f);
        text.setColor(pal.labelText);
        c.drawText(ctx.getString(R.string.sd_versions, installedVersion,
                latestVersion.isEmpty() ? "—" : latestVersion), VW / 2, 180f, text);
        text.setTypeface(regular);
        text.setTextSize(36f);
        text.setColor(pal.date);
        String info = status != null ? status
                : updateAvailable ? ctx.getString(R.string.sd_new_available)
                : latestVersion.isEmpty() ? ctx.getString(R.string.sd_not_checked) : ctx.getString(R.string.sd_up_to_date);
        c.drawText(info, VW / 2, 238f, text);
        if (updateAvailable && !releaseNotes.isEmpty()) {
            text.setTextSize(30f);
            String[] lines = releaseNotes.replace("\r", "").split("\n");
            int y = 290;
            for (int i = 0; i < lines.length && y <= 340; i++) {
                String ln = lines[i].replaceAll("^[#*\\- ]+", "").trim();
                if (ln.isEmpty()) continue;
                labelPaint.set(text);
                c.drawText(TextUtils.ellipsize(ln, labelPaint, 1400f, TextUtils.TruncateAt.END).toString(), VW / 2, y, text);
                y += 40;
            }
        }
        List<Integer> items = sdItems();
        for (int i = 0; i < items.size(); i++) pillButton(c, sdItemRect(i), sdLabel(items.get(i)), sdFocus == i, 255);
    }

    // ---------------------------------------------------------------- hand cursor

    /**
     * The Wii Remote pointer: a white hand with a black outline, pale blue toward the wrist and a blue
     * player "1". Pointing: index finger up, fingertip at (27, 0). Fist: the index finger curls in too.
     */
    private static Path buildHand(boolean pointing) {
        Path p = new Path();
        p.addRoundRect(6, 42, 68, 106, 22, 22, Path.Direction.CW);
        float[][] parts = pointing
                ? new float[][]{{18, 0, 36, 62, 9}, {35, 34, 50, 62, 7.5f}, {47, 40, 61, 66, 7}, {57, 48, 70, 74, 6.5f}}
                : new float[][]{{18, 30, 36, 60, 8}, {34, 32, 50, 60, 7.5f}, {47, 38, 61, 64, 7}, {57, 46, 70, 72, 6.5f}};
        for (float[] k : parts) {
            Path q = new Path();
            q.addRoundRect(k[0], k[1], k[2], k[3], k[4], k[4], Path.Direction.CW);
            p.op(q, Path.Op.UNION);
        }
        Path thumb = new Path();
        thumb.addRoundRect(-4, 58, 24, 80, 11, 11, Path.Direction.CW);
        Matrix m = new Matrix();
        m.setRotate(-32, 18, 69);
        thumb.transform(m);
        p.op(thumb, Path.Op.UNION);
        return p;
    }

    private void drawHand(Canvas c) {
        boolean fist = pressing || grabbing;
        Path hand = fist ? handFist : handPoint;
        float bob = grabbing ? 0f : 3f * (pulse - 0.5f);
        c.save();
        c.translate(handX, handY + bob);
        c.rotate(6f);
        c.scale(1.3f, 1.3f);
        c.translate(-27f, fist ? -24f : 0f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(70, 0, 0, 0));
        c.save();
        c.translate(6f, 8f);
        c.drawPath(hand, paint);
        c.restore();
        paint.setColor(Color.WHITE); // opaque base, or the shadow's alpha would tint the hand
        paint.setShader(handShader);
        c.drawPath(hand, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(4.5f);
        paint.setColor(Color.rgb(0x1E, 0x1E, 0x20));
        c.drawPath(hand, paint);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(bold);
        text.setTextSize(32f);
        text.setColor(Color.rgb(0x2B, 0x9B, 0xE3));
        c.drawText("1", 46f, 99f, text);
        c.restore();
    }

    /** Where the fingertip should rest for the current focus. */
    private float[] handTarget() {
        if (previewApp != null) {
            RectF r = previewPill(previewStartFocused);
            return new float[]{r.centerX() + 150f, r.centerY() - 10f};
        }
        if (sdOpen) {
            RectF r = sdItemRect(sdFocus);
            return new float[]{r.right - 110f, r.centerY() - 10f};
        }
        switch (focus.slot) {
            case Grid.BTN_SETTINGS: return new float[]{BTN_SETTINGS_X + 40f, BTN_Y + 4f};
            case Grid.BTN_MAIL: return new float[]{BTN_MAIL_X + 40f, BTN_Y + 4f};
            case Grid.BTN_SD: return new float[]{SD_X + 22f, SD_Y + 10f};
            default:
                tileRect(focus.slot, 0, rect);
                return new float[]{rect.centerX() + 60f, rect.centerY() - 10f};
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
        int w = Math.max(1, Math.round((TILE_W - 14) * s)), h = Math.max(1, Math.round((TILE_H - 14) * s));
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
                float r = h * 0.11f;
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
                pressing = true;
                invalidate();
            }
            return true;
        }
        if (grabbing) {
            Grid.Dir d = dir(keyCode);
            if (d != null) {
                drag(d);
            } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                drop();
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
        Grid.Dir d = dir(keyCode);
        if (d != null) return step(d);
        switch (keyCode) {
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
        if (isSelect(keyCode) && previewApp == null && !sdOpen && !grabbing) {
            longPressHandled = true;
            startGrab();
            return true;
        }
        return super.onKeyLongPress(keyCode, e);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (!isSelect(keyCode)) return super.onKeyUp(keyCode, e);
        pressing = false;
        invalidate();
        if (longPressHandled || e.isCanceled()) return true;
        if (grabbing) {
            drop();
            return true;
        }
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

    private static Grid.Dir dir(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return Grid.Dir.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Grid.Dir.RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return Grid.Dir.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Grid.Dir.DOWN;
            default: return null;
        }
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

    private void drag(Grid.Dir d) {
        List<Channel> list = visible();
        int from = focus.index(), to = Grid.dragTarget(focus, d, list.size());
        if (to < 0) return;
        Channel moving = list.remove(from);
        list.add(to, moving);
        Grid.Focus next = new Grid.Focus(to / Grid.PER_PAGE, to % Grid.PER_PAGE);
        if (next.page != focus.page) animatePage(next.page);
        focus = next;
        sfx.tick();
        retargetHand(true);
        invalidate();
    }

    private void drop() {
        grabbing = false;
        sfx.select();
        callbacks.onMoved(Collections.unmodifiableList(new ArrayList<>(visible())), openFolder);
        retargetHand(true);
        invalidate();
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
        pageAnim.setDuration(280);
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
        Bitmap big = render(app, 1280, 697);
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
