// Copyright 2026 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.base.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.WindowManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDisplayManager;

/** Explicit test-display routing must not silently fall back to another display. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = Build.VERSION_CODES.Q)
public class BaseActivityTestRuleTest {
    private int mTestDisplayId;
    private Context mContext;
    private DisplayManager mDisplayManager;
    private PackageManager mPackageManager;
    private Display mDisplay;
    private Intent mIntent;

    @Before
    public void setUp() {
        mContext = mock(Context.class);
        mDisplayManager = RuntimeEnvironment.getApplication().getSystemService(DisplayManager.class);
        mTestDisplayId = ShadowDisplayManager.addDisplay("w480dp-h800dp");
        mPackageManager = mock(PackageManager.class);
        mDisplay = mDisplayManager.getDisplay(mTestDisplayId);
        mIntent = new Intent();
        when(mContext.getSystemService(Context.DISPLAY_SERVICE)).thenReturn(mDisplayManager);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(
                        PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS))
                .thenReturn(true);
    }

    @Test
    public void testAbsentArgumentKeepsOrdinaryLaunch() {
        assertNull(BaseActivityTestRule.getLaunchDisplayId(new Bundle()));
        BaseActivityTestRule.startActivityOnDisplay(mContext, mIntent, null);
        verify(mContext).startActivity(mIntent);
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void testDecimalDisplayIdAndIntegerBoundary() {
        Bundle args = new Bundle();
        args.putString(BaseActivityTestRule.LAUNCH_DISPLAY_ID, "0");
        assertEquals(Integer.valueOf(0), BaseActivityTestRule.getLaunchDisplayId(args));
        args.putString(BaseActivityTestRule.LAUNCH_DISPLAY_ID, "2147483647");
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), BaseActivityTestRule.getLaunchDisplayId(args));
    }

    @Test
    public void testMalformedDisplayArgumentFails() {
        for (String value : new String[] {"", "-1", "+1", " 7", "7 ", "1.0", "2147483648", "٧"}) {
            Bundle args = new Bundle();
            args.putString(BaseActivityTestRule.LAUNCH_DISPLAY_ID, value);
            assertThrows(IllegalArgumentException.class, () -> BaseActivityTestRule.getLaunchDisplayId(args));
        }
        Bundle args = new Bundle();
        args.putInt(BaseActivityTestRule.LAUNCH_DISPLAY_ID, mTestDisplayId);
        assertThrows(IllegalArgumentException.class, () -> BaseActivityTestRule.getLaunchDisplayId(args));
    }

    @Test
    public void testAccessibleDisplayUsesExplicitOptions() {
        BaseActivityTestRule.startActivityOnDisplay(mContext, mIntent, mTestDisplayId);
        ArgumentCaptor<Bundle> options = ArgumentCaptor.forClass(Bundle.class);
        verify(mContext).startActivity(eq(mIntent), options.capture());
        assertEquals(mTestDisplayId, ActivityOptions.fromBundle(options.getValue()).getLaunchDisplayId());
        verify(mContext, never()).startActivity(mIntent);
    }

    @Test
    public void testDisplayDisappearingBeforeLaunchDoesNotFallback() {
        ShadowDisplayManager.removeDisplay(mTestDisplayId);
        assertThrows(IllegalStateException.class,
                () -> BaseActivityTestRule.startActivityOnDisplay(mContext, mIntent, mTestDisplayId));
        verify(mContext, never()).startActivity(any(Intent.class), any(Bundle.class));
        verify(mContext, never()).startActivity(any(Intent.class));
    }

    @Test
    public void testMissingSecondaryDisplayFeatureDoesNotLaunch() {
        when(mPackageManager.hasSystemFeature(
                        PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS))
                .thenReturn(false);
        assertThrows(IllegalStateException.class,
                () -> BaseActivityTestRule.startActivityOnDisplay(mContext, mIntent, mTestDisplayId));
        verify(mContext, never()).startActivity(any(Intent.class), any(Bundle.class));
        verify(mContext, never()).startActivity(any(Intent.class));
    }

    @Test
    public void testExplicitDefaultDisplayDoesNotRequireSecondaryFeature() {
        when(mPackageManager.hasSystemFeature(
                        PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS))
                .thenReturn(false);
        BaseActivityTestRule.startActivityOnDisplay(mContext, mIntent, Display.DEFAULT_DISPLAY);
        ArgumentCaptor<Bundle> options = ArgumentCaptor.forClass(Bundle.class);
        verify(mContext).startActivity(eq(mIntent), options.capture());
        assertEquals(Display.DEFAULT_DISPLAY,
                ActivityOptions.fromBundle(options.getValue()).getLaunchDisplayId());
        verify(mContext, never()).startActivity(mIntent);
    }

    @Test
    public void testPlatformAccessDenialPropagatesWithoutFallback() {
        SecurityException denied = new SecurityException("display access denied");
        doThrow(denied).when(mContext).startActivity(eq(mIntent), any(Bundle.class));
        assertSame(denied, assertThrows(SecurityException.class,
                () -> BaseActivityTestRule.startActivityOnDisplay(mContext, mIntent, mTestDisplayId)));
        verify(mContext, never()).startActivity(mIntent);
    }

    @Test
    public void testResultingActivityMustRemainOnRequestedDisplay() {
        Activity activity = mock(Activity.class);
        WindowManager manager = mock(WindowManager.class);
        when(activity.getWindowManager()).thenReturn(manager);
        when(manager.getDefaultDisplay()).thenReturn(mDisplay);
        BaseActivityTestRule.assertActivityOnDisplay(activity, mTestDisplayId);
        when(manager.getDefaultDisplay()).thenReturn(mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY));
        assertThrows(IllegalStateException.class,
                () -> BaseActivityTestRule.assertActivityOnDisplay(activity, mTestDisplayId));
        when(manager.getDefaultDisplay()).thenReturn(mDisplay);
        ShadowDisplayManager.removeDisplay(mTestDisplayId);
        assertThrows(IllegalStateException.class,
                () -> BaseActivityTestRule.assertActivityOnDisplay(activity, mTestDisplayId));
        verify(activity, never()).finish();
    }
}
