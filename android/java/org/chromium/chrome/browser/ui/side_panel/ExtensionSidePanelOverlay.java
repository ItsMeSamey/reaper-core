// Copyright 2026 The Reaper Authors
// Use of this source code is governed by a BSD-style license.

package org.chromium.chrome.browser.ui.side_panel;

import android.view.View;

import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.ui.modaldialog.DialogDismissalCause;
import org.chromium.ui.modaldialog.ModalDialogManager;
import org.chromium.ui.modaldialog.ModalDialogProperties;
import org.chromium.ui.modelutil.PropertyModel;

/** Full-screen Android host for an extension panel, independent of webpage insets. */
@NullMarked
final class ExtensionSidePanelOverlay implements ModalDialogProperties.Controller,
        ModalDialogManager.ModalDialogManagerObserver {
    private final ModalDialogManager mManager;
    private final PropertyModel mModel;
    private final Runnable mRequestNativeClose;
    private boolean mDismissed;
    private boolean mPresented;
    private boolean mYielding;

    ExtensionSidePanelOverlay(ModalDialogManager manager, View view, Runnable requestNativeClose) {
        mManager = manager;
        mRequestNativeClose = requestNativeClose;
        mModel = new PropertyModel.Builder(ModalDialogProperties.ALL_KEYS)
                .with(ModalDialogProperties.CONTROLLER, this)
                .with(ModalDialogProperties.CUSTOM_VIEW, view)
                .with(ModalDialogProperties.TITLE_SCROLLABLE, false)
                .with(ModalDialogProperties.CANCEL_ON_TOUCH_OUTSIDE, false)
                .with(ModalDialogProperties.DIALOG_STYLES,
                        ModalDialogProperties.DialogStyles.FULLSCREEN_DIALOG)
                .build();
    }

    /** Post on the UI queue: the side-UI container can be permanently detached on phones. */
    void showDeferred() {
        ThreadUtils.postOnUiThread(this::show);
    }

    void show() {
        if (mDismissed) return;
        mManager.addObserver(this);
        showAtLowPriority();
    }

    private void showAtLowPriority() {
        mManager.showDialog(mModel, ModalDialogManager.ModalDialogType.APP,
                ModalDialogManager.ModalDialogPriority.LOW);
    }

    @Override
    public void onDialogAdded(PropertyModel model) {
        mPresented = model == mModel;
    }

    @Override
    public void onDialogDismissed(PropertyModel model) {
        if (model == mModel) mPresented = false;
    }

    @Override
    public void onDialogSuppressed(PropertyModel model) {
        if (model == mModel || !mPresented || mYielding || mDismissed
                || !mManager.isShowing()
                || mManager.isSuspended(ModalDialogManager.ModalDialogType.APP)) return;
        // A permission dialog must not wait behind the extension that requested it.
        // Yield presentation only: preserve the native frame and its pending API call.
        mYielding = true;
        mPresented = false;
        mManager.dismissDialog(mModel, DialogDismissalCause.DIALOG_INTERACTION_DEFERRED);
        mYielding = false;
        if (!mDismissed) showAtLowPriority();
    }

    /** Native close and activity teardown must not recursively request another native close. */
    void dismiss() {
        if (mDismissed) return;
        mDismissed = true;
        mManager.removeObserver(this);
        mManager.dismissDialog(mModel, DialogDismissalCause.DISMISSED_BY_NATIVE);
    }

    @Override
    public void onClick(PropertyModel model, int buttonType) {}

    @Override
    public void onDismiss(PropertyModel model, int dismissalCause) {
        if (mDismissed || mYielding) return;
        mDismissed = true;
        mManager.removeObserver(this);
        mRequestNativeClose.run();
    }
}
