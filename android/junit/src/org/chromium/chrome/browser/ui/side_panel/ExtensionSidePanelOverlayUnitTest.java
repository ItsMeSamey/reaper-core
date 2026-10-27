// Copyright 2026 The Reaper Authors
// Use of this source code is governed by a BSD-style license.

package org.chromium.chrome.browser.ui.side_panel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.LooperMode;

import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.ui.modaldialog.DialogDismissalCause;
import org.chromium.ui.modaldialog.ModalDialogManager;
import org.chromium.ui.modaldialog.ModalDialogProperties;
import org.chromium.ui.modelutil.PropertyModel;

@RunWith(BaseRobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ExtensionSidePanelOverlayUnitTest {
    @Test
    public void deferredPresentationDoesNotRequireAnAttachedView() {
        var manager = mock(ModalDialogManager.class);
        var view = new View(RuntimeEnvironment.getApplication());
        var close = mock(Runnable.class);
        var overlay = new ExtensionSidePanelOverlay(manager, view, close);
        assertFalse(view.isAttachedToWindow());

        overlay.showDeferred();
        // Deferral must also let native cache the entry before a dialog can close it.
        verify(manager, never()).showDialog(any(), anyInt(), anyInt());
        shadowOf(Looper.getMainLooper()).idle();

        verify(manager, times(1)).showDialog(any(), eq(ModalDialogManager.ModalDialogType.APP),
                eq(ModalDialogManager.ModalDialogPriority.LOW));
        assertFalse(view.isAttachedToWindow());
        verify(close, never()).run();
        overlay.dismiss();
    }

    @Test
    public void dismissedBeforeDeferredPresentationDoesNotReopen() {
        var manager = mock(ModalDialogManager.class);
        var close = mock(Runnable.class);
        var overlay = new ExtensionSidePanelOverlay(manager, mock(View.class), close);

        overlay.showDeferred();
        overlay.dismiss();
        shadowOf(Looper.getMainLooper()).idle();

        verify(manager, never()).showDialog(any(), anyInt(), anyInt());
        verify(manager, never()).addObserver(overlay);
        verify(close, never()).run();
    }

    @Test
    public void backClosesNativeOnceAndUsesFullScreenContent() {
        var manager = mock(ModalDialogManager.class);
        var view = mock(View.class);
        var close = mock(Runnable.class);
        var overlay = new ExtensionSidePanelOverlay(manager, view, close);
        overlay.show();
        var captured = ArgumentCaptor.forClass(PropertyModel.class);
        verify(manager).showDialog(captured.capture(), eq(ModalDialogManager.ModalDialogType.APP),
                eq(ModalDialogManager.ModalDialogPriority.LOW));
        PropertyModel model = captured.getValue();
        assertSame(view, model.get(ModalDialogProperties.CUSTOM_VIEW));
        assertEquals(ModalDialogProperties.DialogStyles.FULLSCREEN_DIALOG,
                model.get(ModalDialogProperties.DIALOG_STYLES));
        overlay.onDismiss(model, DialogDismissalCause.NAVIGATE_BACK_OR_TOUCH_OUTSIDE);
        overlay.onDismiss(model, DialogDismissalCause.ACTIVITY_DESTROYED);
        overlay.dismiss();
        verify(close, times(1)).run();
        verify(manager, never()).dismissDialog(any(), eq(DialogDismissalCause.DISMISSED_BY_NATIVE));
    }

    @Test
    public void permissionDialogYieldsPresentationWithoutDestroyingNativeFrame() {
        var manager = mock(ModalDialogManager.class);
        var close = mock(Runnable.class);
        var overlay = new ExtensionSidePanelOverlay(manager, mock(View.class), close);
        overlay.show();
        var captured = ArgumentCaptor.forClass(PropertyModel.class);
        verify(manager).showDialog(captured.capture(), eq(ModalDialogManager.ModalDialogType.APP),
                eq(ModalDialogManager.ModalDialogPriority.LOW));
        PropertyModel model = captured.getValue();
        overlay.onDialogAdded(model);
        when(manager.isShowing()).thenReturn(true);
        doAnswer(invocation -> {
            overlay.onDismiss(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(manager).dismissDialog(any(), eq(DialogDismissalCause.DIALOG_INTERACTION_DEFERRED));
        var permission = new PropertyModel.Builder(ModalDialogProperties.ALL_KEYS).build();
        overlay.onDialogSuppressed(permission);
        verify(manager).dismissDialog(model, DialogDismissalCause.DIALOG_INTERACTION_DEFERRED);
        verify(manager, times(2)).showDialog(model, ModalDialogManager.ModalDialogType.APP,
                ModalDialogManager.ModalDialogPriority.LOW);
        verify(close, never()).run();
        // When the prompt finishes, the manager presents the same panel/frame again.
        overlay.onDialogAdded(model);
        overlay.onDismiss(model, DialogDismissalCause.NAVIGATE_BACK_OR_TOUCH_OUTSIDE);
        verify(close, times(1)).run();
    }

    @Test
    public void nativeCloseDoesNotReenterAndTeardownIsIdempotent() {
        var manager = mock(ModalDialogManager.class);
        var close = mock(Runnable.class);
        var overlay = new ExtensionSidePanelOverlay(manager, mock(View.class), close);
        doAnswer(invocation -> {
            overlay.onDismiss(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(manager).dismissDialog(any(), eq(DialogDismissalCause.DISMISSED_BY_NATIVE));
        overlay.show();
        overlay.dismiss();
        overlay.dismiss();
        verify(manager, times(1)).dismissDialog(any(), eq(DialogDismissalCause.DISMISSED_BY_NATIVE));
        verify(close, never()).run();
    }
}
