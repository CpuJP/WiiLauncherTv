package io.github.cpujp.wiilauncher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Checks this project's GitHub releases and installs newer builds through the system installer. */
final class Updater {
    static final String REPO = "CpuJP/WiiLauncherTv";
    static final String RELEASE_DOWNLOAD_PREFIX = "https://github.com/" + REPO + "/releases/download/";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    interface Listener {
        /** {@code latest} is null when the repository has no installable release. */
        void onCheckResult(ReleaseInfo latest, boolean newer);
        void onCheckFailed(String error);
        void onStatus(String message);
    }

    private final Context context;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastCheck;

    Updater(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void checkIfDue(String currentVersion) {
        if (System.currentTimeMillis() - lastCheck >= CHECK_INTERVAL_MS) checkNow(currentVersion);
    }

    void checkNow(String currentVersion) {
        lastCheck = System.currentTimeMillis();
        io.execute(() -> {
            try {
                ReleaseInfo r = ReleaseInfo.parse(new String(get(LATEST), StandardCharsets.UTF_8));
                boolean newer = r != null && Versions.isNewer(r.tag, currentVersion);
                main.post(() -> listener.onCheckResult(r, newer));
            } catch (Exception e) {
                lastCheck = 0; // offline or rate-limited: retry on next resume
                main.post(() -> listener.onCheckFailed(String.valueOf(e.getMessage())));
            }
        });
    }

    void install(ReleaseInfo r) {
        listener.onStatus(context.getString(R.string.update_downloading, r.tag));
        io.execute(() -> {
            PackageInstaller pi = context.getPackageManager().getPackageInstaller();
            try {
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(context.getPackageName());
                int id = pi.createSession(params);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    HttpURLConnection c = open(r.apkUrl);
                    try (InputStream in = c.getInputStream(); OutputStream out = s.openWrite("update.apk", 0, -1)) {
                        byte[] buf = new byte[64 * 1024];
                        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                        s.fsync(out);
                    } finally {
                        c.disconnect();
                    }
                    Intent cb = new Intent(context, InstallResultReceiver.class);
                    PendingIntent p = PendingIntent.getBroadcast(context, id, cb,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    s.commit(p.getIntentSender());
                }
            } catch (IOException | RuntimeException e) {
                main.post(() -> listener.onStatus(context.getString(R.string.update_failed, String.valueOf(e.getMessage()))));
            }
        });
    }

    void shutdown() {
        io.shutdownNow();
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "WiiLauncherTv");
        if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode());
        return c;
    }

    /** Forwards the installer's confirmation screen; Android itself verifies the update's signature. */
    public static final class InstallResultReceiver extends BroadcastReceiver {
        @SuppressWarnings("deprecation") // typed overload needs API 33; minSdk is 31
        @Override public void onReceive(Context ctx, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }
    }
}
