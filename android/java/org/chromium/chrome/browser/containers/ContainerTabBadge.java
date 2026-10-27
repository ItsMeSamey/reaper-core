// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
package org.chromium.chrome.browser.containers;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.widget.TextView;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.tab.Tab;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ephemeral view data derived from immutable tab identity and the native used snapshot. */
@NullMarked
public final class ContainerTabBadge {
    public static final class Info {
        public final String label;
        public final String description;
        public final int color;
        public final int icon;

        Info(String label, String description, int color, int icon) {
            this.label = label;
            this.description = description;
            this.color = color;
            this.icon = icon;
        }
    }

    public static @Nullable Info forTab(Context context, @Nullable Tab tab) {
        if (tab == null || tab.isDestroyed() || tab.isIncognito()) return null;
        @Nullable Profile profile = tab.getProfile();
        if (profile == null
                || profile.shutdownStarted()
                || profile.isOffTheRecord()
                || profile.getNativeBrowserContextPointer() == 0) return null;
        String id = tab.getContainerId();
        if (id.isEmpty()) return null;
        ContainersBridge.ContainerInfo metadata = ContainersBridge.getContainerInfo(profile, id);
        String name = ContainersUi.getDisplayName(context, id, metadata);
        return new Info(
                name,
                context.getString(R.string.reaper_container_identity, name),
                metadata == null ? 0xff1a73e8 : (metadata.backgroundColor | 0xff000000),
                metadata == null ? 0 : metadata.icon);
    }

    /**
     * A group containing different identities must never inherit its representative tab's badge.
     */
    public static @Nullable Info forTabs(Context context, List<Tab> tabs) {
        Map<String, String> identities = new LinkedHashMap<>();
        @Nullable Info single = null;
        @Nullable Profile groupProfile = null;
        for (Tab tab : tabs) {
            if (tab.isDestroyed() || tab.isIncognito()) return null;
            @Nullable Profile profile = tab.getProfile();
            if (profile == null
                    || profile.shutdownStarted()
                    || profile.isOffTheRecord()
                    || profile.getNativeBrowserContextPointer() == 0) return null;
            if (groupProfile != null && profile != groupProfile) return null;
            groupProfile = profile;
            String id = tab.getContainerId();
            @Nullable Info info = forTab(context, tab);
            if (!id.isEmpty() && info == null) return null;
            identities.put(
                    id,
                    info == null
                            ? context.getString(R.string.reaper_container_default)
                            : info.label);
            if (info != null) single = info;
        }
        if (identities.size() < 2) return single;
        return new Info(
                context.getString(R.string.reaper_container_mixed),
                context.getString(
                        R.string.reaper_container_mixed_description,
                        String.join(", ", identities.values())),
                0xff5f6368,
                6);
    }

    public static void bind(View root, @Nullable Info info) {
        bind(root, info, false);
    }

    /** Collapsed rails keep a colored icon while their row accessibility retains the full name. */
    public static void bind(View root, @Nullable Info info, boolean compact) {
        @Nullable TextView badge = root.findViewById(R.id.reaper_tab_container_badge);
        if (badge == null) return;
        badge.setText(info == null || compact ? "" : info.label);
        badge.setContentDescription(info == null ? null : info.description);
        badge.setTooltipText(info == null ? null : info.description);
        if (info == null) {
            badge.setCompoundDrawablesRelative(null, null, null, null);
            badge.setVisibility(View.GONE);
            return;
        }
        Drawable icon = ContainersUi.getIcon(root.getContext(), info.icon, info.color);
        // The compact badge needs smaller glyph insets than the 24dp chooser icon.
        if (icon instanceof LayerDrawable layers) {
            int inset = Math.round(2 * root.getResources().getDisplayMetrics().density);
            layers.setLayerInset(1, inset, inset, inset, inset);
        }
        int size = Math.round(14 * root.getResources().getDisplayMetrics().density);
        icon.setBounds(0, 0, size, size);
        badge.setCompoundDrawablesRelative(icon, null, null, null);
        badge.setCompoundDrawablePadding(
                Math.round(3 * root.getResources().getDisplayMetrics().density));
        badge.setVisibility(View.VISIBLE);
    }

    public static @Nullable CharSequence appendDescription(
            @Nullable CharSequence description, @Nullable Info info) {
        if (info == null) return description;
        return description == null || description.length() == 0
                ? info.description
                : description + ", " + info.description;
    }

    private ContainerTabBadge() {}
}
