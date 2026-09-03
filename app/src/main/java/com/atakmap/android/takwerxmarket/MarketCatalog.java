package com.atakmap.android.takwerxmarket;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.atakmap.coremap.log.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Fetches and parses the market catalog.
 *
 * The catalog is ATAK's own product.inf format so that one hosted tree serves
 * both this plugin and anyone who pastes the market URL into ATAK's built-in
 * Update Server setting.
 */
public final class MarketCatalog {

    private static final String TAG = "TakwerxMarket.Catalog";

    /** Minimum column count ATAK itself accepts for a full entry. */
    private static final int MIN_COLUMNS = 12;

    private MarketCatalog() {
    }

    /**
     * Ask for the catalog built for this exact ATAK first, then the catalog that
     * carries everything. The per-version path is how one URL serves a fleet on
     * mixed ATAK releases; ATAK's own remote provider looks in the same place.
     */
    public static List<MarketEntry> fetch(String baseUrl, String pluginApi) throws IOException {
        String base = stripTrailingSlash(baseUrl);
        IOException first = null;

        String versionDir = versionDirectory(pluginApi);
        if (versionDir != null) {
            try {
                return parse(MarketHttp.getText(base + "/" + versionDir + "/product.inf"));
            } catch (IOException e) {
                first = e;
                Log.d(TAG, "no per-version catalog at " + versionDir + ", falling back");
            }
        }

        try {
            return parse(MarketHttp.getText(base + "/product.inf"));
        } catch (IOException e) {
            throw first != null ? first : e;
        }
    }

    /** "com.atakmap.app@5.8.0.CIV" -> "5.8.0.CIV" */
    static String versionDirectory(String pluginApi) {
        if (pluginApi == null)
            return null;
        int at = pluginApi.indexOf('@');
        if (at < 0 || at == pluginApi.length() - 1)
            return null;
        String dir = pluginApi.substring(at + 1);
        // Belt and braces: this becomes part of a URL path, so allow nothing that
        // could climb out of it.
        return dir.matches("[A-Za-z0-9._]+") && !dir.contains("..") ? dir : null;
    }

    public static List<MarketEntry> parse(String text) {
        List<MarketEntry> out = new ArrayList<>();
        if (text == null)
            return out;

        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.length() == 0 || line.startsWith("#"))
                continue;

            MarketEntry e = parseLine(line);
            if (e != null)
                out.add(e);
            else
                Log.w(TAG, "unable to parse a catalog line");
        }
        return out;
    }

    static MarketEntry parseLine(String line) {
        String[] c = line.split(",", -1);
        if (c.length < MIN_COLUMNS)
            return null;

        try {
            String platform = unescape(c[0]);
            if (!"Android".equalsIgnoreCase(platform))
                return null;

            return new MarketEntry(
                    platform,
                    unescape(c[1]),
                    unescape(c[2]),
                    unescape(c[3]),
                    unescape(c[4]),
                    parseInt(c[5], -1),
                    unescape(c[6]),
                    unescape(c[7]),
                    unescape(c[8]),
                    unescape(c[9]).toLowerCase(java.util.Locale.US),
                    parseInt(c[10], 21),
                    unescape(c[11]),
                    c.length > 12 ? parseLong(c[12], -1) : -1);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The format is comma separated with no quoting, so a comma inside a field is
     * carried as a backslash-u-002c escape and a line break as a backslash-n.
     * ATAK's own writer does the same, so a catalog we generate stays readable by
     * ATAK's native update-server path.
     */
    static String unescape(String s) {
        if (s == null)
            return "";
        return s.trim()
                .replace("\\u002c", ",")
                .replace("\\n", "\n");
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String stripTrailingSlash(String s) {
        while (s != null && s.endsWith("/"))
            s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Fill in what is installed on this device, then order the list for display. */
    public static void resolveInstalled(Context context, List<MarketEntry> entries) {
        PackageManager pm = context.getPackageManager();
        for (MarketEntry e : entries) {
            e.installed = false;
            e.installedRevision = -1;
            e.installedVersion = null;
            e.installedPluginApi = null;
            try {
                PackageInfo pi = pm.getPackageInfo(e.packageName, 0);
                e.installed = true;
                e.installedRevision = pi.versionCode;
                e.installedVersion = pi.versionName;
                // Which ATAK the installed build was made for. Same manifest
                // meta-data ATAK itself reads to decide whether a plugin loads.
                try {
                    android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(
                            e.packageName, PackageManager.GET_META_DATA);
                    if (ai.metaData != null)
                        e.installedPluginApi = ai.metaData.getString("plugin-api");
                } catch (Exception meta) {
                    // Unknown target is treated as matching; the version
                    // comparison still applies.
                }
            } catch (PackageManager.NameNotFoundException nf) {
                // not installed; the default already says so
            }
        }
    }

    /** Updates first, then things you could install, then what is already current. */
    public static void sortForDisplay(List<MarketEntry> entries, final String pluginApi) {
        Collections.sort(entries, new Comparator<MarketEntry>() {
            @Override
            public int compare(MarketEntry a, MarketEntry b) {
                int rank = rank(a) - rank(b);
                if (rank != 0)
                    return rank;
                return a.label.compareToIgnoreCase(b.label);
            }

            private int rank(MarketEntry e) {
                switch (e.status(pluginApi)) {
                    case UPDATE_AVAILABLE:
                        return 0;
                    case NOT_INSTALLED:
                        return 1;
                    case INSTALLED:
                        return 2;
                    default:
                        return 3;
                }
            }
        });
    }

    public static int countUpdates(List<MarketEntry> entries, String pluginApi) {
        int n = 0;
        for (MarketEntry e : entries) {
            if (e.status(pluginApi) == MarketEntry.Status.UPDATE_AVAILABLE)
                n++;
        }
        return n;
    }
}
