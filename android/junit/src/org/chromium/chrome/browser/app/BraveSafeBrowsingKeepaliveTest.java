// Copyright 2026 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Build;
import android.os.Looper;
import android.util.Pair;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import org.chromium.base.BraveFeatureList;
import org.chromium.base.supplier.OneshotSupplier;
import org.chromium.base.task.PostTask;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.base.test.util.Features.DisableFeatures;
import org.chromium.base.test.util.Features.EnableFeatures;
import org.chromium.chrome.browser.app.metrics.LaunchCauseMetrics;
import org.chromium.chrome.browser.app.tabmodel.TabModelOrchestrator;
import org.chromium.chrome.browser.flags.ActivityType;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.flags.ChromeSessionState;
import org.chromium.chrome.browser.keyboard_accessory.ManualFillingComponent;
import org.chromium.chrome.browser.media.BraveYouTubePictureInPictureController;
import org.chromium.chrome.browser.metrics.UmaActivityObserver;
import org.chromium.chrome.browser.night_mode.NightModeStateProvider;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileProvider;
import org.chromium.chrome.browser.safe_browsing.SafeBrowsingBridge;
import org.chromium.chrome.browser.safe_browsing.SafeBrowsingBridgeJni;
import org.chromium.chrome.browser.safe_browsing.SafeBrowsingState;
import org.chromium.chrome.browser.tab.TabDestroyStatus;
import org.chromium.chrome.browser.tabmodel.TabCreator;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.ui.RootUiCoordinator;
import org.chromium.chrome.browser.ui.appmenu.AppMenuPropertiesDelegate;
import org.chromium.components.safe_browsing.BraveSafeBrowsingApiHandler;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real BraveActivity timer methods with external services mocked; no extracted timer code. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.Q, shadows = {
        BraveSafeBrowsingKeepaliveTest.SafeBrowsingHandlerShadow.class,
        BraveSafeBrowsingKeepaliveTest.SessionStateShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
@EnableFeatures(BraveFeatureList.BRAVE_ANDROID_SAFE_BROWSING)
@DisableFeatures(ChromeFeatureList.UMA_SESSION_CORRECTNESS_FIXES)
public class BraveSafeBrowsingKeepaliveTest {
    private static final long EXPECTED_INTERVAL_MS = 30000;
    private static Profile sProfile;
    private static NightModeStateProvider sNightMode;
    private static LaunchCauseMetrics sLaunchMetrics;
    private static ManualFillingComponent sManualFilling;
    private static BraveSafeBrowsingApiHandler sHandler;
    private static final AtomicInteger sProfileReads = new AtomicInteger();

    private final Queue<Runnable> mBackground = new ConcurrentLinkedQueue<>();
    private final AtomicInteger mInitializations = new AtomicInteger();
    private TestActivity mActivity;
    private SafeBrowsingBridge.Natives mSafeBrowsingJni;
    private boolean mDestroyed;

    /** Only the external GMS-facing singleton is replaced. */
    @Implements(value = BraveSafeBrowsingApiHandler.class, isInAndroidSdk = false)
    public static class SafeBrowsingHandlerShadow {
        @Implementation
        protected static BraveSafeBrowsingApiHandler getInstance() { return sHandler; }
    }

    /** Metrics JNI is unrelated to timer ownership and is not loaded by this fixture. */
    @Implements(value = ChromeSessionState.class, isInAndroidSdk = false)
    public static class SessionStateShadow {
        @Implementation
        protected static void setIsInMultiWindowMode(boolean value) {}
        @Implementation
        protected static void setDarkModeState(boolean activity, boolean system) {}
    }

    /** Supplies unrelated collaborators without overriding any tested lifecycle method. */
    public static class TestActivity extends BraveActivity {
        @Override
        protected TabModelOrchestrator createTabModelOrchestrator() { return null; }
        @Override
        protected void createTabModels() {}
        @Override
        protected int destroyTabModels() { return TabDestroyStatus.NO_SHUTDOWN; }
        @Override
        protected Pair<? extends TabCreator, ? extends TabCreator> createTabCreators() {
            return null;
        }
        @Override
        protected LaunchCauseMetrics createLaunchCauseMetrics() { return sLaunchMetrics; }
        @Override
        public AppMenuPropertiesDelegate createAppMenuPropertiesDelegate() { return null; }
        @Override
        public int getActivityType() { return ActivityType.TABBED; }
        @Override
        protected OneshotSupplier<ProfileProvider> createProfileProvider() { return null; }
        @Override
        protected RootUiCoordinator createRootUiCoordinator() { return null; }
        @Override
        protected NightModeStateProvider createNightModeStateProvider() { return sNightMode; }
        @Override
        public ManualFillingComponent getManualFillingComponent() { return sManualFilling; }
        @Override
        public Profile getCurrentProfile() {
            sProfileReads.incrementAndGet();
            return sProfile;
        }
    }

    @Before
    public void setUp() {
        assertEquals(EXPECTED_INTERVAL_MS,
                BraveSafeBrowsingApiHandler.SAFE_BROWSING_INIT_INTERVAL_MS);
        sProfile = mock(Profile.class);
        sNightMode = mock(NightModeStateProvider.class);
        sLaunchMetrics = mock(LaunchCauseMetrics.class);
        sManualFilling = mock(ManualFillingComponent.class);
        sHandler = mock(BraveSafeBrowsingApiHandler.class);
        sProfileReads.set(0);
        doAnswer(invocation -> { mInitializations.incrementAndGet(); return null; })
                .when(sHandler).initSafeBrowsing();
        BraveActivity.Natives activityJni = mock(BraveActivity.Natives.class);
        when(activityJni.getSafeBrowsingApiKey()).thenReturn("local-test-key");
        BraveActivityJni.setInstanceForTesting(activityJni);
        mSafeBrowsingJni = mock(SafeBrowsingBridge.Natives.class);
        when(mSafeBrowsingJni.getSafeBrowsingState(any()))
                .thenReturn(SafeBrowsingState.STANDARD_PROTECTION);
        SafeBrowsingBridgeJni.setInstanceForTesting(mSafeBrowsingJni);
        // Fail rather than silently testing a different scheduler after native migration.
        assertFalse((Boolean) ReflectionHelpers.getStaticField(PostTask.class, "sNativeInitialized"));
        PostTask.setPrenativeThreadPoolExecutorForTesting(mBackground::add);
        mActivity = Robolectric.buildActivity(TestActivity.class).get();
        TabModelSelector selector = mock(TabModelSelector.class);
        when(selector.getCurrentModel()).thenReturn(mock(TabModel.class));
        TabModelOrchestrator orchestrator = mock(TabModelOrchestrator.class);
        when(orchestrator.areTabModelsInitialized()).thenReturn(true);
        when(orchestrator.getTabModelSelector()).thenReturn(selector);
        mActivity.setTabModelOrchestratorForTesting(orchestrator);
        ReflectionHelpers.setField(mActivity, "mUmaActivityObserver", mock(UmaActivityObserver.class));
        ReflectionHelpers.setField(mActivity, "mYouTubePictureInPictureController",
                mock(BraveYouTubePictureInPictureController.class));
    }

    @After
    public void tearDown() {
        if (mActivity != null && !mDestroyed) destroyActivityTimer();
        idleUi();
        drainBackground();
        sHandler = null;
        sProfile = null;
        sNightMode = null;
        sLaunchMetrics = null;
        sManualFilling = null;
    }

    private void idleUi() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    private void tick() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(EXPECTED_INTERVAL_MS, TimeUnit.MILLISECONDS);
        drainBackground();
    }

    private void drainBackground() {
        int count = 0;
        for (Runnable task; (task = mBackground.poll()) != null; ) {
            assertTrue("Background queue must be bounded", ++count <= 20);
            task.run();
        }
    }

    private void destroyActivityTimer() {
        mActivity.onDestroyInternal();
        mDestroyed = true;
    }

    @Test
    public void twoResumesBeforeFirstTickKeepOneChain() {
        mActivity.onResumeWithNative();
        mActivity.onResumeWithNative();
        idleUi();
        drainBackground();
        assertEquals(1, mInitializations.get());
        assertEquals(1, sProfileReads.get());
        tick();
        assertEquals(2, mInitializations.get());
        assertEquals(2, sProfileReads.get());
    }

    @Test
    public void pauseRetainsThirtySecondCadence() {
        mActivity.onResumeWithNative();
        idleUi();
        drainBackground();
        mActivity.onPauseWithNative();
        verify(sManualFilling).onPause();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(29999, TimeUnit.MILLISECONDS);
        drainBackground();
        assertEquals(1, mInitializations.get());
        assertEquals(1, sProfileReads.get());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
        drainBackground();
        assertEquals(2, mInitializations.get());
        assertEquals(2, sProfileReads.get());
    }

    @Test
    public void renewedResumeRetiresQueuedBackgroundAndUiGeneration() {
        mActivity.onResumeWithNative();
        idleUi();
        assertFalse(mBackground.isEmpty());
        mActivity.onResumeWithNative();
        idleUi();
        drainBackground();
        assertEquals(1, mInitializations.get());
        assertEquals(2, sProfileReads.get());
        tick();
        assertEquals(2, mInitializations.get());
        assertEquals(3, sProfileReads.get());
    }

    @Test
    public void destructionCancelsPendingBackgroundAndLaterProfileAccess() {
        mActivity.onResumeWithNative();
        idleUi();
        destroyActivityTimer();
        verify(sHandler).shutdownSafeBrowsing();
        drainBackground();
        tick();
        assertEquals(0, mInitializations.get());
        assertEquals(1, sProfileReads.get());
    }

    @Test
    public void preferenceIsRecheckedAndReenableInitializesNextTick() {
        when(mSafeBrowsingJni.getSafeBrowsingState(any()))
                .thenReturn(SafeBrowsingState.NO_SAFE_BROWSING);
        mActivity.onResumeWithNative();
        idleUi();
        drainBackground();
        tick();
        assertEquals(0, mInitializations.get());
        when(mSafeBrowsingJni.getSafeBrowsingState(any()))
                .thenReturn(SafeBrowsingState.STANDARD_PROTECTION);
        tick();
        assertEquals(1, mInitializations.get());
        assertEquals(3, sProfileReads.get());
    }

    @Test
    public void renewedResumeWaitsForInFlightInitializer() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean observedControllerWait = new AtomicBoolean();
        Thread ui = Thread.currentThread();
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue("Initializer release timeout", release.await(5, TimeUnit.SECONDS));
            mInitializations.incrementAndGet();
            return null;
        }).when(sHandler).initSafeBrowsing();
        mActivity.onResumeWithNative();
        idleUi();
        Runnable initialization = mBackground.poll();
        assertNotNull(initialization);
        Thread worker = new Thread(() -> {
            try { initialization.run(); } catch (Throwable failure) { error.set(failure); }
        }, "keepalive-test-initializer");
        Thread releaser = new Thread(() -> {
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    if (ui.getState() == Thread.State.BLOCKED) {
                        for (StackTraceElement frame : ui.getStackTrace()) {
                            if (frame.getClassName().equals("org.chromium.base.CallbackController")
                                    && frame.getMethodName().equals("destroy")) {
                                observedControllerWait.set(true);
                                return;
                            }
                        }
                    }
                    Thread.sleep(1);
                }
                throw new AssertionError("Resume never waited on real CallbackController");
            } catch (Throwable failure) { error.set(failure); }
            finally { release.countDown(); }
        }, "keepalive-test-release");
        try {
            worker.start();
            assertTrue("Initializer never entered", entered.await(5, TimeUnit.SECONDS));
            releaser.start();
            mActivity.onResumeWithNative();
        } finally {
            release.countDown();
            worker.join(5000);
            releaser.join(5000);
        }
        assertFalse("Initializer leaked", worker.isAlive());
        assertFalse("Release observer leaked", releaser.isAlive());
        if (error.get() != null) throw new AssertionError("Worker failure", error.get());
        assertTrue(observedControllerWait.get());
        idleUi();
        drainBackground();
        assertEquals(2, mInitializations.get());
        tick();
        assertEquals(3, mInitializations.get());
    }
}
