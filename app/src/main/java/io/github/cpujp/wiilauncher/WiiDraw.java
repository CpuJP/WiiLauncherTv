package io.github.cpujp.wiilauncher;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.SparseArray;

/** Wii Menu drawing pieces shared by the menu, the list overlay and the text prompt. */
final class WiiDraw {
    static final int PILL_WHITE = 0, PILL_LAVENDER = 1;

    static final int PILL_BORDER = Color.rgb(0x4E, 0xB9, 0xEA);
    static final int TEXT = Color.rgb(0x3A, 0x3A, 0x3C);
    static final int ORANGE = Color.rgb(0xF2, 0x5C, 0x19);

    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final SparseArray<Shader> pillShaders = new SparseArray<>();
    private final SparseArray<Shader> arrowShaders = new SparseArray<>();
    private final Path handPoint = buildPointingHand(), handFist = buildFist();
    private final Shader handShader = new LinearGradient(0, 0, 0, 80, new int[]{Color.WHITE, Color.WHITE,
            Color.rgb(0x9F, 0xDA, 0xF7), Color.rgb(0x3B, 0x9E, 0xE2)}, new float[]{0f, 0.56f, 0.72f, 1f}, Shader.TileMode.CLAMP);
    private final Shader fistShader = new LinearGradient(0, 0, 0, 50, Color.WHITE, Color.rgb(0xE6, 0xF4, 0xFC), Shader.TileMode.CLAMP);

    /** Rounded rectangle with soft, continuous corners like the Wii's channel frames. */
    static void squircle(Path p, float l, float t, float r, float b, float rad) {
        float k = 0.72f * rad; // longer handles than a circular arc: rounder, "half-rounded" corners
        p.reset();
        p.moveTo(l + rad, t);
        p.lineTo(r - rad, t);
        p.cubicTo(r - rad + k, t, r, t + rad - k, r, t + rad);
        p.lineTo(r, b - rad);
        p.cubicTo(r, b - rad + k, r - rad + k, b, r - rad, b);
        p.lineTo(l + rad, b);
        p.cubicTo(l + rad - k, b, l, b - rad + k, l, b - rad);
        p.lineTo(l, t + rad);
        p.cubicTo(l, t + rad - k, l + rad - k, t, l + rad, t);
        p.close();
    }

    /** Black screen with fine dark scan lines, as behind Wii settings lists. */
    void darkStripes(Canvas c, float top, float bottom, float width) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(0x05, 0x05, 0x06));
        c.drawRect(0, top, width, bottom, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(Color.rgb(0x1B, 0x1B, 0x1D));
        for (float y = top + 2f; y < bottom; y += 6f) c.drawLine(0, y, width, y, paint);
    }

    /** Glossy Wii pill button: white (or lavender for OK) with a light blue rim. */
    void pill(Canvas c, RectF r, String label, int style, boolean focused, int alpha, float textSize) {
        float h = r.height(), rad = Math.min(h / 2f, 44f);
        if (focused) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            for (int i = 3; i >= 1; i--) {
                float d = 5f * i;
                paint.setStrokeWidth(6f);
                paint.setColor(Color.argb(alpha * 70 / 255 / i, 0x4E, 0xC4, 0xF4));
                c.drawRoundRect(r.left - d, r.top - d, r.right + d, r.bottom + d, rad + d, rad + d, paint);
            }
        }
        c.save();
        c.translate(0, r.top);
        paint.setColor(Color.WHITE);
        paint.setAlpha(alpha);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(pillShader(h, style));
        c.drawRoundRect(r.left, 0, r.right, h, rad, rad, paint);
        paint.setShader(null);
        // gloss: a pale band across the upper half
        paint.setColor(Color.argb(alpha * 150 / 255, 255, 255, 255));
        c.drawRoundRect(r.left + 10f, 5f, r.right - 10f, h * 0.46f, rad * 0.8f, rad * 0.8f, paint);
        c.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(focused ? 6f : 4.5f);
        paint.setColor(style == PILL_LAVENDER ? Color.rgb(0x8C, 0x8F, 0xD9) : PILL_BORDER);
        paint.setAlpha(alpha);
        c.drawRoundRect(r.left + 2f, r.top + 2f, r.right - 2f, r.bottom - 2f, rad, rad, paint);
        paint.setAlpha(255);
        text.setTypeface(regular);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(textSize);
        text.setColor(TEXT);
        text.setAlpha(alpha);
        c.drawText(label, r.centerX(), r.centerY() + textSize * 0.36f, text);
        text.setAlpha(255);
    }

    private Shader pillShader(float h, int style) {
        int key = Math.round(h) * 2 + style;
        Shader s = pillShaders.get(key);
        if (s == null) {
            int[] colors = style == PILL_LAVENDER
                    ? new int[]{Color.rgb(0xDA, 0xDB, 0xFB), Color.rgb(0xC3, 0xC5, 0xF3), Color.rgb(0xA4, 0xA6, 0xE6)}
                    : new int[]{Color.WHITE, Color.rgb(0xF1, 0xF1, 0xF2), Color.rgb(0xD6, 0xD6, 0xD9)};
            s = new LinearGradient(0, 0, 0, h, colors, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            pillShaders.put(key, s);
        }
        return s;
    }

    /** Orange corner brackets around the selected list item. */
    void brackets(Canvas c, RectF r) {
        float o = 12f, arm = 28f;
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(8f);
        paint.setStrokeCap(Paint.Cap.SQUARE);
        paint.setColor(ORANGE);
        float l = r.left - o, t = r.top - o, rr = r.right + o, b = r.bottom + o;
        c.drawLine(l, t, l + arm, t, paint);
        c.drawLine(l, t, l, t + arm, paint);
        c.drawLine(rr, t, rr - arm, t, paint);
        c.drawLine(rr, t, rr, t + arm, paint);
        c.drawLine(l, b, l + arm, b, paint);
        c.drawLine(l, b, l, b - arm, paint);
        c.drawLine(rr, b, rr - arm, b, paint);
        c.drawLine(rr, b, rr, b - arm, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** Glossy blue triangle. dir: -1 left, 1 right, -2 up, 2 down. */
    void arrow(Canvas c, float cx, float cy, float w, float h, int dir) {
        path.reset();
        if (dir == 1 || dir == -1) {
            path.moveTo(cx + dir * w / 2, cy);
            path.lineTo(cx - dir * w / 2, cy - h / 2);
            path.lineTo(cx - dir * w / 2, cy + h / 2);
        } else {
            int s = dir / 2;
            path.moveTo(cx, cy + s * h / 2);
            path.lineTo(cx - w / 2, cy - s * h / 2);
            path.lineTo(cx + w / 2, cy - s * h / 2);
        }
        path.close();
        float top = (dir == 2) ? cy - h / 2 : cy - h / 2;
        c.save();
        c.translate(0, top);
        path.offset(0, -top);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(arrowShader(h));
        c.drawPath(path, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(3f);
        paint.setColor(Color.rgb(0x1D, 0x74, 0xBE));
        c.drawPath(path, paint);
        c.restore();
    }

    private Shader arrowShader(float h) {
        int key = Math.round(h);
        Shader s = arrowShaders.get(key);
        if (s == null) {
            s = new LinearGradient(0, 0, 0, h, new int[]{Color.rgb(0xD4, 0xF0, 0xFD), Color.rgb(0x77, 0xC6, 0xF1),
                    Color.rgb(0x2C, 0x8D, 0xD8)}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            arrowShaders.put(key, s);
        }
        return s;
    }

    // ---------------------------------------------------------------- Wii Remote hand

    /** Index finger up; fingertip (hotspot) at (19, 0). Units are about 1/1.7 of screen pixels. */
    private static Path buildPointingHand() {
        Path p = new Path();
        p.addRoundRect(12, 0, 26, 46, 7, 7, Path.Direction.CW);
        union(p, 2, 30, 44, 78, 13);
        union(p, 25, 26, 34, 46, 4.5f);
        union(p, 33, 30, 41, 50, 4f);
        union(p, 39, 35, 46, 55, 3.5f);
        Path thumb = new Path();
        thumb.addRoundRect(-5, 42, 12, 56, 7, 7, Path.Direction.CW);
        Matrix m = new Matrix();
        m.setRotate(-28, 8, 49);
        thumb.transform(m);
        p.op(thumb, Path.Op.UNION);
        return p;
    }

    /** Closed fist seen from the back: four knuckles on top, thumb tucked at the lower left. */
    private static Path buildFist() {
        Path p = new Path();
        p.addRoundRect(2, 8, 46, 50, 12, 12, Path.Direction.CW);
        for (int i = 0; i < 4; i++) union(p, 4 + i * 10.5f, 2, 15 + i * 10.5f, 20, 5.5f);
        union(p, -2, 26, 14, 44, 7);
        return p;
    }

    private static void union(Path p, float l, float t, float r, float b, float rad) {
        Path q = new Path();
        q.addRoundRect(l, t, r, b, rad, rad, Path.Direction.CW);
        p.op(q, Path.Op.UNION);
    }

    private static final float HAND_SCALE = 1.7f;
    private android.graphics.Bitmap pointSprite, fistSprite;
    private float spriteScale;

    /** Draws the hand with its hotspot at (x, y): the fingertip when pointing, the center as a fist. */
    void hand(Canvas c, float x, float y, boolean fist) {
        // Cached bitmaps at the canvas' real pixel scale; the vector hand is only drawn into them.
        float px = c.getMatrix().mapRadius(1f);
        if (pointSprite == null || Math.abs(px - spriteScale) > 0.01f) {
            spriteScale = px;
            pointSprite = handSprite(false, px);
            fistSprite = handSprite(true, px);
        }
        android.graphics.Bitmap b = fist ? fistSprite : pointSprite;
        float ox = fist ? 24f : 19f, oy = fist ? 26f : 0f, pad = 8f;
        rect.set(x - (ox + pad) * HAND_SCALE, y - (oy + pad) * HAND_SCALE,
                x - (ox + pad) * HAND_SCALE + b.getWidth() / px, y - (oy + pad) * HAND_SCALE + b.getHeight() / px);
        paint.setShader(null);
        paint.setAlpha(255);
        c.drawBitmap(b, null, rect, paint);
    }

    private android.graphics.Bitmap handSprite(boolean fist, float px) {
        float pad = 8f, w = 80f, h = 96f;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(
                Math.round((w + pad * 2) * HAND_SCALE * px), Math.round((h + pad * 2) * HAND_SCALE * px), android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(px, px);
        float ox = fist ? 24f : 19f, oy = fist ? 26f : 0f;
        drawHandVector(c, (ox + pad) * HAND_SCALE, (oy + pad) * HAND_SCALE, fist);
        return b;
    }

    private void drawHandVector(Canvas c, float x, float y, boolean fist) {
        float scale = HAND_SCALE;
        c.save();
        c.translate(x, y);
        c.scale(scale, scale);
        if (fist) c.translate(-24f, -26f); else c.translate(-19f, 0f);
        Path hand = fist ? handFist : handPoint;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(Color.argb(80, 0, 0, 0));
        c.save();
        c.translate(4f, 5f);
        c.drawPath(hand, paint);
        c.restore();
        paint.setColor(Color.WHITE);
        paint.setShader(fist ? fistShader : handShader);
        c.drawPath(hand, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(3.2f);
        paint.setColor(Color.rgb(0x16, 0x16, 0x18));
        c.drawPath(hand, paint);
        paint.setStrokeWidth(2.2f);
        if (fist) {
            for (int i = 1; i < 4; i++) c.drawLine(4 + i * 10.5f, 8f, 4 + i * 10.5f, 17f, paint);
            c.drawLine(4f, 30f, 22f, 30f, paint); // tucked thumb
        } else {
            c.drawLine(26f, 40f, 26f, 44f, paint); // knuckle creases
            c.drawLine(34f, 44f, 34f, 48f, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(bold);
        text.setTextSize(fist ? 20f : 22f);
        text.setColor(fist ? Color.rgb(0x2B, 0x8F, 0xDF) : Color.WHITE);
        c.drawText("1", fist ? 32f : 26f, fist ? 46f : 73f, text);
        c.restore();
    }

    RectF tmp() {
        return rect;
    }
}
