package io.github.cpujp.wiilauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The parts of a GitHub "latest release" response the updater needs. */
public final class ReleaseInfo {
    public final String tag;
    public final String apkUrl;
    public final String notes;

    ReleaseInfo(String tag, String apkUrl, String notes) {
        this.tag = tag;
        this.apkUrl = apkUrl;
        this.notes = notes;
    }

    /** Returns null when the release has no APK asset. */
    public static ReleaseInfo parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null;
        JSONArray assets = o.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String url = a.optString("browser_download_url", "");
            // Only accept downloads served by GitHub for this project.
            if (a.optString("name").endsWith(".apk") && url.startsWith(Updater.RELEASE_DOWNLOAD_PREFIX)) {
                return new ReleaseInfo(o.getString("tag_name"), url, o.optString("body", ""));
            }
        }
        return null;
    }
}
