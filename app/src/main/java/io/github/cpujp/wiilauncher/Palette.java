package io.github.cpujp.wiilauncher;

import android.graphics.Color;

/** Menu colors for the light and dark themes. */
final class Palette {
    final int bg, stripe, tileTop, tileBottom, tileEmpty, tileBorder, barTop, barBottom, line, blue;
    final int clock, date, buttonFace, buttonRing, icon, labelFace, labelText, previewBg, shadow;

    private Palette(int... c) {
        bg = c[0]; stripe = c[1]; tileTop = c[2]; tileBottom = c[3]; tileEmpty = c[4]; tileBorder = c[5];
        barTop = c[6]; barBottom = c[7]; line = c[8]; blue = c[9]; clock = c[10]; date = c[11];
        buttonFace = c[12]; buttonRing = c[13]; icon = c[14]; labelFace = c[15]; labelText = c[16];
        previewBg = c[17]; shadow = c[18];
    }

    static final Palette LIGHT = new Palette(
            Color.rgb(0xF3, 0xF3, 0xF4), Color.rgb(0xE9, 0xE9, 0xEB),
            Color.WHITE, Color.rgb(0xE7, 0xE8, 0xEA), Color.rgb(0xE2, 0xE3, 0xE6), Color.rgb(0xC9, 0xCB, 0xD0),
            Color.rgb(0xFA, 0xFA, 0xFB), Color.rgb(0xDA, 0xDC, 0xE0), Color.rgb(0x5C, 0xC6, 0xEE), Color.rgb(0x2F, 0xB4, 0xEB),
            Color.rgb(0x9A, 0x9D, 0xA4), Color.rgb(0x58, 0x5C, 0x63),
            Color.WHITE, Color.rgb(0xB8, 0xBB, 0xC2), Color.rgb(0x8E, 0x92, 0x9A), Color.WHITE, Color.rgb(0x4A, 0x4F, 0x58),
            Color.rgb(0xEC, 0xED, 0xEF), Color.argb(46, 0, 0, 0));

    static final Palette DARK = new Palette(
            Color.rgb(0x15, 0x16, 0x19), Color.rgb(0x1B, 0x1C, 0x20),
            Color.rgb(0x31, 0x33, 0x39), Color.rgb(0x25, 0x27, 0x2C), Color.rgb(0x22, 0x24, 0x29), Color.rgb(0x3A, 0x3D, 0x45),
            Color.rgb(0x2E, 0x30, 0x35), Color.rgb(0x1A, 0x1B, 0x1F), Color.rgb(0x34, 0xBE, 0xED), Color.rgb(0x34, 0xBE, 0xED),
            Color.rgb(0x8C, 0x90, 0x98), Color.rgb(0xC6, 0xC9, 0xCF),
            Color.rgb(0x2E, 0x30, 0x36), Color.rgb(0x4B, 0x4F, 0x58), Color.rgb(0xA9, 0xAD, 0xB5), Color.rgb(0x2E, 0x30, 0x36), Color.rgb(0xE6, 0xE8, 0xEB),
            Color.rgb(0x1E, 0x1F, 0x23), Color.argb(90, 0, 0, 0));
}
