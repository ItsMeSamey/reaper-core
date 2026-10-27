// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.

package org.chromium.chrome.browser.qrreader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.view.SurfaceHolder;

import com.google.android.gms.vision.barcode.Barcode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;

import org.chromium.base.test.BaseRobolectricTestRunner;

import java.io.IOException;

/** Real manager/preview lifecycle with mocked camera resources, without camera or Sync access. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class QRCodeCameraManagerTest {
    private QRCodeCameraManager.Callback mCallback;
    private QRCodeCameraManager.HostProvider mHost;
    private ActivityController<Activity> mActivityController;
    private QRCodeCameraManager mManager;
    private CameraSourcePreview mPreview;
    private CameraSource mSource;

    @Before
    public void setUp() {
        mActivityController = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = mActivityController.get();
        mHost = mock(QRCodeCameraManager.HostProvider.class);
        when(mHost.getHostActivity()).thenReturn(activity);
        when(mHost.isHostValid()).thenReturn(true);
        mCallback = mock(QRCodeCameraManager.Callback.class);
        mManager = new QRCodeCameraManager(mCallback, mHost);
        mPreview = new CameraSourcePreview(RuntimeEnvironment.getApplication(), null);
        mManager.init(mPreview);
        mSource = mock(CameraSource.class);
        // Model the result of detector/source creation without loading GMS or opening a camera.
        ReflectionHelpers.setField(mManager, "mCameraSource", mSource);
    }

    @After
    public void tearDown() {
        mManager.release();
        mActivityController.pause().stop().destroy();
    }

    private static void makeSurfaceAvailable(CameraSourcePreview preview) {
        // Model the surface-availability state; the normal layout/start path remains production code.
        ReflectionHelpers.setField(preview, "mSurfaceAvailable", true);
    }

    @Test
    public void releaseBeforePreviewStartRetiresSourceAndListenerState() {
        DisplayManager.DisplayListener listener =
                ReflectionHelpers.getField(mManager, "mDisplayListener");

        mManager.release();
        mManager.release();
        mManager.stopCameraSource();

        verify(mSource).release();
        assertNull(ReflectionHelpers.getField(mManager, "mDisplayManager"));
        assertNull(mManager.getCameraSourcePreview());
        assertFalse(mManager.startCameraSource());
        clearInvocations(mHost);
        listener.onDisplayChanged(0);
        mManager.createCameraSource();
        verifyNoInteractions(mHost);
    }

    @Test
    public void releaseAttachedSourceCancelsPendingPreviewStart() throws Exception {
        assertTrue(mManager.startCameraSource());

        mManager.release();
        makeSurfaceAvailable(mPreview);
        mPreview.layout(0, 0, 320, 480);
        mPreview.onWindowFocusChanged(true);
        mPreview.release();

        verify(mSource).release();
        verify(mSource, never()).start(any(SurfaceHolder.class));
    }

    @Test
    public void stopCancelsPendingStartAndExplicitRestartStillWorks() throws Exception {
        assertTrue(mManager.startCameraSource());
        mManager.stopCameraSource();
        makeSurfaceAvailable(mPreview);
        mPreview.layout(0, 0, 320, 480);
        verify(mSource, never()).start(any(SurfaceHolder.class));

        assertTrue(mManager.startCameraSource());

        verify(mSource).start(any(SurfaceHolder.class));
        verify(mSource, never()).release();
    }

    @Test
    public void creatingReplacementRetiresPreviousSourceBeforeHostCheck() throws Exception {
        assertTrue(mManager.startCameraSource());
        when(mHost.getHostActivity()).thenReturn(null);

        mManager.createCameraSource();
        makeSurfaceAvailable(mPreview);
        mPreview.layout(0, 0, 320, 480);
        mManager.release();

        verify(mSource).release();
        verify(mSource, never()).start(any(SurfaceHolder.class));
    }

    @Test
    public void failedStartDetachesSourceBeforeLaterRelease() throws Exception {
        makeSurfaceAvailable(mPreview);
        doThrow(new IOException("Camera unavailable")).when(mSource).start(any(SurfaceHolder.class));

        assertFalse(mManager.startCameraSource());
        mManager.release();
        mPreview.release();

        verify(mSource).release();
        assertFalse(mPreview.mCameraExist);
    }

    @Test
    public void retiredPreviewAndCallbackDoNotAffectFreshManager() throws Exception {
        mManager.release();
        QRCodeCameraManager fresh = new QRCodeCameraManager(mCallback, mHost);
        CameraSourcePreview freshPreview =
                new CameraSourcePreview(RuntimeEnvironment.getApplication(), null);
        CameraSource freshSource = mock(CameraSource.class);
        fresh.init(freshPreview);
        ReflectionHelpers.setField(fresh, "mCameraSource", freshSource);
        makeSurfaceAvailable(freshPreview);
        try {
            mPreview.onWindowFocusChanged(true);
            mManager.onDetectedQrCode(new Barcode());
            verifyNoInteractions(mCallback);
            verify(freshSource, never()).release();

            assertTrue(fresh.startCameraSource());
            verify(freshSource).start(any(SurfaceHolder.class));
        } finally {
            fresh.release();
        }
        verify(mSource).release();
        verify(freshSource).release();
    }
}
