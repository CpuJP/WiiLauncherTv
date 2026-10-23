package io.github.cpujp.wiilauncher;

import java.util.List;

/** One tile on the menu: either an app or a folder of apps. */
final class Channel {
    final String key;
    final String label;
    final AppEntry app;            // null for folders
    final Folders.Folder folder;   // null for apps
    final List<AppEntry> folderApps;

    private Channel(String key, String label, AppEntry app, Folders.Folder folder, List<AppEntry> folderApps) {
        this.key = key;
        this.label = label;
        this.app = app;
        this.folder = folder;
        this.folderApps = folderApps;
    }

    static Channel of(AppEntry app) {
        return new Channel(app.pkg, app.label, app, null, null);
    }

    static Channel of(Folders.Folder folder, List<AppEntry> apps) {
        return new Channel(folder.key(), folder.name, null, folder, apps);
    }

    boolean isFolder() {
        return folder != null;
    }
}
