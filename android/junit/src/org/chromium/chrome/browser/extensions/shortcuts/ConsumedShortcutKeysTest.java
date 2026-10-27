// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package org.chromium.chrome.browser.extensions.shortcuts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** The matcher is plain Java; values stand for normalized Android key codes/modifiers. */
@RunWith(JUnit4.class)
public class ConsumedShortcutKeysTest {
    private static final int KEY = 8;
    private static final int CTRL_SHIFT = 0x1001;
    private final ConsumedShortcutKeys mKeys = new ConsumedShortcutKeys();
    private int mDispatches;

    private boolean accept() {
        ++mDispatches;
        return true;
    }

    @Test
    public void exactRegistrationDispatchesOnceAndPairsUpWithoutModifiers() {
        mKeys.setRegistration(KEY, CTRL_SHIFT, true);
        assertTrue(mKeys.onKeyDown(1, KEY, 10, 0, CTRL_SHIFT, this::accept));
        assertTrue(mKeys.onKeyDown(1, KEY, 10, 1, 0, this::accept));
        assertTrue(mKeys.onKeyDown(1, KEY, 10, 5, CTRL_SHIFT | 2, this::accept));
        assertTrue(mKeys.onKeyUp(1, KEY, 10));
        assertFalse(mKeys.onKeyUp(1, KEY, 10));
        assertEquals(1, mDispatches);
    }

    @Test
    public void unmatchedAndAdditionalModifiersPassThroughWithoutDispatch() {
        mKeys.setRegistration(KEY, CTRL_SHIFT, true);
        assertFalse(mKeys.onKeyDown(1, KEY, 10, 0, CTRL_SHIFT | 2, this::accept));
        assertFalse(mKeys.onKeyDown(1, KEY + 1, 11, 0, CTRL_SHIFT, this::accept));
        assertFalse(mKeys.onKeyDown(1, KEY, 12, 0, 0, this::accept));
        assertFalse(mKeys.onKeyUp(1, KEY, 12));
        assertEquals(0, mDispatches);
    }

    @Test
    public void nativeRejectionDoesNotConsumeRepeatOrUp() {
        mKeys.setRegistration(KEY, CTRL_SHIFT, true);
        assertFalse(mKeys.onKeyDown(1, KEY, 10, 0, CTRL_SHIFT, () -> false));
        assertFalse(mKeys.onKeyDown(1, KEY, 10, 1, CTRL_SHIFT, this::accept));
        assertFalse(mKeys.onKeyUp(1, KEY, 10));
        assertEquals(0, mDispatches);
    }

    @Test
    public void repeatWithoutInitialDownIsNeverQueuedOrDispatched() {
        assertFalse(mKeys.onKeyDown(1, KEY, 10, 0, CTRL_SHIFT, this::accept));
        mKeys.setRegistration(KEY, CTRL_SHIFT, true);
        assertFalse(mKeys.onKeyDown(1, KEY, 10, 1, CTRL_SHIFT, this::accept));
        assertFalse(mKeys.onKeyUp(1, KEY, 10));
        assertEquals(0, mDispatches);
    }

    @Test
    public void removalKeepsOnlyAlreadyConsumedPressUntilItsUp() {
        mKeys.setRegistration(KEY, CTRL_SHIFT, true);
        assertTrue(mKeys.onKeyDown(1, KEY, 10, 0, CTRL_SHIFT, this::accept));
        mKeys.setRegistration(KEY, CTRL_SHIFT, false);
        assertTrue(mKeys.onKeyDown(1, KEY, 10, 2, 0, this::accept));
        assertTrue(mKeys.onKeyUp(1, KEY, 10));
        assertFalse(mKeys.onKeyDown(1, KEY, 20, 0, CTRL_SHIFT, this::accept));
        assertEquals(1, mDispatches);
    }

    @Test
    public void backendRetirementDuringDispatchPreservesPairing() {
        mKeys.setRegistration(KEY, CTRL_SHIFT, true);
        assertTrue(
                mKeys.onKeyDown(
                        1,
                        KEY,
                        10,
                        0,
                        CTRL_SHIFT,
                        () -> {
                            mKeys.clearRegistrations();
                            return true;
                        }));
        assertTrue(mKeys.onKeyDown(1, KEY, 10, 1, 0, this::accept));
        assertTrue(mKeys.onKeyUp(1, KEY, 10));
        assertFalse(mKeys.onKeyDown(1, KEY, 20, 0, CTRL_SHIFT, this::accept));
        assertEquals(0, mDispatches);
    }

    @Test
    public void disconnectedEpochCannotReinsertConsumedPressAfterDispatch() {
        mKeys.setRegistration(KEY, CTRL_SHIFT, true);
        assertTrue(
                mKeys.onKeyDown(
                        1,
                        KEY,
                        10,
                        0,
                        CTRL_SHIFT,
                        () -> {
                            mKeys.resetConnection();
                            return true;
                        }));
        mKeys.setRegistration(KEY, CTRL_SHIFT, true);
        assertFalse(mKeys.onKeyDown(1, KEY, 10, 1, CTRL_SHIFT, this::accept));
        assertFalse(mKeys.onKeyUp(1, KEY, 10));
        assertEquals(0, mDispatches);
    }

    @Test
    public void deviceAndDownTimeIdentifyOriginalPress() {
        mKeys.setRegistration(KEY, CTRL_SHIFT, true);
        assertTrue(mKeys.onKeyDown(1, KEY, 10, 0, CTRL_SHIFT, this::accept));
        assertTrue(mKeys.onKeyDown(2, KEY, 11, 0, CTRL_SHIFT, this::accept));
        assertFalse(mKeys.onKeyUp(1, KEY, 11));
        assertFalse(mKeys.onKeyUp(3, KEY, 10));
        assertTrue(mKeys.onKeyUp(2, KEY, 11));
        assertTrue(mKeys.onKeyUp(1, KEY, 10));
        assertEquals(2, mDispatches);
    }

    @Test
    public void newPhysicalPressDoesNotAccumulateOldDownTimes() {
        mKeys.setRegistration(KEY, CTRL_SHIFT, true);
        assertTrue(mKeys.onKeyDown(1, KEY, 10, 0, CTRL_SHIFT, this::accept));
        assertTrue(mKeys.onKeyDown(1, KEY, 20, 0, CTRL_SHIFT, this::accept));
        assertFalse(mKeys.onKeyUp(1, KEY, 10));
        assertTrue(mKeys.onKeyUp(1, KEY, 20));
    }

    @Test
    public void resetClearsBothRegistrationAndConsumedTokens() {
        mKeys.setRegistration(KEY, CTRL_SHIFT, true);
        assertTrue(mKeys.onKeyDown(1, KEY, 10, 0, CTRL_SHIFT, this::accept));
        mKeys.resetConnection();
        assertFalse(mKeys.onKeyUp(1, KEY, 10));
        assertFalse(mKeys.onKeyDown(1, KEY, 20, 0, CTRL_SHIFT, this::accept));
        assertEquals(1, mDispatches);
    }

    @Test
    public void unplugDropsOnlyThatDevicesConsumedPresses() {
        mKeys.setRegistration(KEY, CTRL_SHIFT, true);
        assertTrue(mKeys.onKeyDown(1, KEY, 10, 0, CTRL_SHIFT, this::accept));
        assertTrue(mKeys.onKeyDown(2, KEY, 11, 0, CTRL_SHIFT, this::accept));
        mKeys.removeDevice(1);
        assertFalse(mKeys.onKeyUp(1, KEY, 10));
        assertTrue(mKeys.onKeyUp(2, KEY, 11));
        assertTrue(mKeys.onKeyDown(1, KEY, 20, 0, CTRL_SHIFT, this::accept));
        assertEquals(3, mDispatches);
    }
}
