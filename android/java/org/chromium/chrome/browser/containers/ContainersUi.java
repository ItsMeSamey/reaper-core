// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.

package org.chromium.chrome.browser.containers;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.settings.SettingsNavigationFactory;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tabmodel.ChromeTabCreator;
import org.chromium.chrome.browser.tabmodel.TabCreator;
import org.chromium.chrome.browser.tabmodel.TabCreatorManager;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.ui.util.ColorUtils;
import org.chromium.ui.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Native-service container controls. Changing a container always creates a fresh tab. */
@NullMarked
public final class ContainersUi {
    private static final int[] COLORS = {
        0xff1a73e8, 0xffd93025, 0xff9334e6, 0xff188038,
        0xfff29900, 0xfff9ab00, 0xff008080, 0xffd01884
    };
    private static final int[] ICONS = {
        android.R.drawable.ic_menu_myplaces, android.R.drawable.ic_menu_agenda,
        android.R.drawable.ic_menu_add, android.R.drawable.ic_menu_share,
        android.R.drawable.ic_menu_today, android.R.drawable.ic_menu_manage,
        android.R.drawable.btn_star_big_on, android.R.drawable.ic_menu_directions,
        android.R.drawable.ic_menu_info_details, android.R.drawable.ic_lock_lock,
        android.R.drawable.ic_dialog_email
    };

    private ContainersUi() {}

    static boolean isUsableRegularProfile(Profile profile) {
        return !profile.isOffTheRecord()
                && !profile.shutdownStarted()
                && profile.getNativeBrowserContextPointer() != 0;
    }

    public static ContainersBridge.@Nullable ContainerInfo getInfo(Profile profile, String id) {
        if (!isUsableRegularProfile(profile) || id.isEmpty()) return null;
        return ContainersBridge.getContainerInfo(profile, id);
    }

    /** Blank space separators and NEL are not names; preserve all other content verbatim. */
    static boolean hasNameContent(String name) {
        return name.codePoints()
                .anyMatch(
                        value ->
                                value != 0x0085
                                        && !Character.isWhitespace(value)
                                        && !Character.isSpaceChar(value));
    }

    public static String getName(Context context, Profile profile, String id) {
        return getDisplayName(context, id, id.isEmpty() ? null : getInfo(profile, id));
    }

    /** Read-only label fallback shared by settings, menus and tab accessibility. */
    static String getDisplayName(
            Context context, String id, ContainersBridge.@Nullable ContainerInfo info) {
        if (id.isEmpty()) return context.getString(R.string.reaper_container_default);
        if (info != null && hasNameContent(info.name.trim())) return info.name;
        // Deleted-but-referenced and temporary tabs remain visibly distinct from Default.
        if (id.startsWith("t-"))
            return context.getString(
                    R.string.reaper_container_temporary_unknown,
                    id.substring(2, Math.min(10, id.length())));
        String shortId = id.length() >= 8 ? id.substring(0, 8) : id;
        return context.getString(R.string.reaper_container_unnamed, shortId);
    }

    public static Drawable getIcon(Context context, int icon, int color) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(color);
        Drawable glyph = context.getDrawable(ICONS[Math.max(0, Math.min(icon, ICONS.length - 1))]);
        if (glyph == null) return background;
        glyph = glyph.mutate();
        glyph.setTint(
                ColorUtils.shouldUseLightForegroundOnBackground(color) ? Color.WHITE : Color.BLACK);
        LayerDrawable layers = new LayerDrawable(new Drawable[] {background, glyph});
        int inset = Math.round(5 * context.getResources().getDisplayMetrics().density);
        layers.setLayerInset(1, inset, inset, inset, inset);
        return layers;
    }

    /**
     * Validate again at the user's selection, including source/profile and current-tab identity.
     */
    static boolean openInContainer(
            @Nullable Tab source,
            @Nullable Tab current,
            @Nullable TabCreatorManager creators,
            String id) {
        ThreadUtils.assertOnUiThread();
        if (source == null
                || source != current
                || source.isDestroyed()
                || source.isIncognito()
                || creators == null
                || !isUsableRegularProfile(source.getProfile())) return false;
        Profile profile = source.getProfile();
        if (!ContainersBridge.isEnabled(profile)
                || !ContainersBridge.isContainerIdValid(profile, id)) return false;
        TabCreator creator = creators.getTabCreator(false);
        if (!(creator instanceof ChromeTabCreator)) return false;
        Tab created =
                ((ChromeTabCreator) creator)
                        .createNewTabInContainer(
                                new LoadUrlParams(source.getUrl().getSpec()),
                                id, source);
        return created != null;
    }

    public static boolean showSelector(
            Activity activity,
            Supplier<@Nullable Tab> tabs,
            Supplier<@Nullable TabCreatorManager> creators) {
        ThreadUtils.assertOnUiThread();
        Tab source = tabs.get();
        if (activity.isFinishing()
                || activity.isDestroyed()
                || source == null
                || source.isDestroyed()
                || source.isIncognito()
                || !isUsableRegularProfile(source.getProfile())
                || !ContainersBridge.isEnabled(source.getProfile())) return false;
        Profile profile = source.getProfile();
        List<ContainersBridge.ContainerInfo> containers = ContainersBridge.getContainers(profile);
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        ids.add("");
        labels.add(activity.getString(R.string.reaper_container_default));
        for (ContainersBridge.ContainerInfo info : containers) {
            ids.add(info.id);
            labels.add(getDisplayName(activity, info.id, info));
        }
        for (int i = 0; i < ids.size(); ++i) {
            if (source.getContainerId().equals(ids.get(i))) {
                labels.set(i, activity.getString(R.string.reaper_container_current, labels.get(i)));
            }
        }
        labels.add(activity.getString(R.string.reaper_containers_manage));
        disambiguateSelectorLabels(activity, ids, labels);
        int iconSize = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        int iconPadding = Math.round(12 * activity.getResources().getDisplayMetrics().density);
        ArrayAdapter<String> choices =
                new ArrayAdapter<String>(activity, android.R.layout.select_dialog_item, labels) {
                    @Override
                    public View getView(int position, @Nullable View convertView, ViewGroup parent) {
                        TextView row = (TextView) super.getView(position, convertView, parent);
                        @Nullable Drawable icon = null;
                        if (position > 0 && position <= containers.size()) {
                            ContainersBridge.ContainerInfo info = containers.get(position - 1);
                            icon = getIcon(activity, info.icon, info.backgroundColor);
                            icon.setBounds(0, 0, iconSize, iconSize);
                        }
                        // Clear drawables for recycled Default/Manage rows too.
                        row.setCompoundDrawablesRelative(icon, null, null, null);
                        row.setCompoundDrawablePadding(iconPadding);
                        return row;
                    }
                };
        AlertDialog dialog =
                new AlertDialog.Builder(activity, R.style.ThemeOverlay_BrowserUI_AlertDialog)
                        .setTitle(R.string.reaper_container_open)
                        .setAdapter(
                                choices,
                                (ignored, position) -> {
                                    if (activity.isDestroyed() || activity.isFinishing()) return;
                                    if (position == ids.size()) {
                                        SettingsNavigationFactory.createSettingsNavigation()
                                                .startSettings(activity, ContainersSettings.class);
                                    } else if (!openInContainer(
                                            source,
                                            tabs.get(),
                                            creators.get(),
                                            ids.get(position))) {
                                        Toast.makeText(
                                                        activity,
                                                        R.string.reaper_container_unavailable,
                                                        Toast.LENGTH_SHORT)
                                                .show();
                                    }
                                })
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
        showWithLifecycle(activity, dialog);
        return true;
    }

    /** Keep duplicate metadata names legal while making the final displayed rows distinct. */
    static void disambiguateSelectorLabels(
            Context context, List<String> ids, List<String> labels) {
        Map<String, Integer> labelCounts = new HashMap<>();
        for (String label : labels) labelCounts.merge(label, 1, Integer::sum);
        // Reserve even unchanged names before generating suffixes: a legal container name
        // may itself look like another row's generated name or current-row decoration.
        Set<String> reservedLabels = new HashSet<>(labels);
        // IDs include Default at index 0; the final Manage row has no container ID.
        for (int i = 1; i < ids.size(); ++i) {
            String label = labels.get(i);
            if (labelCounts.getOrDefault(label, 0) < 2) continue;
            String id = ids.get(i);
            int prefixLength = Math.min(8, id.length());
            String suffix = id.substring(0, prefixLength);
            int ordinal = 2;
            while (true) {
                String candidate =
                        context.getString(
                                R.string.reaper_container_selector_distinct_name, label, suffix);
                if (reservedLabels.add(candidate)) {
                    labels.set(i, candidate);
                    break;
                }
                if (prefixLength < id.length()) {
                    suffix = id.substring(0, ++prefixLength);
                } else {
                    // Even a full-ID candidate can match an existing literal name.
                    suffix = id + "-" + ordinal++;
                }
            }
        }
    }

    private static Spinner addChoices(
            LinearLayout form, int label, CharSequence[] values, int selected) {
        Context context = form.getContext();
        TextView title = new TextView(context);
        title.setText(label);
        Spinner choice = new Spinner(context);
        choice.setId(View.generateViewId());
        title.setLabelFor(choice.getId());
        choice.setContentDescription(context.getString(label));
        ArrayAdapter<CharSequence> adapter =
                new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        choice.setAdapter(adapter);
        choice.setSelection(Math.max(0, Math.min(selected, adapter.getCount() - 1)));
        form.addView(title);
        form.addView(choice);
        return choice;
    }

    public static AlertDialog showEditor(
            Activity activity,
            Profile profile,
            ContainersBridge.@Nullable ContainerInfo info,
            Runnable refresh) {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding, padding, padding);
        EditText name = new EditText(activity);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setHint(R.string.reaper_container_name);
        name.setContentDescription(activity.getString(R.string.reaper_container_name));
        name.setText(info == null ? "" : info.name);
        form.addView(name);
        Spinner icon =
                addChoices(
                        form,
                        R.string.reaper_container_icon,
                        activity.getResources().getStringArray(R.array.reaper_container_icons),
                        info == null ? 0 : info.icon);
        List<Integer> colors = new ArrayList<>();
        for (int value : COLORS) colors.add(value);
        List<String> colorLabels =
                new ArrayList<>(
                        List.of(
                                activity.getResources()
                                        .getStringArray(R.array.reaper_container_colors)));
        int currentColor = info == null ? COLORS[0] : (info.backgroundColor | 0xff000000);
        int colorIndex = colors.indexOf(currentColor);
        if (colorIndex < 0) {
            colorIndex = colors.size();
            colors.add(currentColor);
            colorLabels.add(activity.getString(R.string.reaper_container_current_color));
        }
        Spinner color =
                addChoices(
                        form,
                        R.string.reaper_container_color,
                        colorLabels.toArray(new String[0]),
                        colorIndex);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);
        AlertDialog dialog =
                new AlertDialog.Builder(activity)
                        .setTitle(
                                info == null
                                        ? R.string.reaper_container_add
                                        : R.string.reaper_container_edit)
                        .setView(scroll)
                        .setPositiveButton(R.string.reaper_container_save, null)
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
        dialog.setOnShowListener(
                ignored ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                .setOnClickListener(
                                        view -> {
                                            if (activity.isDestroyed()
                                                    || activity.isFinishing()
                                                    || !dialog.isShowing()) return;
                                            String text = name.getText().toString().trim();
                                            if (!hasNameContent(text)) {
                                                name.setError(
                                                        activity.getString(
                                                                R.string
                                                                        .reaper_container_invalid_name));
                                                name.requestFocus();
                                                return;
                                            }
                                            if (!isUsableRegularProfile(profile)
                                                    || !ContainersBridge.isEnabled(profile)) {
                                                Toast.makeText(
                                                                activity,
                                                                R.string
                                                                        .reaper_container_operation_failed,
                                                                Toast.LENGTH_SHORT)
                                                        .show();
                                                return;
                                            }
                                            view.setEnabled(false);
                                            org.chromium.base.Callback<Integer> callback =
                                                    result -> {
                                                        if (activity.isDestroyed()
                                                                || activity.isFinishing()
                                                                || !dialog.isShowing()) return;
                                                        if (result == ContainersBridge.SUCCESS) {
                                                            dialog.dismiss();
                                                            refresh.run();
                                                        } else {
                                                            view.setEnabled(true);
                                                            if (result == 3) {
                                                                name.setError(
                                                                        activity.getString(
                                                                                R.string
                                                                                        .reaper_container_invalid_name));
                                                                name.requestFocus();
                                                            } else {
                                                                Toast.makeText(
                                                                                activity,
                                                                                R.string
                                                                                        .reaper_container_operation_failed,
                                                                                Toast.LENGTH_SHORT)
                                                                        .show();
                                                            }
                                                        }
                                                    };
                                            int selectedIcon = icon.getSelectedItemPosition();
                                            int selectedColor =
                                                    colors.get(color.getSelectedItemPosition());
                                            if (info == null) {
                                                ContainersBridge.addContainer(
                                                        profile,
                                                        text,
                                                        selectedIcon,
                                                        selectedColor,
                                                        callback);
                                            } else {
                                                ContainersBridge.updateContainer(
                                                        profile,
                                                        info.id,
                                                        text,
                                                        selectedIcon,
                                                        selectedColor,
                                                        callback);
                                            }
                                        }));
        showWithLifecycle(activity, dialog);
        return dialog;
    }

    public static AlertDialog showActions(
            Activity activity,
            Profile profile,
            ContainersBridge.ContainerInfo info,
            Runnable refresh) {
        AlertDialog dialog =
                new AlertDialog.Builder(activity)
                        .setTitle(getDisplayName(activity, info.id, info))
                        .setItems(
                                new String[] {
                                    activity.getString(R.string.reaper_container_edit),
                                    activity.getString(R.string.reaper_container_remove)
                                },
                                (ignored, position) -> {
                                    if (activity.isDestroyed()
                                            || activity.isFinishing()
                                            || !isUsableRegularProfile(profile)
                                            || !ContainersBridge.isEnabled(profile)) return;
                                    if (position == 0) {
                                        showEditor(activity, profile, info, refresh);
                                    } else {
                                        AlertDialog confirmation =
                                                new AlertDialog.Builder(activity)
                                                        .setTitle(R.string.reaper_container_remove)
                                                        .setMessage(
                                                                activity.getString(
                                                                        R.string
                                                                                .reaper_container_remove_summary,
                                                                        getDisplayName(
                                                                                activity,
                                                                                info.id,
                                                                                info)))
                                                        .setNegativeButton(
                                                                android.R.string.cancel, null)
                                                        .setPositiveButton(
                                                                R.string.reaper_container_remove,
                                                                (which, button) -> {
                                                                    if (activity.isDestroyed()
                                                                            || activity
                                                                                    .isFinishing()
                                                                            || !isUsableRegularProfile(
                                                                                    profile)
                                                                            || !ContainersBridge
                                                                                    .isEnabled(
                                                                                            profile))
                                                                        return;
                                                                    ContainersBridge
                                                                            .removeContainer(
                                                                                    profile,
                                                                                    info.id,
                                                                                    result -> {
                                                                                        if (activity
                                                                                                        .isDestroyed()
                                                                                                || activity
                                                                                                        .isFinishing())
                                                                                            return;
                                                                                        if (result
                                                                                                == ContainersBridge
                                                                                                        .SUCCESS)
                                                                                            refresh
                                                                                                    .run();
                                                                                        else
                                                                                            Toast
                                                                                                    .makeText(
                                                                                                            activity,
                                                                                                            R
                                                                                                                    .string
                                                                                                                    .reaper_container_operation_failed,
                                                                                                            Toast
                                                                                                                    .LENGTH_SHORT)
                                                                                                    .show();
                                                                                    });
                                                                })
                                                        .create();
                                        showWithLifecycle(activity, confirmation);
                                    }
                                })
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
        showWithLifecycle(activity, dialog);
        return dialog;
    }

    private static void showWithLifecycle(Activity activity, AlertDialog dialog) {
        if (activity.isDestroyed() || activity.isFinishing()) return;
        if (activity instanceof LifecycleOwner) {
            LifecycleOwner owner = (LifecycleOwner) activity;
            DefaultLifecycleObserver observer =
                    new DefaultLifecycleObserver() {
                        @Override
                        public void onDestroy(LifecycleOwner ignored) {
                            dialog.dismiss();
                        }
                    };
            owner.getLifecycle().addObserver(observer);
            dialog.setOnDismissListener(ignored -> owner.getLifecycle().removeObserver(observer));
        }
        dialog.show();
    }
}
