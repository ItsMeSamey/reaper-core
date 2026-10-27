// Copyright 2026 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.components.permissions;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.view.View;

import androidx.activity.ComponentDialog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import org.chromium.base.Callback;
import org.chromium.base.FeatureOverrides;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.build.annotations.Nullable;
import org.chromium.components.content_settings.ContentSettingsType;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.ui.modaldialog.ModalDialogManager;
import org.chromium.ui.modaldialog.ModalDialogManager.ModalDialogType;
import org.chromium.ui.modaldialog.ModalDialogProperties;
import org.chromium.ui.modelutil.PropertyModel;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Actual permission/controller/modal queues; only native prompt metadata and presentation mocked.
 */
@RunWith(BaseRobolectricTestRunner.class)
public class PermissionDialogOwnerTest {
    private ActivityController<Activity> mActivityController;
    private Activity mActivity;
    private WindowAndroid mWindow;
    private ModalDialogManager mManager;
    private PermissionDialogController mController;

    // Pane presentation/selection is separately covered by SplitTabModalDialogTest. Keep the real
    // permission coordinator, mediator, property model, queue, cancellation and embedded path here.
    private static final class TestPresenter extends ModalDialogManager.Presenter {
        @Override
        protected void addDialogView(
                PropertyModel model,
                @Nullable Callback<ComponentDialog> created,
                @Nullable Callback<View> shown) {}

        @Override
        protected void removeDialogView(@Nullable PropertyModel model) {}
    }

    @Before
    public void setUp() {
        FeatureOverrides.disable(PermissionsAndroidFeatureList.APPROXIMATE_GEOLOCATION_PERMISSION);
        FeatureOverrides.disable(
                PermissionsAndroidFeatureList.ANDROID_CANCEL_PERMISSION_PROMPT_ON_TOUCH_OUTSIDE);
        mActivityController = Robolectric.buildActivity(Activity.class).setup();
        mActivity = mActivityController.get();
        mActivity.setTheme(R.style.Theme_BrowserUI_DayNight);
        mManager = new ModalDialogManager(new TestPresenter(), ModalDialogType.TAB);
        mWindow = mock(WindowAndroid.class);
        when(mWindow.getContext()).thenReturn(new WeakReference<>(mActivity));
        when(mWindow.getActivity()).thenReturn(new WeakReference<>(mActivity));
        when(mWindow.getModalDialogManager()).thenReturn(mManager);
        // A fresh real controller avoids modifying the process-wide singleton used by other tests.
        mController = ReflectionHelpers.callConstructor(PermissionDialogController.class);
    }

    @After
    public void tearDown() {
        mManager.destroy();
        mActivityController.pause().stop().destroy();
    }

    private PermissionDialogDelegate delegate(Object owner, boolean embedded) {
        PermissionDialogDelegate delegate = mock(PermissionDialogDelegate.class);
        when(delegate.getWindow()).thenReturn(mWindow);
        when(delegate.getContentSettingsTypes())
                .thenReturn(new int[] {ContentSettingsType.NOTIFICATIONS});
        when(delegate.getDrawableId()).thenReturn(R.drawable.ic_folder_blue_24dp);
        when(delegate.getMessageText()).thenReturn("Controlled owner requests notifications");
        when(delegate.getPositiveButtonText()).thenReturn("Allow");
        when(delegate.getNegativeButtonText()).thenReturn("Block");
        when(delegate.getPositiveEphemeralButtonText()).thenReturn("");
        when(delegate.getBoldedRanges()).thenReturn(List.of());
        when(delegate.getEmbeddedPromptVariant())
                .thenReturn(
                        embedded ? EmbeddedPromptVariant.ASK : EmbeddedPromptVariant.UNINITIALIZED);
        when(delegate.isEmbeddedPromptVariant()).thenReturn(embedded);
        // Exercise the production JNI owner setter/getter; the owner is intentionally opaque to
        // //components/permissions. Chrome tests separately use real WebContents identities.
        doCallRealMethod().when(delegate).setDialogOwner(any());
        doCallRealMethod().when(delegate).getDialogOwner();
        delegate.setDialogOwner(owner);
        return delegate;
    }

    private void enqueue(PermissionDialogDelegate delegate) {
        ReflectionHelpers.callInstanceMethod(
                mController,
                "queueDialog",
                ClassParameter.from(PermissionDialogDelegate.class, delegate));
    }

    private void assertCurrentOwner(Object owner) {
        assertSame(
                owner,
                mManager.getCurrentDialogForTest().get(ModalDialogProperties.TAB_MODAL_OWNER));
    }

    @Test
    public void queuedPermissionRetainsOriginalOwnerWhenPreviousRequestEnds() {
        Object ownerA = new Object();
        Object ownerB = new Object();
        PermissionDialogDelegate first = delegate(ownerA, false);
        PermissionDialogDelegate second = delegate(ownerB, false);
        enqueue(first);
        enqueue(second);
        assertCurrentOwner(ownerA);
        verify(second, never()).destroy();

        mController.dismissFromNative(first);
        assertCurrentOwner(ownerB);
        mController.dismissFromNative(second);
        assertNull(mManager.getCurrentDialogForTest());
    }

    @Test
    public void cancelledQueuedOwnerIsNeverSubstitutedForNextRequest() {
        Object ownerA = new Object();
        Object ownerB = new Object();
        Object ownerC = new Object();
        PermissionDialogDelegate first = delegate(ownerA, false);
        PermissionDialogDelegate cancelled = delegate(ownerB, false);
        PermissionDialogDelegate third = delegate(ownerC, false);
        enqueue(first);
        enqueue(cancelled);
        enqueue(third);

        mController.dismissFromNative(cancelled);
        verify(cancelled).destroy();
        assertCurrentOwner(ownerA);
        mController.dismissFromNative(first);
        assertCurrentOwner(ownerC);
        mController.dismissFromNative(third);
        assertNull(mManager.getCurrentDialogForTest());
    }

    @Test
    public void embeddedPermissionUsesSameQueuedOwnerProperty() {
        Object ownerA = new Object();
        Object ownerB = new Object();
        PermissionDialogDelegate first = delegate(ownerA, false);
        PermissionDialogDelegate embedded = delegate(ownerB, true);
        enqueue(first);
        enqueue(embedded);
        assertCurrentOwner(ownerA);

        mController.dismissFromNative(first);
        assertCurrentOwner(ownerB);
        // Use the actual embedded mediator's close path; native sinks are mocked.
        mController.dismissFromNative(embedded);
        assertNull(mManager.getCurrentDialogForTest());
    }

    @Test
    public void managerSuspensionDoesNotDiscardPermissionOwner() {
        Object ownerA = new Object();
        Object ownerB = new Object();
        int suspension = mManager.suspendType(ModalDialogType.TAB);
        PermissionDialogDelegate first = delegate(ownerA, false);
        PermissionDialogDelegate second = delegate(ownerB, false);
        enqueue(first);
        enqueue(second);
        assertNull(mManager.getCurrentDialogForTest());

        mManager.resumeType(ModalDialogType.TAB, suspension);
        assertCurrentOwner(ownerA);
        mController.dismissFromNative(first);
        assertCurrentOwner(ownerB);
        mController.dismissFromNative(second);
        assertNull(mManager.getCurrentDialogForTest());
    }
}
