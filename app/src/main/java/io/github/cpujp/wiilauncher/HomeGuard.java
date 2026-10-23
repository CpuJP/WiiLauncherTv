package io.github.cpujp.wiilauncher;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Re-enables {@link HomeKeyService} when Android drops it (a force-stop removes it from the
 * enabled list). Needs WRITE_SECURE_SETTINGS, granted once over ADB by tools/setup-tv.sh.
 */
public final class HomeGuard extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        ensureEnabled(context);
    }

    static void ensureEnabled(Context context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
        ContentResolver cr = context.getContentResolver();
        String self = new ComponentName(context, HomeKeyService.class).flattenToString();
        String current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String updated = withService(current, self);
        try {
            if (!updated.equals(current)) Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException ignored) {
            // permission revoked between the check and the write
        }
    }

    /** Adds {@code service} to a colon-separated list, keeping other services. */
    static String withService(String list, String service) {
        if (list == null || list.isEmpty() || list.equals("null")) return service;
        for (String s : list.split(":")) if (s.equalsIgnoreCase(service)) return list;
        return list + ":" + service;
    }
}
