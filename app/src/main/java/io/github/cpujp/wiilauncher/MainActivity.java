package io.github.cpujp.wiilauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** HOME activity: hosts the menu view and routes its actions to the system. */
public final class MainActivity extends Activity implements WiiMenuView.Callbacks, Updater.Listener {
    private static final String PREFS = "ui";
    private static final String KEY_THEME = "theme"; // 0 = follow system, 1 = light (default, as on the Wii), 2 = dark

    // TCL's own input panel, used only if the TV reports no inputs through TvInputManager.
    private static final ComponentName TCL_INPUTS = new ComponentName("com.tcl.suspension", "com.tcl.suspension.DismissService");
    private static final String TCL_INPUTS_ACTION = "com.tcl.quickpanel.show.window";

    private AppRepository repo;
    private WiiMenuView menu;
    private WiiPrompt prompt;
    private Sfx sfx;
    private Updater updater;
    private SharedPreferences prefs;
    private ReleaseInfo latest;

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            reload();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        repo = new AppRepository(this);
        sfx = new Sfx();
        updater = new Updater(this, this);
        menu = new WiiMenuView(this, this, sfx);
        prompt = new WiiPrompt(this);
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.addView(menu);
        root.addView(prompt);
        setContentView(root);
        menu.requestFocus();
        applyTheme();
        menu.setUpdateInfo(versionName(), "", false, "");
        reload();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
    }

    @Override protected void onResume() {
        super.onResume();
        HomeGuard.ensureEnabled(this);
        updater.checkIfDue(versionName());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (prompt.isShowing()) prompt.cancel();
        menu.resetToMenu(); // Home pressed while already home
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyTheme(); // the system's dark mode may have changed
    }

    @Override protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        updater.shutdown();
        sfx.release();
        super.onDestroy();
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && prompt.isShowing()) {
            prompt.cancel();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override public boolean onSearchRequested() {
        // Search / mic key on the remote opens the system assistant, as on the stock home.
        return start(new Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void reload() {
        menu.setChannels(repo.load());
    }

    // ------------------------------------------------------------ menu callbacks

    @Override public void onLaunch(AppEntry app) {
        start(app.launchIntent());
    }

    @Override public void onSettings() {
        start(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override public void onInputs() {
        List<InputSources.Source> sources;
        try {
            sources = InputSources.list(this);
        } catch (RuntimeException e) {
            sources = new ArrayList<>();
        }
        if (sources.isEmpty()) {
            openVendorInputs();
            return;
        }
        List<String> labels = new ArrayList<>();
        for (InputSources.Source s : sources) labels.add(s.label);
        List<InputSources.Source> finalSources = sources;
        menu.showList(getString(R.string.inputs_title), labels, i -> start(InputSources.intentFor(finalSources.get(i))));
    }

    private void openVendorInputs() {
        try {
            if (startService(new Intent(TCL_INPUTS_ACTION).setComponent(TCL_INPUTS)) != null) return;
        } catch (SecurityException | IllegalStateException ignored) {
            // not a TCL TV: fall back to the system settings
        }
        onSettings();
    }

    @Override public void onCheckUpdates() {
        menu.setStatus(getString(R.string.sd_checking));
        updater.checkNow(versionName());
    }

    @Override public void onInstallUpdate() {
        if (latest != null) updater.install(latest);
    }

    @Override public void onCycleTheme() {
        prefs.edit().putInt(KEY_THEME, (prefs.getInt(KEY_THEME, 1) + 1) % 3).apply();
        applyTheme();
    }

    /** Wii-style options list for a channel, a folder, or an app inside a folder. */
    @Override public void onOptions(Channel ch, int index, Channel inFolder) {
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (ch.isFolder()) {
            add(items, actions, R.string.folder_open, () -> menu.openChannel(index));
            add(items, actions, R.string.move, menu::startGrab);
            folderActions(ch, items, actions);
        } else {
            add(items, actions, R.string.move, menu::startGrab);
            if (inFolder != null) {
                add(items, actions, R.string.folder_take_out, () -> takeOut(ch.app.pkg, inFolder));
                folderActions(inFolder, items, actions);
            } else {
                add(items, actions, R.string.folder_add, () -> pickFolder(ch.app));
            }
            add(items, actions, R.string.app_info, () -> start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ch.app.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        }
        menu.showList(ch.label, items, i -> actions.get(i).run());
    }

    /** Rename, take apps out, delete: everything a folder needs, reachable from inside or outside it. */
    private void folderActions(Channel folder, List<String> items, List<Runnable> actions) {
        add(items, actions, R.string.folder_rename, () -> askName(getString(R.string.folder_rename), folder.label, name -> {
            Folders.Folder f = repo.folders().get(folder.folder.id);
            if (f == null) return;
            f.name = name;
            repo.saveFolders();
            reload();
        }));
        add(items, actions, R.string.folder_take_apps, () -> {
            List<String> labels = new ArrayList<>();
            for (AppEntry a : folder.folderApps) labels.add(a.label);
            menu.showList(getString(R.string.folder_take_apps), labels, i -> takeOut(folder.folderApps.get(i).pkg, folder));
        });
        add(items, actions, R.string.folder_delete, () -> menu.showList(
                getString(R.string.folder_delete_confirm, folder.label),
                List.of(getString(R.string.folder_delete)), i -> ungroup(folder)));
    }

    private void add(List<String> items, List<Runnable> actions, int label, Runnable action) {
        items.add(getString(label));
        actions.add(action);
    }

    @Override public void onMoved(List<Channel> order, Channel inFolder) {
        if (inFolder != null) {
            Folders.Folder f = repo.folders().get(inFolder.folder.id);
            if (f == null) return;
            f.pkgs.clear();
            f.pkgs.addAll(AppRepository.keys(order));
            repo.saveFolders();
        } else {
            repo.saveOrder(AppRepository.keys(order));
        }
        reload();
    }

    private void pickFolder(AppEntry app) {
        List<Folders.Folder> existing = new ArrayList<>(repo.folders().all());
        List<String> names = new ArrayList<>();
        for (Folders.Folder f : existing) names.add(f.name);
        names.add(getString(R.string.folder_new));
        menu.showList(getString(R.string.folder_add), names, which -> {
            if (which < existing.size()) {
                addToFolder(app, existing.get(which), false);
            } else {
                askName(getString(R.string.folder_name), getString(R.string.folder_default_name), name ->
                        addToFolder(app, repo.folders().create(name), true));
            }
        });
    }

    /** A new folder takes the app's place on the menu; an existing one just absorbs the app. */
    private void addToFolder(AppEntry app, Folders.Folder folder, boolean isNew) {
        List<String> keys = AppRepository.keys(menu.channelsTopLevel());
        int at = keys.indexOf(app.pkg);
        repo.folders().add(folder.id, app.pkg);
        if (isNew && at >= 0) keys.set(at, folder.key()); else keys.remove(app.pkg);
        repo.saveFolders();
        repo.saveOrder(keys);
        reload();
    }

    /** The app comes back to the menu right after its folder. */
    private void takeOut(String pkg, Channel folder) {
        repo.folders().remove(pkg);
        repo.saveFolders();
        insertAfter(folder.key, List.of(pkg));
    }

    private void ungroup(Channel folder) {
        List<String> pkgs = new ArrayList<>(repo.folders().delete(folder.folder.id));
        repo.saveFolders();
        insertAfter(folder.key, pkgs);
    }

    private void insertAfter(String anchorKey, List<String> pkgs) {
        List<String> keys = AppRepository.keys(menu.channelsTopLevel());
        int at = keys.indexOf(anchorKey);
        keys.addAll(at < 0 ? keys.size() : at + 1, pkgs);
        repo.saveOrder(keys);
        reload();
    }

    private void askName(String title, String initial, WiiPrompt.Callback cb) {
        prompt.show(title, initial, name -> cb.onText(name.isEmpty() ? getString(R.string.folder_default_name) : name),
                menu::requestFocus);
    }

    // ------------------------------------------------------------ theme

    private boolean isDark() {
        int mode = prefs.getInt(KEY_THEME, 1);
        if (mode != 0) return mode == 2;
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyTheme() {
        Palette p = isDark() ? Palette.DARK : Palette.LIGHT;
        getWindow().setBackgroundDrawable(new ColorDrawable(p.bg));
        menu.setPalette(p);
        int[] labels = {R.string.theme_auto, R.string.theme_light, R.string.theme_dark};
        menu.setThemeLabel(getString(labels[prefs.getInt(KEY_THEME, 1)]));
    }

    // ------------------------------------------------------------ updater callbacks

    @Override public void onCheckResult(ReleaseInfo release, boolean newer) {
        latest = newer ? release : null;
        menu.setStatus(null);
        menu.setUpdateInfo(versionName(), release == null ? "" : release.tag, newer, release == null ? "" : release.notes);
    }

    @Override public void onCheckFailed(String error) {
        menu.setStatus(getString(R.string.sd_check_failed, error));
    }

    @Override public void onStatus(String message) {
        menu.setStatus(message);
    }

    private boolean start(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private String versionName() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "v0";
        }
    }
}
