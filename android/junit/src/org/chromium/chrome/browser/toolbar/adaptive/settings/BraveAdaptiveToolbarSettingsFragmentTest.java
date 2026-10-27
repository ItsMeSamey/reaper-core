/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.toolbar.adaptive.settings;

import static org.chromium.chrome.browser.preferences.ChromePreferenceKeys.ADAPTIVE_TOOLBAR_CUSTOMIZATION_ENABLED;
import static org.chromium.chrome.browser.preferences.ChromePreferenceKeys.ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;

import android.os.Bundle;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentFactory;
import androidx.fragment.app.testing.FragmentScenario;
import androidx.test.filters.SmallTest;

import org.chromium.base.Callback;
import org.chromium.base.ContextUtils;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.omnibox.voice.VoiceRecognitionUtil;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.search_engines.TemplateUrlServiceFactory;
import org.chromium.chrome.browser.settings.ProfileDependentSetting;
import org.chromium.chrome.browser.signin.services.UnifiedConsentServiceBridge;
import org.chromium.chrome.browser.toolbar.R;
import org.chromium.chrome.browser.toolbar.adaptive.AdaptiveToolbarBehavior;
import org.chromium.chrome.browser.toolbar.adaptive.AdaptiveToolbarButtonVariant;
import org.chromium.chrome.browser.toolbar.adaptive.AdaptiveToolbarStatePredictor;
import org.chromium.chrome.browser.toolbar.adaptive.BraveAdaptiveToolbarPrefs;
import org.chromium.chrome.browser.toolbar.adaptive.BraveAdaptiveToolbarStatePredictor;
import org.chromium.components.browser_ui.settings.ChromeSwitchPreference;
import org.chromium.components.browser_ui.widget.RadioButtonWithDescription;
import org.chromium.components.prefs.PrefService;
import org.chromium.components.search_engines.TemplateUrlService;
import org.chromium.components.user_prefs.UserPrefsJni;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Tests for {@link AdaptiveToolbarSettingsFragment}. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class BraveAdaptiveToolbarSettingsFragmentTest {

    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock Profile mProfile;
    @Mock private UserPrefsJni mUserPrefsNatives;
    @Mock private PrefService mPrefService;
    @Mock private TemplateUrlService mTemplateUrlService;
    @Mock private AdaptiveToolbarBehavior mToolbarBehavior;

    private ChromeSwitchPreference mSwitchPreference;
    private RadioButtonGroupAdaptiveToolbarPreference mRadioPreference;

    @Before
    public void setUpTest() throws Exception {
        UserPrefsJni.setInstanceForTesting(mUserPrefsNatives);
        doReturn(mPrefService).when(mUserPrefsNatives).get(any());

        ChromeSharedPreferences.getInstance().removeKey(ADAPTIVE_TOOLBAR_CUSTOMIZATION_ENABLED);
        ChromeSharedPreferences.getInstance().removeKey(ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS);
        AdaptiveToolbarStatePredictor.setSegmentationResultsForTesting(
                new Pair<>(
                        false,
                        List.of(
                                AdaptiveToolbarButtonVariant.NEW_TAB,
                                AdaptiveToolbarButtonVariant.SHARE)));

        VoiceRecognitionUtil.setIsVoiceSearchEnabledForTesting(true);
        UnifiedConsentServiceBridge.setUrlKeyedAnonymizedDataCollectionEnabled(true);

        TemplateUrlServiceFactory.setInstanceForTesting(mTemplateUrlService);
    }

    @After
    public void tearDownTest() throws Exception {
        AdaptiveToolbarStatePredictor.setSegmentationResultsForTesting(null);
        ChromeSharedPreferences.getInstance().removeKey(ADAPTIVE_TOOLBAR_CUSTOMIZATION_ENABLED);
        ChromeSharedPreferences.getInstance().removeKey(ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS);
    }

    @Test
    @SmallTest
    public void testSelectShortcuts() {
        FragmentScenario<AdaptiveToolbarSettingsFragment> scenario = buildFragmentScenario();
        scenario.onFragment(
                fragment -> {
                    mSwitchPreference =
                            (ChromeSwitchPreference)
                                    fragment.findPreference(
                                            AdaptiveToolbarSettingsFragment
                                                    .PREF_TOOLBAR_SHORTCUT_SWITCH);
                    mRadioPreference =
                            (RadioButtonGroupAdaptiveToolbarPreference)
                                    fragment.findPreference(
                                            AdaptiveToolbarSettingsFragment
                                                    .PREF_ADAPTIVE_RADIO_GROUP);

                    Assert.assertFalse(
                            ChromeSharedPreferences.getInstance()
                                    .contains(ADAPTIVE_TOOLBAR_CUSTOMIZATION_ENABLED));
                    Assert.assertFalse(
                            BraveAdaptiveToolbarPrefs.isCustomizationPreferenceEnabled());

                    mSwitchPreference.performClick();
                    Assert.assertTrue(BraveAdaptiveToolbarPrefs.isCustomizationPreferenceEnabled());
                    Assert.assertTrue(
                            ChromeSharedPreferences.getInstance()
                                    .contains(ADAPTIVE_TOOLBAR_CUSTOMIZATION_ENABLED));
                    Assert.assertTrue(
                            ChromeSharedPreferences.getInstance()
                                    .readBoolean(ADAPTIVE_TOOLBAR_CUSTOMIZATION_ENABLED, false));

                    int expectedDefaultShortcut = AdaptiveToolbarButtonVariant.NEW_TAB;
                    Assert.assertEquals(
                            "Incorrect default setting.",
                            expectedDefaultShortcut,
                            BraveAdaptiveToolbarPrefs.getCustomizationSetting());
                    assertButtonCheckedCorrectly("Based on your usage", expectedDefaultShortcut);

                    // Check that Auto button is not visible.
                    Assert.assertEquals(
                            R.id.adaptive_option_based_on_usage,
                            getButton(AdaptiveToolbarButtonVariant.AUTO).getId());
                    Assert.assertFalse(
                            getButton(AdaptiveToolbarButtonVariant.AUTO).isVisibleToUser());

                    // Select New tab
                    Assert.assertEquals(
                            R.id.adaptive_option_new_tab,
                            getButton(AdaptiveToolbarButtonVariant.NEW_TAB).getId());
                    selectButton(AdaptiveToolbarButtonVariant.NEW_TAB);
                    assertButtonCheckedCorrectly("New tab", AdaptiveToolbarButtonVariant.NEW_TAB);
                    Assert.assertEquals(
                            AdaptiveToolbarButtonVariant.NEW_TAB, mRadioPreference.getSelection());
                    Assert.assertEquals(
                            AdaptiveToolbarButtonVariant.NEW_TAB,
                            ChromeSharedPreferences.getInstance()
                                    .readInt(ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS));

                    // Select Share
                    Assert.assertEquals(
                            R.id.adaptive_option_share,
                            getButton(AdaptiveToolbarButtonVariant.SHARE).getId());
                    selectButton(AdaptiveToolbarButtonVariant.SHARE);
                    assertButtonCheckedCorrectly("Share", AdaptiveToolbarButtonVariant.SHARE);
                    Assert.assertEquals(
                            AdaptiveToolbarButtonVariant.SHARE, mRadioPreference.getSelection());
                    Assert.assertEquals(
                            AdaptiveToolbarButtonVariant.SHARE,
                            ChromeSharedPreferences.getInstance()
                                    .readInt(ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS));

                    // Select Voice search
                    Assert.assertEquals(
                            R.id.adaptive_option_voice_search,
                            getButton(AdaptiveToolbarButtonVariant.VOICE).getId());
                    selectButton(AdaptiveToolbarButtonVariant.VOICE);
                    assertButtonCheckedCorrectly(
                            "Voice search", AdaptiveToolbarButtonVariant.VOICE);
                    Assert.assertEquals(
                            AdaptiveToolbarButtonVariant.VOICE, mRadioPreference.getSelection());
                    Assert.assertEquals(
                            AdaptiveToolbarButtonVariant.VOICE,
                            ChromeSharedPreferences.getInstance()
                                    .readInt(ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS));

                    // All toolbar variants (upstream and Brave) are persisted to SharedPreferences
                    // as integers. Upstream guards against shifts by using explicit numeric
                    // assignments in the header. Brave values must do the same — if they are
                    // defined implicitly and upstream inserts a new entry above them, the whole
                    // range shifts and users lose their saved setting.
                    Assert.assertEquals(17, AdaptiveToolbarButtonVariant.BOOKMARKS);
                    Assert.assertEquals(18, AdaptiveToolbarButtonVariant.HISTORY);
                    Assert.assertEquals(19, AdaptiveToolbarButtonVariant.DOWNLOADS);
                    Assert.assertEquals(20, AdaptiveToolbarButtonVariant.PLAYLIST);
                    Assert.assertEquals(21, AdaptiveToolbarButtonVariant.LEO);
                    Assert.assertEquals(22, AdaptiveToolbarButtonVariant.WALLET);
                    Assert.assertEquals(23, AdaptiveToolbarButtonVariant.NEWS);
                    Assert.assertEquals(24, AdaptiveToolbarButtonVariant.GLIC);
                    Assert.assertEquals(
                            AdaptiveToolbarButtonVariant.GLIC,
                            AdaptiveToolbarButtonVariant.MAX_VALUE);

                    // Test Bookmarks button
                    Assert.assertEquals(
                            R.id.adaptive_option_bookmarks,
                            getButton(AdaptiveToolbarButtonVariant.BOOKMARKS).getId());
                    selectButton(AdaptiveToolbarButtonVariant.BOOKMARKS);
                    assertButtonCheckedCorrectly(
                            "Bookmarks", AdaptiveToolbarButtonVariant.BOOKMARKS);
                    Assert.assertEquals(
                            AdaptiveToolbarButtonVariant.BOOKMARKS,
                            mRadioPreference.getSelection());
                    Assert.assertEquals(
                            AdaptiveToolbarButtonVariant.BOOKMARKS,
                            ChromeSharedPreferences.getInstance()
                                    .readInt(ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS));

                    // Test History button
                    Assert.assertEquals(
                            R.id.adaptive_option_history,
                            getButton(AdaptiveToolbarButtonVariant.HISTORY).getId());
                    selectButton(AdaptiveToolbarButtonVariant.HISTORY);
                    assertButtonCheckedCorrectly("History", AdaptiveToolbarButtonVariant.HISTORY);
                    Assert.assertEquals(
                            AdaptiveToolbarButtonVariant.HISTORY, mRadioPreference.getSelection());
                    Assert.assertEquals(
                            AdaptiveToolbarButtonVariant.HISTORY,
                            ChromeSharedPreferences.getInstance()
                                    .readInt(ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS));

                    // Test Downloads button
                    Assert.assertEquals(
                            R.id.adaptive_option_downloads,
                            getButton(AdaptiveToolbarButtonVariant.DOWNLOADS).getId());
                    selectButton(AdaptiveToolbarButtonVariant.DOWNLOADS);
                    assertButtonCheckedCorrectly(
                            "Downloads", AdaptiveToolbarButtonVariant.DOWNLOADS);
                    Assert.assertEquals(
                            AdaptiveToolbarButtonVariant.DOWNLOADS,
                            mRadioPreference.getSelection());
                    Assert.assertEquals(
                            AdaptiveToolbarButtonVariant.DOWNLOADS,
                            ChromeSharedPreferences.getInstance()
                                    .readInt(ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS));
                });
    }

    @Test
    @SmallTest
    @Config(qualifiers = "sw360dp")
    public void testPersistedLeoUpgradeOnPhone() {
        assertRemovedOverrideMigrated(
                AdaptiveToolbarButtonVariant.LEO, AdaptiveToolbarButtonVariant.NEW_TAB);
    }

    @Test
    @SmallTest
    @Config(qualifiers = "sw360dp")
    public void testPersistedWalletUpgradeOnPhone() {
        assertRemovedOverrideMigrated(
                AdaptiveToolbarButtonVariant.WALLET, AdaptiveToolbarButtonVariant.NEW_TAB);
    }

    @Test
    @SmallTest
    @Config(qualifiers = "sw600dp")
    public void testPersistedLeoUpgradeOnTablet() {
        assertRemovedOverrideMigrated(
                AdaptiveToolbarButtonVariant.LEO, AdaptiveToolbarButtonVariant.SHARE);
    }

    @Test
    @SmallTest
    @Config(qualifiers = "sw600dp")
    public void testPersistedWalletUpgradeOnTablet() {
        assertRemovedOverrideMigrated(
                AdaptiveToolbarButtonVariant.WALLET, AdaptiveToolbarButtonVariant.SHARE);
    }

    @Test
    @SmallTest
    public void testValidPersistedOverrideIsPreserved() {
        ChromeSharedPreferences.getInstance()
                .writeInt(
                        ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS,
                        AdaptiveToolbarButtonVariant.BOOKMARKS);
        Assert.assertEquals(
                AdaptiveToolbarButtonVariant.BOOKMARKS,
                BraveAdaptiveToolbarPrefs.getCustomizationSetting());
        Assert.assertEquals(
                AdaptiveToolbarButtonVariant.BOOKMARKS,
                ChromeSharedPreferences.getInstance()
                        .readInt(ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS));
        Assert.assertFalse(
                ChromeSharedPreferences.getInstance()
                        .contains(ADAPTIVE_TOOLBAR_CUSTOMIZATION_ENABLED));
    }

    @Test
    @SmallTest
    public void testDelayedLeoSegmentationResultIsRejected() {
        assertDelayedRemovedSegmentRejected(AdaptiveToolbarButtonVariant.LEO);
    }

    @Test
    @SmallTest
    public void testDelayedWalletSegmentationResultIsRejected() {
        assertDelayedRemovedSegmentRejected(AdaptiveToolbarButtonVariant.WALLET);
    }

    private void assertRemovedOverrideMigrated(int removedVariant, int expectedDefault) {
        ChromeSharedPreferences.getInstance()
                .writeInt(ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS, removedVariant);
        ChromeSharedPreferences.getInstance()
                .writeBoolean(ADAPTIVE_TOOLBAR_CUSTOMIZATION_ENABLED, true);
        Assert.assertEquals(expectedDefault, BraveAdaptiveToolbarPrefs.getCustomizationSetting());
        Assert.assertEquals(
                expectedDefault,
                ChromeSharedPreferences.getInstance()
                        .readInt(ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS));
        Assert.assertTrue(BraveAdaptiveToolbarPrefs.isCustomizationPreferenceEnabled());
        Assert.assertFalse(BraveAdaptiveToolbarStatePredictor.isValidSegment(removedVariant));
        // A second settings read must retain the already migrated choice.
        Assert.assertEquals(expectedDefault, BraveAdaptiveToolbarPrefs.getCustomizationSetting());
    }

    private void assertDelayedRemovedSegmentRejected(int removedVariant) {
        AdaptiveToolbarStatePredictor.setSegmentationResultsForTesting(null);
        ChromeSharedPreferences.getInstance()
                .writeInt(
                        ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS,
                        AdaptiveToolbarButtonVariant.UNKNOWN);
        ChromeSharedPreferences.getInstance()
                .writeBoolean(ADAPTIVE_TOOLBAR_CUSTOMIZATION_ENABLED, true);
        List<Integer> staleResults = List.of(removedVariant, AdaptiveToolbarButtonVariant.SHARE);
        doReturn(AdaptiveToolbarButtonVariant.SHARE)
                .when(mToolbarBehavior)
                .getSegmentationDefault(mProfile);
        doReturn(removedVariant).when(mToolbarBehavior).resultFilter(staleResults);
        AtomicReference<Callback<List<Integer>>> pending = new AtomicReference<>();
        AtomicReference<AdaptiveToolbarStatePredictor.UiState> state = new AtomicReference<>();
        AdaptiveToolbarStatePredictor predictor =
                new AdaptiveToolbarStatePredictor(
                        ContextUtils.getApplicationContext(), mProfile, null, mToolbarBehavior) {
                    @Override
                    public void readFromSegmentationPlatform(Callback<List<Integer>> callback) {
                        pending.set(callback);
                    }
                };
        predictor.recomputeUiState(state::set);
        Assert.assertNull(state.get());
        Assert.assertNotNull(pending.get());
        // Deliver the old result after recomputation has returned, as the native backend does.
        pending.get().onResult(staleResults);
        Assert.assertNotNull(state.get());
        Assert.assertEquals(
                List.of(AdaptiveToolbarButtonVariant.SHARE), state.get().rankedToolbarButtonStates);
        Assert.assertEquals(AdaptiveToolbarButtonVariant.AUTO, state.get().preferenceSelection);
        Assert.assertEquals(AdaptiveToolbarButtonVariant.SHARE, state.get().autoButtonCaption);
        Assert.assertFalse(BraveAdaptiveToolbarStatePredictor.isValidSegment(removedVariant));
    }

    private RadioButtonWithDescription getButton(@AdaptiveToolbarButtonVariant int type) {
        return (RadioButtonWithDescription) mRadioPreference.getButton(type);
    }

    private void selectButton(@AdaptiveToolbarButtonVariant int type) {
        getButton(type).onClick(null);
    }

    private boolean isRestUnchecked(@AdaptiveToolbarButtonVariant int selectedType) {
        for (int i = 0; i <= AdaptiveToolbarButtonVariant.MAX_VALUE; i++) {
            RadioButtonWithDescription button = getButton(i);
            if (i != selectedType && button != null && button.isChecked()) {
                return false;
            }
        }
        return true;
    }

    private void assertButtonCheckedCorrectly(
            String buttonTitle, @AdaptiveToolbarButtonVariant int type) {
        Assert.assertTrue(buttonTitle + " button should be checked.", getButton(type).isChecked());
        Assert.assertTrue(
                "Buttons except " + buttonTitle + " should be unchecked.", isRestUnchecked(type));
    }

    private FragmentScenario<AdaptiveToolbarSettingsFragment> buildFragmentScenario() {
        return FragmentScenario.launchInContainer(
                AdaptiveToolbarSettingsFragment.class,
                Bundle.EMPTY,
                R.style.Theme_Chromium_Settings,
                new FragmentFactory() {
                    @Override
                    public Fragment instantiate(
                            @NonNull ClassLoader classLoader, @NonNull String className) {
                        Fragment fragment = super.instantiate(classLoader, className);
                        if (fragment instanceof ProfileDependentSetting) {
                            ((ProfileDependentSetting) fragment).setProfile(mProfile);
                        }
                        return fragment;
                    }
                });
    }

    @Test
    @SmallTest
    @Config(qualifiers = "sw360dp")
    public void testPersistedGlicUpgradeOnPhone() {
        assertRemovedOverrideMigrated(
                AdaptiveToolbarButtonVariant.GLIC, AdaptiveToolbarButtonVariant.NEW_TAB);
    }

    @Test
    @SmallTest
    @Config(qualifiers = "sw600dp")
    public void testPersistedGlicUpgradeOnTablet() {
        assertRemovedOverrideMigrated(
                AdaptiveToolbarButtonVariant.GLIC, AdaptiveToolbarButtonVariant.SHARE);
    }

    @Test
    @SmallTest
    public void testDelayedGlicSegmentationResultIsRejected() {
        assertDelayedRemovedSegmentRejected(AdaptiveToolbarButtonVariant.GLIC);
    }
}
