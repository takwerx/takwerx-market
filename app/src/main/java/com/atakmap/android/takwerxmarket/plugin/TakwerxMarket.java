package com.atakmap.android.takwerxmarket.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.takwerxmarket.ApkInstaller;
import com.atakmap.android.takwerxmarket.AtakTarget;
import com.atakmap.android.takwerxmarket.MarketCatalog;
import com.atakmap.android.takwerxmarket.MarketPreferenceFragment;
import com.atakmap.android.takwerxmarket.MarketEntry;
import com.atakmap.android.takwerxmarket.MarketView;
import com.atakmap.android.takwerxmarket.ToolbarBadge;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.coremap.log.Log;

import java.util.List;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

public class TakwerxMarket implements IPlugin {

    private static final String TAG = "TakwerxMarket";

    /**
     * Cloudflare R2 behind a takwerx.org name. ATAK's own update path also
     * requires HTTPS, so the same tree serves both.
     *
     * Staging home. The permanent name is still to be decided: it should
     * describe the catalog, not this plugin, since ATAK's own Update Server
     * setting can read the same tree — plugins.takwerx.org rather than anything
     * named after this plugin. Needs a bucket and a DNS record; the rclone token is
     * scoped to the mapdepot bucket and cannot create one.
     */
    private static final String DEFAULT_BASE_URL = "https://mapdepot.takwerx.org/depot";

    /**
     * Staging override, DEBUG BUILDS ONLY.
     *
     * A release build reads DEFAULT_BASE_URL and nothing else. This is not a
     * setting anyone should be able to change: ATAK can import preferences from a
     * data package, so a value here need not be one the operator typed, and the
     * market's whole job is to hand the operator something to install. Repointing
     * it at another catalog is the most valuable thing an attacker could do to
     * this plugin — and it is also, separately, not a thing takwerx wants offered.
     *
     * Signer pinning still stands behind this: even a repointed debug build can
     * only install a TAK Product Center-signed APK.
     */
    private static final String PREF_BASE_URL = "takwerxmarket_url";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane marketPane;

    private MarketView marketView;
    private ToolbarBadge badge;

    public TakwerxMarket(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);

        badge = new ToolbarBadge(pluginContext,
                pluginContext.getResources().getDrawable(R.drawable.ic_toolbar));

        Object icon = badge.icon();
        ToolbarItem.Builder builder = (icon instanceof gov.tak.api.commons.graphics.Drawable)
                ? new ToolbarItem.Builder(pluginContext.getString(R.string.app_name),
                        (gov.tak.api.commons.graphics.Drawable) icon)
                : new ToolbarItem.Builder(pluginContext.getString(R.string.app_name),
                        (gov.tak.api.commons.graphics.Bitmap) icon);

        toolbarItem = builder
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                }).setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        if (uiService == null)
            return;
        uiService.addToolbarItem(toolbarItem);
        registerPreferences();
        checkForUpdatesQuietly();
    }

    @Override
    public void onStop() {
        if (uiService == null)
            return;
        uiService.removeToolbarItem(toolbarItem);

        try {
            ToolsPreferenceFragment.unregister(MarketPreferenceFragment.KEY);
        } catch (Throwable t) {
            Log.d(TAG, "could not unregister preferences: " + t);
        }

        if (marketView != null) {
            marketView.dispose();
            marketView = null;
            marketPane = null;
        }
    }

    /**
     * Put the plugin into Settings -> Tool Preferences.
     *
     * Without this the user manual compiled into the APK has no way to be
     * opened, and the catalog URL setting has no way to be seen. Guarded because
     * ToolsPreferenceFragment is an ATAK internal and official ATAK is
     * obfuscated: losing the settings entry should not cost the whole plugin.
     */
    private void registerPreferences() {
        try {
            ToolsPreferenceFragment.register(
                    new ToolsPreferenceFragment.ToolPreference(
                            pluginContext.getString(R.string.app_name),
                            pluginContext.getString(R.string.app_desc),
                            MarketPreferenceFragment.KEY,
                            pluginContext.getResources()
                                    .getDrawable(R.drawable.ic_toolbar),
                            new MarketPreferenceFragment(pluginContext)));
        } catch (Throwable t) {
            Log.w(TAG, "could not register Tool Preferences: " + t);
        }
    }

    private Context hostContext() {
        MapView mv = MapView.getMapView();
        return mv == null ? null : mv.getContext();
    }

    private String baseUrl() {
        if (!BuildConfig.DEBUG)
            return DEFAULT_BASE_URL;

        Context host = hostContext();
        if (host == null)
            return DEFAULT_BASE_URL;
        // DEBUG only: a one-line file a developer can push over adb, since the
        // preference screen for this no longer exists. Release builds never
        // reach this method past the first line.
        try {
            java.io.File f = new java.io.File(android.os.Environment.getExternalStorageDirectory(),
                    "atak/tools/takwerxmarket/catalog-url.txt");
            if (f.isFile()) {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                String line = r.readLine();
                r.close();
                if (line != null && line.trim().startsWith("https://")) {
                    Log.w(TAG, "DEBUG build: catalog from " + f.getName());
                    return line.trim();
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "no catalog-url.txt override");
        }
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(host);
            String url = prefs.getString(PREF_BASE_URL, null);
            if (url != null && url.trim().length() > 0) {
                Log.w(TAG, "DEBUG build: reading a non-default catalog");
                return url.trim();
            }
        } catch (Exception e) {
            Log.d(TAG, "no base URL override set");
        }
        return DEFAULT_BASE_URL;
    }

    private void showPane() {
        final Context host = hostContext();
        if (host == null) {
            Log.w(TAG, "no MapView yet, cannot show the market");
            return;
        }

        if (marketPane == null) {
            marketView = new MarketView(pluginContext, host, baseUrl(),
                    AtakTarget.pluginApi(host, pluginContext));
            marketView.setUpdateCountListener(new MarketView.UpdateCountListener() {
                @Override
                public void onUpdateCount(int updates) {
                    if (badge != null)
                        badge.setCount(updates);
                }
            });

            marketPane = new PaneBuilder(marketView.getRoot())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.6D)
                    .build();

            marketView.load();
        } else {
            // Coming back to an open market after an install: the catalog is still
            // good, only what is on the device has changed.
            marketView.refreshInstalledState();
        }

        if (!uiService.isPaneVisible(marketPane))
            uiService.showPane(marketPane, null);
    }

    /**
     * One toast at startup if anything is out of date. Deliberately quiet
     * otherwise — a plugin that reports "nothing to do" every launch gets ignored.
     */
    private void checkForUpdatesQuietly() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Context host = hostContext();
                if (host == null)
                    return;

                // Nothing from a previous session can still be installing.
                ApkInstaller.purgeDownloads(host);

                try {
                    String api = AtakTarget.pluginApi(host, pluginContext);
                    List<MarketEntry> entries = MarketCatalog.fetch(baseUrl(), api);
                    MarketCatalog.resolveInstalled(host, entries);
                    final int updates = MarketCatalog.countUpdates(entries, api);

                    new android.os.Handler(host.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (badge != null)
                                badge.setCount(updates);
                        }
                    });

                    if (updates <= 0)
                        return;

                    new android.os.Handler(host.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(host,
                                    updates == 1
                                            ? "TAKwerx Market: 1 update available"
                                            : "TAKwerx Market: " + updates + " updates available",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception e) {
                    // A market that cannot be reached at startup is not worth
                    // interrupting anyone over.
                    Log.d(TAG, "startup update check did not complete: " + e.getMessage());
                }
            }
        }, "takwerx-market-startup").start();
    }
}
