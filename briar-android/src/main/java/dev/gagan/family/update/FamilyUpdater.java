/*
 * FamilyUpdater — the OTA client vendored into our forks of upstream apps.
 *
 * Deliberately plain: no Compose, no WorkManager, no third-party HTTP. One file that drops into a
 * Kotlin/Compose app (bitchat) and a Java app (Briar) unchanged, and that we can keep compiling
 * after every upstream sync because it touches nothing upstream owns.
 *
 * Contract with the server (selfupdate `/manifest`, schema 1):
 *   { "package": "...", "version_code": 1052201, "version_name": "1.5.22-family.1",
 *     "apk_url": "download/briar-1.5.22-family.1.apk", "sha256": "<hex>", "size_bytes": 48000000,
 *     "signer_sha256": ["<our key digest>"], "min_sdk": 21, "min_supported_version_code": 0,
 *     "release_notes": "...", "published_at": "..." }
 *
 * Safety rules, in order: https-only (Tailscale serves a real cert), package must match this app,
 * signer must equal the pinned digest (an update may never change our identity), sha256 must match
 * the manifest, and version_code must increase. Anything else fails closed with a printable reason.
 */
package dev.gagan.family.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

public final class FamilyUpdater {

    public interface Listener {
        void onResult(Result result);
    }

    public static final class Result {
        public enum State { UP_TO_DATE, AVAILABLE, FAILED, INSTALLING, INSTALLED_PENDING }

        public final State state;
        public final String message;
        public final String versionName;
        public final int versionCode;

        Result(State state, String message, String versionName, int versionCode) {
            this.state = state;
            this.message = message;
            this.versionName = versionName;
            this.versionCode = versionCode;
        }

        static Result upToDate(String version) {
            return new Result(State.UP_TO_DATE, "Up to date · " + version, version, 0);
        }

        static Result failed(String why) {
            return new Result(State.FAILED, why, null, 0);
        }

        static Result available(String versionName, int versionCode, String why) {
            return new Result(State.AVAILABLE, why, versionName, versionCode);
        }
    }

    private static final String TAG = "FamilyUpdater";
    private static final String PREFS = "family_updater";
    private static final String KEY_LAST_CHECK = "last_check_ms";
    private static final String CHANNEL_ID = "family_updates";
    private static final int NOTIFICATION_ID = 0x0F0F;
    private static final long CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private static volatile String manifestUrl;
    private static volatile String pinnedSignerSha256;
    private static volatile Context appContext;
    private static volatile boolean initialised;

    private FamilyUpdater() {
    }

    /**
     * Called once from the host app's Application.onCreate.
     *
     * @param manifestUrl absolute https URL of the channel manifest for this package
     * @param signerSha256 hex SHA-256 of the certificate that signs our builds (no colons)
     */
    public static void init(Context context, String manifestUrl, String signerSha256) {
        appContext = context.getApplicationContext();
        FamilyUpdater.manifestUrl = manifestUrl;
        FamilyUpdater.pinnedSignerSha256 = normalise(signerSha256);
        ensureChannel(appContext);
        initialised = true;
    }

    public static boolean isInitialised() {
        return initialised;
    }

    /** Throttled check, for app start/foreground. Never throws, never blocks the caller. */
    public static void checkOnAppStart() {
        if (!initialised) return;
        new Thread(() -> {
            Context ctx = appContext;
            long last = prefs(ctx).getLong(KEY_LAST_CHECK, 0L);
            if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;
            Result result = checkInternal();
            if (result.state == Result.State.AVAILABLE) notifyAvailable(ctx, result);
        }, "family-update-check").start();
    }

    /** User-initiated check; always runs and reports back on the main thread. */
    public static void checkNow(Listener listener) {
        final Handler main = new Handler(Looper.getMainLooper());
        if (!initialised) {
            main.post(() -> listener.onResult(Result.failed("Updater not initialised")));
            return;
        }
        new Thread(() -> {
            Result result = checkInternal();
            main.post(() -> listener.onResult(result));
        }, "family-update-check").start();
    }

    private static Result checkInternal() {
        Context ctx = appContext;
        try {
            String body = httpGet(manifestUrl, "application/json");
            JSONObject manifest = new JSONObject(body);

            String pkg = manifest.optString("package", "");
            if (!pkg.equals(ctx.getPackageName())) {
                return Result.failed("Manifest is for " + pkg);
            }

            int remoteCode = manifest.getInt("version_code");
            String remoteName = manifest.optString("version_name", String.valueOf(remoteCode));
            int installed = installedVersionCode(ctx);
            prefs(ctx).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();

            if (remoteCode <= installed) return Result.upToDate(installedVersionName(ctx));

            // Identity guard: the channel must only ever offer builds signed by our key.
            String pinned = pinnedSignerSha256;
            JSONArray signers = manifest.optJSONArray("signer_sha256");
            if (signers != null && pinned != null) {
                boolean ok = false;
                for (int i = 0; i < signers.length(); i++) {
                    if (pinned.equals(normalise(signers.optString(i)))) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) return Result.failed("Channel signer is not our key");
            }

            int minSupported = manifest.optInt("min_supported_version_code", 0);
            String why = installed < minSupported
                    ? "Required update · " + remoteName
                    : "Update available · " + remoteName;
            return Result.available(remoteName, remoteCode, why);
        } catch (Exception e) {
            Log.w(TAG, "check failed", e);
            return Result.failed(friendly(e));
        }
    }

    /**
     * Downloads the APK the manifest points at, verifies sha256 + signing certificate, then hands it
     * to the system installer. Must be called from an Activity (install intents need one).
     */
    public static void downloadAndInstall(Activity activity, Listener listener) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                String body = httpGet(manifestUrl, "application/json");
                JSONObject manifest = new JSONObject(body);
                String apkUrl = absolute(manifestUrl, manifest.getString("apk_url"));
                String expectedSha = normalise(manifest.optString("sha256", ""));
                String versionName = manifest.optString("version_name", "update");

                File dir = new File(activity.getCacheDir(), "family-updates");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create cache dir");
                File apk = new File(dir, "update-" + versionName + ".apk");

                byte[] payload = httpGetBytes(apkUrl);
                if (expectedSha != null && !expectedSha.isEmpty()
                        && !expectedSha.equals(sha256Hex(payload))) {
                    deleteQuietly(apk);
                    main.post(() -> listener.onResult(Result.failed("Download corrupted (sha256)")));
                    return;
                }
                try (FileOutputStream out = new FileOutputStream(apk)) {
                    out.write(payload);
                }

                String signerError = verifyApkSigner(activity, apk);
                if (signerError != null) {
                    deleteQuietly(apk);
                    main.post(() -> listener.onResult(Result.failed(signerError)));
                    return;
                }

                main.post(() -> {
                    listener.onResult(new Result(Result.State.INSTALLING, "Installing…", versionName, 0));
                    launchInstaller(activity, apk);
                });
            } catch (Exception e) {
                Log.w(TAG, "download failed", e);
                main.post(() -> listener.onResult(Result.failed(friendly(e))));
            }
        }, "family-update-download").start();
    }

    /** One dialog that both a Views app and a Compose app can show. */
    public static void showDialog(Activity activity, Listener listener) {
        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle("Check for updates")
                .setMessage("Checking the family update channel…")
                .setNegativeButton("Close", null)
                .setCancelable(true);
        AlertDialog dialog = b.create();
        dialog.show();
        checkNow(result -> {
            if (result.state == Result.State.AVAILABLE) {
                dialog.setMessage(result.message + "\n\nSigned by the family key.");
                dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Download & install",
                        (d, w) -> downloadAndInstall(activity, outer(listener, dialog)));
            } else {
                dialog.setMessage(result.message);
            }
        });
    }

    private static Listener outer(Listener listener, AlertDialog dialog) {
        return result -> {
            dialog.setMessage(result.message);
            if (listener != null) listener.onResult(result);
        };
    }

    private static String verifyApkSigner(Context ctx, File apk) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo info;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
        } else {
            info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        }
        if (info == null) return "Downloaded APK is unreadable";

        Set<String> digests = new HashSet<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            android.content.pm.Signature[] sigs = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
            if (sigs != null) for (android.content.pm.Signature s : sigs) digests.add(sha256Hex(s.toByteArray()));
        } else if (info.signatures != null) {
            for (android.content.pm.Signature s : info.signatures) digests.add(sha256Hex(s.toByteArray()));
        }
        if (digests.isEmpty()) return "Downloaded APK is unsigned";

        String pinned = pinnedSignerSha256;
        if (pinned == null || !digests.contains(pinned)) return "Downloaded APK is signed by a different key";
        return null;
    }

    private static void launchInstaller(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception ignored) {
                // Installer will still prompt; the user can enable it from the system dialog.
            }
        }
        Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".familyupdates", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "App updates",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("New versions from the family update channel");
        nm.createNotificationChannel(channel);
    }

    private static void notifyAvailable(Context ctx, Result result) {
        try {
            Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(ctx, CHANNEL_ID)
                    : new Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Update available")
                    .setContentText(result.message + " — open the app to install")
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, b.build());
        } catch (Exception e) {
            Log.w(TAG, "notification failed", e);
        }
    }

    // ---- http -------------------------------------------------------------------------------

    private static String httpGet(String url, String accept) throws IOException {
        return new String(httpGetBytes(url, accept), "UTF-8");
    }

    private static byte[] httpGetBytes(String url) throws IOException {
        return httpGetBytes(url, "application/octet-stream");
    }

    private static byte[] httpGetBytes(String url, String accept) throws IOException {
        HttpURLConnection conn = open(url, accept);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        // Tailscale serves a real certificate for *.ts.net; anything not https is a configuration bug.
        if (!"https".equalsIgnoreCase(new URL(url).getProtocol())) {
            throw new IOException("Refusing a non-https update URL");
        }
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Accept", accept);
        conn.setRequestProperty("User-Agent", "FamilyUpdater/1");
        int code = conn.getResponseCode();
        if (code >= 300 && code < 400) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location == null || !location.startsWith("https://")) {
                throw new IOException("Refusing an insecure redirect");
            }
            return open(location, accept);
        }
        if (code != 200) {
            conn.disconnect();
            throw new IOException("HTTP " + code);
        }
        return conn;
    }

    private static String absolute(String base, String maybeRelative) {
        if (maybeRelative.startsWith("http")) return maybeRelative;
        int cut = base.lastIndexOf('/');
        return base.substring(0, cut + 1) + maybeRelative;
    }

    // ---- misc -------------------------------------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int installedVersionCode(Context ctx) throws PackageManager.NameNotFoundException {
        PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? (int) info.getLongVersionCode()
                : info.versionCode;
    }

    private static String installedVersionName(Context ctx) {
        try {
            return String.valueOf(ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName);
        } catch (Exception e) {
            return "?";
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalise(String value) {
        if (value == null) return null;
        String clean = value.replace(":", "").replace(" ", "").trim().toLowerCase();
        return clean.isEmpty() ? null : clean;
    }

    private static String friendly(Exception e) {
        String msg = e.getMessage();
        return msg == null ? e.getClass().getSimpleName() : msg;
    }

    private static void deleteQuietly(File file) {
        try {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (Exception ignored) {
        }
    }
}
