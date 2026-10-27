// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.

package org.chromium.chrome.browser.containers;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;

import org.chromium.base.supplier.MonotonicObservableSupplier;
import org.chromium.base.supplier.ObservableSuppliers;
import org.chromium.base.supplier.SettableMonotonicObservableSupplier;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.settings.BravePreferenceFragment;
import org.chromium.chrome.browser.settings.BraveSettingsPreferenceGroupAdapter;
import org.chromium.chrome.browser.settings.search.ChromeBaseSearchIndexProvider;
import org.chromium.components.browser_ui.settings.ChromeBasePreference;
import org.chromium.components.browser_ui.settings.ChromeSwitchPreference;
import org.chromium.components.browser_ui.settings.SettingsUtils;
import org.chromium.ui.util.ColorUtils;

import java.util.ArrayList;
import java.util.List;

/** Settings for the existing profile's named containers. */
@NullMarked
public class ContainersSettings extends BravePreferenceFragment {
    private final SettableMonotonicObservableSupplier<String> mPageTitle =
            ObservableSuppliers.createMonotonic();
    private final ContainerMetadataObserver mMetadataObserver =
            new ContainerMetadataObserver(this::refresh, true);

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        mPageTitle.set(getString(R.string.reaper_containers_title));
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle state, @Nullable String rootKey) {
        super.onCreatePreferences(state, rootKey);
        SettingsUtils.addPreferencesFromResource(this, R.xml.reaper_containers_preferences);
    }

    @Override
    protected PreferenceGroupAdapter onCreateAdapter(PreferenceScreen preferenceScreen) {
        return new BraveSettingsPreferenceGroupAdapter(preferenceScreen) {
            @Override
            protected boolean shouldClearIconTint(Preference preference) {
                // Container icons already combine their color with a contrasting glyph.
                return preference.getParent() instanceof PreferenceCategory category
                        && "reaper_container_list".equals(category.getKey());
            }

            @Override
            public void onBindViewHolder(PreferenceViewHolder holder, int position) {
                super.onBindViewHolder(holder, position);
                @Nullable Preference preference = getItem(position);
                if (preference == null || !shouldClearIconTint(preference)) return;
                @Nullable Drawable icon = preference.getIcon();
                if (!(icon instanceof LayerDrawable layers)
                        || layers.getNumberOfLayers() != 2
                        || !(layers.getDrawable(0) instanceof GradientDrawable background)) return;
                @Nullable ColorStateList colors = background.getColor();
                if (colors == null) return;
                // Clearing ImageView tint also clears each layer's tint. Restore the glyph
                // after the shared adapter finishes, using the renderer's contrast rule.
                layers.getDrawable(1)
                        .setTint(
                                ColorUtils.shouldUseLightForegroundOnBackground(
                                                colors.getDefaultColor())
                                        ? Color.WHITE
                                        : Color.BLACK);
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        mMetadataObserver.setProfile(getProfile());
        refresh();
    }

    @Override
    public void onPause() {
        mMetadataObserver.setProfile(null);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        mMetadataObserver.destroy();
        super.onDestroy();
    }

    private void refresh() {
        if (!isAdded() || !isResumed()) return;
        ChromeSwitchPreference enabled = findPreference("reaper_containers_enabled");
        ChromeBasePreference add = findPreference("reaper_container_add");
        PreferenceCategory list = findPreference("reaper_container_list");
        if (enabled == null || add == null || list == null) return;
        boolean available = ContainersUi.isUsableRegularProfile(getProfile());
        boolean showing = available && ContainersBridge.isEnabled(getProfile());
        enabled.setEnabled(available);
        enabled.setChecked(showing);
        enabled.setOnPreferenceChangeListener(
                (preference, value) -> {
                    if (!ContainersUi.isUsableRegularProfile(getProfile())
                            || !ContainersBridge.setEnabled(getProfile(), (Boolean) value))
                        return false;
                    refresh();
                    return true;
                });
        add.setEnabled(showing);
        add.setOnPreferenceClickListener(
                preference -> {
                    if (isAdded() && ContainersUi.isUsableRegularProfile(getProfile()))
                        ContainersUi.showEditor(
                                requireActivity(), getProfile(), null, this::refresh);
                    return true;
                });
        list.removeAll();
        list.setVisible(showing);
        if (!showing) {
            notifyPreferencesUpdated();
            return;
        }
        List<ContainersBridge.ContainerInfo> containers = ContainersBridge.getContainers(getProfile());
        // Share the selector's display-only disambiguation, including reserved row labels.
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        ids.add("");
        labels.add(getString(R.string.reaper_container_default));
        for (ContainersBridge.ContainerInfo info : containers) {
            ids.add(info.id);
            labels.add(ContainersUi.getDisplayName(requireContext(), info.id, info));
        }
        labels.add(getString(R.string.reaper_containers_manage));
        ContainersUi.disambiguateSelectorLabels(requireContext(), ids, labels);
        int displayIndex = 1;
        for (ContainersBridge.ContainerInfo info : containers) {
            ChromeBasePreference row = new ChromeBasePreference(requireContext());
            row.setKey("reaper_container_" + info.id);
            row.setTitle(labels.get(displayIndex++));
            row.setSummary(R.string.reaper_container_edit_summary);
            row.setIcon(ContainersUi.getIcon(requireContext(), info.icon, info.backgroundColor));
            row.setPersistent(false);
            row.setOnPreferenceClickListener(
                    preference -> {
                        if (isAdded() && ContainersUi.isUsableRegularProfile(getProfile()))
                            ContainersUi.showActions(
                                    requireActivity(), getProfile(), info, this::refresh);
                        return true;
                    });
            list.addPreference(row);
        }
        notifyPreferencesUpdated();
    }

    @Override
    public MonotonicObservableSupplier<String> getPageTitle() {
        return mPageTitle;
    }

    public static final ChromeBaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new ChromeBaseSearchIndexProvider(
                    ContainersSettings.class.getName(), R.xml.reaper_containers_preferences);

    @Override
    public String getMainMenuKey() {
        return "reaper_containers";
    }
}
