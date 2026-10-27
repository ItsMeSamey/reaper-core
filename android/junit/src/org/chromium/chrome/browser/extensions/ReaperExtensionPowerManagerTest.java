// Copyright 2026 The Reaper Authors.
// SPDX-License-Identifier: MPL-2.0

package org.chromium.chrome.browser.extensions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.os.PowerManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowPowerManager.ShadowWakeLock;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

import org.chromium.base.ContextUtils;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.profiles.Profile;

import java.time.Duration;

/** Renewal tests using existing Robolectric shadows; no Android OS power guarantee is implied. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ReaperExtensionPowerManagerTest {
    private ReaperExtensionPowerManager mManager;
    private PowerManager.WakeLock mWakeLock;
    private ShadowWakeLock mShadowWakeLock;
    private ShadowLooper mMainLooper;

    @Before
    public void setUp() {
        Context context = ContextUtils.getApplicationContext();
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        shadowOf(powerManager).setIsWakeLockLevelSupported(PowerManager.PARTIAL_WAKE_LOCK, true);
        mMainLooper = shadowOf(Looper.getMainLooper());
        mManager = ReaperExtensionPowerManager.create(mock(Profile.class));
    }

    @After
    public void tearDown() {
        if (mManager != null) mManager.destroy();
        // Keep cleanup explicit if an assertion aborts the test before its own cancellation.
        if (mWakeLock != null && mWakeLock.isHeld()) mWakeLock.release();
    }

    private void requestCpu() {
        assertEquals("", mManager.apply(true, false, false, true));
        mWakeLock = ShadowPowerManager.getLatestWakeLock();
        assertNotNull(mWakeLock);
        mShadowWakeLock = shadowOf(mWakeLock);
        assertTrue(mWakeLock.isHeld());
        assertFalse(mShadowWakeLock.isReferenceCounted());
    }

    private Runnable captureRenewal() {
        // Model a callback already dequeued when cancel/destroy removes its queued messages.
        // Invoke the actual callback; do not replace Handler or implement another scheduler.
        return ReflectionHelpers.getField(mManager, "mRenewCpuLease");
    }

    @Test
    public void repeatedApplyRefreshesFiniteLeaseAndReplacesOldRenewal() {
        requestCpu();
        // Advance the existing shadow clock without dispatching queued renewal callbacks.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(9));
        assertTrue(mWakeLock.isHeld());
        requestCpu();
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
        assertTrue(
                "The repeated apply must extend the original ten-minute lease", mWakeLock.isHeld());
        mMainLooper.idle();
        assertEquals(
                "The superseded five-minute callback must be removed",
                2,
                mShadowWakeLock.getTimesHeld());
        mMainLooper.idleFor(Duration.ofMinutes(3));
        assertEquals(3, mShadowWakeLock.getTimesHeld());
        // With dispatch stalled again, a renewed lease must still have a finite expiry.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11));
        assertFalse("Renewal must not turn the lease into an unbounded lock", mWakeLock.isHeld());
    }

    @Test
    public void activeRequestRenewsBeyondOriginalTimeout() {
        requestCpu();
        mMainLooper.idleFor(Duration.ofMinutes(11));
        assertTrue(mWakeLock.isHeld());
        assertEquals(3, mShadowWakeLock.getTimesHeld());
        mMainLooper.idleFor(Duration.ofMinutes(11));
        assertTrue(
                "An active request must survive multiple finite lease periods", mWakeLock.isHeld());
        assertEquals(5, mShadowWakeLock.getTimesHeld());
    }

    @Test
    public void cancelSuppressesQueuedAndAlreadyDequeuedRenewal() {
        requestCpu();
        Runnable dequeued = captureRenewal();
        mManager.cancel();
        assertFalse(mWakeLock.isHeld());
        dequeued.run();
        mMainLooper.idleFor(Duration.ofMinutes(11));
        assertFalse(mWakeLock.isHeld());
        assertEquals(
                "Cancellation must not reacquire through either callback path",
                1,
                mShadowWakeLock.getTimesHeld());
    }

    @Test
    public void destroySuppressesQueuedAndAlreadyDequeuedRenewal() {
        requestCpu();
        Runnable dequeued = captureRenewal();
        mManager.destroy();
        assertFalse(mWakeLock.isHeld());
        dequeued.run();
        mMainLooper.idleFor(Duration.ofMinutes(11));
        assertFalse(mWakeLock.isHeld());
        assertEquals(1, mShadowWakeLock.getTimesHeld());
    }
}
