package com.atakmap.android.takwerxmarket;

import android.content.Context;
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

    /** Under ATAK's files dir, inside the path its FileProvider already exposes. */
    private static final String DOWNLOAD_DIR =
            "linked-user-resources/plugins/apks/takwerxmarket";

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
            MarketEntry entry, MarketHttp.Progress progress,
            SessionInstaller.Callback onInstalled) {

        File dir = downloadDir(hostContext);
        if (!dir.exists() && !dir.mkdirs())
            return new Result(false, "Could not create the download folder");

        File apk = new File(dir, safeFileName(entry));
        if (apk.exists() && !apk.delete())
            Log.w(TAG, "could not clear a previous download");

        try {
            String url = resolve(baseUrl, entry.apkPath);
            String actual = MarketHttp.download(url, apk, progress);

            if (entry.hash == null || entry.hash.length() == 0) {
                delete(apk);
                return new Result(false, "The catalog carries no hash for "
                        + entry.label + ", so it was not installed");
            }
            if (!entry.hash.equalsIgnoreCase(actual)) {
                delete(apk);
                return new Result(false, "Download did not match the catalog hash. "
                        + entry.label + " was not installed.");
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
            if (!Signers.isTakSigned(apkSigners)) {
                delete(apk);
                Log.w(TAG, "refusing unsigned/foreign build of " + entry.packageName);
                return new Result(false, entry.label + " was not installed: the"
                        + " downloaded build is not signed by the TAK Product"
                        + " Center. Nothing outside tak.gov's signing pipeline"
                        + " can be installed from here.");
            }

            // Android will not replace an app with one signed by a different key,
            // and the message it gives for that names neither the key nor the app
            // ("package appears to be invalid"). Say the real thing instead.
            if (entry.installed && Signers.conflict(
                    Signers.ofInstalled(hostContext, entry.packageName),
                    apkSigners)) {
                delete(apk);
                return new Result(false, entry.label + " on this device was signed"
                        + " with a different key than the market's build, so"
                        + " Android will not replace it.", true);
            }

            // Everything above is a reason to refuse. Past this point the file
            // is verified and Android takes over, reporting back to onInstalled.
            SessionInstaller.install(hostContext, apk, entry.label, onInstalled);

            // The session has already read the file, so nothing needs it now.
            // Previously a verified APK sat on disk until the next ATAK start.
            delete(apk);
            return new Result(true, null);

        } catch (IOException e) {
            delete(apk);
            Log.e(TAG, "download failed for " + entry.packageName, e);
            return new Result(false, "Download failed: " + e.getMessage());
        } catch (Exception e) {
            delete(apk);
            Log.e(TAG, "install failed for " + entry.packageName, e);
            return new Result(false, "Could not start the installer: " + e.getMessage());
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

    private static void delete(File f) {
        if (f != null && f.exists() && !f.delete())
            Log.w(TAG, "could not remove " + f.getName());
    }
}
