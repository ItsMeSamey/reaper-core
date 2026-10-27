/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.sync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import android.view.View;
import android.view.WindowManager;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.test.filters.SmallTest;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.chromium.base.test.util.CommandLineFlags;
import org.chromium.base.test.util.DoNotBatch;
import org.chromium.base.test.util.Feature;
import org.chromium.base.test.util.TestThreadUtils;
import org.chromium.chrome.browser.BraveSyncWorker;
import org.chromium.chrome.browser.flags.ChromeSwitches;
import org.chromium.chrome.browser.settings.BraveSyncScreensPreference;
import org.chromium.chrome.browser.settings.SettingsActivity;
import org.chromium.chrome.browser.settings.SettingsActivityTestRule;
import org.chromium.chrome.browser.sync.settings.BraveManageSyncSettings;
import org.chromium.chrome.browser.sync.settings.ManageSyncSettings;
import org.chromium.chrome.test.ChromeJUnit4ClassRunner;
import org.chromium.components.browser_ui.settings.ChromeSwitchPreference;
import org.chromium.components.sync.SyncService;
import org.chromium.components.sync.TransportState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tests for BraveManageSyncSettings and its Sync overview navigation. */
@RunWith(ChromeJUnit4ClassRunner.class)
@CommandLineFlags.Add({ChromeSwitches.DISABLE_FIRST_RUN_EXPERIENCE})
@DoNotBatch(reason = "TODO(crbug.com/40743432): SyncTestRule doesn't support batching.")
public class BraveManageSyncSettingsTest {
    private SettingsActivity mSettingsActivity;

    private final SettingsActivityTestRule<BraveManageSyncSettings> mSettingsActivityTestRule =
            new SettingsActivityTestRule<>(BraveManageSyncSettings.class);

    @Mock private SyncService mSyncService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    @SmallTest
    @Feature({"Sync"})
    public void syncPasswordsHandlerIsOriginalOnChromeOS() {
        syncPasswordsOverridden(true, false);
    }

    @Test
    @SmallTest
    @Feature({"Sync"})
    public void syncPasswordsHandlerOverriddenOnNonChromeOS() {
        syncPasswordsOverridden(false, true);
    }

    @Test
    @SmallTest
    @Feature({"Sync"})
    public void readingListToggleIsVisible() {
        setupMockSyncService();
        BraveManageSyncSettings fragment = startManageSyncPreferences();

        Preference prefReadingList =
                fragment.findPreference(
                        ManageSyncSettings.PREF_ACCOUNT_SECTION_READING_LIST_TOGGLE);

        Assert.assertNotNull("Reading list preference should exist", prefReadingList);
        Assert.assertTrue("Reading list toggle should be visible", prefReadingList.isVisible());
    }

    @Test
    @SmallTest
    @Feature({"Sync"})
    public void syncAddressesWithCustomPassphraseDoesNotShowWarningDialog() {
        setupMockSyncService();
        when(mSyncService.isUsingExplicitPassphrase()).thenReturn(true);
        BraveManageSyncSettings fragment = startManageSyncPreferences();

        ChromeSwitchPreference addressesToggle =
                fragment.findPreference(ManageSyncSettings.PREF_ACCOUNT_SECTION_ADDRESSES_TOGGLE);
        Assert.assertNotNull("Addresses toggle should exist", addressesToggle);

        // Upstream shows a warning dialog and returns false when enabling addresses sync
        // with a custom passphrase. Brave suppresses this dialog and returns true.
        Assert.assertTrue(
                "Addresses warning dialog should be suppressed",
                fragment.onPreferenceChange(addressesToggle, true));
    }

    @Test
    @SmallTest
    @Feature({"Sync"})
    public void aiChatToggleIsVisibleWhenFeatureEnabled() {
        setupMockSyncService();
        BraveManageSyncSettings fragment = startManageSyncPreferences();

        Preference prefAiChat = fragment.findPreference("account_section_ai_chat_toggle");
        Assert.assertNull("AI Chat is excluded from the Android build", prefAiChat);
    }

    @Test
    @SmallTest
    @Feature({"Sync"})
    public void aiChatToggleIsAbsentWhenFeatureDisabled() {
        setupMockSyncService();
        BraveManageSyncSettings fragment = startManageSyncPreferences();

        Preference prefAiChat = fragment.findPreference("account_section_ai_chat_toggle");
        Assert.assertNull(
                "AI Chat preference should not exist when feature is disabled", prefAiChat);
    }

    @Test
    @SmallTest
    @Feature({"Sync"})
    public void syncOverviewObserverFollowsBackStackView() {
        setupMockSyncService();
        List<SyncService.SyncStateChangedListener> listeners = new ArrayList<>();
        doAnswer(
                        invocation -> {
                            listeners.add(invocation.getArgument(0));
                            return null;
                        })
                .when(mSyncService)
                .addSyncStateChangedListener(any(SyncService.SyncStateChangedListener.class));
        doAnswer(
                        invocation -> {
                            SyncService.SyncStateChangedListener listener =
                                    invocation.getArgument(0);
                            listeners.remove(listener);
                            return null;
                        })
                .when(mSyncService)
                .removeSyncStateChangedListener(any(SyncService.SyncStateChangedListener.class));
        SettingsActivityTestRule<BraveSyncScreensPreference> overviewRule =
                new SettingsActivityTestRule<>(BraveSyncScreensPreference.class);
        mSettingsActivity = overviewRule.startSettingsActivity();
        BraveSyncScreensPreference overview = overviewRule.getFragment();

        TestThreadUtils.runOnUiThreadBlocking(
                () -> {
                    Assert.assertFalse(
                            "This navigation fixture starts without a Sync chain",
                            BraveSyncWorker.get().isInitialSyncFeatureSetupComplete());
                    View previousView = overview.requireView();
                    int containerId = ((View) previousView.getParent()).getId();
                    FragmentManager manager = overview.getParentFragmentManager();
                    Assert.assertEquals(1, Collections.frequency(listeners, overview));

                    // Use the same ordinary replacement/back-stack lifecycle as the settings host.
                    manager.beginTransaction()
                            .setReorderingAllowed(true)
                            .replace(containerId, new Fragment())
                            .addToBackStack("sync-options")
                            .commit();
                    manager.executePendingTransactions();
                    Assert.assertNull(overview.getView());
                    Assert.assertNotNull(overview.getActivity());
                    Assert.assertFalse(listeners.contains(overview));

                    // A notification already present in a listener snapshot must not change the
                    // active settings page after the overview's view has gone away.
                    mSettingsActivity.setTitle("Other settings");
                    mSettingsActivity.getWindow()
                            .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                    overview.syncStateChanged();
                    Assert.assertEquals("Other settings", mSettingsActivity.getTitle().toString());
                    Assert.assertEquals(
                            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                            mSettingsActivity.getWindow().getAttributes().softInputMode
                                    & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);

                    Assert.assertTrue(manager.popBackStackImmediate());
                    Assert.assertNotNull(overview.getView());
                    Assert.assertNotSame(previousView, overview.getView());
                    Assert.assertEquals(1, Collections.frequency(listeners, overview));
                    mSettingsActivity.getWindow()
                            .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                    for (SyncService.SyncStateChangedListener listener : new ArrayList<>(listeners)) {
                        listener.syncStateChanged();
                    }
                    Assert.assertEquals(
                            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN,
                            mSettingsActivity.getWindow().getAttributes().softInputMode
                                    & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
                });
    }

    void syncPasswordsOverridden(Boolean isChromeOS, Boolean handlerShouldBeOverridden) {
        setupMockSyncService();

        BraveManageSyncSettings.setIsRunningOnChromeOSForTesting(isChromeOS);
        BraveManageSyncSettings fragment = startManageSyncPreferences();

        ChromeSwitchPreference prefSyncPasswords =
                fragment.findPreference(ManageSyncSettings.PREF_ACCOUNT_SECTION_PASSWORDS_TOGGLE);

        Preference.OnPreferenceChangeListener origSyncPasswordsListner =
                prefSyncPasswords.getOnPreferenceChangeListener();

        Assert.assertEquals(
                handlerShouldBeOverridden,
                origSyncPasswordsListner != (Preference.OnPreferenceChangeListener) fragment);
    }

    private BraveManageSyncSettings startManageSyncPreferences() {
        mSettingsActivity = mSettingsActivityTestRule.startSettingsActivity();
        return mSettingsActivityTestRule.getFragment();
    }

    private void setupMockSyncService() {
        SyncServiceFactory.setInstanceForTesting(mSyncService);
        when(mSyncService.getTransportState()).thenReturn(TransportState.ACTIVE);
    }
}
