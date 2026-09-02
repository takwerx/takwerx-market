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

    /** Override for testing against a staging tree, read from ATAK's preferences. */
    private static final String PREF_BASE_URL = "takwerxmarket_url";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane marketPane;

    private MarketView marketView;

    public TakwerxMarket(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
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
        Context host = hostContext();
        if (host == null)
            return DEFAULT_BASE_URL;
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(host);
            String url = prefs.getString(PREF_BASE_URL, null);
            if (url != null && url.trim().length() > 0)
                return url.trim();
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
                    if (updates <= 0)
                        return;

                    new android.os.Handler(host.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(host,
                                    updates == 1
                                            ? "Takwerx Market: 1 update available"
                                            : "Takwerx Market: " + updates + " updates available",
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
