package com.atakmap.android.takwerxmarket;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
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

    private final Context pluginContext;
    private final String pluginApi;
    private final ActionListener listener;
    private final List<MarketEntry> entries = new ArrayList<>();

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

        TextView label = row.findViewById(R.id.row_label);
        TextView status = row.findViewById(R.id.row_status);
        TextView desc = row.findViewById(R.id.row_desc);
        Button action = row.findViewById(R.id.row_action);

        label.setText(e.label);
        if (e.description == null || e.description.length() == 0) {
            desc.setVisibility(View.GONE);
        } else {
            desc.setVisibility(View.VISIBLE);
            desc.setText(e.description);
        }

        MarketEntry.Status s = e.status(pluginApi);
        switch (s) {
            case UPDATE_AVAILABLE:
                status.setText(PluginVersion.number(e.installedVersion) + "  →  " + PluginVersion.number(e.version));
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
                status.setText(pluginContext.getString(R.string.market_installed)
                        + " " + PluginVersion.number(e.installedVersion));
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
        final boolean manageable = e.installed;
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
