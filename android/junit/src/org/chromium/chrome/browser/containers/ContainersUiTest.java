// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.

package org.chromium.chrome.browser.containers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabLaunchType;
import org.chromium.chrome.browser.tabmodel.ChromeTabCreator;
import org.chromium.chrome.browser.tabmodel.TabCreatorManager;
import org.chromium.url.JUnitTestGURLs;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.annotation.Config;

/** UI launch contracts: invalid or stale selections never silently open in Default. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ContainersUiTest {
    private static final String ID = "00112233-4455-6677-8899-aabbccddeeff";
    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock private ContainersBridge.Natives mNative;
    @Mock private Profile mProfile;
    @Mock private Tab mSource;
    @Mock private Tab mOther;
    @Mock private Tab mCreated;
    @Mock private TabCreatorManager mCreators;
    @Mock private ChromeTabCreator mCreator;

    @Before
    public void setUp() {
        ContainersBridgeJni.setInstanceForTesting(mNative);
        when(mProfile.getNativeBrowserContextPointer()).thenReturn(1L);
        when(mSource.getProfile()).thenReturn(mProfile);
        when(mSource.getUrl()).thenReturn(JUnitTestGURLs.EXAMPLE_URL);
        when(mNative.isEnabled(mProfile)).thenReturn(true);
        when(mNative.isContainerIdValid(mProfile, ID)).thenReturn(true);
        when(mCreators.getTabCreator(false)).thenReturn(mCreator);
        when(mCreator.createNewTabInContainer(any(), any(), eq(mSource)))
                .thenReturn(mCreated);
    }

    @Test
    public void testNamedSelectionCreatesFreshValidatedTab() {
        assertTrue(ContainersUi.openInContainer(mSource, mSource, mCreators, ID));
        verify(mNative).isContainerIdValid(mProfile, ID);
        verify(mCreator).createNewTabInContainer(any(), eq(ID), eq(mSource));
        verify(mCreator, never()).createNewTab(any(), eq(TabLaunchType.FROM_CHROME_UI), any());
    }

    @Test
    public void testDefaultSelectionUsesDedicatedFreshCreator() {
        assertTrue(ContainersUi.openInContainer(mSource, mSource, mCreators, ""));
        verify(mCreator).createNewTabInContainer(any(), eq(""), eq(mSource));
        verify(mCreator, never()).createNewTab(any(), eq(TabLaunchType.FROM_CHROME_UI), any());
    }

    @Test
    public void testRemovedContainerDoesNotFallBackToDefault() {
        when(mNative.isContainerIdValid(mProfile, ID)).thenReturn(false);
        assertFalse(ContainersUi.openInContainer(mSource, mSource, mCreators, ID));
        verify(mCreators, never()).getTabCreator(false);
    }

    @Test
    public void testMalformedIdNeverCallsNativeOrCreator() {
        assertFalse(ContainersUi.openInContainer(mSource, mSource, mCreators, "not-a-container"));
        verify(mNative, never()).isContainerIdValid(any(), any());
        verify(mCreators, never()).getTabCreator(false);
    }

    @Test
    public void testIncognitoSelectionIsRejected() {
        when(mSource.isIncognito()).thenReturn(true);
        assertFalse(ContainersUi.openInContainer(mSource, mSource, mCreators, ID));
        verify(mNative, never()).isEnabled(any());
        verify(mCreators, never()).getTabCreator(false);
    }

    @Test
    public void testChangedTabWhileSelectorOpenIsRejected() {
        assertFalse(ContainersUi.openInContainer(mSource, mOther, mCreators, ID));
        verify(mNative, never()).isEnabled(any());
        verify(mCreators, never()).getTabCreator(false);
    }

    @Test
    public void testDestroyedTabWhileSelectorOpenIsRejected() {
        when(mSource.isDestroyed()).thenReturn(true);
        assertFalse(ContainersUi.openInContainer(mSource, mSource, mCreators, ID));
        verify(mNative, never()).isEnabled(any());
        verify(mCreators, never()).getTabCreator(false);
    }

    @Test
    public void testDisabledControlsDoNotOpenAnyTab() {
        when(mNative.isEnabled(mProfile)).thenReturn(false);
        assertFalse(ContainersUi.openInContainer(mSource, mSource, mCreators, ID));
        verify(mCreators, never()).getTabCreator(false);
    }

    @Test
    public void testNativeCreationFailureDoesNotFallBack() {
        when(mCreator.createNewTabInContainer(any(), eq(ID), eq(mSource)))
                .thenReturn(null);
        assertFalse(ContainersUi.openInContainer(mSource, mSource, mCreators, ID));
        verify(mCreator, never()).createNewTab(any(), eq(TabLaunchType.FROM_CHROME_UI), any());
    }
}
