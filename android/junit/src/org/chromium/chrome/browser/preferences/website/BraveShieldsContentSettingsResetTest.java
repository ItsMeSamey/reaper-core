/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.preferences.website;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import org.chromium.base.ContextUtils;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.shields.BraveShieldsMenuObserver;
import org.chromium.chrome.browser.shields.BraveUnifiedPanelHandler;
import org.chromium.url.GURL;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Unit tests for {@link BraveShieldsContentSettings#resetSiteToDefaults}. */
@RunWith(BaseRobolectricTestRunner.class)
public class BraveShieldsContentSettingsResetTest {
    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private Profile mProfile;
    @Mock private BraveShieldsContentSettings.Natives mNatives;

    private BraveUnifiedPanelHandler mHandler;

    private static final String SITE_URL = "https://example.com/";

    @Before
    public void setUp() {
        BraveShieldsContentSettingsJni.setInstanceForTesting(mNatives);
    }

    @After
    public void tearDown() {
        if (mHandler != null) {
            mHandler.destroy();
        }
        BraveShieldsContentSettingsJni.setInstanceForTesting(null);
    }

    @Test
    public void testResetSiteToDefaults_appliesAllGlobalDefaultsToSite() throws Exception {
        when(mNatives.isBraveShieldsEnabled("", mProfile)).thenReturn(true);
        when(mNatives.getNoScriptControlType("", mProfile)).thenReturn("block");
        when(mNatives.getForgetFirstPartyStorageEnabled("", mProfile)).thenReturn(false);
        when(mNatives.getFingerprintingControlType("", mProfile)).thenReturn("block");
        when(mNatives.getHttpsUpgradeControlType("", mProfile)).thenReturn("block");
        when(mNatives.getCookieControlType("", mProfile)).thenReturn("block_third_party");
        when(mNatives.getCosmeticFilteringControlType("", mProfile))
                .thenReturn("block_third_party");
        when(mNatives.getAdControlType("", mProfile)).thenReturn("block");

        when(mNatives.isBraveShieldsEnabled(SITE_URL, mProfile)).thenReturn(true);
        BraveShieldsMenuObserver observer = mock(BraveShieldsMenuObserver.class);
        // The existing application-context host keeps this a reset/callback contract test,
        // without inflating the panel or claiming rendered UI behavior.
        mHandler = new BraveUnifiedPanelHandler(ContextUtils.getApplicationContext());
        mHandler.addObserver(observer);
        setHandlerField("mProfile", mProfile);
        setHandlerField("mUrl", new GURL(SITE_URL));
        Method reset = BraveUnifiedPanelHandler.class.getDeclaredMethod("resetSiteToShieldsDefaults");
        reset.setAccessible(true);
        reset.invoke(mHandler);

        verify(mNatives).setBraveShieldsEnabled(true, SITE_URL, mProfile);
        verify(mNatives).setNoScriptControlType("block", SITE_URL, mProfile);
        verify(mNatives).setForgetFirstPartyStorageEnabled(false, SITE_URL, mProfile);
        verify(mNatives).setFingerprintingControlType("block", SITE_URL, mProfile);
        verify(mNatives).setHttpsUpgradeControlType("block", SITE_URL, mProfile);
        verify(mNatives).setCookieControlType("block_third_party", SITE_URL, mProfile);
        verify(mNatives).setCosmeticFilteringControlType("block_third_party", SITE_URL, mProfile);
        verify(mNatives).setAdControlType("block", SITE_URL, mProfile);

        InOrder completion = inOrder(mNatives, observer);
        completion.verify(mNatives).setAdControlType("block", SITE_URL, mProfile);
        completion.verify(mNatives).isBraveShieldsEnabled(SITE_URL, mProfile);
        completion.verify(observer).onMenuTopShieldsChanged(true, true);
        verifyNoMoreInteractions(observer);
    }

    @Test
    public void testResetSiteToDefaults_callsHttpsUpgradeWhenDefaultIsDefault() {
        when(mNatives.isBraveShieldsEnabled("", mProfile)).thenReturn(true);
        when(mNatives.getNoScriptControlType("", mProfile)).thenReturn("block");
        when(mNatives.getForgetFirstPartyStorageEnabled("", mProfile)).thenReturn(false);
        when(mNatives.getFingerprintingControlType("", mProfile)).thenReturn("default");
        when(mNatives.getHttpsUpgradeControlType("", mProfile)).thenReturn("default");
        when(mNatives.getCookieControlType("", mProfile)).thenReturn("block_third_party");
        when(mNatives.getCosmeticFilteringControlType("", mProfile))
                .thenReturn("block_third_party");
        when(mNatives.getAdControlType("", mProfile)).thenReturn("block");

        BraveShieldsContentSettings.resetSiteToDefaults(mProfile, SITE_URL);

        verify(mNatives).setHttpsUpgradeControlType(anyString(), anyString(), any(Profile.class));
        verify(mNatives).setBraveShieldsEnabled(true, SITE_URL, mProfile);
        verify(mNatives).setFingerprintingControlType("default", SITE_URL, mProfile);
    }

    @Test
    public void testResetSiteToDefaults_setsHttpsUpgradeWhenDefaultIsAllow() {
        when(mNatives.isBraveShieldsEnabled("", mProfile)).thenReturn(false);
        when(mNatives.getNoScriptControlType("", mProfile)).thenReturn("allow");
        when(mNatives.getForgetFirstPartyStorageEnabled("", mProfile)).thenReturn(true);
        when(mNatives.getFingerprintingControlType("", mProfile)).thenReturn("allow");
        when(mNatives.getHttpsUpgradeControlType("", mProfile)).thenReturn("allow");
        when(mNatives.getCookieControlType("", mProfile)).thenReturn("allow");
        when(mNatives.getCosmeticFilteringControlType("", mProfile)).thenReturn("allow");
        when(mNatives.getAdControlType("", mProfile)).thenReturn("allow");

        BraveShieldsContentSettings.resetSiteToDefaults(mProfile, SITE_URL);

        verify(mNatives).setHttpsUpgradeControlType("allow", SITE_URL, mProfile);
        verify(mNatives).setBraveShieldsEnabled(false, SITE_URL, mProfile);
        verify(mNatives).setForgetFirstPartyStorageEnabled(true, SITE_URL, mProfile);
    }

    @Test
    public void testHttpsUpgradeSetting_notifiesAfterWritingEachMode() throws Exception {
        BraveShieldsMenuObserver observer = mock(BraveShieldsMenuObserver.class);
        mHandler = new BraveUnifiedPanelHandler(ContextUtils.getApplicationContext());
        mHandler.addObserver(observer);
        setHandlerField("mProfile", mProfile);
        setHandlerField("mUrl", new GURL(SITE_URL));
        Method setter =
                BraveUnifiedPanelHandler.class.getDeclaredMethod(
                        "setHttpsUpgradeSetting", String.class);
        setter.setAccessible(true);
        InOrder completion = inOrder(mNatives, observer);

        String[] values = {
            BraveShieldsContentSettings.BLOCK_RESOURCE,
            BraveShieldsContentSettings.BLOCK_THIRDPARTY_RESOURCE,
            BraveShieldsContentSettings.ALLOW_RESOURCE
        };
        boolean[] expectedEnabled = {true, true, false};
        for (int i = 0; i < values.length; i++) {
            setter.invoke(mHandler, values[i]);

            completion.verify(mNatives).setHttpsUpgradeControlType(values[i], SITE_URL, mProfile);
            completion.verify(observer).onMenuTopShieldsChanged(expectedEnabled[i], false);
        }
        verifyNoMoreInteractions(mNatives, observer);
    }

    @Test
    public void testHttpsUpgradeSetting_withoutObserverWritesSetting() throws Exception {
        mHandler = new BraveUnifiedPanelHandler(ContextUtils.getApplicationContext());
        setHandlerField("mProfile", mProfile);
        setHandlerField("mUrl", new GURL(SITE_URL));
        Method setter =
                BraveUnifiedPanelHandler.class.getDeclaredMethod(
                        "setHttpsUpgradeSetting", String.class);
        setter.setAccessible(true);

        setter.invoke(mHandler, BraveShieldsContentSettings.ALLOW_RESOURCE);

        verify(mNatives)
                .setHttpsUpgradeControlType(
                        BraveShieldsContentSettings.ALLOW_RESOURCE, SITE_URL, mProfile);
        verifyNoMoreInteractions(mNatives);
    }

    private void setHandlerField(String name, Object value) throws Exception {
        Field field = BraveUnifiedPanelHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mHandler, value);
    }
}
