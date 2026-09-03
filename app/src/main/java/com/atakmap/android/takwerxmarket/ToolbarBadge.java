package com.atakmap.android.takwerxmarket;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;

import com.atakmap.android.tools.BadgeDrawable;
import com.atakmap.coremap.log.Log;

import gov.tak.platform.marshal.MarshalManager;

/**
 * The update count, drawn on the toolbar icon the way ATAK draws its own.
 *
 * ATAK ships BadgeDrawable and uses it for things like Data Sync's unread
 * count, which appears both on the toolbar and in the Tools list — so the icon
 * ATAK holds is drawn live rather than snapshotted, and mutating it is enough.
 * That matters because there is no way to update a toolbar item after the fact:
 * ToolbarItem.icon is final, and IHostUIService offers only add and remove.
 *
 * So the icon handed over is a LayerDrawable we keep a reference into. Changing
 * the count mutates the badge and invalidates, with no remove-and-re-add — which
 * would risk the icon losing its place on the toolbar every time the count moved.
 *
 * BadgeDrawable is an ATAK class and official ATAK is obfuscated, so every use
 * is guarded: losing the badge must not cost the toolbar button.
 */
public final class ToolbarBadge {

    private static final String TAG = "TakwerxMarket.Badge";

    private final Drawable base;
    private BadgeDrawable badge;
    private LayerDrawable composite;
    private gov.tak.api.commons.graphics.Drawable marshalled;

    public ToolbarBadge(Context pluginContext, Drawable baseIcon) {
        this.base = baseIcon;
        try {
            badge = new BadgeDrawable(pluginContext);
            badge.setCount(0);

            composite = new LayerDrawable(new Drawable[] { baseIcon, badge });
            // Top-right quadrant, the corner ATAK puts its own badges in.
            int w = Math.max(1, baseIcon.getIntrinsicWidth());
            int h = Math.max(1, baseIcon.getIntrinsicHeight());
            composite.setLayerInset(1, w / 2, 0, 0, h / 2);
        } catch (Throwable t) {
            Log.w(TAG, "no badge available: " + t);
            badge = null;
            composite = null;
        }
    }

    /**
     * The icon to hand to the toolbar. Marshalled as a Drawable rather than a
     * Bitmap on purpose: a Bitmap is a snapshot and could never show a changing
     * count. Falls back to the bare glyph if anything here is unavailable.
     */
    public Object icon() {
        Drawable d = composite != null ? composite : base;
        try {
            marshalled = MarshalManager.marshal(d, Drawable.class,
                    gov.tak.api.commons.graphics.Drawable.class);
            if (marshalled != null)
                return marshalled;
        } catch (Throwable t) {
            Log.w(TAG, "no Drawable marshal, falling back to a static icon: " + t);
        }
        // Static, so the count will not update — better than no toolbar button.
        return MarshalManager.marshal(d, Drawable.class,
                gov.tak.api.commons.graphics.Bitmap.class);
    }

    /** @param count updates waiting; 0 hides the badge. */
    public void setCount(int count) {
        if (badge == null)
            return;
        try {
            badge.setCount(Math.max(0, count));
            if (composite != null)
                composite.invalidateSelf();
            if (marshalled != null)
                marshalled.invalidate();
        } catch (Throwable t) {
            Log.d(TAG, "could not update the badge: " + t);
        }
    }
}
