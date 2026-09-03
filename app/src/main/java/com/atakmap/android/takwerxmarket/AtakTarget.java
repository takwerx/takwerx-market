package com.atakmap.android.takwerxmarket;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * The plugin-api string of the ATAK build we are actually running inside, e.g.
 * "com.atakmap.app@5.8.0.CIV". This is the value a plugin declares in its
 * manifest and the value ATAK matches against before it will load anything, so
 * it is also what the market catalog is filtered on.
 *
 * Derived entirely from Android's PackageManager. Nothing here reaches into
 * com.atakmap internals, which is deliberate: ATAK's own classes are obfuscated
 * in official builds and this is the one fact the whole plugin depends on.
 */
public final class AtakTarget {

    public static final String PREFIX = "com.atakmap.app@";

    private AtakTarget() {
    }

    /**
     * @param hostContext ATAK's own context
     * @param pluginContext this plugin's context
     * @return e.g. "com.atakmap.app@5.8.0.CIV", or null if it cannot be determined
     */
    public static String pluginApi(Context hostContext, Context pluginContext) {
        String api = fromHost(hostContext);
        if (api != null)
            return api;
        // Fall back to what we ourselves declare. ATAK only loaded us because it
        // matched, so it is a correct answer even if it is a less direct one.
        return fromOwnManifest(pluginContext);
    }

    private static String fromHost(Context hostContext) {
        if (hostContext == null)
            return null;
        try {
            String pkg = hostContext.getPackageName();          // com.atakmap.app.civ
            if (pkg == null || !pkg.startsWith("com.atakmap.app"))
                return null;

            String versionName = hostContext.getPackageManager()
                    .getPackageInfo(pkg, 0).versionName;         // 5.8.0.3 (4f67063)
            String core = coreVersion(versionName);
            if (core == null)
                return null;

            int dot = pkg.lastIndexOf('.');
            if (dot < 0 || dot == pkg.length() - 1)
                return null;
            String flavor = pkg.substring(dot + 1).toUpperCase(java.util.Locale.US);

            return PREFIX + core + "." + flavor;
        } catch (Exception e) {
            return null;
        }
    }

    private static String fromOwnManifest(Context pluginContext) {
        if (pluginContext == null)
            return null;
        try {
            android.os.Bundle meta = pluginContext.getPackageManager()
                    .getApplicationInfo(pluginContext.getPackageName(),
                            PackageManager.GET_META_DATA).metaData;
            return meta == null ? null : meta.getString("plugin-api");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * "5.8.0.3 (4f67063)" -> "5.8.0". ATAK's plugin-api carries only
     * major.minor.subminor; the fourth component is the build and is not part of
     * the compatibility contract.
     */
    /**
     * The plugin-api an ATAK of the given version would report, keeping the
     * running flavor: running "com.atakmap.app@5.7.0.CIV" and an ATAK versionName
     * "5.8.0.4 (174b425)" give "com.atakmap.app@5.8.0.CIV".
     */
    static String apiFor(String runningPluginApi, String atakVersionName) {
        String core = coreVersion(atakVersionName);
        if (core == null || runningPluginApi == null)
            return null;
        int dot = runningPluginApi.lastIndexOf('.');
        String flavor = dot < 0 ? "" : runningPluginApi.substring(dot);
        return PREFIX + core + flavor;
    }

    static String coreVersion(String versionName) {
        if (versionName == null)
            return null;
        StringBuilder sb = new StringBuilder();
        int parts = 0;
        int i = 0;
        while (i < versionName.length() && parts < 3) {
            char c = versionName.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
                i++;
            } else if (c == '.') {
                parts++;
                if (parts < 3)
                    sb.append('.');
                i++;
            } else {
                break;
            }
        }
        String out = sb.toString();
        // Require all three components, e.g. "5.8.0" and not "5.8".
        return out.split("\\.", -1).length == 3 ? out : null;
    }
}
