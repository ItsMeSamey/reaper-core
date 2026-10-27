// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.selection;

import static org.chromium.ui.listmenu.ListMenuItemProperties.CLICK_LISTENER;
import static org.chromium.ui.listmenu.ListMenuItemProperties.TITLE;
import static org.chromium.ui.listmenu.ListMenuSubmenuItemProperties.SUBMENU_PROVIDER;

import android.app.Activity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.VisibleForTesting;

import org.jni_zero.CalledByNative;
import org.jni_zero.CalledByNativeForTesting;
import org.jni_zero.JNINamespace;
import org.jni_zero.JniType;
import org.jni_zero.NativeMethods;

import org.chromium.base.ContextUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabObserver;
import org.chromium.content_public.browser.RenderFrameHost;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.ui.listmenu.MenuModelBridge;
import org.chromium.ui.modelutil.MVCListAdapter.ListItem;
import org.chromium.ui.modelutil.MVCListAdapter.ModelList;
import org.chromium.ui.modelutil.PropertyModel;

/** A short-lived, document-bound extension menu for the Android text-selection action. */
@NullMarked
@JNINamespace("extensions")
final class ReaperSelectionMenu implements TabObserver {
    private final Tab mTab;
    private long mNativePtr;
    private @Nullable ModelList mItems;
    private @Nullable View mAnchor;
    private @Nullable Runnable mPostedShow;
    private @Nullable ChromeSelectionDropdownMenuDelegate mPopup;
    private boolean mClosed;

    /** Snapshot while the selection is active; never reconstruct context from focus at click time. */
    static @Nullable ReaperSelectionMenu create(
            Tab tab, String selectedText, boolean isSelectionPassword, boolean isSelectionReadOnly) {
        ThreadUtils.assertOnUiThread();
        if (tab.isDestroyed() || isSelectionPassword || selectedText.isEmpty()) return null;
        WebContents webContents = tab.getWebContents();
        if (webContents == null || webContents.isDestroyed()) return null;
        RenderFrameHost frame = webContents.getFocusedFrame();
        if (frame == null) return null;
        ReaperSelectionMenu menu = new ReaperSelectionMenu(tab);
        menu.mNativePtr = ReaperSelectionMenuJni.get().init(
                webContents, frame, selectedText, !isSelectionReadOnly, menu);
        if (menu.mNativePtr == 0) {
            menu.destroy();
            return null;
        }
        MenuModelBridge bridge = ReaperSelectionMenuJni.get().getMenuModel(menu.mNativePtr);
        if (bridge == null || bridge.getListItems().isEmpty()) {
            menu.destroy();
            return null;
        }
        menu.mItems = bridge.populateModelList();
        return menu;
    }

    private ReaperSelectionMenu(Tab tab) {
        mTab = tab;
        tab.addObserver(this);
    }

    boolean show(@Nullable View anchor) {
        ThreadUtils.assertOnUiThread();
        if (mClosed || mNativePtr == 0 || anchor == null || !anchor.isAttachedToWindow()) return false;
        if (mPostedShow != null || mPopup != null) return true;
        mAnchor = anchor;
        // ActionMode.finish() / the old dropdown's dismissal runs after the action callback.
        // Posting prevents its focus cleanup from immediately dismissing the new popup.
        mPostedShow = () -> {
            mPostedShow = null;
            if (mClosed || mNativePtr == 0 || mItems == null || mTab.isDestroyed()
                    || !anchor.isAttachedToWindow()) {
                destroy();
                return;
            }
            Activity activity = ContextUtils.activityFromContext(anchor.getContext());
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                destroy();
                return;
            }
            ChromeSelectionDropdownMenuDelegate popup = createPopup();
            mPopup = popup;
            try {
                popup.show(anchor.getContext(), anchor, mItems,
                        // The protected popup hook dispatches the native/hierarchical callback.
                        model -> {}, this::destroy, Math.max(0, anchor.getWidth() / 2),
                        Math.max(0, anchor.getHeight() / 2));
            } catch (WindowManager.BadTokenException exception) {
                destroy();
            }
        };
        if (!anchor.post(mPostedShow)) {
            destroy();
            return false;
        }
        return true;
    }

    @VisibleForTesting
    ChromeSelectionDropdownMenuDelegate createPopup() {
        return new ChromeSelectionDropdownMenuDelegate() {
            private boolean mDismissing;

            @Override
            protected void onMenuItemClick(PropertyModel model, View view) {
                if (mClosed || !model.containsKey(CLICK_LISTENER)) return;
                View.OnClickListener listener = model.get(CLICK_LISTENER);
                if (listener != null) listener.onClick(view);
            }

            @Override
            public void dismiss() {
                if (mDismissing) return;
                mDismissing = true;
                super.dismiss();
                // Covers Back, outside touch and window dismissal as well as terminal commands.
                ReaperSelectionMenu.this.destroy();
            }
        };
    }

    void destroy() {
        ThreadUtils.assertOnUiThread();
        if (mClosed) return;
        mClosed = true;
        mTab.removeObserver(this);
        if (mAnchor != null && mPostedShow != null) mAnchor.removeCallbacks(mPostedShow);
        mPostedShow = null;
        mAnchor = null;
        mItems = null;
        ChromeSelectionDropdownMenuDelegate popup = mPopup;
        mPopup = null;
        long pointer = mNativePtr;
        mNativePtr = 0;
        if (popup != null) popup.dismiss();
        if (pointer != 0) ReaperSelectionMenuJni.get().destroy(pointer);
    }

    @CalledByNative
    private void onNativeDestroyed() {
        // Clear the handle before native navigation/frame teardown deletes the owner.
        mNativePtr = 0;
        destroy();
    }

    // Browser tests use the real owner and retain real copied rows across an
    // extension reload. These hooks do not replace native registry/dispatch.
    @CalledByNativeForTesting
    private static @Nullable ReaperSelectionMenu createForTesting(
            Tab tab, @JniType("std::u16string") String selectedText) {
        return create(tab, selectedText, false, true);
    }

    @CalledByNativeForTesting
    private boolean isClosedForTesting() {
        return mClosed;
    }

    @CalledByNativeForTesting
    private @Nullable PropertyModel getItemForTesting(@JniType("std::u16string") String title) {
        return mItems == null ? null : findItemForTesting(mItems, title);
    }

    private static @Nullable PropertyModel findItemForTesting(
            Iterable<ListItem> items, String title) {
        for (ListItem item : items) {
            PropertyModel model = item.model;
            if (model.containsKey(TITLE) && title.equals(model.get(TITLE))
                    && model.containsKey(CLICK_LISTENER) && model.get(CLICK_LISTENER) != null) {
                return model;
            }
            if (model.containsKey(SUBMENU_PROVIDER)) {
                PropertyModel child = findItemForTesting(model.get(SUBMENU_PROVIDER).get(), title);
                if (child != null) return child;
            }
        }
        return null;
    }

    @CalledByNativeForTesting
    private static boolean clickItemForTesting(PropertyModel item) {
        ThreadUtils.assertOnUiThread();
        View.OnClickListener callback = item.get(CLICK_LISTENER);
        if (callback == null) return false;
        callback.onClick(new View(ContextUtils.getApplicationContext()));
        return true;
    }

    @CalledByNativeForTesting
    private void destroyForTesting() {
        destroy();
    }

    @Override
    public void onHidden(Tab tab, int type) {
        destroy();
    }

    @Override
    public void onDestroyed(Tab tab) {
        destroy();
    }

    @Override
    public void onContentChanged(Tab tab) {
        destroy();
    }

    @Override
    public void onActivityAttachmentChanged(Tab tab, @Nullable WindowAndroid window) {
        destroy();
    }

    @NativeMethods
    interface Natives {
        long init(@JniType("content::WebContents*") WebContents webContents,
                @JniType("content::RenderFrameHost*") RenderFrameHost frame,
                @JniType("std::u16string") String selectedText, boolean editable,
                ReaperSelectionMenu caller);
        @Nullable MenuModelBridge getMenuModel(long nativeReaperSelectionMenu);
        void destroy(long nativeReaperSelectionMenu);
    }
}
