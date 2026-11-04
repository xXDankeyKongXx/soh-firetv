package com.dishii.soh;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-app updates from the project's public GitHub releases.
 *
 * Each signed CI build publishes a release tagged "vc-&lt;versionCode&gt;" with the APK attached.
 * Every build is signed with the same key, so Android installs it over the current version and
 * keeps all data (settings, saves, mods and the extracted game live in /sdcard/SOH anyway).
 */
final class Updater {

    private static final String TAG = "SoH-Update";
    static final String ACTION_INSTALL_STATUS = "com.dishii.soh.INSTALL_STATUS";

    static final class Release {
        int versionCode;
        String title, notes, apkUrl, pageUrl;
        long apkSize;
    }

    /** Latest release, or null if none / unreachable. Blocking. */
    static Release latest() throws IOException, JSONException {
        String url = "https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest";
        HttpURLConnection c = GameBananaApi.open(url);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        String body;
        try {
            int code = c.getResponseCode();
            if (code == 404) return null; // no releases yet (or the repo isn't public)
            if (code >= 400) throw new IOException("GitHub answered HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                body = out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
        JSONObject j = new JSONObject(body);
        String tag = j.optString("tag_name", "");
        if (!tag.startsWith("vc-")) return null;
        Release r = new Release();
        try {
            r.versionCode = Integer.parseInt(tag.substring(3));
        } catch (NumberFormatException e) {
            return null;
        }
        r.title = j.optString("name", tag);
        r.notes = j.optString("body", "");
        r.pageUrl = j.optString("html_url", "");
        JSONArray assets = j.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name", "").toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                    r.apkUrl = a.optString("browser_download_url");
                    r.apkSize = a.optLong("size");
                    break;
                }
            }
        }
        return r.apkUrl == null ? null : r;
    }

    static boolean isNewer(Release r) {
        return r != null && r.versionCode > BuildConfig.VERSION_CODE;
    }

    /** Fire OS asks once per app before it may install APKs ("Install unknown apps"). */
    static boolean canInstall(Context ctx) {
        return Build.VERSION.SDK_INT < 26 || ctx.getPackageManager().canRequestPackageInstalls();
    }

    static Intent unknownSourcesSettings(Context ctx) {
        return new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:" + ctx.getPackageName()));
    }

    /** Downloads the APK into the app cache. Blocking. */
    static File download(Context ctx, Release r, ModInstaller.Progress progress, AtomicBoolean cancel)
            throws IOException {
        File dir = new File(ctx.getCacheDir(), "update");
        ModInstaller.deleteRecursive(dir);
        if (!dir.mkdirs()) throw new IOException("Couldn't create the download folder");
        File apk = new File(dir, "update.apk");
        HttpURLConnection c = GameBananaApi.open(r.apkUrl);
        c.setRequestProperty("Accept", "application/octet-stream");
        c.setReadTimeout(60000);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("Download failed (HTTP " + code + ")");
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : r.apkSize;
            long done = 0, last = 0;
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 OutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancel.get()) throw new ModInstaller.CancelledException();
                    out.write(buf, 0, n);
                    done += n;
                    if (done - last > 512 * 1024 || done == total) {
                        last = done;
                        progress.onProgress("Downloading update…", done, total);
                    }
                }
            }
            if (total > 0 && done != total) throw new IOException("Download was cut off. Try again.");
        } finally {
            c.disconnect();
        }
        return apk;
    }

    /** Hands the APK to the system installer; Fire OS shows its confirmation screen. */
    static void install(Context ctx, File apk) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        params.setSize(apk.length());
        int sessionId = pi.createSession(params);
        try (PackageInstaller.Session session = pi.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent status = new Intent(ctx, InstallStatusReceiver.class).setAction(ACTION_INSTALL_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE; // installer fills in extras
            PendingIntent pending = PendingIntent.getBroadcast(ctx, sessionId, status, flags);
            session.commit(pending.getIntentSender());
        } catch (IOException | RuntimeException e) {
            pi.abandonSession(sessionId);
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
    }

    /** Receives the installer's status: shows the confirmation screen or reports a failure. */
    public static final class InstallStatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Log.i(TAG, "install status " + status + " " + msg);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(confirm);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String why = status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                        ? "This update is signed differently from the installed app. Uninstall once, then install it."
                        : status == PackageInstaller.STATUS_FAILURE_ABORTED ? "Update cancelled."
                        : "Update failed" + (msg != null ? ": " + msg : ".");
                Toast.makeText(ctx, why, Toast.LENGTH_LONG).show();
            }
        }
    }

    private Updater() { }
}
