package com.atakmap.android.takwerxmarket;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Button;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.takwerxmarket.plugin.R;

import java.util.ArrayList;
import java.util.List;

/** Renders the catalog. One row per product, with a single action per row. */
public class MarketAdapter extends BaseAdapter {

    public interface ActionListener {
        void onInstall(MarketEntry entry);

        /** Row tapped for an already-installed plugin: load, unload, uninstall. */
        void onManage(MarketEntry entry);
    }

    private static final int GREEN = 0xFF8BC34A;
    private static final int AMBER = 0xFFFFB300;
    private static final int GREY = 0xFF9E9E9E;

    /**
     * Installed is not the same as running. ATAK can hold a plugin installed but
     * unloaded, and from the map there is no way to tell the two apart — so the
     * row says which. Green for loaded, yellow for unloaded, and the word is
     * spelled out so the row does not depend on colour alone: an update row is
     * already amber for a different reason.
     */
    private static CharSequence withLoadState(CharSequence base, Boolean loaded) {
        if (loaded == null)
            return base;                       // registry unreachable; claim nothing
        String tag = loaded ? "  \u00b7  LOADED" : "  \u00b7  UNLOADED";
        SpannableStringBuilder sb = new SpannableStringBuilder(base);
        int at = sb.length();
        sb.append(tag);
        sb.setSpan(new ForegroundColorSpan(loaded ? GREEN : AMBER),
                at, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private final Context pluginContext;
    private final String pluginApi;
    private final ActionListener listener;
    private final List<MarketEntry> entries = new ArrayList<>();

    /** Package currently downloading, and how far along, or null for none. */
    private String downloadingPackage;
    private int downloadPercent;

    /** Package handed to Android and awaiting its verdict, or null. */
    private String installingPackage;

    /**
     * Between the hand-off and Android's verdict. Worth saying on the row
     * because ATAK toasts "uninstalled" partway through a replace, and a row
     * that says "Installing…" at that moment is the thing that makes it read as
     * a step rather than a failure.
     */
    public void setInstalling(String packageName) {
        this.installingPackage = packageName;
        this.downloadingPackage = null;
        notifyDataSetChanged();
    }

    /**
     * @param packageName the plugin being fetched, or null when nothing is
     * @param percent 0-100, or -1 when the server did not declare a length
     */
    public void setDownloading(String packageName, int percent) {
        this.downloadingPackage = packageName;
        this.downloadPercent = percent;
        if (packageName == null)
            this.installingPackage = null;
        notifyDataSetChanged();
    }

    public MarketAdapter(Context pluginContext, String pluginApi, ActionListener listener) {
        this.pluginContext = pluginContext;
        this.pluginApi = pluginApi;
        this.listener = listener;
    }

    public void setEntries(List<MarketEntry> newEntries) {
        entries.clear();
        if (newEntries != null)
            entries.addAll(newEntries);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return entries.size();
    }

    @Override
    public MarketEntry getItem(int position) {
        return entries.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        if (row == null)
            row = PluginLayoutInflater.inflate(pluginContext, R.layout.market_row, null);

        final MarketEntry e = getItem(position);

        ImageView icon = row.findViewById(R.id.row_icon);
        ProgressBar progress = row.findViewById(R.id.row_progress);
        TextView label = row.findViewById(R.id.row_label);
        TextView status = row.findViewById(R.id.row_status);
        TextView desc = row.findViewById(R.id.row_desc);
        Button action = row.findViewById(R.id.row_action);

        // Rows are recycled, so an entry without an icon must clear the one the
        // previous occupant left behind rather than inherit it.
        android.graphics.Bitmap bmp = IconCache.get(e.packageName);
        if (bmp != null) {
            icon.setImageBitmap(bmp);
            icon.setVisibility(View.VISIBLE);
        } else {
            icon.setImageDrawable(null);
            icon.setVisibility(View.INVISIBLE);
        }

        boolean downloading = e.packageName.equals(downloadingPackage);
        if (downloading) {
            progress.setVisibility(View.VISIBLE);
            // A server that declares no length gets a moving bar rather than a
            // bar stuck at zero, which reads as broken.
            progress.setIndeterminate(downloadPercent < 0);
            if (downloadPercent >= 0)
                progress.setProgress(downloadPercent);
        } else {
            progress.setVisibility(View.GONE);
        }

        label.setText(e.label);
        if (e.description == null || e.description.length() == 0) {
            desc.setVisibility(View.GONE);
        } else {
            desc.setVisibility(View.VISIBLE);
            desc.setText(e.description);
        }

        // Read what is on the device HERE rather than trusting what the last
        // refresh stored.
        //
        // The package broadcasts this pane listens for are an optimisation, not
        // the source of truth: measured on official ATAK 5.7, a plugin was
        // uninstalled and the row still read "Installed 1.1" because no
        // broadcast arrived to trigger the re-read. Loading and unloading raise
        // nothing at all in the first place. Anything that redraws the row --
        // a scroll, a refresh, reopening the pane -- now corrects it, and
        // correctness no longer depends on a message turning up.
        //
        // Two lookups per row per draw. A handful of rows makes that far cheaper
        // than being wrong about what is installed.
        MarketCatalog.resolveInstalled(pluginContext, java.util.Collections.singletonList(e));
        // ATAK itself is not a plugin: nothing to load or unload.
        e.loaded = e.installed && e.isPlugin() ? PluginControl.isLoaded(e.packageName) : null;

        MarketEntry.Status s = e.status(pluginApi);

        switch (s) {
            case UPDATE_AVAILABLE:
                if (e.installedForOtherAtak(pluginApi)) {
                    // Same number, wrong ATAK: say what actually differs. No
                    // load state, because a build for another ATAK cannot
                    // be loaded and "UNLOADED" would read as something to fix.
                    String from = PluginVersion.number(e.installedVersion);
                    String to = PluginVersion.number(e.version);
                    String a = MarketEntry.atakOf(e.installedPluginApi);
                    String b = MarketEntry.atakOf(pluginApi);
                    // Say which side each fact belongs to. "0.1 for 5.7.0.CIV →
                    // 0.5 for 5.6.0.CIV" read as an ATAK downgrade; "1.0 → 1.1 ·
                    // built for 5.6.0.CIV" read as if the NEW build were for
                    // 5.6. Measured on the S21, both times. So: what is on the
                    // phone and what it was built for, then what is offered,
                    // which is by definition for this ATAK.
                    status.setText(from + " built for " + a + "  →  " + to + " for this ATAK");
                } else if (e.isAtak() && !e.alternatives.isEmpty()) {
                    // More than one target on offer; the newest leads and the
                    // choice comes when Update is tapped.
                    StringBuilder sb = new StringBuilder(PluginVersion.number(e.installedVersion))
                            .append("  →  ").append(PluginVersion.number(e.version));
                    String or = null;
                    for (MarketEntry alt : e.alternatives) {
                        if (PluginVersion.isNewer(alt.version, e.installedVersion)) {
                            or = PluginVersion.number(alt.version);
                            break;
                        }
                    }
                    if (or != null)
                        sb.append("  ·  or ").append(or);
                    status.setText(sb);
                } else {
                    status.setText(withLoadState(
                            PluginVersion.number(e.installedVersion) + "  →  "
                                    + PluginVersion.number(e.version), e.loaded));
                }
                status.setTextColor(AMBER);
                action.setText(R.string.market_update);
                action.setEnabled(true);
                action.setVisibility(View.VISIBLE);
                break;

            case NOT_INSTALLED:
                status.setText(PluginVersion.number(e.version));
                status.setTextColor(GREY);
                action.setText(R.string.market_install);
                action.setEnabled(true);
                action.setVisibility(View.VISIBLE);
                break;

            case INSTALLED:
                status.setText(withLoadState(
                        pluginContext.getString(R.string.market_installed) + " "
                                + PluginVersion.number(e.installedVersion), e.loaded));
                status.setTextColor(GREEN);
                action.setVisibility(View.INVISIBLE);
                action.setEnabled(false);
                break;

            default:
                // Shown, never silently dropped: the operator should be able to see
                // that the plugin exists and why it is not on offer here.
                String builtFor = e.builtForAtak();
                status.setText(pluginContext.getString(R.string.market_unavailable)
                        + (builtFor == null ? "" : " · built for " + builtFor));
                status.setTextColor(GREY);
                action.setVisibility(View.INVISIBLE);
                action.setEnabled(false);
                break;
        }

        if (e.packageName.equals(installingPackage)) {
            status.setText("Installing\u2026");
            status.setTextColor(AMBER);
            desc.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
            progress.setIndeterminate(true);
            action.setEnabled(false);
            action.setVisibility(View.INVISIBLE);
        } else if (downloading) {
            // Overrides whatever the switch just wrote. A bar alone is not
            // enough: three megabytes on a good connection is gone in about a
            // second, which reads as a flicker rather than as progress and
            // leaves the operator unsure the tap registered.
            status.setText(downloadPercent >= 0
                    ? "Downloading\u2026  " + downloadPercent + "%"
                    : "Downloading\u2026");
            status.setTextColor(AMBER);
            desc.setVisibility(View.GONE);
            action.setEnabled(false);
            action.setVisibility(View.INVISIBLE);
        }

        row.setAlpha(s == MarketEntry.Status.INCOMPATIBLE ? 0.45f : 1.0f);

        action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null)
                    listener.onInstall(e);
            }
        });

        // The row itself carries the manage actions. A ListView stops firing
        // OnItemClickListener once a row contains a focusable Button, so the
        // listener goes on the row rather than on the ListView.
        final boolean manageable = e.installed && e.isPlugin();
        row.setOnClickListener(manageable ? new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null)
                    listener.onManage(e);
            }
        } : null);
        row.setClickable(manageable);

        return row;
    }

}
