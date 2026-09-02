package com.atakmap.android.takwerxmarket;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.takwerxmarket.plugin.R;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * The market pane: fetch the catalog, show what is on offer for the ATAK we are
 * running, and install on request.
 *
 * All ATAK-facing UI — dialogs and toasts — uses the host context. A plugin
 * context throws BadTokenException and takes ATAK down with it.
 */
public class MarketView implements MarketAdapter.ActionListener {

    private static final String TAG = "TakwerxMarket.View";

    private final Context pluginContext;
    private final Context hostContext;
    private final String baseUrl;
    private final String pluginApi;

    private final View root;
    private final TextView statusView;
    private final TextView footnote;
    private final Button refresh;
    private final MarketAdapter adapter;

    private final List<MarketEntry> entries = new ArrayList<>();
    private boolean busy;

    /**
     * Keeps the rows honest while the pane stays open.
     *
     * Android's installer runs in its own process and we never learn its outcome,
     * so without this a row still reads "Install" after the install finished —
     * until the operator closes the pane or taps Refresh. Watching the package
     * broadcasts is the only way to see it happen.
     *
     * Registered at runtime rather than in the manifest, which is what keeps it
     * working on Android 8 and up: implicit broadcasts declared in a manifest are
     * blocked there, runtime-registered ones are not.
     */
    private final BroadcastReceiver packageWatcher = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            root.post(new Runnable() {
                @Override
                public void run() {
                    refreshInstalledState();
                }
            });
        }
    };

    public MarketView(Context pluginContext, Context hostContext, String baseUrl,
            String pluginApi) {
        this.pluginContext = pluginContext;
        this.hostContext = hostContext;
        this.baseUrl = baseUrl;
        this.pluginApi = pluginApi;

        root = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
        statusView = root.findViewById(R.id.market_status);
        footnote = root.findViewById(R.id.market_footnote);
        refresh = root.findViewById(R.id.market_refresh);

        adapter = new MarketAdapter(pluginContext, pluginApi, this);
        ListView list = root.findViewById(R.id.market_list);
        list.setAdapter(adapter);

        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        hostContext.registerReceiver(packageWatcher, f);
    }

    /** Called when the plugin stops. Leaving the receiver registered leaks it. */
    public void dispose() {
        try {
            hostContext.unregisterReceiver(packageWatcher);
        } catch (Exception e) {
            Log.d(TAG, "package watcher was not registered");
        }
    }

    public View getRoot() {
        return root;
    }

    /** Fetch the catalog on a background thread and repaint when it lands. */
    public void load() {
        if (busy)
            return;
        busy = true;
        refresh.setEnabled(false);
        statusView.setText(R.string.market_loading);

        new Thread(new Runnable() {
            @Override
            public void run() {
                List<MarketEntry> fetched = null;
                String error = null;
                try {
                    fetched = MarketCatalog.fetch(baseUrl, pluginApi);
                    MarketCatalog.resolveInstalled(hostContext, fetched);
                    MarketCatalog.sortForDisplay(fetched, pluginApi);
                } catch (Exception e) {
                    Log.w(TAG, "catalog fetch failed: " + e.getMessage(), e);
                    error = e.getMessage();
                }
                final List<MarketEntry> result = fetched;
                final String err = error;
                root.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        refresh.setEnabled(true);
                        if (result == null) {
                            // The exception text is for the log, not for someone
                            // standing at a truck. Say what they can act on.
                            statusView.setText("Could not reach the market — check "
                                    + "your connection, then tap Refresh");
                            footnote.setVisibility(View.GONE);
                            return;
                        }
                        entries.clear();
                        entries.addAll(result);
                        adapter.setEntries(entries);
                        paintSummary();
                    }
                });
            }
        }, "takwerx-market-catalog").start();
    }

    /**
     * Say what is on offer and what is not. A list that silently omits everything
     * built for another ATAK reads as "there is nothing else", which is wrong.
     */
    private void paintSummary() {
        int offered = 0;
        int updates = 0;
        int otherAtak = 0;
        for (MarketEntry e : entries) {
            switch (e.status(pluginApi)) {
                case UPDATE_AVAILABLE:
                    updates++;
                    offered++;
                    break;
                case INCOMPATIBLE:
                    otherAtak++;
                    break;
                default:
                    offered++;
                    break;
            }
        }

        String atak = pluginApi == null ? "this ATAK"
                : "ATAK " + pluginApi.substring(pluginApi.indexOf('@') + 1);
        StringBuilder sb = new StringBuilder();
        sb.append(offered).append(offered == 1 ? " plugin for " : " plugins for ").append(atak);
        if (updates > 0)
            sb.append(" · ").append(updates).append(updates == 1 ? " update" : " updates");
        statusView.setText(sb.toString());

        // A tappable row nobody knows is tappable is not a feature.
        int installed = 0;
        for (MarketEntry e : entries) {
            if (e.installed)
                installed++;
        }

        if (otherAtak > 0) {
            footnote.setText(otherAtak + (otherAtak == 1
                    ? " more plugin is built for a different ATAK release and cannot be installed here."
                    : " more plugins are built for different ATAK releases and cannot be installed here."));
            footnote.setVisibility(View.VISIBLE);
        } else if (installed > 0) {
            footnote.setText("Tap a plugin to load, unload or uninstall it.");
            footnote.setVisibility(View.VISIBLE);
        } else {
            footnote.setVisibility(View.GONE);
        }
    }

    @Override
    public void onInstall(final MarketEntry entry) {
        if (busy)
            return;
        busy = true;
        refresh.setEnabled(false);
        statusView.setText("Downloading " + entry.label + " " + entry.version + "…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final ApkInstaller.Result r = ApkInstaller.fetchAndInstall(
                        hostContext, baseUrl, entry, null);
                root.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        refresh.setEnabled(true);
                        if (!r.ok) {
                            statusView.setText(r.message);
                            Toast.makeText(hostContext, r.message, Toast.LENGTH_LONG).show();
                        } else {
                            paintSummary();
                        }
                    }
                });
            }
        }, "takwerx-market-install").start();
    }

    /**
     * Load, unload or uninstall an installed plugin — the same three things ATAK's
     * own plugin manager offers, without leaving the market.
     *
     * Built on the host context. An AlertDialog on a plugin context throws
     * BadTokenException and takes ATAK down with it.
     */
    @Override
    public void onManage(final MarketEntry entry) {
        final Boolean loaded = PluginControl.isLoaded(entry.packageName);

        final String toggle;
        if (loaded == null)
            toggle = null;                       // registry unreachable; hide it
        else
            toggle = loaded ? "Unload from ATAK" : "Load into ATAK";

        final java.util.List<String> items = new ArrayList<>();
        if (toggle != null)
            items.add(toggle);
        items.add("Uninstall " + entry.label);

        new AlertDialog.Builder(hostContext)
                .setTitle(entry.label + "  " + PluginVersion.number(entry.installedVersion))
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String chosen = items.get(which);
                        if (chosen.equals(toggle)) {
                            boolean want = !Boolean.TRUE.equals(loaded);
                            if (PluginControl.setLoaded(entry.packageName, want)) {
                                statusView.setText(entry.label
                                        + (want ? " loaded into ATAK" : " unloaded from ATAK"));
                            } else {
                                Toast.makeText(hostContext,
                                        "ATAK would not " + (want ? "load " : "unload ")
                                                + entry.label,
                                        Toast.LENGTH_LONG).show();
                            }
                        } else {
                            // Android runs its own confirmation and never tells us
                            // the outcome, so re-read rather than assume.
                            PluginControl.uninstall(hostContext, entry.packageName);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Re-read what is installed, without going back to the network. */
    public void refreshInstalledState() {
        if (entries.isEmpty())
            return;
        MarketCatalog.resolveInstalled(hostContext, entries);
        MarketCatalog.sortForDisplay(entries, pluginApi);
        adapter.setEntries(entries);
        paintSummary();
    }
}
