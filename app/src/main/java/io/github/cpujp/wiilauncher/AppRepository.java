package io.github.cpujp.wiilauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lists launchable apps (TV apps first) and persists the user's channel order. */
final class AppRepository {
    private static final String PREFS = "channels";
    private static final String KEY_ORDER = "order";

    private final Context context;
    private final SharedPreferences prefs;

    AppRepository(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<AppEntry> load() {
        PackageManager pm = context.getPackageManager();
        Map<String, AppEntry> byPkg = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, byPkg);
        collect(pm, Intent.CATEGORY_LAUNCHER, false, byPkg); // phone-only apps still deserve a channel
        byPkg.remove(context.getPackageName());

        List<AppEntry> sorted = new ArrayList<>(byPkg.values());
        Collator c = Collator.getInstance();
        sorted.sort((a, b) -> c.compare(a.label, b.label));
        List<String> names = new ArrayList<>();
        for (AppEntry e : sorted) names.add(e.pkg);

        List<String> order = AppOrder.merge(AppOrder.split(prefs.getString(KEY_ORDER, "")), names);
        List<AppEntry> out = new ArrayList<>();
        for (String p : order) out.add(byPkg.get(p));
        return out;
    }

    void saveOrder(List<AppEntry> apps) {
        List<String> order = new ArrayList<>();
        for (AppEntry e : apps) order.add(e.pkg);
        prefs.edit().putString(KEY_ORDER, AppOrder.join(order)).apply();
    }

    private static void collect(PackageManager pm, String category, boolean leanback, Map<String, AppEntry> out) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (out.containsKey(pkg)) continue;
            out.put(pkg, new AppEntry(pkg, String.valueOf(ri.loadLabel(pm)), ri.activityInfo, leanback));
        }
    }
}
