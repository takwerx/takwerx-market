package com.atakmap.android.takwerxmarket;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceManager;

import com.atakmap.android.preference.PluginPreferenceFragment;
import com.atakmap.android.takwerxmarket.plugin.R;
import com.atakmap.android.util.PdfHelper;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;

/**
 * The plugin's entry in Settings -> Tool Preferences.
 *
 * This is the only way a user can reach the manual. A PDF compiled into
 * assets/ with no preferences entry ships inside the APK and cannot be opened by
 * anybody — which has happened before, undetected, precisely because the file
 * WAS in the APK. Check this by opening Settings on a device, never by unzipping.
 */
public class MarketPreferenceFragment extends PluginPreferenceFragment {

    private static final String TAG = "TakwerxMarket.Prefs";

    public static final String KEY = "takwerxmarket_preferences";

    private static final String USER_GUIDE = "usermanual.pdf";
    private static final String EXTRACTED = FileSystemUtils.getRoot()
            + File.separator + "tools" + File.separator + "takwerxmarket"
            + File.separator + "TAKWERX Market User Guide.pdf";

    /** Remembers which release's manual was last written to EXTRACTED. */
    private static final String PREF_MANUAL_VERSION = "takwerxmarket_manual_version";

    private static Context pluginContext;

    public MarketPreferenceFragment() {
        super(pluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public MarketPreferenceFragment(Context context) {
        super(context, R.xml.preferences);
        pluginContext = context;
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", "TAKWERX Market");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Preference manual = findPreference("manual");
        if (manual == null)
            return;

        manual.setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        dropStaleManual();
                        PdfHelper.extractAndShow(pluginContext, getActivity(),
                                USER_GUIDE, EXTRACTED, true);
                        return true;
                    }
                });
    }

    /**
     * Delete the extracted PDF when it belongs to a different release.
     *
     * PdfHelper.extractAndShow decides whether to re-extract by comparing
     * versionCode — and every tak.gov-signed build reports versionCode 1, because
     * the submission is a zip with no .git for getVersionCode() to read. So the
     * first manual a user ever opens is the manual they keep forever, no matter
     * how many releases follow. Comparing the version NAME ourselves is the only
     * way out, and it has to happen before extractAndShow is called.
     */
    private void dropStaleManual() {
        if (pluginContext == null)
            return;
        try {
            String current = pluginContext.getPackageManager()
                    .getPackageInfo(pluginContext.getPackageName(), 0).versionName;

            SharedPreferences prefs = PreferenceManager
                    .getDefaultSharedPreferences(getActivity());
            String written = prefs.getString(PREF_MANUAL_VERSION, null);

            if (current != null && current.equals(written))
                return;

            File stale = new File(EXTRACTED);
            if (stale.exists() && !stale.delete())
                Log.w(TAG, "could not remove the stale manual at " + EXTRACTED);
            else if (stale.exists())
                Log.d(TAG, "removed the manual from " + written);

            prefs.edit().putString(PREF_MANUAL_VERSION, current).apply();
        } catch (Exception e) {
            // Never let manual housekeeping stop the manual from opening.
            Log.w(TAG, "could not check the extracted manual: " + e.getMessage());
        }
    }
}
