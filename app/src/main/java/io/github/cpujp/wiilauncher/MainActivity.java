package io.github.cpujp.wiilauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import java.util.List;

/** HOME activity: hosts the menu view and routes its actions to the system. */
public final class MainActivity extends Activity implements WiiMenuView.Callbacks, Updater.Listener {
    private AppRepository repo;
    private WiiMenuView menu;
    private Sfx sfx;
    private Updater updater;
    private ReleaseInfo pendingUpdate;

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            menu.setApps(repo.load());
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        repo = new AppRepository(this);
        sfx = new Sfx();
        updater = new Updater(this, this);
        menu = new WiiMenuView(this, this, sfx);
        setContentView(menu);
        menu.requestFocus();
        menu.setApps(repo.load());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
    }

    @Override protected void onResume() {
        super.onResume();
        updater.checkIfDue(versionName());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        menu.closePreview(); // Home pressed while already home
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

    // ------------------------------------------------------------ menu callbacks

    @Override public void onLaunch(AppEntry app) {
        start(app.launchIntent());
    }

    @Override public void onOptions(AppEntry app, int index) {
        String[] items = {getString(R.string.move_left), getString(R.string.move_right), getString(R.string.app_info)};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(items, (d, which) -> {
                    if (which == 2) {
                        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return;
                    }
                    List<AppEntry> apps = menu.apps();
                    int target = index + (which == 0 ? -1 : 1);
                    if (target < 0 || target >= apps.size()) return;
                    apps.set(index, apps.set(target, apps.get(index)));
                    repo.saveOrder(apps);
                    menu.setApps(apps);
                    menu.focusIndex(target);
                })
                .show();
    }

    @Override public void onSettings() {
        start(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override public void onMail() {
        if (pendingUpdate == null) {
            Toast.makeText(this, R.string.no_messages, Toast.LENGTH_SHORT).show();
            return;
        }
        ReleaseInfo r = pendingUpdate;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.update_title, r.tag))
                .setMessage(r.notes.isEmpty() ? getString(R.string.update_body) : r.notes)
                .setPositiveButton(R.string.install, (d, w) -> updater.install(r))
                .setNegativeButton(R.string.later, null)
                .show();
    }

    // ------------------------------------------------------------ updater callbacks

    @Override public void onUpdateAvailable(ReleaseInfo release) {
        pendingUpdate = release;
        menu.setMailBlink(true);
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
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }
}
