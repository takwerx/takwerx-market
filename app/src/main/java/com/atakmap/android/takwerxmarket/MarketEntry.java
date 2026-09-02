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
    /** Kept for the record; not used to detect updates. See {@link PluginVersion}. */
    public int installedRevision = -1;
    public String installedVersion;

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
