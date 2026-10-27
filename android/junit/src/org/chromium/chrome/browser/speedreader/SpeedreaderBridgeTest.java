// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.

package org.chromium.chrome.browser.speedreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content_public.browser.WebContents;

/** Guards the real tab/document boundary when an Android menu action is dispatched. */
@RunWith(BaseRobolectricTestRunner.class)
public class SpeedreaderBridgeTest {
    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private Tab mTab;
    @Mock private WebContents mContents;
    @Mock private SpeedreaderBridge.Natives mNatives;

    @Before
    public void setUp() {
        SpeedreaderBridgeJni.setInstanceForTesting(mNatives);
        when(mTab.getWebContents()).thenReturn(mContents);
    }

    @After
    public void tearDown() {
        SpeedreaderBridgeJni.setInstanceForTesting(null);
    }

    @Test
    public void testMissingCurrentTabDoesNotEnterNativeCode() {
        assertEquals(SpeedreaderBridge.State.UNAVAILABLE, SpeedreaderBridge.getState(null));
        assertFalse(SpeedreaderBridge.toggle(null));
        verifyNoInteractions(mNatives);
    }

    @Test
    public void testDestroyedTabDoesNotEnterNativeCode() {
        when(mTab.isDestroyed()).thenReturn(true);
        assertEquals(SpeedreaderBridge.State.UNAVAILABLE, SpeedreaderBridge.getState(mTab));
        assertFalse(SpeedreaderBridge.toggle(mTab));
        verifyNoInteractions(mNatives);
    }

    @Test
    public void testLazyTabWithoutContentsDoesNotCreateContents() {
        when(mTab.getWebContents()).thenReturn(null);
        assertEquals(SpeedreaderBridge.State.UNAVAILABLE, SpeedreaderBridge.getState(mTab));
        assertFalse(SpeedreaderBridge.toggle(mTab));
        verifyNoInteractions(mNatives);
    }

    @Test
    public void testDestroyedContentsDoesNotEnterNativeCode() {
        when(mContents.isDestroyed()).thenReturn(true);
        assertFalse(SpeedreaderBridge.toggle(mTab));
        verifyNoInteractions(mNatives);
    }

    @Test
    public void testBusyDocumentDoesNotDispatchSecondExtraction() {
        when(mNatives.getState(mContents)).thenReturn(SpeedreaderBridge.State.BUSY);
        assertFalse(SpeedreaderBridge.toggle(mTab));
        verify(mNatives, never()).toggle(mContents);
    }

    @Test
    public void testNavigationAfterMenuCreationRechecksDocument() {
        when(mNatives.getState(mContents))
                .thenReturn(SpeedreaderBridge.State.ORIGINAL, SpeedreaderBridge.State.UNAVAILABLE);
        assertEquals(SpeedreaderBridge.State.ORIGINAL, SpeedreaderBridge.getState(mTab));
        assertFalse(SpeedreaderBridge.toggle(mTab));
        verify(mNatives, never()).toggle(mContents);
    }

    @Test
    public void testOriginalDocumentDispatchesNativeExtraction() {
        when(mNatives.getState(mContents)).thenReturn(SpeedreaderBridge.State.ORIGINAL);
        when(mNatives.toggle(mContents)).thenReturn(true);
        assertTrue(SpeedreaderBridge.toggle(mTab));
        verify(mNatives).toggle(mContents);
    }

    @Test
    public void testReaderDocumentDispatchesNativeRevert() {
        when(mNatives.getState(mContents)).thenReturn(SpeedreaderBridge.State.READER);
        when(mNatives.toggle(mContents)).thenReturn(true);
        assertTrue(SpeedreaderBridge.toggle(mTab));
        verify(mNatives).toggle(mContents);
    }

    @Test
    public void testNativePolicyRecheckCanRejectDispatch() {
        when(mNatives.getState(mContents)).thenReturn(SpeedreaderBridge.State.ORIGINAL);
        when(mNatives.toggle(mContents)).thenReturn(false);
        assertFalse(SpeedreaderBridge.toggle(mTab));
    }
}
