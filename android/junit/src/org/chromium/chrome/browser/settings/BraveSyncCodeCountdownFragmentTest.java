// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.

package org.chromium.chrome.browser.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.view.View;

import androidx.fragment.app.FragmentManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;

import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.R;
import org.chromium.ui.base.TestActivity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises real fragment view lifetimes without a Timer thread or any Sync/native state. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class BraveSyncCodeCountdownFragmentTest {
    private static final LocalDateTime EXPIRED = LocalDateTime.of(2000, 1, 1, 0, 0);

    private ActivityController<TestActivity> mController;
    private FragmentManager mFragmentManager;
    private TestCountdownFragment mFragment;

    public static class TestCountdownFragment extends BraveSyncCodeCountdownFragment {
        final List<Timer> timers = new ArrayList<>();
        final List<TimerTask> tasks = new ArrayList<>();

        @Override
        Timer createTextUpdater() {
            Timer timer = mock(Timer.class);
            doAnswer(
                            invocation -> {
                                tasks.add(invocation.getArgument(0));
                                return null;
                            })
                    .when(timer)
                    .schedule(any(TimerTask.class), eq(0L), eq(1000L));
            timers.add(timer);
            return timer;
        }
    }

    @Before
    public void setUp() {
        mController = Robolectric.buildActivity(TestActivity.class);
        mController.get().setTheme(R.style.Theme_BrowserUI_DayNight);
        mController.setup();
        mFragmentManager = mController.get().getSupportFragmentManager();
        mFragment = new TestCountdownFragment();
        mFragmentManager
                .beginTransaction()
                .add(android.R.id.content, mFragment)
                .commitNow();
        assertNotNull(mFragment.getView());
    }

    @After
    public void tearDown() {
        mController.pause().stop().destroy();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void startExpiredCountdown(AtomicInteger expired) {
        mFragment.setExpiredRunnable(expired::incrementAndGet);
        mFragment.setNotAfter(EXPIRED);
    }

    private void recreateView() {
        View oldView = mFragment.requireView();
        mFragmentManager.beginTransaction().detach(mFragment).commitNow();
        assertNull(mFragment.getView());
        mFragmentManager.beginTransaction().attach(mFragment).commitNow();
        assertNotSame(oldView, mFragment.requireView());
    }

    @Test
    public void destroyViewCancelsTimerWithoutAnotherTick() {
        AtomicInteger expired = new AtomicInteger();
        startExpiredCountdown(expired);
        Timer timer = mFragment.timers.get(0);

        mFragmentManager.beginTransaction().detach(mFragment).commitNow();

        assertNull(mFragment.getView());
        verify(timer).cancel();
        verify(timer).purge();
        assertEquals(0, expired.get());
    }

    @Test
    public void recreatedViewRunsFreshCountdown() {
        startExpiredCountdown(new AtomicInteger());
        recreateView();
        AtomicInteger expired = new AtomicInteger();
        startExpiredCountdown(expired);

        mFragment.tasks.get(1).run();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, expired.get());
        assertEquals(
                View.VISIBLE,
                mFragment.requireView().findViewById(R.id.brave_sync_code_expired_block)
                        .getVisibility());
        assertEquals(
                View.GONE,
                mFragment.requireView().findViewById(R.id.brave_sync_code_countdown_block)
                        .getVisibility());
        verify(mFragment.timers.get(1)).cancel();
    }

    @Test
    public void queuedOldTickDoesNotTouchRecreatedView() throws Exception {
        AtomicInteger oldExpired = new AtomicInteger();
        startExpiredCountdown(oldExpired);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            // Exercise the actual TimerTask's normal UI dispatch while the main looper is paused.
            worker.submit(mFragment.tasks.get(0)).get();
        } finally {
            worker.shutdown();
        }
        recreateView();
        AtomicInteger freshExpired = new AtomicInteger();
        startExpiredCountdown(freshExpired);
        Timer freshTimer = mFragment.timers.get(1);

        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, oldExpired.get());
        assertEquals(0, freshExpired.get());
        verify(freshTimer, never()).cancel();
        verify(freshTimer, never()).purge();

        mFragment.tasks.get(1).run();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, freshExpired.get());
        verify(freshTimer).cancel();
    }
}
