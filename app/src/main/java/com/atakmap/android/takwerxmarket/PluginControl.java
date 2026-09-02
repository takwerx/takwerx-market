package com.atakmap.android.takwerxmarket;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.atak.plugins.impl.AtakPluginRegistry;
import com.atakmap.coremap.log.Log;

/**
 * Load, unload and uninstall — the three things ATAK's own plugin manager offers.
 *
 * Uninstall is a plain Android intent. ATAK holds REQUEST_DELETE_PACKAGES and a
 * plugin runs in ATAK's process, so nothing ATAK-specific is needed and nothing
 * here can be broken by an ATAK release.
 *
 * Load and unload have no Android equivalent — "loaded into ATAK" is ATAK's own
 * concept — so they go through AtakPluginRegistry. That is a com.atak.plugins.impl
 * class, and official ATAK is obfuscated, so every call is guarded: if the method
 * is gone the feature reports itself unavailable instead of taking ATAK down. The
 * rest of the market keeps working either way.
 */
public final class PluginControl {

    private static final String TAG = "TakwerxMarket.Control";

    private PluginControl() {
    }

    /** TRUE/FALSE if known, null if ATAK's registry could not be reached. */
    public static Boolean isLoaded(String packageName) {
        try {
            AtakPluginRegistry reg = AtakPluginRegistry.get();
            if (reg == null)
                return null;
            return reg.isPluginLoaded(packageName) ? Boolean.TRUE : Boolean.FALSE;
        } catch (Throwable t) {
            Log.w(TAG, "registry unavailable for isLoaded: " + t);
            return null;
        }
    }

    /** @return true when ATAK accepted the change. */
    public static boolean setLoaded(String packageName, boolean load) {
        try {
            AtakPluginRegistry reg = AtakPluginRegistry.get();
            if (reg == null)
                return false;
            return load ? reg.loadPlugin(packageName) : reg.unloadPlugin(packageName);
        } catch (Throwable t) {
            Log.w(TAG, "registry unavailable for setLoaded: " + t);
            return false;
        }
    }

    /**
     * Hand the package to Android's uninstaller. Android shows its own
     * confirmation and we never learn the outcome, so callers should re-read
     * what is installed rather than assume.
     */
    public static boolean uninstall(Context hostContext, String packageName) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE,
                    Uri.parse("package:" + packageName));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            hostContext.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "could not start the uninstaller for " + packageName, e);
            return false;
        }
    }
}
