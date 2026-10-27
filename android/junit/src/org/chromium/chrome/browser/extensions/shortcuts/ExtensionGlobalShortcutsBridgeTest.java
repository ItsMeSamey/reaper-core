// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package org.chromium.chrome.browser.extensions.shortcuts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.view.KeyEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.profiles.Profile;

@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ExtensionGlobalShortcutsBridgeTest {
    private final ExtensionGlobalShortcutsBridge.Natives mNative =
            mock(ExtensionGlobalShortcutsBridge.Natives.class);
    private final Profile mProfile = mock(Profile.class);
    private final ConsumedShortcutKeys mKeys = new ConsumedShortcutKeys();
    private int mUnavailable;

    @Before
    public void setUp() {
        ExtensionGlobalShortcutsBridgeJni.setInstanceForTesting(mNative);
        when(mNative.init(eq(mProfile), any())).thenReturn(7L);
    }

    private ExtensionGlobalShortcutsBridge create() {
        return new ExtensionGlobalShortcutsBridge(mProfile, mKeys, () -> ++mUnavailable);
    }

    private KeyEvent down() {
        return new KeyEvent(
                10,
                10,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_1,
                0,
                KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_SHIFT_RIGHT_ON,
                1,
                0);
    }

    private boolean dispatch(ExtensionGlobalShortcutsBridge bridge, KeyEvent event) {
        return mKeys.onKeyDown(
                event.getDeviceId(),
                event.getKeyCode(),
                event.getDownTime(),
                event.getRepeatCount(),
                ExtensionGlobalShortcutsBridge.normalizeModifiers(event.getMetaState()),
                () -> bridge.dispatchKeyDown(event));
    }

    @Test
    public void synchronousInitRegistrationBecomesUsableOnlyWithReadyHandle() {
        doAnswer(
                        invocation -> {
                            ExtensionGlobalShortcutsBridge bridge = invocation.getArgument(1);
                            assertFalse(bridge.isReady());
                            assertTrue(
                                    bridge.setRegistration(
                                            KeyEvent.KEYCODE_1,
                                            KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON,
                                            true));
                            return 7L;
                        })
                .when(mNative)
                .init(eq(mProfile), any());
        ExtensionGlobalShortcutsBridge bridge = create();
        when(mNative.onKeyDown(eq(7L), any())).thenReturn(true);
        assertTrue(bridge.isReady());
        assertTrue(dispatch(bridge, down()));
        bridge.destroy();
        assertTrue(mKeys.onKeyUp(1, KeyEvent.KEYCODE_1, 10));
    }

    @Test
    public void initDenialClearsAnyTentativeRegistration() {
        doAnswer(
                        invocation -> {
                            ExtensionGlobalShortcutsBridge bridge = invocation.getArgument(1);
                            bridge.setRegistration(
                                    KeyEvent.KEYCODE_1,
                                    KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON,
                                    true);
                            return 0L;
                        })
                .when(mNative)
                .init(eq(mProfile), any());
        ExtensionGlobalShortcutsBridge bridge = create();
        assertFalse(bridge.isReady());
        assertFalse(dispatch(bridge, down()));
        verify(mNative, never()).onKeyDown(eq(0L), any());
        verify(mNative, never()).destroy(0L);
    }

    @Test
    public void backendRetiresDuringNativeDispatchWithoutLosingPairedUp() {
        ExtensionGlobalShortcutsBridge bridge = create();
        bridge.setRegistration(
                KeyEvent.KEYCODE_1, KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON, true);
        doAnswer(
                        invocation -> {
                            bridge.onNativeUnavailable();
                            return true;
                        })
                .when(mNative)
                .onKeyDown(eq(7L), any());
        assertTrue(dispatch(bridge, down()));
        assertFalse(bridge.isReady());
        assertEquals(1, mUnavailable);
        assertTrue(mKeys.onKeyUp(1, KeyEvent.KEYCODE_1, 10));
        assertFalse(dispatch(bridge, down()));
        bridge.destroy();
        verify(mNative).destroy(7L);
    }

    @Test
    public void destroyedBridgeRejectsLateNativeCallbacksAndFutureKeys() {
        ExtensionGlobalShortcutsBridge bridge = create();
        bridge.destroy();
        assertFalse(bridge.setRegistration(KeyEvent.KEYCODE_1, KeyEvent.META_CTRL_ON, true));
        bridge.onNativeUnavailable();
        assertEquals(0, mUnavailable);
        assertFalse(bridge.dispatchKeyDown(down()));
        verify(mNative, never()).onKeyDown(eq(7L), any());
        verify(mNative).destroy(7L);
    }

    @Test
    public void unsupportedModifierRegistrationIsRejected() {
        ExtensionGlobalShortcutsBridge bridge = create();
        assertFalse(bridge.setRegistration(KeyEvent.KEYCODE_1, KeyEvent.META_CAPS_LOCK_ON, true));
        assertEquals(
                KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON,
                ExtensionGlobalShortcutsBridge.normalizeModifiers(down().getMetaState()));
        assertTrue(
                bridge.setRegistration(
                        KeyEvent.KEYCODE_1,
                        KeyEvent.META_FUNCTION_ON | KeyEvent.META_META_ON,
                        true));
        bridge.destroy();
    }
}
