// Copyright 2026 The Reaper Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.ntp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.LooperMode;

import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.ntp.StaticNtpSurfaceCoordinator.RestoringState;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabHidingType;
import org.chromium.chrome.browser.tab.TabObserver;
import org.chromium.chrome.browser.ui.native_page.NativePage;
import org.chromium.content_public.browser.NavigationController;
import org.chromium.content_public.browser.NavigationEntry;
import org.chromium.content_public.browser.NavigationHandle;
import org.chromium.content_public.browser.WebContents;
import org.chromium.url.GURL;
import org.chromium.url.JUnitTestGURLs;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the retained NTP's real scrolling, input, and entry-owned local state. */
@RunWith(BaseRobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class StaticNtpSurfaceCoordinatorTest {
    private static final int ENTRY_INDEX = 3;
    private static final long ENTRY_TIME = 1200L;
    private static final int WIDTH = 400;
    private static final int HEIGHT = 600;
    private static final int HEADER_HEIGHT = 1800;

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private Tab mTab;
    @Mock private WebContents mWebContents;
    @Mock private NavigationController mNavigationController;
    @Mock private NativePage mNativePage;
    @Mock private NavigationHandle mNavigationHandle;
    @Mock private SnapScrollHelperImpl mSnapHelper;
    @Mock private StaticNtpSurfaceCoordinator.Delegate mDelegate;

    private ActivityController<Activity> mActivityController;
    private Activity mActivity;
    private FrameLayout mHeader;
    private StaticNtpSurfaceCoordinator mCoordinator;
    private ViewGroup mRoot;
    private RecyclerView mRecycler;

    @Before
    public void setUp() {
        mActivityController = Robolectric.buildActivity(Activity.class).setup();
        mActivity = mActivityController.get();
        when(mTab.getWebContents()).thenReturn(mWebContents);
        when(mWebContents.getNavigationController()).thenReturn(mNavigationController);
        when(mNavigationController.getLastCommittedEntryIndex()).thenReturn(ENTRY_INDEX);
        when(mNavigationController.getEntryAtIndex(ENTRY_INDEX))
                .thenReturn(entry(ENTRY_INDEX, ENTRY_TIME, JUnitTestGURLs.NTP_URL));
        when(mNavigationController.getEntryExtraData(
                        ENTRY_INDEX, StaticNtpSurfaceCoordinator.SCROLL_STATE_KEY))
                .thenReturn("");
    }

    @After
    public void tearDown() {
        if (mCoordinator != null) mCoordinator.destroy();
        mActivityController.pause().stop().destroy();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static NavigationEntry entry(int index, long timestamp, GURL url) {
        return new NavigationEntry(index, url, url, url, "", null, 0, timestamp, false);
    }

    private void createSurface(String savedState, int headerHeight) {
        when(mNavigationController.getEntryExtraData(
                        ENTRY_INDEX, StaticNtpSurfaceCoordinator.SCROLL_STATE_KEY))
                .thenReturn(savedState);
        mHeader = new FrameLayout(mActivity);
        mHeader.setMinimumHeight(headerHeight);
        mCoordinator =
                new StaticNtpSurfaceCoordinator(mActivity, mTab, mHeader, mSnapHelper, mDelegate);
        mRoot = mCoordinator.getView();
        mRecycler = mCoordinator.getRecyclerView();
        mActivity.setContentView(mRoot);
    }

    private void layoutOnce() {
        mRoot.forceLayout();
        mRecycler.forceLayout();
        mRoot.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        mRoot.layout(0, 0, WIDTH, HEIGHT);
    }

    private void layoutSurface() {
        // RecyclerView applies scrollToPositionWithOffset on its next real layout.
        for (int pass = 0; pass < 3; pass++) {
            layoutOnce();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        layoutOnce();
    }

    private void assertRestoringState(int expected) {
        assertEquals(expected, (int) mCoordinator.getRestoringStateSupplier().get());
    }

    private TabObserver tabObserver() {
        ArgumentCaptor<TabObserver> observer = ArgumentCaptor.forClass(TabObserver.class);
        verify(mTab).addObserver(observer.capture());
        return observer.getValue();
    }

    private void captureThumbnail() {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        mCoordinator.captureThumbnail(new Canvas(bitmap));
        bitmap.recycle();
    }

    @Test
    public void testOneLocalHeaderInitializesWithoutSavedState() {
        createSurface("", HEADER_HEIGHT);

        assertRestoringState(RestoringState.NO_STATE_TO_RESTORE);
        assertFalse(mCoordinator.getScrollDelegate().isScrollViewInitialized());
        layoutSurface();

        assertSame(mCoordinator, mCoordinator.getScrollDelegate());
        assertNotNull(mRecycler.getAdapter());
        assertEquals(1, mRecycler.getAdapter().getItemCount());
        assertTrue(mCoordinator.isScrollViewInitialized());
        assertTrue(mCoordinator.isChildVisibleAtPosition(0));
        assertFalse(mCoordinator.isChildVisibleAtPosition(1));
        assertEquals(0, mCoordinator.getVerticalScrollOffset());
        assertNotNull(mCoordinator.getUiConfig());
        verify(mSnapHelper).setView(mRecycler);
    }

    @Test
    public void testSavedOffsetWaitsForLayoutThenRestores() {
        when(mNavigationController.getEntryAtIndex(ENTRY_INDEX))
                .thenReturn(entry(ENTRY_INDEX, ENTRY_TIME, JUnitTestGURLs.NTP_NATIVE_URL));
        createSurface("120", HEADER_HEIGHT);
        assertRestoringState(RestoringState.WAITING_TO_RESTORE);

        layoutSurface();

        assertRestoringState(RestoringState.RESTORED);
        assertEquals(120, mCoordinator.getVerticalScrollOffset());
    }

    @Test
    public void testEntryCommittedAfterConstructionRestoresOnFirstLayout() {
        when(mNavigationController.getLastCommittedEntryIndex()).thenReturn(-1);
        createSurface("120", HEADER_HEIGHT);
        assertRestoringState(RestoringState.NO_STATE_TO_RESTORE);

        when(mNavigationController.getLastCommittedEntryIndex()).thenReturn(ENTRY_INDEX);
        layoutSurface();

        assertRestoringState(RestoringState.RESTORED);
        assertEquals(120, mCoordinator.getVerticalScrollOffset());
        verify(mNavigationController)
                .getEntryExtraData(ENTRY_INDEX, StaticNtpSurfaceCoordinator.SCROLL_STATE_KEY);
    }

    @Test
    public void testSameBoundsFollowupLayoutCompletesPendingRestoration() {
        createSurface("120", HEADER_HEIGHT);
        layoutOnce();
        assertRestoringState(RestoringState.WAITING_TO_RESTORE);

        // Hold the parent layout so idling only consumes the posted completion, not the
        // requested RecyclerView layout. Otherwise the attached activity can finish both.
        mRoot.suppressLayout(true);
        try {
            mRecycler.requestLayout();
            assertTrue(mRecycler.isLayoutRequested());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(mRecycler.isLayoutRequested());
            assertRestoringState(RestoringState.WAITING_TO_RESTORE);

            int left = mRecycler.getLeft();
            int top = mRecycler.getTop();
            int right = mRecycler.getRight();
            int bottom = mRecycler.getBottom();
            mRecycler.forceLayout();
            mRecycler.measure(
                    View.MeasureSpec.makeMeasureSpec(right - left, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(bottom - top, View.MeasureSpec.EXACTLY));
            mRecycler.layout(left, top, right, bottom);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals(left, mRecycler.getLeft());
            assertEquals(top, mRecycler.getTop());
            assertEquals(right, mRecycler.getRight());
            assertEquals(bottom, mRecycler.getBottom());
            assertRestoringState(RestoringState.RESTORED);
            assertEquals(120, mCoordinator.getVerticalScrollOffset());
        } finally {
            mRoot.suppressLayout(false);
        }
    }

    @Test
    public void testSavedOffsetClampsToAvailableContent() {
        createSurface(Integer.toString(Integer.MAX_VALUE), HEADER_HEIGHT);

        layoutSurface();

        assertRestoringState(RestoringState.RESTORED);
        int maximum =
                Math.max(
                        0,
                        mHeader.getHeight()
                                - mRecycler.getHeight()
                                + mRecycler.getPaddingTop()
                                + mRecycler.getPaddingBottom());
        assertTrue(mCoordinator.getVerticalScrollOffset() > 0);
        assertTrue(mCoordinator.getVerticalScrollOffset() <= maximum);
    }

    @Test
    public void testShortHeaderClampsSavedOffsetToZero() {
        createSurface("500", 100);

        layoutSurface();

        assertRestoringState(RestoringState.RESTORED);
        assertEquals(0, mCoordinator.getVerticalScrollOffset());
        assertFalse(mRecycler.canScrollVertically(1));
    }

    @Test
    public void testMalformedAndNegativeOffsetsDoNotRestore() {
        for (String state : new String[] {"bad state", "-1", "2147483648"}) {
            createSurface(state, HEADER_HEIGHT);

            assertRestoringState(RestoringState.NO_STATE_TO_RESTORE);
            layoutSurface();
            assertEquals(0, mCoordinator.getVerticalScrollOffset());

            mCoordinator.destroy();
        }
    }

    @Test
    public void testPendingRestorationDoesNotFollowReplacementEntry() {
        createSurface("120", HEADER_HEIGHT);
        assertRestoringState(RestoringState.WAITING_TO_RESTORE);
        // A new visit to the same URL at the same history index is a distinct entry.
        when(mNavigationController.getEntryAtIndex(ENTRY_INDEX))
                .thenReturn(entry(ENTRY_INDEX, ENTRY_TIME + 1, JUnitTestGURLs.NTP_URL));

        layoutSurface();

        assertRestoringState(RestoringState.NO_STATE_TO_RESTORE);
        assertEquals(0, mCoordinator.getVerticalScrollOffset());
        verify(mNavigationController, never())
                .setEntryExtraData(anyInt(), anyString(), anyString());
    }

    @Test
    public void testHiddenNavigationAndDestroySaveOnlyTheOriginalEntry() {
        createSurface("", HEADER_HEIGHT);
        TabObserver observer = tabObserver();
        layoutSurface();
        mRecycler.scrollBy(0, 160);
        assertEquals(160, mCoordinator.getVerticalScrollOffset());

        observer.onHidden(mTab, TabHidingType.CHANGED_TABS);
        observer.onPageLoadStarted(mTab, JUnitTestGURLs.EXAMPLE_URL);
        mCoordinator.destroy();
        mCoordinator.destroy();

        verify(mNavigationController, times(3))
                .setEntryExtraData(
                        ENTRY_INDEX, StaticNtpSurfaceCoordinator.SCROLL_STATE_KEY, "160");
        verify(mTab).removeObserver(observer);
        verify(mSnapHelper).destroy();
    }

    @Test
    public void testReusedNtpBindsNewEntriesAndRestoresOnlyTheirState() {
        createSurface("120", HEADER_HEIGHT);
        TabObserver observer = tabObserver();
        when(mTab.getNativePage()).thenReturn(mNativePage);
        when(mNativePage.getView()).thenReturn(mRoot);
        when(mNavigationHandle.hasCommitted()).thenReturn(true);
        when(mNavigationHandle.isInPrimaryMainFrame()).thenReturn(true);
        when(mNavigationHandle.getUrl()).thenReturn(JUnitTestGURLs.NTP_URL);
        layoutSurface();
        assertEquals(120, mCoordinator.getVerticalScrollOffset());

        // Reusing the same native NTP for a fresh history entry must not carry old scrolling.
        when(mNavigationController.getEntryAtIndex(ENTRY_INDEX))
                .thenReturn(entry(ENTRY_INDEX, ENTRY_TIME + 1, JUnitTestGURLs.NTP_URL));
        when(mNavigationController.getEntryExtraData(
                        ENTRY_INDEX, StaticNtpSurfaceCoordinator.SCROLL_STATE_KEY))
                .thenReturn("");
        observer.onDidFinishNavigationInPrimaryMainFrame(mTab, mNavigationHandle);
        layoutSurface();
        assertEquals(0, mCoordinator.getVerticalScrollOffset());

        // A later visit with its own persisted state restores that entry's offset.
        when(mNavigationController.getEntryAtIndex(ENTRY_INDEX))
                .thenReturn(entry(ENTRY_INDEX, ENTRY_TIME + 2, JUnitTestGURLs.NTP_URL));
        when(mNavigationController.getEntryExtraData(
                        ENTRY_INDEX, StaticNtpSurfaceCoordinator.SCROLL_STATE_KEY))
                .thenReturn("200");
        observer.onDidFinishNavigationInPrimaryMainFrame(mTab, mNavigationHandle);
        layoutSurface();

        assertRestoringState(RestoringState.RESTORED);
        assertEquals(200, mCoordinator.getVerticalScrollOffset());
        observer.onHidden(mTab, TabHidingType.CHANGED_TABS);
        verify(mNavigationController)
                .setEntryExtraData(
                        ENTRY_INDEX, StaticNtpSurfaceCoordinator.SCROLL_STATE_KEY, "200");
    }

    @Test
    public void testRestorationObserverCanDestroyReusedSurfaceDuringCommit() {
        createSurface("", HEADER_HEIGHT);
        layoutSurface();
        when(mTab.getNativePage()).thenReturn(mNativePage);
        when(mNativePage.getView()).thenReturn(mRoot);
        when(mNavigationHandle.hasCommitted()).thenReturn(true);
        mCoordinator
                .getRestoringStateSupplier()
                .addSyncObserverAndPostIfNonNull(
                        state -> {
                            if (state == RestoringState.WAITING_TO_RESTORE) mCoordinator.destroy();
                        });
        when(mNavigationController.getEntryAtIndex(ENTRY_INDEX))
                .thenReturn(entry(ENTRY_INDEX, ENTRY_TIME + 1, JUnitTestGURLs.NTP_URL));
        when(mNavigationController.getEntryExtraData(
                        ENTRY_INDEX, StaticNtpSurfaceCoordinator.SCROLL_STATE_KEY))
                .thenReturn("200");

        TabObserver observer = tabObserver();
        observer.onDidFinishNavigationInPrimaryMainFrame(mTab, mNavigationHandle);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, mRoot.getChildCount());
        assertNull(mRecycler.getAdapter());
        verify(mTab).removeObserver(observer);
        verify(mSnapHelper).destroy();
        verify(mNavigationController, never())
                .setEntryExtraData(anyInt(), anyString(), anyString());
    }

    @Test
    public void testCommitOwnedByAnotherNativePageDoesNotRebindOldSurface() {
        createSurface("", HEADER_HEIGHT);
        TabObserver observer = tabObserver();
        layoutSurface();
        mRecycler.scrollBy(0, 80);
        assertEquals(80, mCoordinator.getVerticalScrollOffset());
        when(mTab.getNativePage()).thenReturn(mNativePage);
        when(mNativePage.getView()).thenReturn(new FrameLayout(mActivity));
        when(mNavigationHandle.hasCommitted()).thenReturn(true);
        when(mNavigationHandle.isInPrimaryMainFrame()).thenReturn(true);
        when(mNavigationHandle.getUrl()).thenReturn(JUnitTestGURLs.NTP_URL);
        when(mNavigationController.getEntryAtIndex(ENTRY_INDEX))
                .thenReturn(entry(ENTRY_INDEX, ENTRY_TIME + 1, JUnitTestGURLs.NTP_URL));
        when(mNavigationController.getEntryExtraData(
                        ENTRY_INDEX, StaticNtpSurfaceCoordinator.SCROLL_STATE_KEY))
                .thenReturn("200");
        clearInvocations(mNavigationController);

        observer.onDidFinishNavigationInPrimaryMainFrame(mTab, mNavigationHandle);
        layoutSurface();
        observer.onHidden(mTab, TabHidingType.CHANGED_TABS);

        assertEquals(80, mCoordinator.getVerticalScrollOffset());
        verify(mNavigationController, never()).getEntryExtraData(anyInt(), anyString());
        verify(mNavigationController, never())
                .setEntryExtraData(anyInt(), anyString(), anyString());
    }

    @Test
    public void testChangedHistoryIndexDoesNotReceiveOldScrollState() {
        createSurface("", HEADER_HEIGHT);
        TabObserver observer = tabObserver();
        layoutSurface();
        mRecycler.scrollBy(0, 80);
        when(mNavigationController.getLastCommittedEntryIndex()).thenReturn(ENTRY_INDEX + 1);
        when(mNavigationController.getEntryAtIndex(ENTRY_INDEX + 1))
                .thenReturn(entry(ENTRY_INDEX + 1, ENTRY_TIME, JUnitTestGURLs.NTP_URL));

        observer.onHidden(mTab, TabHidingType.CHANGED_TABS);
        mCoordinator.destroy();

        verify(mNavigationController, never())
                .setEntryExtraData(anyInt(), anyString(), anyString());
    }

    @Test
    public void testReplacedEntryTimestampDoesNotReceiveOldScrollState() {
        createSurface("", HEADER_HEIGHT);
        TabObserver observer = tabObserver();
        layoutSurface();
        mRecycler.scrollBy(0, 80);
        when(mNavigationController.getEntryAtIndex(ENTRY_INDEX))
                .thenReturn(entry(ENTRY_INDEX, ENTRY_TIME + 1, JUnitTestGURLs.NTP_URL));

        observer.onHidden(mTab, TabHidingType.CHANGED_TABS);
        mCoordinator.destroy();

        verify(mNavigationController, never())
                .setEntryExtraData(anyInt(), anyString(), anyString());
    }

    @Test
    public void testNonNtpEntryHasNoLocalNtpState() {
        when(mNavigationController.getEntryAtIndex(ENTRY_INDEX))
                .thenReturn(entry(ENTRY_INDEX, ENTRY_TIME, JUnitTestGURLs.EXAMPLE_URL));
        createSurface("120", HEADER_HEIGHT);
        layoutSurface();

        assertRestoringState(RestoringState.NO_STATE_TO_RESTORE);
        assertEquals(0, mCoordinator.getVerticalScrollOffset());
        mCoordinator.destroy();
        verify(mNavigationController, never()).getEntryExtraData(anyInt(), anyString());
        verify(mNavigationController, never())
                .setEntryExtraData(anyInt(), anyString(), anyString());
    }

    @Test
    public void testDestroyBeforeLayoutRetiresObserversAndQueuedRestoration() {
        createSurface("120", HEADER_HEIGHT);
        TabObserver observer = tabObserver();
        RecyclerView recycler = mRecycler;

        mCoordinator.destroy();
        mCoordinator.destroy();
        layoutSurface();
        observer.onHidden(mTab, TabHidingType.CHANGED_TABS);
        observer.onPageLoadStarted(mTab, JUnitTestGURLs.EXAMPLE_URL);

        assertFalse(mCoordinator.isScrollViewInitialized());
        assertFalse(mCoordinator.shouldCaptureThumbnail());
        assertEquals(0, mRoot.getChildCount());
        assertNull(recycler.getAdapter());
        verify(mTab).removeObserver(observer);
        verify(mSnapHelper).destroy();
        verify(mNavigationController, never())
                .setEntryExtraData(anyInt(), anyString(), anyString());
    }

    @Test
    public void testThumbnailBecomesDirtyAfterReloadScrollAndResize() {
        createSurface("", HEADER_HEIGHT);
        layoutSurface();
        assertTrue(mCoordinator.shouldCaptureThumbnail());
        captureThumbnail();
        assertFalse(mCoordinator.shouldCaptureThumbnail());

        mCoordinator.reload();
        assertTrue(mCoordinator.shouldCaptureThumbnail());
        layoutSurface();
        captureThumbnail();
        assertFalse(mCoordinator.shouldCaptureThumbnail());

        mRecycler.scrollBy(0, 40);
        assertTrue(mCoordinator.shouldCaptureThumbnail());
        captureThumbnail();
        assertFalse(mCoordinator.shouldCaptureThumbnail());

        mRoot.layout(0, 0, WIDTH + 1, HEIGHT);
        assertTrue(mCoordinator.shouldCaptureThumbnail());
    }

    @Test
    public void testTouchTrackingFocusAndInterception() {
        createSurface("", HEADER_HEIGHT);
        layoutSurface();
        MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10, 10, 0);
        try {
            assertFalse(mRoot.onInterceptTouchEvent(event));
            assertTrue(mRoot.isFocused());
            verify(mDelegate).sendMotionEventForInputTracking(event);
            verify(mDelegate).onInterceptTouchEvent(event);

            clearInvocations(mDelegate);
            mCoordinator.getTouchEnabledDelegate().setTouchEnabled(false);
            assertTrue(mRoot.onInterceptTouchEvent(event));
            verify(mDelegate).sendMotionEventForInputTracking(event);
            verify(mDelegate, never()).onInterceptTouchEvent(event);

            mCoordinator.getTouchEnabledDelegate().setTouchEnabled(true);
            when(mDelegate.onInterceptTouchEvent(event)).thenReturn(true);
            assertTrue(mRoot.onInterceptTouchEvent(event));

            clearInvocations(mDelegate);
            mCoordinator.destroy();
            assertTrue(mRoot.onInterceptTouchEvent(event));
            verifyNoInteractions(mDelegate);
        } finally {
            event.recycle();
        }
    }

    @Test
    public void testFocusedChildReceivesItsKeysBeforePageScrolling() {
        createSurface("", HEADER_HEIGHT);
        View child = new View(mActivity);
        child.setFocusable(true);
        child.setFocusableInTouchMode(true);
        AtomicInteger keyCount = new AtomicInteger();
        child.setOnKeyListener(
                (view, keyCode, event) -> {
                    if (keyCode != KeyEvent.KEYCODE_SPACE) return false;
                    keyCount.incrementAndGet();
                    return true;
                });
        mHeader.addView(child, new FrameLayout.LayoutParams(100, 100));
        layoutSurface();
        assertTrue(child.requestFocus());
        mRoot.focusableViewAvailable(new View(mActivity));
        assertTrue(child.isFocused());

        assertTrue(
                mRoot.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)));

        assertEquals(1, keyCount.get());
        assertEquals(0, mCoordinator.getVerticalScrollOffset());
    }

    @Test
    public void testUnconsumedPageKeyScrollsButModifiedShortcutDoesNot() {
        createSurface("", HEADER_HEIGHT);
        layoutSurface();
        assertTrue(mRoot.requestFocus());
        assertTrue(mRecycler.canScrollVertically(1));

        assertFalse(
                mRoot.dispatchKeyEvent(
                        new KeyEvent(
                                0,
                                0,
                                KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_SPACE,
                                0,
                                KeyEvent.META_CTRL_ON)));
        assertEquals(0, mCoordinator.getVerticalScrollOffset());
        assertTrue(
                mRoot.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        assertTrue(mCoordinator.getVerticalScrollOffset() > 0);
    }

    @Test
    public void testInputConnectionDisablesFullscreenWithoutChangingAction() {
        createSurface("", HEADER_HEIGHT);
        EditorInfo attrs = new EditorInfo();
        attrs.imeOptions = EditorInfo.IME_ACTION_SEARCH;

        mRoot.onCreateInputConnection(attrs);

        assertTrue((attrs.imeOptions & EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0);
        assertEquals(EditorInfo.IME_ACTION_SEARCH, attrs.imeOptions & EditorInfo.IME_MASK_ACTION);
    }
}
