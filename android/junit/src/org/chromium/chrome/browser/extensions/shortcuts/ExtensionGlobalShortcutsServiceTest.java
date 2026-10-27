// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package org.chromium.chrome.browser.extensions.shortcuts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAccessibilityManager;
import org.robolectric.util.ReflectionHelpers;

import org.chromium.base.ContextUtils;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.firstrun.FirstRunStatus;
import org.chromium.chrome.browser.init.EmptyBrowserParts;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;

import java.util.List;

@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = Build.VERSION_CODES.Q)
public class ExtensionGlobalShortcutsServiceTest {
    public static class TestService extends ExtensionGlobalShortcutsService {
        EmptyBrowserParts startup;

        @Override
        void startBrowser(EmptyBrowserParts parts) {
            startup = parts;
        }
    }

    private final ExtensionGlobalShortcutsBridge.Natives mNative =
            mock(ExtensionGlobalShortcutsBridge.Natives.class);
    private ServiceController<TestService> mController;
    private TestService mService;
    private ShadowAccessibilityManager mManager;
    private AccessibilityServiceInfo mOwnInfo;

    @Before
    public void setUp() {
        ExtensionGlobalShortcutsBridgeJni.setInstanceForTesting(mNative);
        when(mNative.init(any(), any())).thenReturn(7L);
        Context context = RuntimeEnvironment.getApplication();
        mManager = shadowOf(context.getSystemService(AccessibilityManager.class));
        mOwnInfo =
                serviceInfo(
                        new ComponentName(context, ExtensionGlobalShortcutsService.class), true);
        mManager.setEnabledAccessibilityServiceList(List.of(mOwnInfo));
        FirstRunStatus.setFirstRunFlowComplete(true);
        ProfileManager.setLastUsedProfileForTesting(mock(Profile.class));
        mController = Robolectric.buildService(TestService.class).create();
        mService = mController.get();
    }

    @After
    public void tearDown() {
        mController.destroy();
        mManager.setEnabledAccessibilityServiceList(List.of());
        FirstRunStatus.setFirstRunFlowComplete(false);
    }

    private static AccessibilityServiceInfo serviceInfo(ComponentName component, boolean capable) {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        ResolveInfo resolve = new ResolveInfo();
        resolve.serviceInfo = new ServiceInfo();
        resolve.serviceInfo.packageName = component.getPackageName();
        resolve.serviceInfo.name = component.getClassName();
        ReflectionHelpers.setField(info, "mResolveInfo", resolve);
        ReflectionHelpers.setField(
                info,
                "mCapabilities",
                capable ? AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS : 0);
        return info;
    }

    @Test
    public void osDisabledServiceCannotStartBrowserOrNativeBackend() {
        mManager.setEnabledAccessibilityServiceList(List.of());
        mService.onServiceConnected();
        assertNull(mService.startup);
        assertEquals(
                ExtensionGlobalShortcutsService.Status.DISABLED,
                ExtensionGlobalShortcutsService.getStatus(mService));
        verify(mNative, never()).init(any(), any());
    }

    @Test
    public void realCompetingFilterPreventsStartupButIneffectiveFlagDoesNotConflict() {
        AccessibilityServiceInfo other =
                serviceInfo(new ComponentName("other", "other.Filter"), true);
        mManager.setEnabledAccessibilityServiceList(List.of(mOwnInfo, other));
        mService.onServiceConnected();
        assertNull(mService.startup);
        assertEquals(
                ExtensionGlobalShortcutsService.Status.CONFLICT,
                ExtensionGlobalShortcutsService.getStatus(mService));
        ReflectionHelpers.setField(other, "mCapabilities", 0);
        assertEquals(
                ExtensionGlobalShortcutsService.Status.ENABLED_NOT_CONNECTED,
                ExtensionGlobalShortcutsService.getStatus(mService));
        verify(mNative, never()).init(any(), any());
    }

    @Test
    public void disconnectWhileStartingRejectsLateNativeInitialization() {
        mService.onServiceConnected();
        EmptyBrowserParts startup = mService.startup;
        assertNotNull(startup);
        mService.onUnbind(new Intent());
        startup.finishNativeInitialization();
        assertEquals(
                ExtensionGlobalShortcutsService.Status.ENABLED_NOT_CONNECTED,
                ExtensionGlobalShortcutsService.getStatus(mService));
        verify(mNative, never()).init(any(), any());
    }

    @Test
    public void startupFailureInvalidatesLateSuccessForSameGeneration() {
        mService.onServiceConnected();
        EmptyBrowserParts startup = mService.startup;
        assertNotNull(startup);
        startup.onStartupFailure(null);
        startup.finishNativeInitialization();
        assertEquals(
                ExtensionGlobalShortcutsService.Status.UNAVAILABLE,
                ExtensionGlobalShortcutsService.getStatus(mService));
        verify(mNative, never()).init(any(), any());
    }

    @Test
    public void firstRunRevocationStopsNewDispatchBeforePostedObserverRuns() {
        doAnswer(
                        invocation -> {
                            ExtensionGlobalShortcutsBridge bridge = invocation.getArgument(1);
                            bridge.setRegistration(KeyEvent.KEYCODE_1, KeyEvent.META_CTRL_ON, true);
                            return 7L;
                        })
                .when(mNative)
                .init(any(), any());
        when(mNative.onKeyDown(eq(7L), any())).thenReturn(true);
        mService.onServiceConnected();
        mService.startup.finishNativeInitialization();
        assertEquals(
                ExtensionGlobalShortcutsService.Status.CONNECTED,
                ExtensionGlobalShortcutsService.getStatus(mService));
        assertTrue(
                mService.onKeyEvent(
                        new KeyEvent(
                                10,
                                10,
                                KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_1,
                                0,
                                KeyEvent.META_CTRL_ON,
                                1,
                                0)));
        FirstRunStatus.setFirstRunFlowComplete(false);
        assertTrue(
                mService.onKeyEvent(
                        new KeyEvent(
                                10, 11, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1, 1, 0, 1, 0)));
        assertTrue(
                mService.onKeyEvent(
                        new KeyEvent(10, 12, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_1, 0, 0, 1, 0)));
        assertFalse(
                mService.onKeyEvent(
                        new KeyEvent(
                                20,
                                20,
                                KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_1,
                                0,
                                KeyEvent.META_CTRL_ON,
                                1,
                                0)));
        verify(mNative).onKeyDown(eq(7L), any());
        verify(mNative).destroy(7L);
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.R)
    public void clearingPreferencesStopsNewDispatchAndKeepsConsumedUp() {
        doAnswer(
                        invocation -> {
                            ExtensionGlobalShortcutsBridge bridge = invocation.getArgument(1);
                            bridge.setRegistration(KeyEvent.KEYCODE_1, KeyEvent.META_CTRL_ON, true);
                            return 7L;
                        })
                .when(mNative)
                .init(any(), any());
        when(mNative.onKeyDown(eq(7L), any())).thenReturn(true);
        mService.onServiceConnected();
        mService.startup.finishNativeInitialization();
        assertTrue(
                mService.onKeyEvent(
                        new KeyEvent(
                                10,
                                10,
                                KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_1,
                                0,
                                KeyEvent.META_CTRL_ON,
                                1,
                                0)));
        // Android R+ reports clear() to preference observers with a null key.
        // Do not run posted work before the second device's new DOWN.
        ContextUtils.getAppSharedPreferences().edit().clear().apply();
        assertFalse(
                mService.onKeyEvent(
                        new KeyEvent(
                                20,
                                20,
                                KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_1,
                                0,
                                KeyEvent.META_CTRL_ON,
                                2,
                                0)));
        assertTrue(
                mService.onKeyEvent(
                        new KeyEvent(10, 21, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_1, 0, 0, 1, 0)));
        verify(mNative).onKeyDown(eq(7L), any());
        verify(mNative).destroy(7L);
    }
}
