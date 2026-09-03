package com.atakmap.android.takwerxmarket;

/**
 * One line of the market catalog, plus what we learned about it from the device.
 *
 * The catalog is ATAK's own product.inf format, so the same file also works if a
 * user pastes the market URL into ATAK's Update Server setting. Columns, from
 * ATAK's own assets/apks/product.inf header:
 *
 *   platform, type, full package name, display/label, version, revision code,
 *   relative path to APK, relative path to icon, description, apk hash,
 *   os requirement, tak prereq (plugin-api), apk size
 */
public class MarketEntry {

    public enum Status {
        /** Built for a different ATAK; offered greyed out with the reason. */
        INCOMPATIBLE,
        NOT_INSTALLED,
        INSTALLED,
        UPDATE_AVAILABLE
    }

    public final String platform;
    public final String type;
    public final String packageName;
    public final String label;
    public final String version;
    public final int revision;
    public final String apkPath;
    public final String iconPath;
    public final String description;
    public final String hash;
    public final int osRequirement;
    public final String takRequirement;
    public final long size;

    public boolean installed;

    /**
     * Whether ATAK currently has this plugin loaded. TRUE, FALSE, or null when
     * ATAK's registry could not be reached — null is "do not claim", not "no".
     * Set by the view rather than the catalog, so the catalog layer stays free
     * of ATAK internals.
     */
    public Boolean loaded;
    /** Kept for the record; not used to detect updates. See {@link PluginVersion}. */
    public int installedRevision = -1;
    public String installedVersion;

    /**
     * The plugin-api the INSTALLED APK declares, e.g. "com.atakmap.app@5.7.0.CIV",
     * or null when it could not be read. Distinct from takRequirement, which is
     * what the catalog's build declares.
     */
    public String installedPluginApi;

    /**
     * Installed, but built for an ATAK other than the one running. Such a
     * plugin cannot load here whatever its version number says, and ATAK's own
     * package manager marks it incompatible. The version comparison alone
     * cannot see this: after an ATAK upgrade from 5.7 to 5.8, PLSS 0.5 built
     * for 5.7 and the catalog's 0.5 built for 5.8 are the same number, and the
     * row would go green over a plugin that will never load again.
     */
    public boolean installedForOtherAtak(String runningPluginApi) {
        return installed && installedPluginApi != null && runningPluginApi != null
                && !installedPluginApi.equalsIgnoreCase(runningPluginApi);
    }

    /**
     * The catalog row for ATAK itself: type "app", ATAK's own package. It has no
     * plugin-api requirement, so it is "compatible" with every ATAK, and its
     * installed version is whatever ATAK this code is running inside.
     */
    public boolean isAtak() {
        return !isPlugin() && packageName != null && packageName.startsWith("com.atakmap.app");
    }

    /** "com.atakmap.app@5.7.0.CIV" -> "5.7.0.CIV"; null -> null. */
    public static String atakOf(String pluginApi) {
        if (pluginApi == null)
            return null;
        int at = pluginApi.indexOf('@');
        return at < 0 ? pluginApi : pluginApi.substring(at + 1);
    }

    public MarketEntry(String platform, String type, String packageName, String label,
            String version, int revision, String apkPath, String iconPath,
            String description, String hash, int osRequirement, String takRequirement,
            long size) {
        this.platform = platform;
        this.type = type;
        this.packageName = packageName;
        this.label = label;
        this.version = version;
        this.revision = revision;
        this.apkPath = apkPath;
        this.iconPath = iconPath;
        this.description = description;
        this.hash = hash;
        this.osRequirement = osRequirement;
        this.takRequirement = takRequirement;
        this.size = size;
    }

    public boolean isPlugin() {
        return "plugin".equalsIgnoreCase(type) || "systemplugin".equalsIgnoreCase(type);
    }

    /**
     * A plugin is offered only when it was built for exactly the ATAK we are
     * running. ATAK's own matcher is looser in places, but "we know this loads"
     * is the only claim worth putting in front of an operator.
     */
    public boolean isCompatibleWith(String runningPluginApi) {
        if (!isPlugin())
            return true;                 // plain apps carry no plugin-api requirement
        if (takRequirement == null || takRequirement.length() == 0)
            return false;
        return takRequirement.equalsIgnoreCase(runningPluginApi);
    }

    /**
     * Note that this compares version NAMES, not revision codes. Every
     * tak.gov-signed release reports versionCode 1, so a revision comparison can
     * never see an update. See {@link PluginVersion}.
     */
    public Status status(String runningPluginApi) {
        if (!isCompatibleWith(runningPluginApi))
            return Status.INCOMPATIBLE;
        if (!installed)
            return Status.NOT_INSTALLED;
        // The catalog's build for THIS ATAK replaces a build for another one,
        // whatever the numbers say. Android accepts the replace: same package,
        // same signer, and tak.gov builds all carry versionCode 1.
        if (installedForOtherAtak(runningPluginApi))
            return Status.UPDATE_AVAILABLE;
        return PluginVersion.isNewer(version, installedVersion)
                ? Status.UPDATE_AVAILABLE
                : Status.INSTALLED;
    }

    /** The ATAK release this entry was built for, e.g. "5.7.0", for display. */
    public String builtForAtak() {
        if (takRequirement == null)
            return null;
        int at = takRequirement.indexOf('@');
        return at < 0 ? takRequirement : takRequirement.substring(at + 1);
    }
}
