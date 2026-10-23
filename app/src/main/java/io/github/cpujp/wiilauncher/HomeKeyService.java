package io.github.cpujp.wiilauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Google TV keeps its own launcher as HOME regardless of the default-home setting,
 * so the Home key is intercepted here and routed to this launcher instead.
 */
public final class HomeKeyService extends AccessibilityService {
    private static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;

    @Override protected void onServiceConnected() {
        // The system binds this service shortly after boot, when the stock home is already up.
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) openMenu();
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) openMenu();
        return true;
    }

    private void openMenu() {
        startActivity(new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
