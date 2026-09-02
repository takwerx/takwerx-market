package com.atakmap.android.takwerxmarket;

/**
 * Comparing plugin versions, which cannot be done with versionCode.
 *
 * tak.gov builds from a source zip with no .git, so getVersionCode() falls
 * through to 1 on EVERY signed release. Measured against the live catalog on
 * 2026-09-01: PLSS 0.5, Traffic 0.5, Map Depot 1.4 and Cam Depot 1.1 all report
 * revision 1. A market that compared revisions could therefore never report an
 * update, and would have looked like it was working.
 *
 * So the comparison is on the version NAME, whose leading token is the
 * PLUGIN_VERSION the release was cut under. The rest of an ATAK plugin's
 * versionName — "1.4 (68c423b8) - [5.8.0]" — is the git stamp, blank on signed
 * builds, and the ATAK target, which the catalog already filters on.
 */
public final class PluginVersion {

    private PluginVersion() {
    }

    /** "1.4 (68c423b8) - [5.8.0]" -> "1.4"; "1.4" -> "1.4"; null -> null. */
    public static String number(String versionName) {
        if (versionName == null)
            return null;
        String s = versionName.trim();
        int space = s.indexOf(' ');
        if (space > 0)
            s = s.substring(0, space);
        return s.length() == 0 ? null : s;
    }

    /**
     * Compare two plugin version names. Negative when a is older than b.
     *
     * Dotted numeric components, compared left to right, with a missing
     * component treated as zero so "1.4" and "1.4.0" are equal. A component that
     * is not a number falls back to a string comparison of that component, which
     * keeps something sensible happening rather than throwing.
     */
    public static int compare(String aName, String bName) {
        String a = number(aName);
        String b = number(bName);
        if (a == null && b == null)
            return 0;
        if (a == null)
            return -1;
        if (b == null)
            return 1;

        String[] ap = a.split("\\.");
        String[] bp = b.split("\\.");
        int n = Math.max(ap.length, bp.length);

        for (int i = 0; i < n; i++) {
            String as = i < ap.length ? ap[i] : "0";
            String bs = i < bp.length ? bp[i] : "0";

            Integer ai = asInt(as);
            Integer bi = asInt(bs);
            int c;
            if (ai != null && bi != null)
                c = ai.compareTo(bi);
            else
                c = as.compareTo(bs);
            if (c != 0)
                return c;
        }
        return 0;
    }

    /** True when the catalog is offering something newer than what is installed. */
    public static boolean isNewer(String candidate, String installed) {
        return compare(candidate, installed) > 0;
    }

    private static Integer asInt(String s) {
        try {
            return Integer.valueOf(Integer.parseInt(s.trim()));
        } catch (Exception e) {
            return null;
        }
    }
}
