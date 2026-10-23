package io.github.cpujp.wiilauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Google TV keeps its own launcher as HOME regardless of the default-home setting.
 * This service routes the Home key here and replaces the stock home whenever it shows up anyway
 * (boot, Back out of an app, vendor shortcuts). It only receives events from the stock launcher.
 */
public final class HomeKeyService extends AccessibilityService {
    static final String STOCK_HOME_PKG = "com.google.android.apps.tv.launcherx";
    private static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;
    private static final long DEBOUNCE_MS = 1000L;
    private long lastOpen;

    @Override protected void onServiceConnected() {
        // The system binds this service shortly after boot, when the stock home is already up.
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) openMenu();
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) openMenu();
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null
                && STOCK_HOME_PKG.contentEquals(event.getPackageName())
                && isStockHome(event.getClassName())) {
            openMenu();
        }
    }

    /** Only the home screens: other stock-launcher screens (profiles, app lists) stay usable. */
    static boolean isStockHome(CharSequence cls) {
        if (cls == null) return false;
        String c = cls.toString();
        return c.endsWith(".home.HomeActivity") || c.endsWith(".home.VanillaModeHomeActivity");
    }

    @Override public void onInterrupt() {}

    private void openMenu() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastOpen < DEBOUNCE_MS) return;
        lastOpen = now;
        startActivity(new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }
}
