package com.atakmap.android.takwerxmarket;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
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

    /** Told the number of updates waiting whenever the pane recomputes it. */
    public interface UpdateCountListener {
        void onUpdateCount(int updates);
    }

    private UpdateCountListener updateCountListener;

    public void setUpdateCountListener(UpdateCountListener l) {
        this.updateCountListener = l;
    }

    /** Match MarketAdapter's colours; the header and the rows are one idea. */
    private static final int AMBER = 0xFFFFB300;
    private static final int GREEN = 0xFF8BC34A;

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

    /** Package handed to Android and not yet reported back on. */
    private String installing;

    /**
     * Loading a plugin raises no broadcast, so there was nothing to notice when
     * ATAK loaded something after an install — the row kept saying UNLOADED
     * until it happened to be redrawn. ATAK does record it though: it writes a
     * preference per plugin, keyed on AtakPluginRegistry.pluginLoadedBasename
     * ("plugin.version.loaded."), and a preference change is observable.
     *
     * Match on the PREFIX only. Measured 2026-09-02, the key is suffixed with
     * the plugin's display name, not its package -- "plugin.version.loaded.Cam
     * Depot". Matching on package name would compile, read sensibly, and never
     * once fire.
     *
     * Held in a field on purpose. SharedPreferences keeps only a weak reference
     * to its listeners, so one that is not retained is collected and simply
     * stops firing, with nothing to indicate why.
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener loadWatcher =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    if (key == null || !key.startsWith(loadedKeyPrefix()))
                        return;
                    root.post(new Runnable() {
                        @Override
                        public void run() {
                            refreshInstalledState();
                        }
                    });
                }
            };

    /** ATAK's own constant where it can be reached, its literal value otherwise. */
    private static String loadedKeyPrefix() {
        try {
            return com.atak.plugins.impl.AtakPluginRegistry.pluginLoadedBasename;
        } catch (Throwable t) {
            return "plugin.version.loaded.";
        }
    }

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
            final String pkg = intent.getData() == null ? null
                    : intent.getData().getSchemeSpecificPart();
            root.post(new Runnable() {
                @Override
                public void run() {
                    if (pkg != null && pkg.equals(installing))
                        clearInstalling();
                    refreshInstalledState();
                }
            });
        }
    };

    /** How long a row says "Installing…" with no word from Android. */
    private static final long INSTALL_WAIT_MS = 90_000;

    private void clearInstalling() {
        installing = null;
        adapter.setDownloading(null, 0);
    }

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

        try {
            PreferenceManager.getDefaultSharedPreferences(hostContext)
                    .registerOnSharedPreferenceChangeListener(loadWatcher);
        } catch (Exception e) {
            Log.d(TAG, "could not watch plugin load state: " + e.getMessage());
        }
    }

    /** Called when the plugin stops. Leaving these registered leaks them. */
    public void dispose() {
        try {
            hostContext.unregisterReceiver(packageWatcher);
        } catch (Exception e) {
            Log.d(TAG, "package watcher was not registered");
        }
        try {
            PreferenceManager.getDefaultSharedPreferences(hostContext)
                    .unregisterOnSharedPreferenceChangeListener(loadWatcher);
        } catch (Exception e) {
            Log.d(TAG, "load watcher was not registered");
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
                    resolveLoaded(fetched);
                    MarketCatalog.sortForDisplay(fetched, pluginApi);
                    // Still on the background thread: the icons are a network
                    // fetch on first run and must not touch the main one.
                    IconCache.warm(hostContext, baseUrl, fetched);
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
        int installed = 0;
        for (MarketEntry e : entries) {
            if (e.installed)
                installed++;
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

        // The toolbar badge is driven from here, not from the startup check
        // alone. Setting it only at start left "3" on the icon after all three
        // had been updated, while the header underneath said nothing to update.
        if (updateCountListener != null)
            updateCountListener.onUpdateCount(updates);

        String atak = pluginApi == null ? "this ATAK"
                : "ATAK " + pluginApi.substring(pluginApi.indexOf('@') + 1);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(String.valueOf(offered))
                .append(offered == 1 ? " plugin for " : " plugins for ").append(atak);
        if (updates > 0) {
            // Amber, the same colour the rows use for a pending update, so the
            // count and the rows it refers to read as one thing.
            int at = sb.length();
            sb.append("  ·  ").append(String.valueOf(updates))
                    .append(updates == 1 ? " update" : " updates");
            sb.setSpan(new ForegroundColorSpan(AMBER), at, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (installed > 0) {
            // Only claim this when something is actually installed. With four
            // plugins offered and none installed there are zero updates, but
            // nothing is up to date either, and saying so would be a lie told in
            // green — the one colour an operator will not stop to question.
            int at = sb.length();
            sb.append("  ·  ").append(installed == offered
                    ? "all up to date" : "nothing to update");
            sb.setSpan(new ForegroundColorSpan(GREEN), at, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        statusView.setText(sb);

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
        adapter.setDownloading(entry.packageName, 0);

        // Progress arrives every few kilobytes, which is far more often than a
        // screen can usefully change. Repaint only when the whole percent moves.
        final MarketHttp.Progress progress = new MarketHttp.Progress() {
            private int lastPercent = -2;

            @Override
            public void onProgress(long bytesRead, long total) {
                final int pct = total > 0
                        ? (int) Math.min(100, bytesRead * 100 / total)
                        : -1;
                if (pct == lastPercent)
                    return;
                lastPercent = pct;
                root.post(new Runnable() {
                    @Override
                    public void run() {
                        adapter.setDownloading(entry.packageName, pct);
                    }
                });
            }
        };

        new Thread(new Runnable() {
            @Override
            public void run() {
                final ApkInstaller.Result r = ApkInstaller.fetchAndInstall(
                        hostContext, baseUrl, entry, progress);
                root.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        refresh.setEnabled(true);
                        if (r.ok) {
                            // Handed to Android. The row keeps saying so until
                            // the package broadcast arrives, so ATAK's own
                            // "uninstalled" toast during a replace is visibly
                            // contradicted by the row it refers to. Nothing
                            // arrives if the operator cancels, so the row
                            // gives up on its own after a while.
                            installing = entry.packageName;
                            adapter.setInstalling(entry.packageName);
                            root.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (entry.packageName.equals(installing))
                                        clearInstalling();
                                }
                            }, INSTALL_WAIT_MS);
                            return;
                        }
                        adapter.setDownloading(null, 0);
                        if (r.signerConflict) {
                            offerUninstall(entry, r.message);
                        } else {
                            statusView.setText(r.message);
                            Toast.makeText(hostContext, r.message, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "takwerx-market-install").start();
    }

    /**
     * A signing-key clash is the one install failure the operator can clear, so
     * offer to clear it instead of telling them to go and do it somewhere else.
     *
     * Removing it is all that is offered: once Android is done the package
     * watcher flips the row to Install on its own, so the next step is in front
     * of them. Chaining an automatic reinstall would mean holding an intention
     * across two Android confirmations and a process boundary, which is more
     * ways to get it wrong than it saves taps.
     */
    private void offerUninstall(final MarketEntry entry, String why) {
        // Short here, detail in the dialog. The status line shares a row with the
        // Refresh button, so a full sentence wraps and clips — which is how this
        // read before the dialog existed: an explanation nobody could finish.
        statusView.setText(entry.label + " was not updated");
        new AlertDialog.Builder(hostContext)
                .setTitle(entry.label)
                .setMessage(why + "\n\nRemove the installed copy now? You can then"
                        + " install " + entry.label + " from the market.")
                .setPositiveButton("Uninstall", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        PluginControl.uninstall(hostContext, entry.packageName);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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
                                // The badge is the whole point of the change, so
                                // reflect it now rather than at the next refresh.
                                refreshInstalledState();
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

    /**
     * Ask ATAK which of these it currently has loaded.
     *
     * Done here rather than in MarketCatalog so the catalog layer depends on
     * nothing but Android, and done once per refresh rather than per getView:
     * the adapter is called repeatedly while scrolling.
     */
    private void resolveLoaded(List<MarketEntry> list) {
        for (MarketEntry e : list)
            e.loaded = e.installed ? PluginControl.isLoaded(e.packageName) : null;
    }

    /** Re-read what is installed, without going back to the network. */
    public void refreshInstalledState() {
        if (entries.isEmpty())
            return;
        MarketCatalog.resolveInstalled(hostContext, entries);
        resolveLoaded(entries);
        MarketCatalog.sortForDisplay(entries, pluginApi);
        adapter.setEntries(entries);
        paintSummary();
    }
}
