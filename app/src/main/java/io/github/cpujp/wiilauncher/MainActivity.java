package io.github.cpujp.wiilauncher;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** HOME activity: hosts the menu view and routes its actions to the system. */
public final class MainActivity extends Activity implements WiiMenuView.Callbacks, Updater.Listener {
    private static final String PREFS = "ui";
    private static final String KEY_THEME = "theme"; // 0 = follow system, 1 = light (default, as on the Wii), 2 = dark

    // TCL's input panel (HDMI, TV, AirPlay...), the same one the remote's input key opens.
    private static final ComponentName TCL_INPUTS = new ComponentName("com.tcl.suspension", "com.tcl.suspension.DismissService");
    private static final String TCL_INPUTS_ACTION = "com.tcl.quickpanel.show.window";

    private AppRepository repo;
    private WiiMenuView menu;
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
        setContentView(menu);
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
        try {
            if (startService(new Intent(TCL_INPUTS_ACTION).setComponent(TCL_INPUTS)) != null) return;
        } catch (SecurityException | IllegalStateException ignored) {
            // not a TCL TV, or the panel is unavailable: fall back to the system input settings
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

    @Override public void onOptions(Channel ch, int index, Channel inFolder) {
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add(getString(R.string.move));
        actions.add(menu::startGrab);
        if (ch.isFolder()) {
            items.add(getString(R.string.folder_rename));
            actions.add(() -> askName(ch.label, name -> {
                ch.folder.name = name;
                repo.saveFolders();
                reload();
            }));
            items.add(getString(R.string.folder_ungroup));
            actions.add(() -> ungroup(ch));
        } else {
            if (inFolder != null) {
                items.add(getString(R.string.folder_take_out));
                actions.add(() -> takeOut(ch.app.pkg, inFolder));
            } else {
                items.add(getString(R.string.folder_add));
                actions.add(() -> pickFolder(ch.app));
            }
            items.add(getString(R.string.app_info));
            actions.add(() -> start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ch.app.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        }
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle(ch.label)
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    // ------------------------------------------------------------ channel order and folders

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
        String[] names = new String[existing.size() + 1];
        for (int i = 0; i < existing.size(); i++) names[i] = existing.get(i).name;
        names[existing.size()] = getString(R.string.folder_new);
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle(R.string.folder_add)
                .setItems(names, (d, which) -> {
                    if (which < existing.size()) {
                        addToFolder(app, existing.get(which), false);
                    } else {
                        askName(getString(R.string.folder_default_name), name ->
                                addToFolder(app, repo.folders().create(name), true));
                    }
                })
                .show();
    }

    /** A new folder takes the app's place on the menu; an existing one just absorbs the app. */
    private void addToFolder(AppEntry app, Folders.Folder folder, boolean isNew) {
        List<String> keys = AppRepository.keys(menu.visible());
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

    private interface NameCallback {
        void onName(String name);
    }

    private void askName(String initial, NameCallback cb) {
        AlertDialog.Builder b = new AlertDialog.Builder(this, dialogTheme());
        EditText input = new EditText(b.getContext()); // dialog context gives text colors that match its background
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        input.setText(initial);
        input.selectAll();
        b.setTitle(R.string.folder_name)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    cb.onName(name.isEmpty() ? getString(R.string.folder_default_name) : name);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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

    private int dialogTheme() {
        return isDark() ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
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
            return "0";
        }
    }
}
