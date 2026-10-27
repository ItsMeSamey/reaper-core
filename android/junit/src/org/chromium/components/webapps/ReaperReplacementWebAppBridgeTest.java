// Copyright 2026 The Reaper Authors.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.components.webapps;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowShortcutManager;

import org.chromium.base.ContextUtils;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.content_public.browser.WebContents;

import java.lang.reflect.Method;

@RunWith(BaseRobolectricTestRunner.class)
public class ReaperReplacementWebAppBridgeTest {
    private final Profile mProfile = mock(Profile.class);
    private final WebContents mContents = mock(WebContents.class);
    private final ReaperReplacementWebAppBridge.Natives mNative =
            mock(ReaperReplacementWebAppBridge.Natives.class);

    @Before
    public void setUp() {
        ReaperReplacementWebAppBridgeJni.setInstanceForTesting(mNative);
        ProfileManager.setLastUsedProfileForTesting(mProfile);
        when(mProfile.isInitialProfile()).thenReturn(true);
        when(mNative.isCurrent(7L)).thenReturn(true);
    }

    private ReaperReplacementWebAppBridge create() {
        return ReaperReplacementWebAppBridge.create(7L, mContents, mProfile,
                "https://example.test/app-id", "https://example.test/app/start",
                "https://example.test/app/", "Example App", "Example",
                Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888), false, 3, 0, 0, 0,
                "example.test");
    }

    @Test
    public void offTheRecordAndOtherProfilesAreRejected() {
        assertTrue(ReaperReplacementWebAppBridge.canInstall(mProfile));
        when(mProfile.isOffTheRecord()).thenReturn(true);
        assertFalse(ReaperReplacementWebAppBridge.canInstall(mProfile));
        when(mProfile.isOffTheRecord()).thenReturn(false);
        when(mProfile.isInitialProfile()).thenReturn(false);
        assertFalse(ReaperReplacementWebAppBridge.canInstall(mProfile));
        when(mProfile.isInitialProfile()).thenReturn(true);
        ProfileManager.setLastUsedProfileForTesting(mock(Profile.class));
        assertFalse(ReaperReplacementWebAppBridge.canInstall(mProfile));
    }

    @Test
    public void shuttingDownProfileIsRejectedBeforeNativeQueries() {
        when(mProfile.shutdownStarted()).thenReturn(true);
        assertFalse(ReaperReplacementWebAppBridge.canInstall(mProfile));
    }

    @Test
    public void noWindowFailsWithoutInstallingOrReportingSuccess() {
        ReaperReplacementWebAppBridge bridge = create();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verify(mNative, times(1)).onFinished(7L, false);
        verify(mNative, never()).onFinished(7L, true);
        bridge.destroy();
        verify(mNative, times(1)).onFinished(7L, false);
    }

    @Test
    public void changedDocumentFailsBeforeConsent() {
        when(mNative.isCurrent(7L)).thenReturn(false);
        create();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verify(mNative).onFinished(7L, false);
        verify(mNative, never()).onFinished(7L, true);
    }

    @Test
    public void nativeCancellationRemovesQueuedConsentAndNeverCallsFreedRequest() {
        ReaperReplacementWebAppBridge bridge = create();
        bridge.destroy();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        bridge.onViewDismissed();
        verify(mNative, never()).onFinished(7L, true);
        verify(mNative, never()).onFinished(7L, false);
    }

    @Test
    public void dismissalCompletesOnlyOnce() {
        ReaperReplacementWebAppBridge bridge = create();
        bridge.onViewDismissed();
        bridge.onViewDismissed();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verify(mNative, times(1)).onFinished(7L, false);
    }

    @Test
    public void launcherWithoutPinSupportFailsHonestly() throws Exception {
        ShortcutManager manager =
                ContextUtils.getApplicationContext().getSystemService(ShortcutManager.class);
        ShadowShortcutManager shadow = Shadows.shadowOf(manager);
        shadow.setIsRequestPinShortcutSupported(false);
        ReaperReplacementWebAppBridge bridge = create();
        Method method =
                ReaperReplacementWebAppBridge.class.getDeclaredMethod("requestPin", Intent.class);
        method.setAccessible(true);
        method.invoke(bridge, new Intent(Intent.ACTION_VIEW));
        verify(mNative, times(1)).onFinished(7L, false);
        verify(mNative, never()).onFinished(7L, true);
        assertTrue(manager.getPinnedShortcuts().isEmpty());
    }

    @Test
    public void distinctManifestIdsNeverAliasByOriginOrScope() {
        String first = ReaperReplacementWebAppBridge.stableId("https://example.test/app-one");
        String second = ReaperReplacementWebAppBridge.stableId("https://example.test/app-two");
        assertNotEquals(first, second);
        assertTrue(first.startsWith("reaper.webapp.v1."));
    }
}
