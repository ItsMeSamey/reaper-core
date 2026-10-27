// Copyright 2026 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.ui.extensions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.FrameLayout;

import androidx.activity.ComponentDialog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import org.chromium.base.Callback;
import org.chromium.base.supplier.ObservableSuppliers;
import org.chromium.base.supplier.OneshotSupplierImpl;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.back_press.BackPressManager;
import org.chromium.chrome.browser.browser_controls.BrowserControlsVisibilityManager;
import org.chromium.chrome.browser.compositor.SplitTabTestFixture;
import org.chromium.chrome.browser.fullscreen.FullscreenManager;
import org.chromium.chrome.browser.lifecycle.ActivityLifecycleDispatcher;
import org.chromium.chrome.browser.modaldialog.ChromeTabModalPresenter;
import org.chromium.chrome.browser.modaldialog.TabModalLifetimeHandler;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabObscuringHandler;
import org.chromium.chrome.browser.tab.TabObserver;
import org.chromium.chrome.browser.tab.TabSelectionType;
import org.chromium.chrome.browser.tabmodel.TabModelObserver;
import org.chromium.chrome.browser.toolbar.ToolbarManager;
import org.chromium.components.browser_ui.util.ComposedBrowserControlsVisibilityDelegate;
import org.chromium.content.browser.selection.SelectionPopupControllerImpl;
import org.chromium.content_public.browser.RenderFrameHost;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.TestActivity;
import org.chromium.ui.base.ViewAndroidDelegate;
import org.chromium.ui.modaldialog.DialogDismissalCause;
import org.chromium.ui.modaldialog.ModalDialogManager;
import org.chromium.ui.modaldialog.ModalDialogManager.ModalDialogType;
import org.chromium.ui.modaldialog.ModalDialogProperties;
import org.chromium.ui.modelutil.PropertyModel;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Actual install bridge, queue, lifetime handler and presenter with mocked tab events and JNI
 * sinks.
 */
@RunWith(BaseRobolectricTestRunner.class)
@Config(qualifiers = "sw320dp")
public class ExtensionInstallDialogOwnerTest {
    private static final long NATIVE_VIEW = 100L;
    private static final Bitmap ICON = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);

    private ActivityController<TestActivity> mActivityController;
    private SplitTabTestFixture mFixture;
    private ModalDialogManager mManager;
    private TabModalLifetimeHandler mLifetime;
    private ExtensionInstallDialogBridge.Natives mNative;
    private final IdentityHashMap<Tab, List<TabObserver>> mTabObservers = new IdentityHashMap<>();
    private final List<TabModelObserver> mModelObservers = new ArrayList<>();

    // APP rendering is not exercised. Its real manager priority holds TAB dialogs in the queue.
    private static final class QueueBlockerPresenter extends ModalDialogManager.Presenter {
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
        mActivityController = Robolectric.buildActivity(TestActivity.class).setup();
        Activity activity = mActivityController.get();
        mFixture = new SplitTabTestFixture(activity);
        installNativeSinks(mFixture.first, mFixture.firstContents, mFixture.firstView);
        installNativeSinks(mFixture.second, mFixture.secondContents, mFixture.secondView);
        installTabEvents(mFixture.first);
        installTabEvents(mFixture.second);
        doAnswer(
                        call -> {
                            mModelObservers.add(call.getArgument(0));
                            return null;
                        })
                .when(mFixture.model)
                .addObserver(any());
        doAnswer(
                        call -> {
                            mModelObservers.remove(call.getArgument(0));
                            return null;
                        })
                .when(mFixture.model)
                .removeObserver(any());

        // Same real pane/view fixture and presenter prerequisites as SplitTabModalDialogTest.
        mFixture.holder.setId(R.id.coordinator);
        ViewStub stub = new ViewStub(activity);
        stub.setId(R.id.tab_modal_dialog_container_stub);
        stub.setInflatedId(R.id.tab_modal_dialog_container);
        mFixture.holder.addView(
                stub,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View sibling = new View(activity);
        sibling.setId(R.id.tab_modal_dialog_container_sibling_view);
        mFixture.holder.addView(sibling);
        BrowserControlsVisibilityManager controls = mock(BrowserControlsVisibilityManager.class);
        when(controls.getTopControlsHeight()).thenReturn(56);
        when(controls.getBottomControlsHeight()).thenReturn(24);
        ToolbarManager toolbar = mock(ToolbarManager.class);
        when(toolbar.getMenuButtonView()).thenReturn(new View(activity));
        OneshotSupplierImpl<ToolbarManager> toolbarSupplier = new OneshotSupplierImpl<>();
        toolbarSupplier.set(toolbar);
        BackPressManager backPress = mock(BackPressManager.class);
        when(backPress.has(anyInt())).thenReturn(true);
        ComposedBrowserControlsVisibilityDelegate appVisibilityDelegate =
                new ComposedBrowserControlsVisibilityDelegate();
        TabObscuringHandler obscuring = new TabObscuringHandler();

        mManager = new ModalDialogManager(new QueueBlockerPresenter(), ModalDialogType.APP);
        when(mFixture.window.getModalDialogManager()).thenReturn(mManager);
        mNative = mock(ExtensionInstallDialogBridge.Natives.class);
        ExtensionInstallDialogBridgeJni.setInstanceForTesting(mNative);
        mLifetime =
                new TabModalLifetimeHandler(
                        activity,
                        mock(ActivityLifecycleDispatcher.class),
                        mManager,
                        () -> appVisibilityDelegate,
                        () -> obscuring,
                        toolbarSupplier,
                        () -> {},
                        () -> mFixture.selector,
                        () -> controls,
                        () -> mock(FullscreenManager.class),
                        backPress,
                        ObservableSuppliers.alwaysNull(),
                        ObservableSuppliers.alwaysNull());
        mLifetime.onFinishNativeInitialization();
    }

    private void installNativeSinks(Tab tab, WebContents contents, View view) {
        when(tab.isUserInteractable()).thenReturn(true);
        when(contents.getTopLevelNativeWindow()).thenReturn(mFixture.window);
        when(contents.getMainFrame()).thenReturn(mock(RenderFrameHost.class));
        when(contents.getViewAndroidDelegate())
                .thenReturn(ViewAndroidDelegate.createBasicDelegate((ViewGroup) view));
        when(contents.getOrSetUserData(eq(SelectionPopupControllerImpl.class), any()))
                .thenReturn(mock(SelectionPopupControllerImpl.class));
    }

    private void installTabEvents(Tab tab) {
        List<TabObserver> observers = new ArrayList<>();
        mTabObservers.put(tab, observers);
        doAnswer(
                        call -> {
                            observers.add(call.getArgument(0));
                            return null;
                        })
                .when(tab)
                .addObserver(any());
        doAnswer(
                        call -> {
                            observers.remove(call.getArgument(0));
                            return null;
                        })
                .when(tab)
                .removeObserver(any());
    }

    private void select(Tab tab) {
        int previous = mFixture.selector.getCurrentTab().getId();
        mFixture.select(tab);
        for (TabModelObserver observer : new ArrayList<>(mModelObservers)) {
            observer.didSelectTab(tab, TabSelectionType.FROM_USER, previous);
        }
    }

    private void navigate(Tab tab) {
        for (TabObserver observer : new ArrayList<>(mTabObservers.get(tab))) {
            observer.onPageLoadStarted(tab, tab.getUrl());
        }
    }

    private void destroyTab(Tab tab) {
        when(tab.isDestroyed()).thenReturn(true);
        for (TabObserver observer : new ArrayList<>(mTabObservers.get(tab))) {
            observer.onDestroyed(tab);
        }
    }

    private ExtensionInstallDialogBridge showInstall(@Nullable WebContents owner) {
        ExtensionInstallDialogBridge bridge =
                ExtensionInstallDialogBridge.create(NATIVE_VIEW, mFixture.window, owner);
        assertNotNull(bridge);
        bridge.buildDialog("Add extension?", ICON, "Add extension", "Cancel");
        bridge.showDialog();
        return bridge;
    }

    private ChromeTabModalPresenter presenter() {
        return (ChromeTabModalPresenter) mManager.getPresenterForTest(ModalDialogType.TAB);
    }

    private PropertyModel assertShownFor(Tab tab) {
        PropertyModel model = mManager.getCurrentDialogForTest();
        assertNotNull(model);
        assertSame(tab.getWebContents(), model.get(ModalDialogProperties.TAB_MODAL_OWNER));
        assertNotNull(presenter().getDialogViewForTest());
        assertSame(
                mFixture.coordinator.getDialogHost(tab.getWebContents()),
                presenter().getDialogContainerForTest().getParent());
        assertTrue(ChromeTabModalPresenter.isDialogShowing(tab));
        return model;
    }

    private PropertyModel blockTabDialogs() {
        PropertyModel blocker =
                new PropertyModel.Builder(ModalDialogProperties.ALL_KEYS)
                        .with(
                                ModalDialogProperties.CONTROLLER,
                                mock(ModalDialogProperties.Controller.class))
                        .with(ModalDialogProperties.TITLE, "Other application dialog")
                        .build();
        mManager.showDialog(blocker, ModalDialogType.APP);
        return blocker;
    }

    private PropertyModel assertQueuedFor(WebContents contents) {
        List<PropertyModel> pending = mManager.getPendingDialogsForTest(ModalDialogType.TAB);
        assertNotNull(pending);
        assertEquals(1, pending.size());
        PropertyModel model = pending.get(0);
        assertSame(contents, model.get(ModalDialogProperties.TAB_MODAL_OWNER));
        return model;
    }

    private void assertDismissedOnce() {
        verify(mNative, times(1)).onDialogDismissed(NATIVE_VIEW);
        verify(mNative, times(1)).destroy(NATIVE_VIEW);
        verifyNoMoreInteractions(mNative);
    }

    @Test
    public void delayedInstallKeepsRequestingPaneAfterPeerSelection() {
        WebContents originalRequestor = mFixture.firstContents;
        // Icon/manifest work finishes after B became selected. Invoke the actual factory then.
        select(mFixture.second);
        ExtensionInstallDialogBridge bridge = showInstall(originalRequestor);
        PropertyModel model = assertShownFor(mFixture.first);
        assertSame(mFixture.second, mFixture.selector.getCurrentTab());
        assertFalse(ChromeTabModalPresenter.isDialogShowing(mFixture.second));

        navigate(mFixture.second);
        assertSame(model, mManager.getCurrentDialogForTest());
        verifyNoMoreInteractions(mNative);
        bridge.onClick(model, ModalDialogProperties.ButtonType.POSITIVE);
        assertNull(mManager.getCurrentDialogForTest());
        verify(mNative).onDialogAccepted(NATIVE_VIEW, "");
        verify(mNative).destroy(NATIVE_VIEW);
        verifyNoMoreInteractions(mNative);
    }

    @Test
    public void originalPaneNavigationDismissesOnceWhilePeerIsSelected() {
        select(mFixture.second);
        ExtensionInstallDialogBridge bridge = showInstall(mFixture.firstContents);
        PropertyModel model = assertShownFor(mFixture.first);
        navigate(mFixture.first);
        assertNull(mManager.getCurrentDialogForTest());
        // A stale completion cannot complete or release the native dialog a second time.
        bridge.onDismiss(model, DialogDismissalCause.NAVIGATE);
        mManager.dismissDialog(model, DialogDismissalCause.NAVIGATE);
        assertDismissedOnce();
    }

    @Test
    public void originalPaneClosureDismissesOnceWhilePeerIsSelected() {
        select(mFixture.second);
        ExtensionInstallDialogBridge bridge = showInstall(mFixture.firstContents);
        PropertyModel model = assertShownFor(mFixture.first);
        destroyTab(mFixture.first);
        assertNull(mManager.getCurrentDialogForTest());
        bridge.onDismiss(model, DialogDismissalCause.TAB_DESTROYED);
        assertDismissedOnce();
    }

    @Test
    public void queuedInstallKeepsOwnerThroughPeerSelectionAndNavigation() {
        PropertyModel blocker = blockTabDialogs();
        select(mFixture.second);
        ExtensionInstallDialogBridge bridge = showInstall(mFixture.firstContents);
        PropertyModel queued = assertQueuedFor(mFixture.firstContents);
        assertSame(blocker, mManager.getCurrentDialogForTest());
        select(mFixture.first);
        select(mFixture.second);
        navigate(mFixture.second);
        assertSame(queued, assertQueuedFor(mFixture.firstContents));
        verifyNoMoreInteractions(mNative);

        mManager.dismissDialog(blocker, DialogDismissalCause.POSITIVE_BUTTON_CLICKED);
        assertSame(queued, assertShownFor(mFixture.first));
        bridge.onClick(queued, ModalDialogProperties.ButtonType.NEGATIVE);
        assertNull(mManager.getCurrentDialogForTest());
        verify(mNative).onDialogCanceled(NATIVE_VIEW);
        verify(mNative).destroy(NATIVE_VIEW);
        verifyNoMoreInteractions(mNative);
    }

    @Test
    public void queuedOriginalPaneNavigationCancelsOnlyTheInstall() {
        PropertyModel blocker = blockTabDialogs();
        select(mFixture.second);
        ExtensionInstallDialogBridge bridge = showInstall(mFixture.firstContents);
        PropertyModel queued = assertQueuedFor(mFixture.firstContents);
        navigate(mFixture.first);
        assertSame(blocker, mManager.getCurrentDialogForTest());
        List<PropertyModel> pending = mManager.getPendingDialogsForTest(ModalDialogType.TAB);
        assertTrue(pending == null || pending.isEmpty());
        bridge.onDismiss(queued, DialogDismissalCause.NAVIGATE);
        assertDismissedOnce();
        mManager.dismissDialog(blocker, DialogDismissalCause.POSITIVE_BUTTON_CLICKED);
        assertNull(mManager.getCurrentDialogForTest());
        assertFalse(ChromeTabModalPresenter.isDialogShowing(mFixture.second));
    }

    @Test
    public void delayedInstallForUnrelatedInactiveTabIsDeniedWithoutRebinding() {
        WebContents originalRequestor = mFixture.firstContents;
        mFixture.info = null;
        mFixture.refresh();
        select(mFixture.second);
        showInstall(originalRequestor);
        // The actual presenter posts denial after the manager installs its presenter state.
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(mManager.getCurrentDialogForTest());
        assertNull(presenter().getDialogViewForTest());
        assertFalse(ChromeTabModalPresenter.isDialogShowing(mFixture.first));
        assertFalse(ChromeTabModalPresenter.isDialogShowing(mFixture.second));
        assertDismissedOnce();
    }

    @Test
    public void nativeWindowOnlyPromptRetainsSelectedTabFallback() {
        select(mFixture.second);
        ExtensionInstallDialogBridge bridge = showInstall(null);
        PropertyModel model = assertShownFor(mFixture.second);
        bridge.onClick(model, ModalDialogProperties.ButtonType.NEGATIVE);
        assertNull(mManager.getCurrentDialogForTest());
        verify(mNative).onDialogCanceled(NATIVE_VIEW);
        verify(mNative).destroy(NATIVE_VIEW);
        verifyNoMoreInteractions(mNative);
    }

    @After
    public void tearDown() {
        if (mLifetime != null) mLifetime.onDestroy();
        if (mManager != null) mManager.destroy();
        if (mFixture != null) mFixture.destroy();
        if (mActivityController != null) mActivityController.pause().stop().destroy();
    }
}
