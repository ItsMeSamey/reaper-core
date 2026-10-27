/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.test.filters.SmallTest;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.chromium.base.BraveFeatureList;
import org.chromium.base.ContextUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.base.test.util.CriteriaHelper;
import org.chromium.base.test.util.DoNotBatch;
import org.chromium.base.test.util.Features.DisableFeatures;
import org.chromium.base.test.util.Features.EnableFeatures;
import org.chromium.chrome.browser.preferences.ChromePreferenceKeys;
import org.chromium.chrome.browser.tasks.tab_management.TabsSettings;
import org.chromium.chrome.browser.tracing.settings.DeveloperSettings;
import org.chromium.chrome.test.ChromeJUnit4ClassRunner;

/** Test for {@link MainSettings}. Main purpose is to have a quick confidence check on the xml. */
@RunWith(ChromeJUnit4ClassRunner.class)
@DoNotBatch(reason = "Tests cannot run batched because they launch a Settings activity.")
public class BraveMainSettingsFragmentTest {
    private static final String PREF_BRAVE_ORIGIN = "brave_origin";

    @Rule
    public final SettingsActivityTestRule<MainSettings> mSettingsActivityTestRule =
            new SettingsActivityTestRule<>(MainSettings.class);

    private MainSettings mMainSettings;

    @Before
    public void setup() {
        Looper.prepare();
        DeveloperSettings.setIsEnabledForTests(true);
    }

    private static final String[] sSortedPrefKeys = {
        "brave_account",
        "features_section",
        "brave_shields_and_privacy",
        "reaper_extensions",
        "global_extension_shortcuts",
        "extension_push_messaging",
        "reaper_containers",
        "general_section",
        "brave_search_engines",
        "homepage",
        "home_screen_widget",
        "brave_sync_layout",
        "brave_stats",
        "notifications",
        "content_settings",
        "brave_downloads",
        "closing_all_tabs_closes_brave",
        "default_browser",
        "display_section",
        "tabs",
        "media",
        "brave_appearance",
        "accessibility",
        "brave_languages",
        "autofill_section",
        "passwords",
        "autofill_options",
        "autofill_payment_methods",
        "autofill_addresses",
        "support_section",
        "about_section",
        "developer",
        "about_chrome",
    };

    // This test is intended to check the issue where prefs order was wrong
    // when app language was switched to some other than default English,
    // like French. The test infrastructure does not support switching app
    // interface language. So the test may help to catch order violation
    // with English.
    @Test
    @SmallTest
    @DisableFeatures(BraveFeatureList.BRAVE_ANDROID_TAB_GROUPS_SETTINGS)
    public void testMainSettingsPrefsOrder() {
        startSettings();

        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    @Nullable Preference prevPref = null;
                    for (String key : sSortedPrefKeys) {
                        Preference pref = mMainSettings.getPreferenceScreen().findPreference(key);
                        // Account, feature and device-dependent rows can be absent.
                        if (pref == null) continue;
                        if (prevPref != null) {
                            assertTrue(
                                    prevPref.getTitle() + " should precede " + pref.getTitle(),
                                    pref.getOrder() > prevPref.getOrder());
                        }
                        prevPref = pref;
                    }
                });
    }

    @Test
    @SmallTest
    @DisableFeatures(BraveFeatureList.BRAVE_ANDROID_TAB_GROUPS_SETTINGS)
    public void testLegacyTabRowsShownWhenTabGroupSettingsFeatureDisabled() {
        startSettings();

        Preference tabsPreference = mMainSettings.findPreference(MainSettings.PREF_TABS);
        Preference closingAllTabsClosesBravePreference =
                mMainSettings
                        .getPreferenceScreen()
                        .findPreference(
                                BraveMainPreferencesBase.PREF_CLOSING_ALL_TABS_CLOSES_BRAVE);

        assertNotNull(tabsPreference);
        assertNotNull(closingAllTabsClosesBravePreference);
        assertTrue(closingAllTabsClosesBravePreference.isVisible());
        assertEquals(TabsSettings.class.getName(), tabsPreference.getFragment());
    }

    @Test
    @SmallTest
    @EnableFeatures(BraveFeatureList.BRAVE_ANDROID_TAB_GROUPS_SETTINGS)
    public void testLegacyTabRowsHiddenWhenTabGroupSettingsFeatureEnabled() {
        startSettings();

        Preference tabsPreference = mMainSettings.findPreference(MainSettings.PREF_TABS);
        Preference closingAllTabsClosesBravePreference =
                mMainSettings
                        .getPreferenceScreen()
                        .findPreference(
                                BraveMainPreferencesBase.PREF_CLOSING_ALL_TABS_CLOSES_BRAVE);

        assertNotNull(tabsPreference);
        assertNull(closingAllTabsClosesBravePreference);
        assertEquals(BraveTabsAndTabGroupsSettings.class.getName(), tabsPreference.getFragment());
    }

    @Test
    @SmallTest
    public void testRemovedOriginPreferenceIsAbsent() {
        startSettings();
        CriteriaHelper.pollUiThread(
                () ->
                        mMainSettings.getPreferenceScreen().findPreference(PREF_BRAVE_ORIGIN) == null
                                && mMainSettings.getPreferenceScreen()
                                                .findPreference("brave_shields_and_privacy")
                                        != null
                                && mMainSettings.getPreferenceScreen()
                                                .findPreference("brave_sync_layout")
                                        != null,
                "Origin must be absent while Shields and Sync remain available");
    }

    @Test
    @SmallTest
    public void testRequiredAndExcludedPreferences() {
        startSettings();
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    for (String key :
                            new String[] {
                                "brave_shields_and_privacy", "brave_sync_layout", "reaper_extensions"
                            }) {
                        assertNotNull(key, mMainSettings.getPreferenceScreen().findPreference(key));
                    }
                    for (String key :
                            new String[] {
                                "brave_news_v2", "brave_wallet", "brave_vpn", "pref_vpn_callout",
                                "brave_rewards", "rate_brave", "brave_origin"
                            }) {
                        assertNull(key, mMainSettings.getPreferenceScreen().findPreference(key));
                    }
                });
    }

    // Brave shows the address bar preference in its own Appearance settings. Upstream re-adds it to
    // the main list on a configuration change and on a toolbar position change, neither of which
    // goes through onResume, so both paths must stay covered.
    @Test
    @SmallTest
    public void testAddressBarPrefNeverShown() {
        startSettings();

        assertAddressBarPrefAbsent("on start");

        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        mMainSettings.onConfigurationChanged(
                                mMainSettings.getResources().getConfiguration()));
        assertAddressBarPrefAbsent("after a configuration change");

        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        mMainSettings.onSharedPreferenceChanged(
                                ContextUtils.getAppSharedPreferences(),
                                ChromePreferenceKeys.TOOLBAR_TOP_ANCHORED));
        assertAddressBarPrefAbsent("after a toolbar position change");
    }

    private void assertAddressBarPrefAbsent(String when) {
        // getPreferenceScreen() is checked directly, as BraveMainPreferencesBase.findPreference()
        // also returns removed preferences.
        assertNull(
                MainSettings.PREF_ADDRESS_BAR + " should not be in the main settings " + when,
                mMainSettings.getPreferenceScreen().findPreference(MainSettings.PREF_ADDRESS_BAR));
    }

    private void startSettings() {
        mSettingsActivityTestRule.startSettingsActivity();
        mMainSettings = mSettingsActivityTestRule.getFragment();
        Assert.assertNotNull("SettingsActivity failed to launch.", mMainSettings);
    }
}
