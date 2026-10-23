package io.github.cpujp.wiilauncher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;

/** One launchable app shown as a channel. */
final class AppEntry {
    final String pkg;
    final String label;
    final ActivityInfo activity;
    final boolean leanback;

    AppEntry(String pkg, String label, ActivityInfo activity, boolean leanback) {
        this.pkg = pkg;
        this.label = label;
        this.activity = activity;
        this.leanback = leanback;
    }

    Intent launchIntent() {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(activity.packageName, activity.name));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }
}
