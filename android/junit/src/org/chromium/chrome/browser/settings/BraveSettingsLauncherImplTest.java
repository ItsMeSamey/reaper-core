/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Browser;

import androidx.fragment.app.Fragment;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import org.chromium.base.DeviceInfo;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.base.test.util.Features.DisableFeatures;
import org.chromium.base.test.util.Features.EnableFeatures;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.document.ChromeLauncherActivity;
import org.chromium.chrome.browser.IntentHandler;
import org.chromium.chrome.browser.browsing_data.BraveClearBrowsingDataFragment;
import org.chromium.chrome.browser.browsing_data.ClearBrowsingDataFragment;
import org.chromium.chrome.browser.download.settings.BraveDownloadSettings;
import org.chromium.chrome.browser.download.settings.DownloadSettings;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.safe_browsing.settings.BraveStandardProtectionSettingsFragment;
import org.chromium.chrome.browser.safe_browsing.settings.StandardProtectionSettingsFragment;
import org.chromium.components.browser_ui.settings.SettingsNavigation.SettingsFragment;
import org.chromium.components.embedder_support.util.UrlConstants;
import org.chromium.ui.base.TestActivity;

/** Tests for {@link BraveSettingsLauncherImpl}. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
// Settings fragments are EmbeddableSettingsPages, so SettingsIntentUtil reads these flags while
// building the intent. Pin them so these tests don't depend on the field trial state. With
// SettingsInTab the intent targets ChromeLauncherActivity with a chrome://settings URL instead of
// the settings activity, which is a separate code path from the substitution tested here.
@DisableFeatures({ChromeFeatureList.SETTINGS_SINGLE_ACTIVITY, ChromeFeatureList.SETTINGS_IN_TAB})
public class BraveSettingsLauncherImplTest {
    private Application mContext;
    private BraveSettingsLauncherImpl mSettingsNavigation;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mSettingsNavigation = new BraveSettingsLauncherImpl();
    }

    /**
     * Entry points that pass a {@link SettingsFragment} value instead of a fragment class must
     * still get the Brave version of the fragment.
     *
     * <p>Regression test for https://github.com/brave/brave-browser/issues/57217: Quick Delete's
     * "More options" (reachable from the tab switcher menu) and the History page's "Delete browsing
     * data" use this overload, and used to land on the upstream screen, which has no Leo AI option
     * and no "Clear Brave Ads data" entry.
     */
    @Test
    @SmallTest
    public void testStartSettingsWithEnumSubstitutesFragment() {
        mSettingsNavigation.startSettings(mContext, SettingsFragment.CLEAR_BROWSING_DATA);

        Intent intent = shadowOf(mContext).getNextStartedActivity();
        assertNotNull(intent);
        assertShowsBraveClearBrowsingData(intent);
    }

    @Test
    @SmallTest
    public void testCreateSettingsIntentWithEnumSubstitutesFragment() {
        Intent intent =
                mSettingsNavigation.createSettingsIntent(
                        mContext, SettingsFragment.CLEAR_BROWSING_DATA, /* fragmentArgs= */ null);

        assertShowsBraveClearBrowsingData(intent);
    }

    @Test
    @SmallTest
    public void testStartSettingsWithFragmentClassSubstitutesFragment() {
        mSettingsNavigation.startSettings(
                mContext, ClearBrowsingDataFragment.class, /* fragmentArgs= */ null);

        Intent intent = shadowOf(mContext).getNextStartedActivity();
        assertNotNull(intent);
        assertShowsBraveClearBrowsingData(intent);
    }

    /**
     * Covers the way {@link org.chromium.chrome.browser.site_settings.BraveSiteSettingsDelegate}
     * opens the screen: fragment args plus addToBackStack, which travel through overloads that used
     * to bypass the substitution.
     */
    @Test
    @SmallTest
    public void testStartSettingsPreservesArgsAndBackStack() {
        Bundle fragmentArgs = ClearBrowsingDataFragment.createFragmentArgs("referrer");

        mSettingsNavigation.startSettings(
                mContext,
                ClearBrowsingDataFragment.class,
                fragmentArgs,
                /* addToBackStack= */ true);

        Intent intent = shadowOf(mContext).getNextStartedActivity();
        assertNotNull(intent);
        assertShowsBraveClearBrowsingData(intent);
        assertNotNull(intent.getBundleExtra(SettingsIntentUtil.EXTRA_SHOW_FRAGMENT_ARGUMENTS));
        assertTrue(intent.getBooleanExtra(SettingsIntentUtil.EXTRA_ADD_TO_BACK_STACK, false));
    }

    @Test
    @SmallTest
    public void testOtherFragmentsAreStillSubstituted() {
        assertEquals(
                BraveDownloadSettings.class.getName(),
                showFragmentName(
                        mSettingsNavigation.createSettingsIntent(
                                mContext, DownloadSettings.class)));
        assertEquals(
                BraveStandardProtectionSettingsFragment.class.getName(),
                showFragmentName(
                        mSettingsNavigation.createSettingsIntent(
                                mContext, StandardProtectionSettingsFragment.class)));
    }

    @Test
    @SmallTest
    public void testMainSettingsStillOpensBraveSettingsActivity() {
        Intent intent =
                mSettingsNavigation.createSettingsIntent(
                        mContext, SettingsFragment.MAIN, /* fragmentArgs= */ null);

        // MAIN maps to a null fragment, so there is nothing to substitute, but the intent must
        // still target the Brave settings activity.
        assertNull(showFragmentName(intent));
        assertEquals(BraveSettingsActivity.class.getName(), intent.getComponent().getClassName());
    }

    @Test
    @SmallTest
    public void testExtensionsLaunchCarriesDestinationWithoutRunningBrowserActivity() {
        // No browser Activity exists in this test. Settings must carry the target URL rather
        // than first trying to open a tab through an already running BraveActivity.
        Intent intent = BraveMainPreferencesBase.createExtensionsIntent(mContext);

        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals(UrlConstants.CHROME_EXTENSIONS_URL, intent.getDataString());
        assertEquals(ChromeLauncherActivity.class.getName(), intent.getComponent().getClassName());
        assertTrue(intent.getBooleanExtra(Browser.EXTRA_CREATE_NEW_TAB, false));
        assertEquals(mContext.getPackageName(), intent.getStringExtra(Browser.EXTRA_APPLICATION_ID));
        assertFalse(intent.getBooleanExtra(IntentHandler.EXTRA_OPEN_NEW_INCOGNITO_TAB, true));
        assertTrue(IntentHandler.wasIntentSenderChrome(intent));
    }

    @Test
    @SmallTest
    @Config(sdk = {33, 34})
    @EnableFeatures({ChromeFeatureList.SETTINGS_IN_TAB_DESKTOP, ChromeFeatureList.SETTINGS_MULTI_COLUMN})
    public void testDesktopSettingsInTabMainPreservesLauncherDestination() {
        DeviceInfo.setIsDesktopForTesting(true);
        SettingsIntentUtil.setLastIntentForTesting(null);

        Intent intent =
                mSettingsNavigation.createSettingsIntent(
                        mContext, SettingsFragment.MAIN, /* fragmentArgs= */ null);

        assertNull(showFragmentName(intent));
        assertSettingsTabIntent(intent);
        assertSame(intent, SettingsIntentUtil.takeLastIntent());
        assertNull(SettingsIntentUtil.takeLastIntent());
    }

    @Test
    @SmallTest
    @Config(sdk = {33, 34})
    @EnableFeatures({ChromeFeatureList.SETTINGS_IN_TAB_DESKTOP, ChromeFeatureList.SETTINGS_MULTI_COLUMN})
    public void testDesktopSettingsInTabSubstitutesFragmentAndPreservesArgs() {
        DeviceInfo.setIsDesktopForTesting(true);
        SettingsIntentUtil.setLastIntentForTesting(null);
        Bundle fragmentArgs = new Bundle();
        fragmentArgs.putString("settings_argument", "desktop");

        Intent intent =
                mSettingsNavigation.createSettingsIntent(
                        mContext,
                        ClearBrowsingDataFragment.class,
                        fragmentArgs,
                        /* addToBackStack= */ true,
                        "desktop-settings");

        assertEquals(BraveClearBrowsingDataFragment.class.getName(), showFragmentName(intent));
        assertSettingsTabIntent(intent);
        Bundle actualArgs = intent.getBundleExtra(SettingsIntentUtil.EXTRA_SHOW_FRAGMENT_ARGUMENTS);
        assertNotNull(actualArgs);
        assertEquals("desktop", actualArgs.getString("settings_argument"));
        assertTrue(intent.getBooleanExtra(SettingsIntentUtil.EXTRA_ADD_TO_BACK_STACK, false));
        assertEquals("desktop-settings", intent.getStringExtra(SettingsIntentUtil.EXTRA_FRAGMENT_TAG));
        assertSame(intent, SettingsIntentUtil.takeLastIntent());
        assertNull(SettingsIntentUtil.takeLastIntent());
    }

    @Test
    @SmallTest
    @Config(sdk = {33, 34})
    @EnableFeatures(ChromeFeatureList.SETTINGS_MULTI_COLUMN)
    @DisableFeatures(ChromeFeatureList.SETTINGS_IN_TAB_DESKTOP)
    public void testDesktopSettingsInTabDisabledOpensBraveSettingsActivity() {
        DeviceInfo.setIsDesktopForTesting(true);
        SettingsIntentUtil.setLastIntentForTesting(null);

        Intent mainIntent =
                mSettingsNavigation.createSettingsIntent(
                        mContext, SettingsFragment.MAIN, /* fragmentArgs= */ null);
        assertNull(showFragmentName(mainIntent));
        assertEquals(BraveSettingsActivity.class.getName(), mainIntent.getComponent().getClassName());
        assertNull(mainIntent.getAction());
        assertNull(mainIntent.getData());

        Intent fragmentIntent =
                mSettingsNavigation.createSettingsIntent(mContext, ClearBrowsingDataFragment.class);
        assertShowsBraveClearBrowsingData(fragmentIntent);
        assertNull(fragmentIntent.getAction());
        assertNull(fragmentIntent.getData());
        assertNull(SettingsIntentUtil.takeLastIntent());
    }

    @Test
    @SmallTest
    @Config(sdk = {33, 34})
    @EnableFeatures({ChromeFeatureList.SETTINGS_IN_TAB_DESKTOP, ChromeFeatureList.SETTINGS_MULTI_COLUMN})
    public void testDesktopSettingsInTabForceActivityOverrideOpensBraveSettingsActivity() {
        DeviceInfo.setIsDesktopForTesting(true);
        SettingsIntentUtil.setLastIntentForTesting(null);
        mSettingsNavigation.setUseSettingsActivityForTesting(true);

        Intent intent =
                mSettingsNavigation.createSettingsIntent(mContext, ClearBrowsingDataFragment.class);

        assertShowsBraveClearBrowsingData(intent);
        assertNull(intent.getAction());
        assertNull(intent.getData());
        assertNull(SettingsIntentUtil.takeLastIntent());
    }

    @Test
    @SmallTest
    @Config(sdk = {33, 34})
    @EnableFeatures({ChromeFeatureList.SETTINGS_IN_TAB_DESKTOP, ChromeFeatureList.SETTINGS_MULTI_COLUMN})
    public void testDesktopSettingsInTabStandaloneFragmentOpensBraveSettingsActivity() {
        DeviceInfo.setIsDesktopForTesting(true);
        SettingsIntentUtil.setLastIntentForTesting(null);

        // Fragment itself does not implement EmbeddableSettingsPage, so it uses the activity host.
        Intent intent = mSettingsNavigation.createSettingsIntent(mContext, Fragment.class);

        assertEquals(Fragment.class.getName(), showFragmentName(intent));
        assertEquals(BraveSettingsActivity.class.getName(), intent.getComponent().getClassName());
        assertTrue(intent.getBooleanExtra(SettingsIntentUtil.EXTRA_SHOW_FRAGMENT_STANDALONE, false));
        assertNull(intent.getAction());
        assertNull(intent.getData());
        assertNull(SettingsIntentUtil.takeLastIntent());
    }

    @Test
    @SmallTest
    @Config(sdk = {33, 34})
    @EnableFeatures({ChromeFeatureList.SETTINGS_IN_TAB_DESKTOP, ChromeFeatureList.SETTINGS_MULTI_COLUMN})
    public void testDesktopStartSettingsInActiveHostSubstitutesFragmentAndPreservesArgs() {
        DeviceInfo.setIsDesktopForTesting(true);
        SettingsIntentUtil.setLastIntentForTesting(null);
        var activityController = Robolectric.buildActivity(TestActivity.class).setup().visible();
        try {
            TestActivity activity = activityController.get();
            RecordingSettingsHostFragment host = attachSettingsHost(activity);
            Bundle fragmentArgs = new Bundle();
            fragmentArgs.putString("settings_argument", "active-host");

            mSettingsNavigation.startSettings(
                    activity,
                    StandardProtectionSettingsFragment.class,
                    fragmentArgs,
                    /* addToBackStack= */ true,
                    "active-host-settings");

            assertEquals(1, host.mShowCount);
            assertNotNull(host.mShownFragment);
            assertEquals(
                    BraveStandardProtectionSettingsFragment.class, host.mShownFragment.getClass());
            assertNotNull(host.mShownFragment.getArguments());
            assertEquals(
                    "active-host", host.mShownFragment.getArguments().getString("settings_argument"));
            assertTrue(host.mAddToBackStack);
            assertEquals("active-host-settings", host.mShownTag);
            assertNull(shadowOf(activity).getNextStartedActivity());
            assertNull(SettingsIntentUtil.takeLastIntent());
        } finally {
            activityController.pause().stop().destroy();
        }
    }

    @Test
    @SmallTest
    @Config(sdk = {33, 34})
    @EnableFeatures({ChromeFeatureList.SETTINGS_IN_TAB_DESKTOP, ChromeFeatureList.SETTINGS_MULTI_COLUMN})
    public void testDesktopStartSettingsInActiveHostEnumAndMainStayInHost() {
        DeviceInfo.setIsDesktopForTesting(true);
        SettingsIntentUtil.setLastIntentForTesting(null);
        var activityController = Robolectric.buildActivity(TestActivity.class).setup().visible();
        try {
            TestActivity activity = activityController.get();
            RecordingSettingsHostFragment host = attachSettingsHost(activity);

            mSettingsNavigation.startSettings(activity, SettingsFragment.CLEAR_BROWSING_DATA);

            assertEquals(1, host.mShowCount);
            assertNotNull(host.mShownFragment);
            assertEquals(BraveClearBrowsingDataFragment.class, host.mShownFragment.getClass());
            assertNotNull(host.mShownFragment.getArguments());
            assertFalse(host.mAddToBackStack);
            assertNull(host.mShownTag);

            mSettingsNavigation.startSettings(activity, SettingsFragment.MAIN);

            assertEquals(2, host.mShowCount);
            assertNull(host.mShownFragment);
            assertFalse(host.mAddToBackStack);
            assertNull(host.mShownTag);
            assertNull(shadowOf(activity).getNextStartedActivity());
            assertNull(SettingsIntentUtil.takeLastIntent());
        } finally {
            activityController.pause().stop().destroy();
        }
    }

    private RecordingSettingsHostFragment attachSettingsHost(TestActivity activity) {
        RecordingSettingsHostFragment host = new RecordingSettingsHostFragment();
        activity.getSupportFragmentManager()
                .beginTransaction()
                .add(android.R.id.content, host, SettingsHostFragment.SETTINGS_NATIVE_PAGE_TAG)
                .commitNow();
        assertSame(host, SettingsHostFragment.get(activity));
        return host;
    }

    /** Records host dispatch without rendering the target fragment's native-dependent preferences. */
    public static class RecordingSettingsHostFragment extends SettingsHostFragment {
        Fragment mShownFragment;
        boolean mAddToBackStack;
        String mShownTag;
        int mShowCount;

        @Override
        protected Fragment createInitialFragment(@Nullable Intent intent) {
            return new Fragment();
        }

        @Override
        public boolean showFragment(
                @Nullable Fragment fragment, boolean addToBackStack, @Nullable String tag) {
            mShownFragment = fragment;
            mAddToBackStack = addToBackStack;
            mShownTag = tag;
            mShowCount++;
            return true;
        }
    }

    private void assertSettingsTabIntent(Intent intent) {
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals(UrlConstants.SETTINGS_URL, intent.getDataString());
        assertEquals(ChromeLauncherActivity.class.getName(), intent.getComponent().getClassName());
        assertTrue(IntentHandler.wasIntentSenderChrome(intent));
        assertFalse(intent.getBooleanExtra(SettingsIntentUtil.EXTRA_SHOW_FRAGMENT_STANDALONE, false));
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    private void assertShowsBraveClearBrowsingData(Intent intent) {
        assertEquals(BraveClearBrowsingDataFragment.class.getName(), showFragmentName(intent));
        // BraveSettingsActivity is what provides the Brave overflow menu, so the substituted
        // fragment has to be hosted by it rather than by the upstream SettingsActivity.
        assertEquals(BraveSettingsActivity.class.getName(), intent.getComponent().getClassName());
    }

    private static String showFragmentName(Intent intent) {
        return intent.getStringExtra(SettingsIntentUtil.EXTRA_SHOW_FRAGMENT);
    }
}
