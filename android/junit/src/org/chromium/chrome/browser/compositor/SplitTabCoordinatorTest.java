// Copyright 2026 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.compositor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.compositor.layouts.components.LayoutTab;
import org.chromium.chrome.browser.compositor.scene_layer.StaticTabSceneLayer;
import org.chromium.chrome.browser.compositor.scene_layer.StaticTabSceneLayerJni;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabHidingType;
import org.chromium.chrome.browser.tab.TabObserver;
import org.chromium.chrome.browser.tab.TabSelectionType;
import org.chromium.chrome.browser.tabmodel.SplitInfo;
import org.chromium.content_public.browser.Visibility;
import org.chromium.ui.modelutil.PropertyModel;

import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the actual pane hierarchy and activation/owner bridge, without native renderers. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class SplitTabCoordinatorTest {
    private ActivityController<Activity> mActivity;
    private SplitTabTestFixture mFixture;

    @Before
    public void setUp() {
        mActivity = Robolectric.buildActivity(Activity.class).setup().visible();
        mFixture = new SplitTabTestFixture(mActivity.get());
    }

    @After
    public void tearDown() {
        mFixture.destroy();
        mActivity.pause().stop().destroy();
    }

    @Test
    public void realViewsHaveDisjointPanesAndResizeOnRotation() {
        Rect first = mFixture.coordinator.getContentBounds(mFixture.firstContents);
        Rect second = mFixture.coordinator.getContentBounds(mFixture.secondContents);
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(first.right < second.left);
        assertEquals(first.height(), second.height());
        assertEquals(first.width(), mFixture.firstView.getWidth());
        assertEquals(second.width(), mFixture.secondView.getWidth());
        assertTrue(mFixture.firstView.getParent() != mFixture.secondView.getParent());
        mFixture.info = new SplitInfo(mFixture.info.getToken(), 11, 12, SplitInfo.STACKED, 0.4);
        mFixture.refresh();
        mFixture.layout(600, 800);
        first = mFixture.coordinator.getContentBounds(mFixture.firstContents);
        second = mFixture.coordinator.getContentBounds(mFixture.secondContents);
        assertEquals(600, first.width());
        assertEquals(600, second.width());
        assertTrue(first.bottom < second.top);
        assertTrue(first.height() < second.height());
    }

    @Test
    public void programmaticFocusCannotActivateInactivePane() {
        assertTrue(mFixture.firstView.requestFocus());
        assertFalse(mFixture.secondView.requestFocus());
        assertSame(mFixture.first, mFixture.selector.getCurrentTab());
    }

    @Test
    public void pointerSelectsBeforeTargetReceivesInput() {
        assertTrue(mFixture.firstView.requestFocus());
        Rect second = mFixture.coordinator.getContentBounds(mFixture.secondContents);
        AtomicInteger events = new AtomicInteger();
        mFixture.secondView.setOnTouchListener(
                (view, event) -> {
                    assertSame(mFixture.second, mFixture.selector.getCurrentTab());
                    events.incrementAndGet();
                    return true;
                });
        long now = SystemClock.uptimeMillis();
        MotionEvent down =
                MotionEvent.obtain(
                        now, now, MotionEvent.ACTION_DOWN, second.centerX(), second.centerY(), 0);
        MotionEvent up =
                MotionEvent.obtain(
                        now, now + 1, MotionEvent.ACTION_UP, second.centerX(), second.centerY(), 0);
        try {
            assertTrue(mFixture.coordinator.dispatchTouchEvent(down));
            assertTrue(mFixture.coordinator.dispatchTouchEvent(up));
            assertEquals(2, events.get());
            assertFalse(mFixture.firstView.hasFocus());
            assertTrue(mFixture.secondView.hasFocus());
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    @Test
    public void accessibilityActionActivatesBeforeForwardingAndRejectsDestroyedOwner() {
        doAnswer(
                        call -> {
                            assertSame(mFixture.second, mFixture.selector.getCurrentTab());
                            return true;
                        })
                .when(mFixture.secondView.provider)
                .performAction(anyInt(), anyInt(), isNull());
        AccessibilityNodeProvider provider = mFixture.secondView.getAccessibilityNodeProvider();
        assertTrue(provider.performAction(7, AccessibilityNodeInfo.ACTION_CLICK, null));
        when(mFixture.secondContents.isDestroyed()).thenReturn(true);
        assertFalse(provider.performAction(7, AccessibilityNodeInfo.ACTION_CLICK, null));
    }

    @Test
    public void clearingAccessibilityFocusDoesNotActivateOtherPane() {
        when(mFixture.secondView.provider.performAction(anyInt(), anyInt(), isNull()))
                .thenReturn(true);
        assertTrue(
                mFixture.secondView
                        .getAccessibilityNodeProvider()
                        .performAction(
                                7, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null));
        assertSame(mFixture.first, mFixture.selector.getCurrentTab());
    }

    @Test
    public void dialogBlocksOnlyOwnerAndPeerSelectionKeepsHost() {
        ViewGroup host = mFixture.coordinator.getDialogHost(mFixture.firstContents);
        assertNotNull(host);
        int oldImportance = mFixture.firstView.getImportantForAccessibility();
        mFixture.coordinator.setDialogShowing(mFixture.firstContents, true);
        assertEquals(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                mFixture.firstView.getImportantForAccessibility());
        assertFalse(
                mFixture.firstView
                        .getAccessibilityNodeProvider()
                        .performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null));
        mFixture.select(mFixture.second);
        assertSame(host, mFixture.coordinator.getDialogHost(mFixture.firstContents));
        assertTrue(mFixture.secondView.requestFocus());
        assertFalse(mFixture.firstView.requestFocus());
        mFixture.coordinator.setDialogShowing(mFixture.firstContents, false);
        assertEquals(oldImportance, mFixture.firstView.getImportantForAccessibility());
    }

    @Test
    public void ownerInvalidationPrecedesRemovalAndRunsOnce() {
        AtomicInteger calls = new AtomicInteger();
        mFixture.coordinator.addPaneUnavailableObserver(
                mFixture.firstContents,
                () -> {
                    assertNotNull(mFixture.firstView.getParent());
                    calls.incrementAndGet();
                });
        mFixture.info = null;
        mFixture.refresh();
        mFixture.coordinator.setShown(false);
        assertEquals(1, calls.get());
        assertNull(SplitTabCoordinator.fromWebContents(mFixture.firstContents));
        assertNull(mFixture.firstView.getParent());
    }

    @Test
    public void activityVisibilityIncludesPeerWithoutActivatingIt() {
        ArgumentCaptor<TabObserver> observer = ArgumentCaptor.forClass(TabObserver.class);
        verify(mFixture.first, times(2)).addObserver(observer.capture());
        mFixture.nativeTabs.prepareForRealHide(mFixture.second);
        observer.getValue().onHidden(mFixture.first, TabHidingType.ACTIVITY_HIDDEN);
        // TabImpl.hide is final: verify its real visibility effect, not an intercepted call.
        verify(mFixture.secondContents).updateWebContentsVisibility(Visibility.HIDDEN);
        assertTrue(mFixture.second.isHidden());
        assertFalse(mFixture.coordinator.isShown());
        assertNull(mFixture.coordinator.getDialogHost(mFixture.firstContents));
        observer.getValue().onShown(mFixture.first, TabSelectionType.FROM_USER);
        verify(mFixture.second).show(TabSelectionType.FROM_USER);
        assertTrue(mFixture.coordinator.isShown());
        assertSame(mFixture.first, mFixture.selector.getCurrentTab());
    }

    @Test
    public void emptyPaneClearsNativeLayersAndSuppressesSingleTabBindingUntilExit() {
        StaticTabSceneLayer.Natives nativeScene = mock(StaticTabSceneLayer.Natives.class);
        StaticTabSceneLayerJni.setInstanceForTesting(nativeScene);
        when(nativeScene.init(any())).thenReturn(1L);
        StaticTabSceneLayer scene = new StaticTabSceneLayer();
        scene.updateSplit(mFixture.coordinator, true, true, 0);
        mFixture.layout(0, 0);
        scene.updateSplit(mFixture.coordinator, true, true, 0);
        verify(nativeScene).hideSplitTabLayers(1L);
        PropertyModel normal = new PropertyModel(LayoutTab.ALL_KEYS);
        scene.update(normal);
        verify(nativeScene, never()).updateTabLayer(1L, Tab.INVALID_TAB_ID, false, 0, 0f, 0f, null);
        scene.clearSplit(normal);
        verify(nativeScene).updateTabLayer(1L, Tab.INVALID_TAB_ID, false, 0, 0f, 0f, null);
    }

    @Test
    public void emptyContentViewportInvalidatesDialogOwnerAndRejectsActions() {
        AtomicInteger dismissed = new AtomicInteger();
        mFixture.coordinator.addPaneUnavailableObserver(
                mFixture.firstContents, dismissed::incrementAndGet);
        mFixture.layout(0, 0);
        assertEquals(1, dismissed.get());
        assertNull(mFixture.coordinator.getDialogHost(mFixture.firstContents));
        assertFalse(
                mFixture.firstView
                        .getAccessibilityNodeProvider()
                        .performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null));
    }
}
