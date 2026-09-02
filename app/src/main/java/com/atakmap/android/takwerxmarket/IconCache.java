package com.atakmap.android.takwerxmarket;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.atakmap.coremap.log.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Plugin icons for the list. In memory only, fetched once per ATAK session.
 *
 * There was a disk cache and it was wrong. Nothing in the catalog identifies an
 * icon's content: the obvious key is the package name, which never changes, and
 * the next idea is the APK hash, which only changes when the plugin is rebuilt.
 * An icon can change on its own — regenerate the catalog to serve a different
 * glyph and every device that has already looked keeps the old one forever.
 * Measured 2026-09-02: a CDN served a stale icon just long enough for devices to
 * cache it under a key that would never be asked again.
 *
 * Four icons at a few tens of kilobytes, once per ATAK launch, is not worth a
 * cache that can be wrong. Offline, the rows simply draw without icons.
 *
 * Decoding is bounded because the catalog serves whatever glyph a plugin ships —
 * 512px and up is normal — and a list holding full-size bitmaps for every row is
 * how a plugin runs a device out of memory.
 */
public final class IconCache {

    private static final String TAG = "TakwerxMarket.Icons";

    /** Comfortably above the ~48dp the row draws, even on a dense screen. */
    private static final int MAX_EDGE = 192;

    private static final Map<String, Bitmap> MEMORY = new HashMap<>();

    /** The catalog path each cached bitmap came from, so a changed URL re-fetches. */
    private static final Map<String, String> SOURCE = new HashMap<>();

    private IconCache() {
    }

    /** Main-thread lookup. Never fetches; returns null until warm() has run. */
    public static synchronized Bitmap get(String packageName) {
        return MEMORY.get(packageName);
    }

    /**
     * Fetch and decode anything missing. Blocking — call it off the main thread.
     * A failure for one entry is not a failure for the list: the row just draws
     * without an icon.
     */
    public static void warm(Context hostContext, String baseUrl,
            Iterable<MarketEntry> entries) {

        File dir = hostContext.getCacheDir();
        for (MarketEntry e : entries) {
            if (e.iconPath == null || e.iconPath.length() == 0)
                continue;
            synchronized (IconCache.class) {
                // Keyed on the catalog path, not just the package: the path
                // carries a content hash, so an icon that changed upstream has a
                // different path and must be fetched again even mid-session.
                if (e.iconPath.equals(SOURCE.get(e.packageName)))
                    continue;
            }

            // A scratch file, deleted as soon as it is decoded. Named from the
            // package, never from the catalog's own path.
            File tmp = new File(dir, "takwerxmarket-icon-"
                    + e.packageName.replaceAll("[^A-Za-z0-9._-]", "_") + ".png");
            try {
                MarketHttp.download(ApkInstaller.resolve(baseUrl, e.iconPath), tmp, null);
                Bitmap b = decodeBounded(tmp);
                if (b != null) {
                    synchronized (IconCache.class) {
                        MEMORY.put(e.packageName, b);
                        SOURCE.put(e.packageName, e.iconPath);
                    }
                }
            } catch (Exception ex) {
                Log.d(TAG, "no icon for " + e.packageName + ": " + ex.getMessage());
            } finally {
                if (tmp.exists() && !tmp.delete())
                    Log.d(TAG, "could not remove " + tmp.getName());
            }
        }
    }

    private static Bitmap decodeBounded(File f) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), probe);

        int longest = Math.max(probe.outWidth, probe.outHeight);
        int sample = 1;
        while (longest / sample > MAX_EDGE)
            sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
    }
}
