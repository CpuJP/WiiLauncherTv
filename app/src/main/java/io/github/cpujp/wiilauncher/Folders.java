package io.github.cpujp.wiilauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** User-made folders of apps, stored as JSON. An app belongs to at most one folder. */
public final class Folders {
    /** Prefix of a folder's key in the top-level channel order. */
    public static final String KEY_PREFIX = "folder:";

    public static final class Folder {
        public final String id;
        public String name;
        public final List<String> pkgs = new ArrayList<>();

        Folder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String key() {
            return KEY_PREFIX + id;
        }
    }

    private final Map<String, Folder> byId = new LinkedHashMap<>();

    public static Folders parse(String json) {
        Folders f = new Folders();
        if (json == null || json.isEmpty()) return f;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Folder folder = new Folder(o.getString("id"), o.optString("name", ""));
                JSONArray pkgs = o.optJSONArray("apps");
                if (pkgs != null) for (int j = 0; j < pkgs.length(); j++) folder.pkgs.add(pkgs.getString(j));
                f.byId.put(folder.id, folder);
            }
        } catch (JSONException e) {
            // corrupt data: start with no folders rather than crash the home screen
            f.byId.clear();
        }
        return f;
    }

    public String toJson() {
        JSONArray arr = new JSONArray();
        try {
            for (Folder f : byId.values()) {
                JSONObject o = new JSONObject();
                o.put("id", f.id);
                o.put("name", f.name);
                o.put("apps", new JSONArray(f.pkgs));
                arr.put(o);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return arr.toString();
    }

    public Collection<Folder> all() {
        return byId.values();
    }

    public Folder get(String id) {
        return byId.get(id);
    }

    public static String idFromKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX) ? key.substring(KEY_PREFIX.length()) : null;
    }

    public Folder create(String name) {
        int n = 1;
        while (byId.containsKey("f" + n)) n++;
        Folder f = new Folder("f" + n, name);
        byId.put(f.id, f);
        return f;
    }

    /** Moves {@code pkg} into folder {@code id}, taking it out of any other folder. */
    public void add(String id, String pkg) {
        Folder target = byId.get(id);
        if (target == null) return;
        remove(pkg);
        target.pkgs.add(pkg);
    }

    /** Takes {@code pkg} out of its folder; returns the folder id it was in, or null. */
    public String remove(String pkg) {
        for (Folder f : byId.values()) if (f.pkgs.remove(pkg)) return f.id;
        return null;
    }

    public String folderOf(String pkg) {
        for (Folder f : byId.values()) if (f.pkgs.contains(pkg)) return f.id;
        return null;
    }

    /** Deletes the folder and returns its apps so the caller can put them back on the menu. */
    public List<String> delete(String id) {
        Folder f = byId.remove(id);
        return f == null ? new ArrayList<>() : f.pkgs;
    }

    /** Drops uninstalled apps and folders left empty. */
    public void prune(Collection<String> installed) {
        for (Iterator<Folder> it = byId.values().iterator(); it.hasNext(); ) {
            Folder f = it.next();
            f.pkgs.retainAll(installed);
            if (f.pkgs.isEmpty()) it.remove();
        }
    }
}
