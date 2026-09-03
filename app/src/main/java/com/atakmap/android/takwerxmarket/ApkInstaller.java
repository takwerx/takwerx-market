package com.atakmap.android.takwerxmarket;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Downloads a catalog entry's APK and hands it to Android's package installer.
 *
 * Three things here are load bearing:
 *
 * The APK is staged in ATAK's INTERNAL files directory, not its external one.
 * External storage under Android/data is readable and writable by any app holding
 * WRITE_EXTERNAL_STORAGE on API 29 and below, and this plugin's minSdk is 21. The
 * verified APK sits on disk for as long as Android's install prompt is on screen,
 * and PackageInstaller re-reads the file when the operator confirms — so staging
 * it somewhere another app can write means the bytes that get installed need not
 * be the bytes that were hash-checked. Internal storage closes that on every API
 * level. A plugin cannot write to its OWN data directory (the code runs under
 * ATAK's uid), but it can write to ATAK's, which is what this does.
 *
 * The directory is a subdirectory of the path ATAK's FileProvider already
 * declares — res/xml/provider_paths.xml maps files-path
 * "linked-user-resources/plugins/apks" — so a content:// URI resolves, and
 * everything we write stays in our own corner of it. That separation matters:
 * purgeDownloads() empties the directory, and it must never be pointed at files
 * ATAK put there itself.
 *
 * The local filename is built from the package name and revision, never from the
 * catalog's own path field. A server-supplied path is a path-traversal waiting to
 * happen, and it buys nothing.
 */
public final class ApkInstaller {

    private static final String TAG = "TakwerxMarket.Install";

    /**
     * ATAK's res/xml/provider_paths.xml declares this files-path under the name
     * PROVIDER_NAME. The market writes into its own subdirectory of it.
     */
    private static final String PROVIDER_PATH = "linked-user-resources/plugins/apks";
    private static final String PROVIDER_NAME = "eud-plugin-apks";
    private static final String SUBDIR = "takwerxmarket";
    private static final String DOWNLOAD_DIR = PROVIDER_PATH + "/" + SUBDIR;

    private ApkInstaller() {
    }

    private static File downloadDir(Context hostContext) {
        return new File(hostContext.getFilesDir(), DOWNLOAD_DIR);
    }

    public static class Result {
        public final boolean ok;
        public final String message;

        /**
         * True when the only thing standing in the way is that the installed
         * build carries a different signing key. That is the one failure the
         * operator can clear themselves, so the caller offers to do it rather
         * than printing an instruction.
         */
        public final boolean signerConflict;

        Result(boolean ok, String message) {
            this(ok, message, false);
        }

        Result(boolean ok, String message, boolean signerConflict) {
            this.ok = ok;
            this.message = message;
            this.signerConflict = signerConflict;
        }
    }

    /**
     * Download, verify, and launch the installer. Blocking — call it off the main
     * thread. The installer prompt itself is Android's and cannot be bypassed.
     */
    public static Result fetchAndInstall(Context hostContext, String baseUrl,
            MarketEntry entry, MarketHttp.Progress progress) {
        Fetched f = fetchAndVerify(hostContext, baseUrl, entry, progress);
        if (f.apk == null)
            return f.result;
        try {
            handToInstaller(hostContext, f.apk);
        } catch (Exception e) {
            delete(f.apk);
            Log.e(TAG, "install failed for " + entry.packageName, e);
            return new Result(false, "Could not start the installer: " + e.getMessage());
        }
        return new Result(true, null);
    }

    /** A verified file ready for the installer, or the reason there is none. */
    public static final class Fetched {
        public final File apk;
        public final Result result;
        /** versionName read out of the file itself, never out of the catalog. */
        public final String versionName;

        Fetched(File apk, Result result) {
            this(apk, result, null);
        }

        Fetched(File apk, Result result, String versionName) {
            this.apk = apk;
            this.result = result;
            this.versionName = versionName;
        }
    }

    /**
     * Download and verify, but do not install. The caller decides when the
     * file goes to Android -- the ATAK upgrade needs two verified files in hand
     * before either is handed over.
     */
    public static Fetched fetchAndVerify(Context hostContext, String baseUrl,
            MarketEntry entry, MarketHttp.Progress progress) {

        File dir = downloadDir(hostContext);
        if (!dir.exists() && !dir.mkdirs())
            return new Fetched(null, new Result(false, "Could not create the download folder"));

        File apk = new File(dir, safeFileName(entry));
        if (apk.exists() && !apk.delete())
            Log.w(TAG, "could not clear a previous download");

        // ATAK is ~370 MB and the installer stages its own copy, so ask before
        // filling the disk: the download plus room for the copy.
        if (entry.size > 0 && entry.size < Long.MAX_VALUE / 4) {
            long need = entry.size * 2 + 50L * 1024 * 1024;
            long free = dir.getUsableSpace();
            if (free < need)
                return new Fetched(null, new Result(false, "Not enough free space for "
                        + entry.label + ": " + (need / (1024 * 1024)) + " MB needed, "
                        + (free / (1024 * 1024)) + " MB free."));
        }

        try {
            String url = resolve(baseUrl, entry.apkPath);
            String actual = MarketHttp.download(url, apk, progress);

            if (entry.hash == null || entry.hash.length() == 0) {
                delete(apk);
                return new Fetched(null, new Result(false, "The catalog carries no hash for "
                        + entry.label + ", so it was not installed"));
            }
            if (!entry.hash.equalsIgnoreCase(actual)) {
                delete(apk);
                return new Fetched(null, new Result(false, "Download did not match the catalog hash. "
                        + entry.label + " was not installed."));
            }

            // The hash only proves the bytes match the catalog. If the catalog is
            // the attacker's, that proves nothing — so this is the check that the
            // binary actually came out of tak.gov's signing pipeline. It gates the
            // install and fails closed.
            //
            // NOT gated on entry.isPlugin(). That reads the catalog's own "type"
            // column, so gating on it would let a hostile catalog switch its own
            // signature check off by writing type=app — and nothing else in the
            // parser or the pane treats a non-plugin row differently, so it would
            // render and install exactly like a plugin. Everything the market
            // installs is pinned, whatever the catalog calls it.
            java.util.Set<String> apkSigners = Signers.ofApk(hostContext, apk);
            if (!Signers.isTakSigned(hostContext, entry.packageName, apkSigners)) {
                delete(apk);
                Log.w(TAG, "refusing unsigned/foreign build of " + entry.packageName);
                return new Fetched(null, new Result(false, entry.label + " was not installed: the"
                        + " downloaded build is not signed by the TAK Product"
                        + " Center. Nothing outside tak.gov's signing pipeline"
                        + " can be installed from here."));
            }

            // Android will not replace an app with one signed by a different key,
            // and the message it gives for that names neither the key nor the app
            // ("package appears to be invalid"). Say the real thing instead.
            if (entry.installed && Signers.conflict(
                    Signers.ofInstalled(hostContext, entry.packageName),
                    apkSigners)) {
                delete(apk);
                if (entry.isAtak())
                    return new Fetched(null, new Result(false, "The ATAK on this device was"
                            + " not installed from tak.gov (it carries a different"
                            + " signing key), so it cannot be updated from here."));
                return new Fetched(null, new Result(false, entry.label + " on this device was signed"
                        + " with a different key than the market's build, so"
                        + " Android will not replace it.", true));
            }

            // The signer proves who built it, not what it is. A hostile catalog
            // could label an older tak.gov-signed ATAK as a new version and
            // Android would take it, because ATAK's versionCode is a build
            // timestamp rather than the release number (5.6.0.23 outranks
            // 5.7.0.14). So read the file's own identity and hold it to the row.
            android.content.pm.PackageInfo pi = hostContext.getPackageManager()
                    .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (pi == null || !entry.packageName.equals(pi.packageName)) {
                delete(apk);
                return new Fetched(null, new Result(false, "The downloaded file is not "
                        + entry.label + " (it is "
                        + (pi == null ? "unreadable" : pi.packageName) + "), so it was not installed."));
            }
            if (entry.isAtak()) {
                // This code runs inside ATAK, so ATAK's own row always has an
                // installed version. A null here means the row is not really
                // ATAK's package, and the "newer than installed" check below
                // would pass against nothing. Refuse.
                if (entry.installedVersion == null) {
                    delete(apk);
                    return new Fetched(null, new Result(false, "Refusing to treat "
                            + entry.packageName + " as ATAK: no installed version to compare."));
                }
                String fileCore = AtakTarget.coreVersion(pi.versionName);
                String rowCore = AtakTarget.coreVersion(entry.version);
                if (fileCore == null || !fileCore.equals(rowCore)) {
                    delete(apk);
                    return new Fetched(null, new Result(false, "The downloaded ATAK is "
                            + pi.versionName + ", not the " + entry.version
                            + " the catalog promised, so it was not installed."));
                }
                if (!PluginVersion.isNewer(pi.versionName, entry.installedVersion)) {
                    delete(apk);
                    return new Fetched(null, new Result(false, "The downloaded ATAK ("
                            + pi.versionName + ") is not newer than the one running ("
                            + entry.installedVersion + "), so it was not installed."));
                }
            }

            // Everything above is a reason to refuse. Past this point the file
            // is verified. Whoever hands it to Android: the installer copies it
            // into its own staging when it launches (API 24 and up), but we are
            // not told when, so the file stays on disk until the next ATAK
            // start, when purgeDownloads() clears it.
            return new Fetched(apk, new Result(true, null), pi.versionName);

        } catch (IOException e) {
            delete(apk);
            Log.e(TAG, "download failed for " + entry.packageName, e);
            return new Fetched(null, new Result(false, "Download failed: " + e.getMessage()));
        } catch (Exception e) {
            delete(apk);
            Log.e(TAG, "verify failed for " + entry.packageName, e);
            return new Fetched(null, new Result(false, "Could not verify the download: " + e.getMessage()));
        }
    }

    /**
     * Hands the verified file to Android's installer the way ATAK's own package
     * manager does: ACTION_VIEW on a content:// URI from ATAK's FileProvider.
     *
     * Not a PackageInstaller session, and that is a measured decision. 0.3 and
     * 0.4 used a session, which reports the outcome back -- and makes ATAK the
     * installer of record. Android delivers PACKAGE_ADDED once to everyone and a
     * second copy straight to the installer, and ATAK asks "Load plugin?" once
     * per copy. Measured on ATAK-CIV 5.7.0.5, Android 14, replacing Cam Depot
     * 1.1 over itself: installer of record shell, one event, one prompt;
     * installer of record ATAK, two events, two prompts. Through the system
     * installer the question comes once, which is what ATAK's own package
     * manager gives a user.
     *
     * The cost is blindness to cancel: nothing says the operator backed out. The
     * pane watches the package broadcast for success and times out otherwise.
     */
    public static void handToInstaller(Context hostContext, File apk) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(contentUri(hostContext, apk),
                "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        hostContext.startActivity(i);
    }

    private static Uri contentUri(Context hostContext, File apk) {
        String authority = hostContext.getPackageName() + ".provider";
        try {
            return androidx.core.content.FileProvider.getUriForFile(hostContext, authority, apk);
        } catch (Throwable t) {
            // Official ATAK is obfuscated and need not keep the helper. The
            // mapping is fixed by ATAK's own provider_paths.xml, so the URI can
            // be written directly: files-path PROVIDER_NAME -> PROVIDER_PATH.
            Log.d(TAG, "FileProvider unavailable, building the URI directly: " + t);
            return new Uri.Builder().scheme("content").authority(authority)
                    .appendPath(PROVIDER_NAME).appendPath(SUBDIR)
                    .appendPath(apk.getName()).build();
        }
    }

    /**
     * A catalog path is relative to the market base. An absolute one is taken as
     * given, but still has to be HTTPS — MarketHttp refuses anything else.
     */
    static String resolve(String baseUrl, String path) throws IOException {
        if (path == null || path.length() == 0)
            throw new IOException("catalog entry has no APK path");

        String lower = path.toLowerCase(Locale.US);
        if (lower.startsWith("https://"))
            return path;
        if (lower.startsWith("http://"))
            throw new IOException("catalog entry uses a plain-http URL");
        if (path.contains(".."))
            throw new IOException("catalog entry has a relative path segment");

        String base = baseUrl;
        while (base.endsWith("/"))
            base = base.substring(0, base.length() - 1);
        return base + "/" + (path.startsWith("/") ? path.substring(1) : path);
    }

    /** Built from what we control, not from what the server sent. */
    static String safeFileName(MarketEntry entry) {
        String pkg = entry.packageName == null ? "unknown" : entry.packageName;
        pkg = pkg.replaceAll("[^A-Za-z0-9._-]", "_");
        return pkg + "-" + entry.revision + ".apk";
    }

    /**
     * Delete anything left in the download directory.
     *
     * A downloaded APK cannot be removed right after the installer is launched:
     * Android is still reading it through the content URI. And the market never
     * learns whether the install finished, so there is no later moment in the
     * session that is safe either. Sweeping at plugin start is — a fresh ATAK
     * session has no install in flight from a previous one.
     */
    public static void purgeDownloads(Context hostContext) {
        File dir = downloadDir(hostContext);
        File[] stale = dir.listFiles();
        if (stale == null)
            return;
        for (File f : stale) {
            if (f.isFile() && !f.delete())
                Log.w(TAG, "could not remove stale download " + f.getName());
        }
    }

    /** Drop a verified file that will not be handed over after all. */
    public static void discard(File f) {
        delete(f);
    }

    private static void delete(File f) {
        if (f != null && f.exists() && !f.delete())
            Log.w(TAG, "could not remove " + f.getName());
    }
}
