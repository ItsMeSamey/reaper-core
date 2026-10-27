/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.components.browser_ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.containers.ContainersSettings;
import org.chromium.chrome.browser.containers.ContainersUi;
import org.chromium.components.browser_ui.styles.R;
import org.chromium.components.browser_ui.styles.SemanticColorUtils;

/** Unit tests for {@link BraveSettingsIconTintUtils}. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = Build.VERSION_CODES.S)
public class BraveSettingsIconTintUtilsTest {
    private static final int[] DISABLED_STATE_SET = new int[] {-android.R.attr.state_enabled};
    private static final int[] ENABLED_STATE_SET = new int[] {android.R.attr.state_enabled};
    private static final int[] DEFAULT_STATE_SET = new int[] {};

    @Test
    public void testApplyIconTint_dynamicColorsDisabled_usesDefaultTint() {
        assertUsesDefaultTint();
    }

    @Test
    public void testApplyIconTint_dynamicColorsEnabled_preservesDisabledTint() {
        ColorStateList defaultTint = createDefaultTint();
        Context context =
                new DefaultTintContext(
                        new ContextThemeWrapper(
                                ApplicationProvider.getApplicationContext(),
                                R.style.Theme_MaterialComponents_DayNight),
                        defaultTint);
        TintCapturingImageView icon = new TintCapturingImageView(context);

        BraveSettingsIconTintUtils.applyIconTint(icon, true);

        ColorStateList actualTint = icon.getCapturedTint();
        assertNotNull(actualTint);
        assertEquals(
                defaultTint.getColorForState(DISABLED_STATE_SET, defaultTint.getDefaultColor()),
                actualTint.getColorForState(DISABLED_STATE_SET, actualTint.getDefaultColor()));
        assertEquals(
                SemanticColorUtils.getDefaultIconColorAccent1(context),
                actualTint.getColorForState(ENABLED_STATE_SET, actualTint.getDefaultColor()));
    }

    @Test
    public void testClearIconTint_removesPreviouslyAppliedTint() {
        Context context =
                new DefaultTintContext(
                        ApplicationProvider.getApplicationContext(), createDefaultTint());
        TintCapturingImageView icon = new TintCapturingImageView(context);
        BraveSettingsIconTintUtils.applyIconTint(icon, false);
        icon.setColorFilter(0xffaabbcc);

        assertNotNull(icon.getCapturedTint());
        assertNotNull(icon.getColorFilter());

        BraveSettingsIconTintUtils.clearIconTint(icon);

        assertNull(icon.getCapturedTint());
        assertNull(icon.getColorFilter());

        assertContainerRowsClearTintOnRebind();
    }

    private void assertContainerRowsClearTintOnRebind() {
        Context context =
                new DefaultTintContext(
                        new ContextThemeWrapper(
                                ApplicationProvider.getApplicationContext(),
                                org.chromium.chrome.R.style.Theme_Chromium_Settings),
                        createDefaultTint());
        PreferenceScreen screen = new PreferenceManager(context).createPreferenceScreen(context);
        ChromeBasePreference normal = new ChromeBasePreference(context);
        normal.setKey("reaper_container_add");
        normal.setIcon(new ColorDrawable(0xffaabbcc));
        screen.addPreference(normal);
        PreferenceCategory containers = new PreferenceCategory(context);
        containers.setKey("reaper_container_list");
        screen.addPreference(containers);
        ChromeBasePreference container = new ChromeBasePreference(context);
        container.setKey("reaper_container_11111111-1111-4111-8111-111111111111");
        containers.addPreference(container);

        TestContainersSettings settings = new TestContainersSettings();
        try {
            PreferenceGroupAdapter adapter = settings.onCreateAdapter(screen);
            int normalPosition = adapter.getPreferenceAdapterPosition(normal);
            int containerPosition = adapter.getPreferenceAdapterPosition(container);
            assertEquals(
                    adapter.getItemViewType(normalPosition),
                    adapter.getItemViewType(containerPosition));
            PreferenceViewHolder holder =
                    adapter.onCreateViewHolder(
                            new FrameLayout(context), adapter.getItemViewType(normalPosition));
            ImageView rowIcon = (ImageView) holder.findViewById(android.R.id.icon);
            assertNotNull(rowIcon);

            for (int backgroundColor : new int[] {Color.BLACK, Color.WHITE}) {
                int foregroundColor =
                        backgroundColor == Color.BLACK ? Color.WHITE : Color.BLACK;
                // Own the layer state before installing the recorder; ImageView.mutate()
                // must not clone the recorder through ColorDrawable's base ConstantState.
                LayerDrawable containerIcon =
                        (LayerDrawable) ContainersUi.getIcon(context, 0, backgroundColor).mutate();
                TintCapturingDrawable glyph = new TintCapturingDrawable();
                containerIcon.setDrawable(1, glyph);
                glyph.setTint(foregroundColor);
                container.setIcon(containerIcon);

                // A fresh bind and a recycled normal row must both retain glyph contrast.
                adapter.onBindViewHolder(holder, containerPosition);
                assertContainerIcon(rowIcon, containerIcon, glyph, foregroundColor);
                int clearsBeforeRecycling = glyph.getTintClearCount();
                adapter.onBindViewHolder(holder, normalPosition);
                assertNotNull(rowIcon.getImageTintList());
                adapter.onBindViewHolder(holder, containerPosition);
                assertContainerIcon(rowIcon, containerIcon, glyph, foregroundColor);
                assertTrue(glyph.getTintClearCount() > clearsBeforeRecycling);

                // Reuse in the other direction must retain normal settings tint behavior.
                adapter.onBindViewHolder(holder, normalPosition);
                assertNotNull(rowIcon.getImageTintList());
            }
        } finally {
            // Constructing the fragment registers its metadata observer, even before attachment.
            settings.onDestroy();
        }
    }

    private void assertContainerIcon(
            ImageView rowIcon,
            LayerDrawable expectedIcon,
            TintCapturingDrawable glyph,
            int foregroundColor) {
        assertSame(expectedIcon, rowIcon.getDrawable());
        assertNull(rowIcon.getImageTintList());
        assertNull(rowIcon.getColorFilter());
        assertTrue(glyph.getTintClearCount() > 0);
        assertNotNull(glyph.getCapturedTint());
        assertEquals(foregroundColor, glyph.getCapturedTint().getDefaultColor());
    }

    private static class TintCapturingDrawable extends ColorDrawable {
        private ColorStateList mTint;
        private int mTintClearCount;

        @Override
        public void setTintList(ColorStateList tint) {
            super.setTintList(tint);
            mTint = tint;
            if (tint == null) mTintClearCount++;
        }

        ColorStateList getCapturedTint() {
            return mTint;
        }

        int getTintClearCount() {
            return mTintClearCount;
        }
    }

    private static class TestContainersSettings extends ContainersSettings {
        @Override
        public PreferenceGroupAdapter onCreateAdapter(PreferenceScreen screen) {
            return super.onCreateAdapter(screen);
        }
    }

    private void assertUsesDefaultTint() {
        ColorStateList expectedTint = createDefaultTint();
        Context context =
                new DefaultTintContext(ApplicationProvider.getApplicationContext(), expectedTint);
        TintCapturingImageView icon = new TintCapturingImageView(context);

        BraveSettingsIconTintUtils.applyIconTint(icon, false);

        assertSame(expectedTint, icon.getCapturedTint());
    }

    private static ColorStateList createDefaultTint() {
        return new ColorStateList(
                new int[][] {DISABLED_STATE_SET, DEFAULT_STATE_SET},
                new int[] {0xffaabbcc, 0xff112233});
    }

    private static class DefaultTintContext extends ContextWrapper {
        private final Resources mResources;

        DefaultTintContext(Context base, ColorStateList defaultTint) {
            super(base);
            mResources = new DefaultTintResources(base.getResources(), defaultTint);
        }

        @Override
        public Resources getResources() {
            return mResources;
        }
    }

    private static class DefaultTintResources extends Resources {
        private final ColorStateList mDefaultTint;

        DefaultTintResources(Resources base, ColorStateList defaultTint) {
            super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
            mDefaultTint = defaultTint;
        }

        @Override
        public ColorStateList getColorStateList(int resId) {
            if (resId == R.color.default_icon_color_tint_list) {
                return mDefaultTint;
            }
            return super.getColorStateList(resId);
        }

        @Override
        public ColorStateList getColorStateList(int resId, Theme theme) {
            if (resId == R.color.default_icon_color_tint_list) {
                return mDefaultTint;
            }
            return super.getColorStateList(resId, theme);
        }
    }

    private static class TintCapturingImageView extends ImageView {
        private ColorStateList mTint;

        TintCapturingImageView(Context context) {
            super(context);
        }

        @Override
        public void setImageTintList(ColorStateList tint) {
            super.setImageTintList(tint);
            mTint = tint;
        }

        ColorStateList getCapturedTint() {
            return mTint;
        }
    }
}
