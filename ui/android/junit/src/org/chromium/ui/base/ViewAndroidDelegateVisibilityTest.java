// Copyright 2026 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.ui.base;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;

import org.chromium.base.test.BaseRobolectricTestRunner;

/** Tests launch eligibility against the actual Android container hierarchy. */
@RunWith(BaseRobolectricTestRunner.class)
public class ViewAndroidDelegateVisibilityTest {
    private ActivityController<Activity> mActivityController;
    private FrameLayout mHolder;
    private FrameLayout mContainer;
    private ViewAndroidDelegate mDelegate;

    @Before
    public void setUp() {
        mActivityController = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = mActivityController.get();
        mHolder = new FrameLayout(activity);
        mContainer = new FrameLayout(activity);
        mContainer.setFocusable(false);
        mHolder.addView(
                mContainer,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        activity.setContentView(mHolder);
        mActivityController.visible().windowFocusChanged(true);
        mDelegate = ViewAndroidDelegate.createBasicDelegate(mContainer);
        assertTrue(mContainer.isAttachedToWindow());
        assertTrue(mContainer.isShown());
        assertTrue(mContainer.hasWindowFocus());
        assertTrue(mDelegate.isAttachedAndVisibleWithWindowFocus());
    }

    @After
    public void tearDown() {
        mActivityController.close();
    }

    @Test
    public void attachedVisibleNonFocusableContainerIsEligible() {
        assertFalse(mContainer.isFocusable());
        assertTrue(mDelegate.isAttachedAndVisibleWithWindowFocus());
    }

    @Test
    public void detachedContainerIsIneligible() {
        mHolder.removeView(mContainer);
        assertFalse(mContainer.isAttachedToWindow());
        assertFalse(mDelegate.isAttachedAndVisibleWithWindowFocus());
    }

    @Test
    public void hiddenAncestorIsIneligible() {
        mHolder.setVisibility(View.INVISIBLE);
        assertTrue(mContainer.isAttachedToWindow());
        assertFalse(mContainer.isShown());
        assertFalse(mDelegate.isAttachedAndVisibleWithWindowFocus());
        mHolder.setVisibility(View.VISIBLE);
        assertTrue(mDelegate.isAttachedAndVisibleWithWindowFocus());
    }

    @Test
    public void windowFocusIsRequiredAndMayReturn() {
        mActivityController.windowFocusChanged(false);
        assertTrue(mContainer.isShown());
        assertFalse(mContainer.hasWindowFocus());
        assertFalse(mDelegate.isAttachedAndVisibleWithWindowFocus());
        mActivityController.windowFocusChanged(true);
        assertTrue(mDelegate.isAttachedAndVisibleWithWindowFocus());
    }

    @Test
    public void missingContainerIsIneligible() {
        assertFalse(
                ViewAndroidDelegate.createBasicDelegate(null)
                        .isAttachedAndVisibleWithWindowFocus());
    }
}
