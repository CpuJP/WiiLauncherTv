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
 * so it stays sharp at 1080p and 4K without bitmaps for the chrome. Geometry is measured from
 * 1920x1080 captures of the Wii Menu.
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

    interface ListCallback {
        void onPick(int index);
    }

    // Layout in virtual units (1920x1080).
    private static final float VW = 1920f, VH = 1080f;
    private static final float TILE_W = 382f, TILE_H = 208f, GAP_X = 22f, GAP_Y = 20f;
    private static final float GRID_LEFT = 163f, GRID_TOP = 92f;
    private static final float PAGE_W = Grid.COLS * (TILE_W + GAP_X); // next page peeks in at the right edge
    private static final float RADIUS = 34f;
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
    private final WiiDraw wii = new WiiDraw();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> banners = new LruCache<>(Grid.PER_PAGE * 3);
    private final Set<String> loading = new HashSet<>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint();
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Typeface boldItalic = Typeface.create("sans-serif", Typeface.BOLD_ITALIC);
    private final RectF rect = new RectF();
    private final RectF tile = new RectF();
    private final Path path = new Path();
    private final Path tilePath = new Path();
    private final Path grabPath = new Path();
    private final Path barEdge = new Path();
    private final Path barFill = new Path();
    private final Shader sdShader, grabShader;
    private final Path[] digitPaths = new Path[10];
    // Tile frames pre-rendered at the screen's pixel size: drawing the soft-cornered path 75 times
    // per frame cost half the frames on this TV.
    private static final float SPRITE_M = 26f; // margin for glow and shadow, in virtual units
    private Bitmap spriteEmpty, spriteFull, spriteRim, spriteGlow, spriteFocusRim;
    private float[] stripes = new float[0];
    private Shader barShader, tileShader, emptyShader, buttonShader, wellShader, previewBarShader;
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

    // Channel preview ("zoomed in" channel with Wii Menu / Start and arrows to the neighbours).
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

    // Wii settings-style list (inputs, options, folders).
    private String listTitle;
    private List<String> listItems;
    private ListCallback listCallback;
    private int listSel, listTop;
    private static final int LIST_VISIBLE = 4;

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
        WiiDraw.squircle(tilePath, 0, 0, TILE_W, TILE_H, RADIUS);
        for (int d = 0; d < 10; d++) digitPaths[d] = buildDigit(SevenSegment.mask((char) ('0' + d)));
        WiiDraw.squircle(grabPath, 0, 0, 308f, 163f, 28f);
        sdShader = new LinearGradient(0, SD_Y - 48f, 0, SD_Y + 48f, Color.WHITE, Color.rgb(0xD2, 0xEC, 0xFA), Shader.TileMode.CLAMP);
        grabShader = new LinearGradient(0, 0, 0, 163f, new int[]{Color.rgb(0x9C, 0xD8, 0xEE), Color.rgb(0x52, 0xAC, 0xD3),
                Color.rgb(0x2B, 0x84, 0xB3)}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
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

    Channel openFolder() {
        return openFolder;
    }

    void openChannel(int index) {
        List<Channel> list = visible();
        if (index < 0 || index >= list.size()) return;
        focus = new Grid.Focus(index / Grid.PER_PAGE, index % Grid.PER_PAGE);
        shownPage = focus.page;
        Channel ch = list.get(index);
        if (ch.isFolder()) enterFolder(ch); else openPreview(ch.app);
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

    /** Shows a Wii settings-style list; OK picks the highlighted item, Back closes it. */
    void showList(String title, List<String> items, ListCallback cb) {
        if (items.isEmpty()) return;
        listTitle = title;
        listItems = new ArrayList<>(items);
        listCallback = cb;
        listSel = 0;
        listTop = 0;
        sfx.select();
        retargetHand(false);
        invalidate();
    }

    boolean listOpen() {
        return listItems != null;
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
        listItems = null;
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
        previewBarShader = new LinearGradient(0, 767f, 0, 1062f, Color.rgb(0xF1, 0xF0, 0xEB), Color.rgb(0xD6, 0xD4, 0xCC), Shader.TileMode.CLAMP);
        buildSprites();
    }

    // ---------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas c) {
        c.save();
        c.scale(getWidth() / VW, getHeight() / VH);

        if (listItems != null) {
            drawList(c);
            wii.hand(c, handX, handY, pressing);
            c.restore();
            return;
        }

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
            if (grabbing) {
                // Everything but the grabbed channel fades back, as on the Wii.
                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(140, Color.red(pal.bg), Color.green(pal.bg), Color.blue(pal.bg)));
                c.drawRect(0, 0, VW, BAR_TOP, paint);
            }
            drawArrows(c);
        }
        drawBottomBar(c);
        if (grabbing) drawGrabbed(c);
        if (previewApp != null) drawPreview(c);
        if (!grabbing) wii.hand(c, handX, handY + 3f * (pulse - 0.5f), pressing);
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
            if (grabbing && isFocus) ch = null; // the grabbed channel rides under the fist
            drawTile(c, tile, ch, isFocus && !grabbing);
        }
    }

    private static void tileRect(int slot, float dx, RectF out) {
        int col = slot % Grid.COLS, row = slot / Grid.COLS;
        float l = GRID_LEFT + col * (TILE_W + GAP_X) + dx, t = GRID_TOP + row * (TILE_H + GAP_Y);
        out.set(l, t, l + TILE_W, t + TILE_H);
    }

    private void drawTile(Canvas c, RectF r, Channel ch, boolean focused) {
        if (spriteEmpty == null) return;
        rect.set(r.left - SPRITE_M, r.top - SPRITE_M, r.right + SPRITE_M, r.bottom + SPRITE_M);
        paint.setShader(null);
        paint.setAlpha(255);
        if (focused) {
            paint.setAlpha((int) (120 + 135 * pulse));
            c.drawBitmap(spriteGlow, null, rect, paint);
            paint.setAlpha(255);
        }
        c.drawBitmap(ch == null ? spriteEmpty : spriteFull, null, rect, paint);
        if (ch == null) return;
        if (ch.isFolder()) {
            drawFolderFace(c, r, ch);
        } else {
            Bitmap b = banner(ch.app);
            if (b != null) fitInto(c, b, r.left + 6, r.top + 6, r.right - 6, r.bottom - 6, ch.app);
        }
        rect.set(r.left - SPRITE_M, r.top - SPRITE_M, r.right + SPRITE_M, r.bottom + SPRITE_M);
        c.drawBitmap(focused ? spriteFocusRim : spriteRim, null, rect, paint);
    }

    /** Renders the tile frame layers once, at the real pixel scale. */
    private void buildSprites() {
        if (getWidth() == 0) return;
        float s = getWidth() / VW;
        int w = Math.round((TILE_W + 2 * SPRITE_M) * s), h = Math.round((TILE_H + 2 * SPRITE_M) * s);
        spriteEmpty = sprite(w, h, s, c -> {
            frameBase(c, emptyShader);
            rim(c, pal.tileBorder, 3f);
        });
        spriteFull = sprite(w, h, s, c -> frameBase(c, tileShader));
        spriteRim = sprite(w, h, s, c -> rim(c, pal.tileBorder, 3f));
        spriteFocusRim = sprite(w, h, s, c -> rim(c, pal.focus, 7f));
        spriteGlow = sprite(w, h, s, c -> {
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            for (int i = 3; i >= 1; i--) {
                paint.setStrokeWidth(6f * i + 4f);
                paint.setColor(pal.focus);
                paint.setAlpha(230 / (i + 1));
                c.drawPath(tilePath, paint);
            }
            paint.setAlpha(255);
        });
    }

    private interface SpritePainter {
        void paint(Canvas c);
    }

    private Bitmap sprite(int w, int h, float s, SpritePainter painter) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(s, s);
        c.translate(SPRITE_M, SPRITE_M);
        painter.paint(c);
        b.prepareToDraw();
        return b;
    }

    private void frameBase(Canvas c, Shader fill) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(pal.shadow);
        c.save();
        c.translate(0, 3f);
        c.drawPath(tilePath, paint);
        c.restore();
        paint.setColor(Color.WHITE);
        paint.setShader(fill);
        c.drawPath(tilePath, paint);
        paint.setShader(null);
    }

    private void rim(Canvas c, int color, float width) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(Color.WHITE);
        c.drawPath(tilePath, paint); // white inner rim
        paint.setStrokeWidth(width);
        paint.setColor(color);
        c.drawPath(tilePath, paint);
    }

    /** While dragging, the channel becomes a small glossy blue box carried by the fist. */
    private void drawGrabbed(Canvas c) {
        float w = 308f, h = 163f, l = handX - w / 2, t = handY - h / 2;
        c.save();
        c.translate(l, t);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(70, 0, 0, 0));
        c.save();
        c.translate(8f, 12f);
        c.drawPath(grabPath, paint);
        c.restore();
        paint.setColor(Color.WHITE);
        paint.setShader(grabShader);
        c.drawPath(grabPath, paint);
        paint.setShader(null);
        paint.setColor(Color.argb(70, 255, 255, 255));
        c.drawRoundRect(12f, 8f, w - 12f, h * 0.45f, 22f, 22f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(Color.rgb(0xE9, 0xF7, 0xFD));
        c.drawPath(grabPath, paint);
        c.restore();
        wii.hand(c, handX, handY, true);
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
        if (focus.page > 0) wii.arrow(c, 98f, ARROW_Y, 50f, 86f, -1);
        if (focus.page < pages - 1) wii.arrow(c, 1822f, ARROW_Y, 50f, 86f, 1);
    }

    private void drawBottomBar(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
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
        gear(c, BTN_SETTINGS_X, BTN_Y);
        roundButton(c, BTN_MAIL_X, grid && focus.slot == Grid.BTN_MAIL);
        envelope(c, BTN_MAIL_X, BTN_Y);
        sdCard(c, grid && focus.slot == Grid.BTN_SD);
    }

    // ---------------------------------------------------------------- clock

    private static final float DIGIT_W = 50f, DIGIT_H = 76f, CLOCK_TOP = 790f, SEG_T = 13f;

    /** Hollow LCD digits with a blinking colon centered on screen, then AM/PM, as on the Wii Menu. */
    private void drawClock(Canvas c) {
        Calendar now = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(getContext());
        int hour = now.get(h24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        if (!h24 && hour == 0) hour = 12;
        String hh = String.valueOf(hour), mm = String.format(Locale.ROOT, "%02d", now.get(Calendar.MINUTE));

        if (hh.length() == 2) digit(c, hh.charAt(0), 806f);
        digit(c, hh.charAt(hh.length() - 1), 885f);
        digit(c, mm.charAt(0), 985f);
        digit(c, mm.charAt(1), 1047f);
        if (now.get(Calendar.SECOND) % 2 == 0) {
            for (float cy : new float[]{CLOCK_TOP + 21f, CLOCK_TOP + 55f}) {
                rect.set(VW / 2 + 1f - 7f, cy - 7f, VW / 2 + 1f + 7f, cy + 7f);
                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(pal.clockInner);
                c.drawRect(rect, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3.5f);
                paint.setColor(pal.clock);
                c.drawRect(rect, paint);
            }
        }
        if (!h24) {
            text.setTextAlign(Paint.Align.LEFT);
            text.setTypeface(bold);
            text.setTextSize(44f);
            text.setColor(pal.clock);
            c.drawText(now.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM", 1140f, CLOCK_TOP + DIGIT_H, text);
        }

        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(bold);
        text.setTextSize(80f);
        text.setColor(pal.date);
        String line = openFolder != null ? openFolder.label : dateLine(now);
        labelPaint.set(text);
        c.drawText(TextUtils.ellipsize(line, labelPaint, 900f, TextUtils.TruncateAt.END).toString(), VW / 2, 985f, text);
    }

    /** One digit: its segments merged into a single hollow glyph, pale inside a gray outline. */
    private void digit(Canvas c, char ch, float x) {
        if (ch < '0' || ch > '9') return;
        c.save();
        c.translate(x, CLOCK_TOP);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.clockInner);
        c.drawPath(digitPaths[ch - '0'], paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(4f);
        paint.setColor(pal.clock);
        c.drawPath(digitPaths[ch - '0'], paint);
        c.restore();
    }

    /** Union of hexagonal segments with pointed ends; touching segments merge into one outline. */
    private static Path buildDigit(int mask) {
        float w = DIGIT_W, h = DIGIT_H, mid = h / 2, t = SEG_T / 2f;
        float[][] seg = {{0, 0, w, 0}, {w, 0, w, mid}, {w, mid, w, h}, {0, h, w, h}, {0, mid, 0, h}, {0, 0, 0, mid}, {0, mid, w, mid}};
        Path out = new Path();
        for (int i = 0; i < 7; i++) {
            if ((mask & (1 << i)) == 0) continue;
            float x0 = seg[i][0], y0 = seg[i][1], x1 = seg[i][2], y1 = seg[i][3];
            Path p = new Path();
            if (y0 == y1) {
                p.moveTo(x0, y0);
                p.lineTo(x0 + t, y0 - t);
                p.lineTo(x1 - t, y0 - t);
                p.lineTo(x1, y0);
                p.lineTo(x1 - t, y0 + t);
                p.lineTo(x0 + t, y0 + t);
            } else {
                p.moveTo(x0, y0);
                p.lineTo(x0 + t, y0 + t);
                p.lineTo(x0 + t, y1 - t);
                p.lineTo(x0, y1);
                p.lineTo(x0 - t, y1 - t);
                p.lineTo(x0 - t, y0 + t);
            }
            p.close();
            out.op(p, Path.Op.UNION);
        }
        return out;
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
        paint.setColor(Color.WHITE);
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
        paint.setColor(Color.WHITE);
        paint.setShader(buttonShader);
        c.drawCircle(0, 0, r, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(focused ? 7f : 5f);
        paint.setColor(pal.ring);
        c.drawCircle(0, 0, r - 2f, paint);
        c.restore();
    }

    /** Settings gear in the Wii button's place, embossed in the same gray. */
    private void gear(Canvas c, float cx, float cy) {
        c.save();
        c.translate(cx, cy);
        for (int pass = 0; pass < 2; pass++) {
            c.save();
            if (pass == 0) c.translate(0, 3f);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(pass == 0 ? pal.buttonTop : pal.icon);
            for (int i = 0; i < 8; i++) {
                c.save();
                c.rotate(i * 45f);
                c.drawRoundRect(-10f, -52f, 10f, -28f, 4f, 4f, paint);
                c.restore();
            }
            c.drawCircle(0, 0, 36f, paint);
            c.restore();
        }
        paint.setColor(pal.buttonBottom);
        c.drawCircle(0, 0, 14f, paint);
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

    /** SD card icon beside the settings button; it pulses when GitHub has a newer release. */
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
        paint.setShader(null);
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

    private static final float PREVIEW_PANEL_TOP = 767f;

    private void drawPreview(Canvas c) {
        float t = previewT;
        float l = lerp(previewFrom.left, 0, t), top = lerp(previewFrom.top, 0, t);
        float r = lerp(previewFrom.right, VW, t), b = lerp(previewFrom.bottom, VH, t);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        c.drawRoundRect(l, top, r, b, RADIUS * (1 - t), RADIUS * (1 - t), paint);
        if (t < 0.6f) return;
        int alpha = (int) (255 * (t - 0.6f) / 0.4f);

        if (previewBanner != null) {
            paint.setAlpha(alpha);
            float bw = 1280f, bh = 697f;
            if (!(previewApp.leanback && previewBanner.getWidth() > previewBanner.getHeight())) bw = bh = 440f;
            rect.set((VW - bw) / 2, 384f - bh / 2, (VW + bw) / 2, 384f + bh / 2);
            c.drawBitmap(previewBanner, null, rect, paint);
            paint.setAlpha(255);
        }
        if (neighbour(-1) != null) wii.arrow(c, 103f, 405f, 40f, 72f, -1);
        if (neighbour(1) != null) wii.arrow(c, 1817f, 405f, 40f, 72f, 1);

        // Warm gray panel with rounded corners holding the two buttons.
        paint.setColor(Color.WHITE);
        paint.setAlpha(alpha);
        paint.setShader(previewBarShader);
        c.drawRoundRect(18f, PREVIEW_PANEL_TOP, VW - 18f, 1062f, 46f, 46f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(Color.argb(alpha * 40 / 255, 0, 0, 0));
        for (float y = PREVIEW_PANEL_TOP + 8f; y < 1050f; y += 5f) c.drawLine(60f, y, VW - 60f, y, paint);
        paint.setAlpha(255);
        wii.pill(c, previewPill(false), getContext().getString(R.string.menu), WiiDraw.PILL_WHITE, !previewStartFocused, alpha, 55f);
        wii.pill(c, previewPill(true), getContext().getString(R.string.start), WiiDraw.PILL_WHITE, previewStartFocused, alpha, 55f);
    }

    private static RectF previewPill(boolean start) {
        float cx = start ? 1269f : 643f;
        return new RectF(cx - 267f, 803f, cx + 267f, 941f);
    }

    /** The app channel before or after the previewed one, skipping folders. */
    private AppEntry neighbour(int dir) {
        List<Channel> list = visible();
        int i = -1;
        for (int k = 0; k < list.size(); k++) if (!list.get(k).isFolder() && list.get(k).app == previewApp) i = k;
        if (i < 0) return null;
        for (int k = i + dir; k >= 0 && k < list.size(); k += dir) if (!list.get(k).isFolder()) return list.get(k).app;
        return null;
    }

    private void switchPreview(int dir) {
        AppEntry next = neighbour(dir);
        if (next == null) return;
        List<Channel> list = visible();
        for (int k = 0; k < list.size(); k++) {
            if (list.get(k).app == next) {
                focus = new Grid.Focus(k / Grid.PER_PAGE, k % Grid.PER_PAGE);
                shownPage = focus.page;
            }
        }
        previewApp = next;
        Bitmap big = render(next, 1280, 697);
        previewBanner = big != null ? big : banners.get(next.pkg);
        sfx.tick();
        invalidate();
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
        return new RectF(VW / 2 - 497f, 330f + i * 110f, VW / 2 + 497f, 425f + i * 110f);
    }

    private void drawSdMenu(Canvas c) {
        rect.set(GRID_LEFT, GRID_TOP, VW - GRID_LEFT, 756f);
        c.save();
        c.translate(0, rect.top);
        paint.setColor(Color.WHITE);
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
                latestVersion.isEmpty() ? "—" : latestVersion), VW / 2, 170f, text);
        text.setTypeface(regular);
        text.setTextSize(36f);
        text.setColor(pal.date);
        String info = status != null ? status
                : updateAvailable ? ctx.getString(R.string.sd_new_available)
                : latestVersion.isEmpty() ? ctx.getString(R.string.sd_not_checked) : ctx.getString(R.string.sd_up_to_date);
        c.drawText(info, VW / 2, 225f, text);
        if (updateAvailable && !releaseNotes.isEmpty()) {
            text.setTextSize(30f);
            String[] lines = releaseNotes.replace("\r", "").split("\n");
            int y = 275;
            for (int i = 0; i < lines.length && y <= 300; i++) {
                String ln = lines[i].replaceAll("^[#*\\- ]+", "").trim();
                if (ln.isEmpty()) continue;
                labelPaint.set(text);
                c.drawText(TextUtils.ellipsize(ln, labelPaint, 1400f, TextUtils.TruncateAt.END).toString(), VW / 2, y, text);
                y += 40;
            }
        }
        List<Integer> items = sdItems();
        for (int i = 0; i < items.size(); i++) {
            RectF r = sdItemRect(i);
            wii.pill(c, r, sdLabel(items.get(i)), WiiDraw.PILL_WHITE, sdFocus == i, 255, 48f);
            if (sdFocus == i) wii.brackets(c, r);
        }
    }

    // ---------------------------------------------------------------- Wii list overlay

    private static RectF listItemRect(int visibleRow) {
        return new RectF(462f, 200f + visibleRow * 128f, 1455f, 303f + visibleRow * 128f);
    }

    private static final RectF LIST_BACK = new RectF(337f, 886f, 941f, 1023f);
    private static final RectF LIST_OK = new RectF(984f, 886f, 1588f, 1023f);

    private void drawList(Canvas c) {
        wii.darkStripes(c, 0, VH, VW);
        // Title tab: a small gray tab, then a white one with a rounded top-right corner.
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(0x9A, 0x9A, 0x9C));
        c.drawRoundRect(316f, 92f, 420f, 170f, 14f, 14f, paint);
        path.reset();
        path.moveTo(385f, 154f);
        path.lineTo(385f, 92f);
        path.quadTo(385f, 77f, 400f, 77f);
        path.lineTo(1290f, 77f);
        path.quadTo(1335f, 77f, 1335f, 122f);
        path.lineTo(1335f, 154f);
        path.close();
        paint.setColor(Color.WHITE);
        c.drawPath(path, paint);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(regular);
        text.setTextSize(48f);
        text.setColor(WiiDraw.TEXT);
        labelPaint.set(text);
        c.drawText(TextUtils.ellipsize(listTitle, labelPaint, 900f, TextUtils.TruncateAt.END).toString(), 407f, 133f, text);
        paint.setColor(Color.WHITE);
        c.drawRect(0, 152f, VW, 156f, paint);
        c.drawRect(0, 867f, VW, 871f, paint);

        for (int row = 0; row < LIST_VISIBLE; row++) {
            int i = listTop + row;
            if (i >= listItems.size()) break;
            RectF r = listItemRect(row);
            labelPaint.setTextSize(52f);
            String label = TextUtils.ellipsize(listItems.get(i), labelPaint, 900f, TextUtils.TruncateAt.END).toString();
            wii.pill(c, r, label, WiiDraw.PILL_WHITE, false, 255, 52f);
            if (i == listSel) wii.brackets(c, r);
        }
        if (listTop > 0) wii.arrow(c, 1560f, 274f, 80f, 56f, -2);
        if (listTop + LIST_VISIBLE < listItems.size()) wii.arrow(c, 1560f, 640f, 80f, 56f, 2);

        wii.pill(c, LIST_BACK, getContext().getString(R.string.back), WiiDraw.PILL_WHITE, false, 255, 55f);
        wii.pill(c, LIST_OK, getContext().getString(R.string.ok), WiiDraw.PILL_LAVENDER, false, 255, 55f);
    }

    private void listMove(int delta) {
        int next = Math.max(0, Math.min(listItems.size() - 1, listSel + delta));
        if (next == listSel) return;
        listSel = next;
        if (listSel < listTop) listTop = listSel;
        if (listSel >= listTop + LIST_VISIBLE) listTop = listSel - LIST_VISIBLE + 1;
        sfx.tick();
        retargetHand(true);
    }

    private void closeList() {
        listItems = null;
        listCallback = null;
        retargetHand(false);
        invalidate();
    }

    // ---------------------------------------------------------------- hand placement

    /** Where the hand's hotspot should rest for the current focus. */
    private float[] handTarget() {
        if (listItems != null) {
            RectF r = listItemRect(listSel - listTop);
            return new float[]{r.right - 120f, r.centerY() - 6f};
        }
        if (previewApp != null) {
            RectF r = previewPill(previewStartFocused);
            return new float[]{r.centerX() + 160f, r.centerY() - 12f};
        }
        if (sdOpen) {
            RectF r = sdItemRect(sdFocus);
            return new float[]{r.right - 120f, r.centerY() - 8f};
        }
        switch (focus.slot) {
            case Grid.BTN_SETTINGS: return new float[]{BTN_SETTINGS_X + 40f, BTN_Y + 4f};
            case Grid.BTN_MAIL: return new float[]{BTN_MAIL_X + 40f, BTN_Y + 4f};
            case Grid.BTN_SD: return new float[]{SD_X + 22f, SD_Y + 10f};
            default:
                tileRect(focus.slot, 0, rect);
                return grabbing ? new float[]{rect.centerX(), rect.centerY()} : new float[]{rect.centerX() + 60f, rect.centerY() - 20f};
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
        int w = Math.max(1, Math.round((TILE_W - 12) * s)), h = Math.max(1, Math.round((TILE_H - 12) * s));
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
                // Same soft corners as the frame, baked in once instead of clipping on every frame.
                Path clip = new Path();
                WiiDraw.squircle(clip, 0, 0, w, h, h * 0.14f);
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
        if (listItems != null) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) listMove(-1);
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) listMove(1);
            else if (keyCode == KeyEvent.KEYCODE_BACK) {
                sfx.select();
                closeList();
            }
            return true;
        }
        if (grabbing) {
            Grid.Dir d = dir(keyCode);
            if (d != null) drag(d);
            else if (keyCode == KeyEvent.KEYCODE_BACK) drop();
            return true;
        }
        if (previewApp != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (previewStartFocused) {
                        previewStartFocused = false;
                        sfx.tick();
                        retargetHand(true);
                    } else {
                        switchPreview(-1);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (!previewStartFocused) {
                        previewStartFocused = true;
                        sfx.tick();
                        retargetHand(true);
                    } else {
                        switchPreview(1);
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
        if (isSelect(keyCode) && listItems == null && previewApp == null && !sdOpen && !grabbing) {
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
        if (listItems != null) {
            ListCallback cb = listCallback;
            int picked = listSel;
            sfx.select();
            closeList();
            if (cb != null) cb.onPick(picked);
            return true;
        }
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
