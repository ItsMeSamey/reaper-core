// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.
package org.chromium.chrome.browser.selection;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content_public.browser.RenderFrameHost;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.listmenu.MenuModelBridge;
import org.chromium.ui.modelutil.MVCListAdapter.ModelList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(BaseRobolectricTestRunner.class)
public class ReaperSelectionMenuTest {
    private final Tab mTab = mock(Tab.class);
    private final WebContents mWebContents = mock(WebContents.class);
    private final RenderFrameHost mFrame = mock(RenderFrameHost.class);
    private final ReaperSelectionMenu.Natives mNative = mock(ReaperSelectionMenu.Natives.class);
    private final MenuModelBridge mModelBridge = mock(MenuModelBridge.class);

    @Before
    public void setUp() {
        ReaperSelectionMenuJni.setInstanceForTesting(mNative);
        when(mTab.getWebContents()).thenReturn(mWebContents);
        when(mWebContents.getFocusedFrame()).thenReturn(mFrame);
        when(mNative.init(eq(mWebContents), eq(mFrame), anyString(), anyBoolean(), any()))
                .thenReturn(7L);
        when(mNative.getMenuModel(7L)).thenReturn(mModelBridge);
        // Only nonemptiness matters here; commands and native item metadata stay in the bridge.
        when(mModelBridge.getListItems()).thenReturn(List.of(new org.chromium.ui.modelutil.MVCListAdapter.ListItem(
                org.chromium.ui.listmenu.ListItemType.MENU_ITEM,
                new org.chromium.ui.modelutil.PropertyModel.Builder(
                        org.chromium.ui.listmenu.ListMenuItemProperties.ALL_KEYS).build())));
        when(mModelBridge.populateModelList()).thenReturn(new ModelList());
    }

    @Test
    public void passwordAndEmptySelectionsNeverReachNative() {
        assertNull(ReaperSelectionMenu.create(mTab, "secret", true, false));
        assertNull(ReaperSelectionMenu.create(mTab, "", false, true));
        verify(mNative, never()).init(any(), any(), anyString(), anyBoolean(), any());
    }

    @Test
    public void focusedFrameAndEditableTextAreCapturedOnce() {
        ReaperSelectionMenu menu = ReaperSelectionMenu.create(mTab, "original selection", false, false);
        assertNotNull(menu);
        verify(mNative).init(eq(mWebContents), eq(mFrame), eq("original selection"), eq(true), eq(menu));
        verify(mWebContents, times(1)).getFocusedFrame();
        menu.destroy();
    }

    @Test
    public void missingFrameAndEmptyExtensionListDoNotAddEntry() {
        when(mWebContents.getFocusedFrame()).thenReturn(null);
        assertNull(ReaperSelectionMenu.create(mTab, "text", false, true));
        when(mWebContents.getFocusedFrame()).thenReturn(mFrame);
        when(mModelBridge.getListItems()).thenReturn(List.of());
        assertNull(ReaperSelectionMenu.create(mTab, "text", false, true));
        verify(mNative).destroy(7L);
    }

    @Test
    public void tabHideAndDestroyReleaseNativeExactlyOnce() {
        ReaperSelectionMenu menu = ReaperSelectionMenu.create(mTab, "text", false, true);
        assertNotNull(menu);
        menu.onHidden(mTab, 0);
        menu.onDestroyed(mTab);
        menu.destroy();
        verify(mNative, times(1)).destroy(7L);
        verify(mTab, times(1)).removeObserver(menu);
    }

    @Test
    public void nativeDocumentInvalidationCancelsQueuedPopupWithoutDoubleDelete() throws Exception {
        ReaperSelectionMenu menu = ReaperSelectionMenu.create(mTab, "text", false, true);
        assertNotNull(menu);
        View anchor = mock(View.class);
        AtomicReference<Runnable> posted = new AtomicReference<>();
        when(anchor.isAttachedToWindow()).thenReturn(true);
        when(anchor.post(any())).thenAnswer(invocation -> {
            posted.set(invocation.getArgument(0));
            return true;
        });
        assertTrue(menu.show(anchor));
        Runnable runnable = posted.get();
        assertNotNull(runnable);
        Method invalidation = ReaperSelectionMenu.class.getDeclaredMethod("onNativeDestroyed");
        invalidation.setAccessible(true);
        invalidation.invoke(menu);
        // Even an already dequeued callback must not reopen a menu for the replaced document.
        runnable.run();
        verify(anchor).removeCallbacks(runnable);
        verify(mNative, never()).destroy(7L);
        assertFalse(menu.show(anchor));
    }

    @Test
    public void callbackUsesActualClickedViewOnceAndDismissReleasesOwner() {
        ReaperSelectionMenu menu = ReaperSelectionMenu.create(mTab, "text", false, true);
        assertNotNull(menu);
        ChromeSelectionDropdownMenuDelegate popup = menu.createPopup();
        View clicked = mock(View.class);
        View.OnClickListener callback = mock(View.OnClickListener.class);
        org.chromium.ui.modelutil.PropertyModel model =
                new org.chromium.ui.modelutil.PropertyModel.Builder(
                        org.chromium.ui.listmenu.ListMenuItemProperties.ALL_KEYS)
                        .with(org.chromium.ui.listmenu.ListMenuItemProperties.CLICK_LISTENER, callback)
                        .build();
        popup.onMenuItemClick(model, clicked);
        verify(callback, times(1)).onClick(clicked);
        // Back/outside dismissal follows this same override; no terminal click is required.
        popup.dismiss();
        popup.dismiss();
        popup.onMenuItemClick(model, clicked);
        verify(callback, times(1)).onClick(clicked);
        verify(mNative, times(1)).destroy(7L);
        verify(mTab, times(1)).removeObserver(menu);
    }

    @Test
    public void nativeInvalidationClosesBeforeReentrantPopupCallbacks() throws Exception {
        ReaperSelectionMenu menu = ReaperSelectionMenu.create(mTab, "text", false, true);
        assertNotNull(menu);
        ChromeSelectionDropdownMenuDelegate popup = spy(menu.createPopup());
        View.OnClickListener callback = mock(View.OnClickListener.class);
        View clicked = mock(View.class);
        org.chromium.ui.modelutil.PropertyModel row =
                new org.chromium.ui.modelutil.PropertyModel
                        .Builder(org.chromium.ui.listmenu.ListMenuItemProperties.ALL_KEYS)
                        .with(org.chromium.ui.listmenu.ListMenuItemProperties.CLICK_LISTENER,
                                callback)
                        .build();
        Field popupField = ReaperSelectionMenu.class.getDeclaredField("mPopup");
        popupField.setAccessible(true);
        popupField.set(menu, popup);
        doAnswer(invocation -> {
            // Dismissal can reenter both a retained row and the Tab observer.
            // The real Java owner must already be closed and its native handle cleared.
            popup.onMenuItemClick(row, clicked);
            menu.onHidden(mTab, 0);
            menu.destroy();
            return invocation.callRealMethod();
        })
                .when(popup)
                .dismiss();
        Method invalidation = ReaperSelectionMenu.class.getDeclaredMethod("onNativeDestroyed");
        invalidation.setAccessible(true);
        invalidation.invoke(menu);
        invalidation.invoke(menu);
        verify(popup, times(1)).dismiss();
        verify(callback, never()).onClick(any());
        verify(mTab, times(1)).removeObserver(menu);
        verify(mNative, never()).destroy(7L);
        assertFalse(menu.show(mock(View.class)));
    }

    @Test
    public void detachedViewNeverShowsPopup() {
        ReaperSelectionMenu menu = ReaperSelectionMenu.create(mTab, "text", false, true);
        assertNotNull(menu);
        View anchor = mock(View.class);
        assertFalse(menu.show(anchor));
        verify(anchor, never()).post(any());
        menu.destroy();
    }
}
