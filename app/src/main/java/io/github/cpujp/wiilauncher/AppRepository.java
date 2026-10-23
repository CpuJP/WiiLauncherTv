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

/** Lists launchable apps, groups them into the user's folders and persists the channel order. */
final class AppRepository {
    private static final String PREFS = "channels";
    private static final String KEY_ORDER = "order";
    private static final String KEY_FOLDERS = "folders";

    private final Context context;
    private final SharedPreferences prefs;
    private Folders folders = new Folders();

    AppRepository(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    Folders folders() {
        return folders;
    }

    /** Top-level channels in the saved order; new apps go at the end, sorted by name. */
    List<Channel> load() {
        PackageManager pm = context.getPackageManager();
        Map<String, AppEntry> byPkg = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, byPkg);
        collect(pm, Intent.CATEGORY_LAUNCHER, false, byPkg); // phone-only apps still deserve a channel
        byPkg.remove(context.getPackageName());

        folders = Folders.parse(prefs.getString(KEY_FOLDERS, ""));
        folders.prune(byPkg.keySet());

        List<AppEntry> sorted = new ArrayList<>(byPkg.values());
        Collator c = Collator.getInstance();
        sorted.sort((a, b) -> c.compare(a.label, b.label));
        List<String> keys = new ArrayList<>();
        for (AppEntry e : sorted) if (folders.folderOf(e.pkg) == null) keys.add(e.pkg);
        for (Folders.Folder f : folders.all()) keys.add(f.key());

        List<String> order = AppOrder.merge(AppOrder.split(prefs.getString(KEY_ORDER, "")), keys);
        List<Channel> out = new ArrayList<>();
        for (String k : order) {
            Folders.Folder f = folders.get(Folders.idFromKey(k));
            if (f != null) {
                List<AppEntry> apps = new ArrayList<>();
                for (String p : f.pkgs) apps.add(byPkg.get(p));
                out.add(Channel.of(f, apps));
            } else {
                out.add(Channel.of(byPkg.get(k)));
            }
        }
        return out;
    }

    void saveOrder(List<String> keys) {
        prefs.edit().putString(KEY_ORDER, AppOrder.join(keys)).apply();
    }

    void saveFolders() {
        prefs.edit().putString(KEY_FOLDERS, folders.toJson()).apply();
    }

    static List<String> keys(List<Channel> channels) {
        List<String> out = new ArrayList<>();
        for (Channel ch : channels) out.add(ch.key);
        return out;
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
