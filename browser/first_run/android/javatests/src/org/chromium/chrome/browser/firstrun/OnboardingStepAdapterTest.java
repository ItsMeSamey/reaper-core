/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.firstrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.brave.browser.firstrun.R;

/** Unit tests for {@link OnboardingStepAdapter}. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class OnboardingStepAdapterTest {
    private static final int BLOCK_INTERRUPTIONS = 0;
    private static final int MAKE_BRAVE_BETTER = 1;
    private static final int TOTAL_STEPS = 2;

    private ViewGroup mParent;
    private TestListener mListener;

    @Before
    public void setUp() {
        ContextThemeWrapper themedContext =
                new ContextThemeWrapper(
                        ApplicationProvider.getApplicationContext(),
                        R.style.Theme_Brave_WelcomeOnboarding);
        mParent = new android.widget.FrameLayout(themedContext);
        mListener = new TestListener();
    }

    @Test
    public void testItemCountAndViewTypes() {
        OnboardingStepAdapter adapter = new OnboardingStepAdapter(mListener);

        assertEquals(TOTAL_STEPS, adapter.getItemCount());
        assertEquals(BLOCK_INTERRUPTIONS, adapter.getItemViewType(0));
        assertEquals(MAKE_BRAVE_BETTER, adapter.getItemViewType(1));
    }

    @Test
    public void testBlockInterruptionsNextPage() {
        OnboardingStepAdapter adapter = new OnboardingStepAdapter(mListener);

        OnboardingStepAdapter.OnboardingBaseViewHolder holder =
                adapter.onCreateViewHolder(mParent, 0);
        Button continueButton = holder.itemView.findViewById(R.id.onboarding_continue);

        assertNotNull(continueButton);
        continueButton.performClick();
        assertEquals(1, mListener.mRequestedPage);

        adapter.onViewRecycled(holder);
    }

    @Test
    public void testMakeBraveBetterManagedHidesCheckboxes() {
        OnboardingStepAdapter adapter = new OnboardingStepAdapter(mListener);
        OnboardingStepAdapter.OnboardingBaseViewHolder holder =
                adapter.onCreateViewHolder(mParent, 1);
        assertNoReportingControls(holder.itemView);
        adapter.onBindViewHolder(holder, 1);
        assertNoReportingControls(holder.itemView);
        Button startBrowsing = holder.itemView.findViewById(R.id.onboarding_start_browsing);
        assertNotNull(startBrowsing);
        startBrowsing.performClick();
        assertEquals(1, mListener.mDismissCount);
        adapter.onViewRecycled(holder);
    }

    @Test
    public void testMakeBraveBetterCrashesManagedHidesCheckbox() {
        OnboardingStepAdapter adapter = new OnboardingStepAdapter(mListener);
        OnboardingStepAdapter.OnboardingBaseViewHolder holder =
                adapter.onCreateViewHolder(mParent, 1);
        assertNoReportingControls(holder.itemView);
        adapter.onBindViewHolder(holder, 1);
        assertNoReportingControls(holder.itemView);
        Button startBrowsing = holder.itemView.findViewById(R.id.onboarding_start_browsing);
        assertNotNull(startBrowsing);
        startBrowsing.performClick();
        assertEquals(1, mListener.mDismissCount);
        adapter.onViewRecycled(holder);
    }

    @Test
    public void testMakeBraveBetterP3aManagedHidesCheckbox() {
        OnboardingStepAdapter adapter = new OnboardingStepAdapter(mListener);
        OnboardingStepAdapter.OnboardingBaseViewHolder holder =
                adapter.onCreateViewHolder(mParent, 1);
        assertNoReportingControls(holder.itemView);
        adapter.onBindViewHolder(holder, 1);
        assertNoReportingControls(holder.itemView);
        Button startBrowsing = holder.itemView.findViewById(R.id.onboarding_start_browsing);
        assertNotNull(startBrowsing);
        startBrowsing.performClick();
        assertEquals(1, mListener.mDismissCount);
        adapter.onViewRecycled(holder);
    }

    @Test
    public void testMakeBraveBetterCrashesCheckedSetsCheckbox() {
        OnboardingStepAdapter adapter = new OnboardingStepAdapter(mListener);
        OnboardingStepAdapter.OnboardingBaseViewHolder holder =
                adapter.onCreateViewHolder(mParent, 1);
        assertNoReportingControls(holder.itemView);
        adapter.onBindViewHolder(holder, 1);
        assertNoReportingControls(holder.itemView);
        Button startBrowsing = holder.itemView.findViewById(R.id.onboarding_start_browsing);
        assertNotNull(startBrowsing);
        startBrowsing.performClick();
        assertEquals(1, mListener.mDismissCount);
        adapter.onViewRecycled(holder);
    }

    @Test
    public void testMakeBraveBetterP3aCheckedSetsCheckbox() {
        OnboardingStepAdapter adapter = new OnboardingStepAdapter(mListener);
        OnboardingStepAdapter.OnboardingBaseViewHolder holder =
                adapter.onCreateViewHolder(mParent, 1);
        assertNoReportingControls(holder.itemView);
        adapter.onBindViewHolder(holder, 1);
        assertNoReportingControls(holder.itemView);
        Button startBrowsing = holder.itemView.findViewById(R.id.onboarding_start_browsing);
        assertNotNull(startBrowsing);
        startBrowsing.performClick();
        assertEquals(1, mListener.mDismissCount);
        adapter.onViewRecycled(holder);
    }

    @Test
    public void testMakeBraveBetterCrashReportingToggleNotifiesListener() {
        OnboardingStepAdapter adapter = new OnboardingStepAdapter(mListener);
        OnboardingStepAdapter.OnboardingBaseViewHolder holder =
                adapter.onCreateViewHolder(mParent, 1);
        assertNoReportingControls(holder.itemView);
        adapter.onBindViewHolder(holder, 1);
        assertNoReportingControls(holder.itemView);
        Button startBrowsing = holder.itemView.findViewById(R.id.onboarding_start_browsing);
        assertNotNull(startBrowsing);
        startBrowsing.performClick();
        assertEquals(1, mListener.mDismissCount);
        adapter.onViewRecycled(holder);
    }

    @Test
    public void testMakeBraveBetterP3aToggleNotifiesListener() {
        OnboardingStepAdapter adapter = new OnboardingStepAdapter(mListener);
        OnboardingStepAdapter.OnboardingBaseViewHolder holder =
                adapter.onCreateViewHolder(mParent, 1);
        assertNoReportingControls(holder.itemView);
        adapter.onBindViewHolder(holder, 1);
        assertNoReportingControls(holder.itemView);
        Button startBrowsing = holder.itemView.findViewById(R.id.onboarding_start_browsing);
        assertNotNull(startBrowsing);
        startBrowsing.performClick();
        assertEquals(1, mListener.mDismissCount);
        adapter.onViewRecycled(holder);
    }

    private static void assertNoReportingControls(View view) {
        assertFalse(view instanceof CompoundButton);
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                assertNoReportingControls(group.getChildAt(index));
            }
        }
    }

    private static final class TestListener
            implements OnboardingStepAdapter.OnboardingNavigationListener {
        int mRequestedPage = -1;
        int mDismissCount;

        @Override
        public void onRequestPageChange(int position) {
            mRequestedPage = position;
        }

        @Override
        public void onDismiss() {
            mDismissCount++;
        }
    }
}
