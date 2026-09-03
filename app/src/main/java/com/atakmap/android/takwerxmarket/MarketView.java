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

    /** Rows queued behind the one in progress; "Update all" fills it. */
    private final java.util.ArrayDeque<MarketEntry> queue = new java.util.ArrayDeque<>();

    /**
     * ATAK's own verified APK, held while the market's build for the NEW ATAK
     * lands first. Handed to Android the moment that package-added arrives.
     */
    private java.io.File pendingAtakApk;
    /** The plugin-api of the ATAK that file is; the market's build for it must land first. */
    private String pendingTargetApi;
    private Button updateAll;

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
                    if (pkg != null && pkg.equals(installing)) {
                        if (pendingAtakApk != null
                                && Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())
                                && pkg.equals(pluginContext.getPackageName())) {
                            handAtakOver();
                            return;
                        }
                        clearInstalling();
                    }
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
        startNext();
    }

    private void startNext() {
        if (busy || queue.isEmpty())
            return;
        onInstall(queue.poll());
    }

    /**
     * Queue every pending update except ATAK's and run them one after another,
     * each through the same three screens as a single update. ATAK's own row is
     * deliberately not part of this: it restarts the process, and it has its own
     * warning.
     */
    private void updateAllPending() {
        if (busy)
            return;
        queue.clear();
        MarketEntry self = null;
        for (MarketEntry e : entries) {
            if (e.isAtak() || e.status(pluginApi) != MarketEntry.Status.UPDATE_AVAILABLE)
                continue;
            if (e.packageName.equals(pluginContext.getPackageName()))
                self = e;                    // last: replacing the market ends this queue
            else
                queue.add(e);
        }
        if (self != null)
            queue.add(self);
        startNext();
    }

    /** Repaint only when the whole percent moves; progress arrives far more often. */
    private MarketHttp.Progress progressFor(final MarketEntry entry) {
        return new MarketHttp.Progress() {
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
    }

    /**
     * Updating ATAK itself is a two-step handoff, and the order is the whole
     * design. First the market's OWN build for the new ATAK goes on (Android
     * replaces the file; this code keeps running from the old one), then ATAK.
     * ATAK restarts on the new version, the new market loads, and every plugin
     * built for the old ATAK shows up as an update. Done the other way round
     * there is no market on the new ATAK to finish the job.
     *
     * Both files are downloaded and verified before either is handed over, so
     * the two Android prompts come back to back with nothing in between.
     */
    private void confirmAtakUpgrade(final MarketEntry atak) {
        if (busy)
            return;
        // Only the package this code runs inside is ATAK. Checked here, before
        // any chooser, so a catalog row for some other com.atakmap.app.*
        // package never gets as far as being offered.
        if (!Signers.isAtakPackage(hostContext, atak.packageName)) {
            statusView.setText("Refusing to treat " + atak.packageName + " as ATAK.");
            return;
        }
        // Which ATAK? The newest leads the row; every alternative that is
        // still newer than what runs is offered too, so a phone can take the
        // safe 5.7 while 5.8 has its vector-tile problem. Installed state is
        // READ for each alternative, not copied from the row.
        MarketCatalog.resolveInstalled(hostContext, atak.alternatives);
        final List<MarketEntry> choices = new ArrayList<>();
        choices.add(atak);
        for (MarketEntry alt : atak.alternatives) {
            if (alt.installed && alt.packageName.equals(atak.packageName)
                    && PluginVersion.isNewer(alt.version, alt.installedVersion))
                choices.add(alt);
        }
        if (choices.size() == 1) {
            guardAndConfirmAtak(atak);
            return;
        }
        final String[] labels = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            String core = AtakTarget.coreVersion(choices.get(i).version);
            String tail = pluginApi == null ? "" : pluginApi.substring(pluginApi.lastIndexOf('.'));
            boolean sameRelease = pluginApi != null
                    && pluginApi.equalsIgnoreCase(AtakTarget.PREFIX + core + tail);
            labels[i] = PluginVersion.number(choices.get(i).version)
                    + (i == 0 ? "  (newest)" : "")
                    + (sameRelease ? "  ·  same release, plugins keep working" : "");
        }
        new AlertDialog.Builder(hostContext)
                .setTitle("Update ATAK to which version?")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        guardAndConfirmAtak(choices.get(which));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void guardAndConfirmAtak(final MarketEntry atak) {
        final String newApi = AtakTarget.apiFor(pluginApi, atak.version);

        // Known bad combination, measured 2026-09-03 on official ATAK-CIV
        // 5.8.0.4: with any vector tile package (.vtpk, Map Depot's public-
        // lands maps) already cataloged, ATAK dies in its own imagery scan on
        // every start after the first. A phone with packages that is moved to
        // 5.8 by this market would therefore not start again. So the update is
        // not offered to such a phone, and the message says why. The market
        // does not move anyone's files; the operator decided that. The check
        // is by target release, not build number, until a fixed 5.8 is
        // confirmed.
        String core = AtakTarget.coreVersion(atak.version);
        int packages = core != null && core.startsWith("5.8.") ? countVectorTilePackages() : 0;
        if (packages > 0) {
            String why = "ATAK " + PluginVersion.number(atak.version) + " update not available"
                    + " on this phone: " + packages + " vector tile package"
                    + (packages == 1 ? "" : "s") + " in atak/imagery (Map Depot's public-lands"
                    + " maps). ATAK 5.8.0.4 does not start with them. Waiting on a fix from"
                    + " tak.gov.\n\nTo update anyway, remove the vector tile packages from the"
                    + " phone first (Map Depot's Offline Public Lands list can delete them),"
                    + " then tap Update again.";
            new AlertDialog.Builder(hostContext)
                    .setTitle("Not yet")
                    .setMessage(why)
                    .setPositiveButton("OK", null)
                    .show();
            statusView.setText(why);
            return;
        }
        confirmAtakUpgradeStep2(atak, newApi);
    }

    /** How many .vtpk sit directly under ATAK's imagery folder. */
    private static int countVectorTilePackages() {
        int n = 0;
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStorageDirectory(), "atak/imagery");
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    if (f.isFile() && f.getName().toLowerCase(java.util.Locale.US).endsWith(".vtpk"))
                        n++;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "could not list atak/imagery: " + e.getMessage());
        }
        return n;
    }

    private void confirmAtakUpgradeStep2(final MarketEntry atak, final String newApi) {
        final String to = MarketEntry.atakOf(newApi);
        int plugins = 0;
        for (MarketEntry e : entries)
            if (e.installed && e.isPlugin())
                plugins++;
        String mb = atak.size > 0 ? " (" + (atak.size / (1024 * 1024)) + " MB)" : "";
        // Same release (5.7.0.5 -> 5.7.0.14): the plugin-api does not change,
        // so nothing built for it changes either. ATAK alone.
        final boolean sameRelease = pluginApi != null && pluginApi.equalsIgnoreCase(newApi);
        String message = sameRelease
                ? "The market will download ATAK" + mb + ", verify it, and hand it to"
                        + " Android. ATAK restarts on the new version. It is the same ATAK"
                        + " release, so the market and your " + plugins
                        + (plugins == 1 ? " plugin keep" : " plugins keep") + " working as they are."
                        + "\n\nAnswer Android's prompt when it comes, and do not cancel the"
                        + " install once it starts."
                : "The market will download ATAK" + mb + ", then install its own"
                        + " build for ATAK " + to + ", then hand ATAK to Android."
                        + " ATAK restarts on the new version; open the market again and it"
                        + " will offer " + to + " builds of your " + plugins
                        + (plugins == 1 ? " plugin." : " plugins.")
                        + "\n\nAnswer Android's prompts as they come. If ATAK asks to load"
                        + " TAKwerx Market in between, tap Cancel; it loads after the restart."
                        + " Do not cancel the ATAK install once it starts.";
        new AlertDialog.Builder(hostContext)
                .setTitle("Update ATAK to " + PluginVersion.number(atak.version) + "?")
                .setMessage(message)
                .setPositiveButton("Update ATAK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        upgradeAtak(atak, newApi);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void upgradeAtak(final MarketEntry atak, final String claimedApi) {
        if (busy)
            return;
        if (!Signers.isAtakPackage(hostContext, atak.packageName)) {
            // Only the package this code runs inside is ATAK. A row that merely
            // starts with ATAK's name is not, whatever its type column says.
            statusView.setText("Refusing to treat " + atak.packageName + " as ATAK.");
            return;
        }
        if (!Signers.hostIsTakSigned(hostContext)) {
            String why = "The ATAK on this device was not installed from tak.gov (it carries"
                    + " a different signing key), so it cannot be updated from here.";
            statusView.setText(why);
            Toast.makeText(hostContext, why, Toast.LENGTH_LONG).show();
            return;
        }
        busy = true;
        refresh.setEnabled(false);
        queue.clear();
        adapter.setDownloading(atak.packageName, 0);

        new Thread(new Runnable() {
            @Override
            public void run() {
                String fail = null;
                java.io.File atakApk = null;
                java.io.File marketApk = null;
                MarketEntry marketNew = null;
                String newApi = claimedApi;
                try {
                    ApkInstaller.Fetched a = ApkInstaller.fetchAndVerify(
                            hostContext, baseUrl, atak, progressFor(atak));
                    if (a.apk == null) {
                        fail = a.result.message;
                    } else {
                        atakApk = a.apk;
                        // Which ATAK is actually in hand decides which market
                        // build goes on first -- read from the file, not the row.
                        newApi = AtakTarget.apiFor(pluginApi, a.versionName);
                        // The market's own build for the ATAK about to be
                        // installed. Not there yet? Then ATAK is not updated
                        // either: a phone with no market on it is the one
                        // outcome this must never produce.
                        //
                        // Unless the plugin-api does not change (5.7.0.5 to
                        // 5.7.0.14): then the market on the phone is already
                        // the right build, and fetching "its own build for the
                        // new ATAK" would replace it with whatever the catalog
                        // lists -- possibly older. ATAK alone in that case.
                        String me = pluginContext.getPackageName();
                        if (pluginApi != null && pluginApi.equalsIgnoreCase(newApi)) {
                            marketNew = null;
                        } else {
                            for (MarketEntry e : MarketCatalog.fetchExact(baseUrl, newApi)) {
                                if (me.equals(e.packageName) && e.isCompatibleWith(newApi))
                                    marketNew = e;
                            }
                        }
                        boolean sameRelease = pluginApi != null && pluginApi.equalsIgnoreCase(newApi);
                        if (sameRelease) {
                            // ATAK alone; the hand-over below sees marketNew == null.
                        } else if (marketNew == null) {
                            fail = "No TAKwerx Market build for ATAK " + MarketEntry.atakOf(newApi)
                                    + " is published yet, so ATAK was not updated.";
                        } else {
                            MarketCatalog.resolveInstalled(hostContext,
                                    java.util.Collections.singletonList(marketNew));
                            final String fetching = "Fetching TAKwerx Market for ATAK "
                                    + MarketEntry.atakOf(newApi) + "\u2026";
                            root.post(new Runnable() {
                                @Override
                                public void run() {
                                    statusView.setText(fetching);
                                }
                            });
                            ApkInstaller.Fetched m = ApkInstaller.fetchAndVerify(
                                    hostContext, baseUrl, marketNew, progressFor(atak));
                            if (m.apk == null)
                                fail = m.result.message;
                            else
                                marketApk = m.apk;
                        }
                    }
                } catch (Exception e) {
                    fail = "ATAK update did not start: " + e.getMessage();
                }

                final String failMsg = fail;
                final java.io.File atakFile = atakApk;
                final java.io.File marketFile = marketApk;
                final MarketEntry marketEntry = marketNew;
                final String targetApi = newApi;
                root.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        refresh.setEnabled(true);
                        if (failMsg != null) {
                            if (atakFile != null)
                                ApkInstaller.discard(atakFile);
                            adapter.setDownloading(null, 0);
                            statusView.setText(failMsg);
                            Toast.makeText(hostContext, failMsg, Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (marketEntry == null) {
                            // Same release: no market step. ATAK goes straight
                            // to Android; it dies in the replace and comes back.
                            installing = hostContext.getPackageName();
                            adapter.setInstalling(atak.packageName);
                            statusView.setText("Handing ATAK to Android\u2026");
                            try {
                                ApkInstaller.handToInstaller(hostContext, atakFile);
                            } catch (Exception e) {
                                ApkInstaller.discard(atakFile);
                                clearInstalling();
                                statusView.setText("Could not start the installer: " + e.getMessage());
                            }
                            return;
                        }
                        // Step one: the market's own new build. Step two runs
                        // from the package watcher when Android reports it in.
                        pendingAtakApk = atakFile;
                        pendingTargetApi = targetApi;
                        installing = marketEntry.packageName;
                        adapter.setInstalling(atak.packageName);
                        statusView.setText("Installing TAKwerx Market for ATAK "
                                + MarketEntry.atakOf(targetApi) + ", then ATAK\u2026");
                        try {
                            ApkInstaller.handToInstaller(hostContext, marketFile);
                        } catch (Exception e) {
                            pendingAtakApk = null;
                            ApkInstaller.discard(atakFile);
                            clearInstalling();
                            statusView.setText("Could not start the installer: " + e.getMessage());
                            return;
                        }
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (pendingAtakApk != null
                                        && marketEntry.packageName.equals(installing)) {
                                    // Nothing came back: the market install was
                                    // cancelled, so ATAK's file is dropped and
                                    // nothing else happens.
                                    ApkInstaller.discard(pendingAtakApk);
                                    pendingAtakApk = null;
                                    clearInstalling();
                                    statusView.setText("ATAK update cancelled before it started.");
                                }
                            }
                        }, INSTALL_WAIT_MS);
                    }
                });
            }
        }, "takwerx-market-atak").start();
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
        updateAll = root.findViewById(R.id.market_update_all);
        updateAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateAllPending();
            }
        });

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

    /**
     * Step two of the ATAK update: the market's build for the new ATAK is on
     * the device, now ATAK itself. ATAK dies in the replace and comes back on
     * the new version, where that build loads.
     *
     * Called from whichever fires first. The package watcher sees the market's
     * own package-added; but ATAK's registry also sees the package-REMOVED that
     * precedes it and unloads the plugin, which disposes this view and its
     * watcher before the added event arrives. So dispose() calls this too.
     * Whichever runs, the other finds nothing pending.
     */
    private void handAtakOver() {
        java.io.File atak = pendingAtakApk;
        if (atak == null)
            return;
        pendingAtakApk = null;
        installing = hostContext.getPackageName();

        // ATAK decides what to load at start from "shouldLoad-<package>", and
        // it set ours to false a moment ago when it unloaded us for the replace
        // (AtakPluginRegistry.unloadPlugin). Left like that, the new ATAK comes
        // up with the new market installed and not loaded, and the operator has
        // to find it in TAK Package Mgmt -- measured on the S22, 2026-09-03.
        // Set it back so the new build loads on the first start of the new ATAK.
        try {
            PreferenceManager.getDefaultSharedPreferences(hostContext).edit()
                    .putBoolean("shouldLoad-" + pluginContext.getPackageName(), true)
                    .apply();
        } catch (Exception e) {
            Log.d(TAG, "could not mark the new market to load at start: " + e.getMessage());
        }
        try {
            ApkInstaller.handToInstaller(hostContext, atak);
            Log.d(TAG, "ATAK handed to the installer");
        } catch (Exception e) {
            installing = null;
            Log.e(TAG, "could not start the ATAK install", e);
            Toast.makeText(hostContext, "Could not start the ATAK install: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Is the market on the device now the build for the ATAK about to go on? */
    private boolean marketBuildLanded() {
        try {
            android.content.pm.ApplicationInfo ai = hostContext.getPackageManager()
                    .getApplicationInfo(pluginContext.getPackageName(),
                            android.content.pm.PackageManager.GET_META_DATA);
            String api = ai.metaData == null ? null : ai.metaData.getString("plugin-api");
            return pendingTargetApi != null && pendingTargetApi.equalsIgnoreCase(api);
        } catch (Exception e) {
            return false;
        }
    }

    /** Called when the plugin stops. Leaving these registered leaks them. */
    public void dispose() {
        // Unloaded mid-upgrade is expected: ATAK unloads a plugin whose package
        // is being replaced. Finish the job on the way out -- but only if the
        // market's build for the new ATAK really landed. dispose() also runs
        // when ATAK exits, and an operator who cancelled step one and then quit
        // must not be handed step two: that is the new ATAK with no market.
        if (pendingAtakApk != null) {
            if (marketBuildLanded()) {
                handAtakOver();
            } else {
                ApkInstaller.discard(pendingAtakApk);
                pendingAtakApk = null;
                Log.d(TAG, "ATAK update dropped: the market's new build did not land");
            }
        }
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
            MarketEntry.Status st = e.status(pluginApi);
            if (e.isAtak()) {
                // ATAK is a row, not a plugin: it counts as an update when it
                // is one, and nowhere else in the header.
                if (st == MarketEntry.Status.UPDATE_AVAILABLE)
                    updates++;
                continue;
            }
            if (e.installed)
                installed++;
            switch (st) {
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

        int pluginUpdates = 0;
        for (MarketEntry e : entries)
            if (!e.isAtak() && e.status(pluginApi) == MarketEntry.Status.UPDATE_AVAILABLE)
                pluginUpdates++;
        updateAll.setText("Update all (" + pluginUpdates + ")");
        updateAll.setVisibility(pluginUpdates >= 2 ? View.VISIBLE : View.GONE);

        String atak = pluginApi == null ? "this ATAK"
                : "ATAK " + pluginApi.substring(pluginApi.indexOf('@') + 1);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(String.valueOf(offered))
                .append(offered == 1 ? " plugin for " : " plugins for ").append(atak);
        // Second line, under the count, so the state of the list is its own
        // sentence rather than a clause tacked onto the ATAK version.
        if (updates > 0) {
            // Amber, the same colour the rows use for a pending update, so the
            // count and the rows it refers to read as one thing.
            int at = sb.length();
            sb.append("\n").append(String.valueOf(updates))
                    .append(updates == 1 ? " update available" : " updates available");
            sb.setSpan(new ForegroundColorSpan(AMBER), at, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (installed > 0) {
            // Only claim this when something is actually installed. With four
            // plugins offered and none installed there are zero updates, but
            // nothing is up to date either, and saying so would be a lie told in
            // green — the one colour an operator will not stop to question.
            //
            // The same lie in a smaller size: "All up to date" while two of the
            // four were not installed at all read as wrong on the S22, because
            // "all" is heard as all four. So it is only said when every plugin
            // offered is on the device; otherwise the line says what is missing.
            int at = sb.length();
            int missing = offered - installed;
            if (missing <= 0) {
                sb.append("\nAll up to date");
            } else {
                sb.append("\nNo updates  \u00b7  ").append(String.valueOf(missing))
                        .append(" not installed");
            }
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
        if (entry.isAtak()) {
            confirmAtakUpgrade(entry);
            return;
        }
        busy = true;
        refresh.setEnabled(false);
        adapter.setDownloading(entry.packageName, 0);

        final MarketHttp.Progress progress = progressFor(entry);

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
                        queue.clear();               // one failure stops "Update all"
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
            e.loaded = e.installed && e.isPlugin() ? PluginControl.isLoaded(e.packageName) : null;
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
