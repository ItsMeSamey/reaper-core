// Copyright 2026 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.modaldialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
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
import org.chromium.ui.modaldialog.ModalDialogManager.ModalDialogPriority;
import org.chromium.ui.modaldialog.ModalDialogManager.ModalDialogType;
import org.chromium.ui.modaldialog.ModalDialogProperties;
import org.chromium.ui.modaldialog.ModalDialogProperties.Controller;
import org.chromium.ui.modelutil.PropertyKey;
import org.chromium.ui.modelutil.PropertyModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Real dialog manager, lifetime handler, presenter, and pane hierarchy with native sinks mocked.
 */
@RunWith(BaseRobolectricTestRunner.class)
@Config(qualifiers = "sw320dp")
public class SplitTabModalDialogTest {
    private ActivityController<TestActivity> mActivityController;
    private SplitTabTestFixture mFixture;
    private ModalDialogManager mManager;
    private TabModalLifetimeHandler mLifetime;
    private final TabObscuringHandler mObscuring = new TabObscuringHandler();
    private final IdentityHashMap<Tab, List<TabObserver>> mTabObservers = new IdentityHashMap<>();
    private final List<TabModelObserver> mModelObservers = new ArrayList<>();
    private final IdentityHashMap<PropertyModel, Controller> mControllers = new IdentityHashMap<>();
    private View mMenuButton;
    private boolean mLifetimeDestroyed;

    // APP presentation is outside this test; the real manager still executes priority/suspension.
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
        installNativeDialogSinks(mFixture.first, mFixture.firstContents, mFixture.firstView);
        installNativeDialogSinks(mFixture.second, mFixture.secondContents, mFixture.secondView);
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
        mMenuButton = new View(activity);
        when(toolbar.getMenuButtonView()).thenReturn(mMenuButton);
        OneshotSupplierImpl<ToolbarManager> toolbarSupplier = new OneshotSupplierImpl<>();
        toolbarSupplier.set(toolbar);
        BackPressManager backPress = mock(BackPressManager.class);
        when(backPress.has(anyInt())).thenReturn(true);
        ComposedBrowserControlsVisibilityDelegate appVisibilityDelegate =
                new ComposedBrowserControlsVisibilityDelegate();

        mManager = new ModalDialogManager(new QueueBlockerPresenter(), ModalDialogType.APP);
        mLifetime =
                new TabModalLifetimeHandler(
                        activity,
                        mock(ActivityLifecycleDispatcher.class),
                        mManager,
                        () -> appVisibilityDelegate,
                        () -> mObscuring,
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

    private void installNativeDialogSinks(Tab tab, WebContents contents, View view) {
        when(tab.isUserInteractable()).thenReturn(true);
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
        int oldId = mFixture.selector.getCurrentTab().getId();
        mFixture.select(tab);
        for (TabModelObserver observer : new ArrayList<>(mModelObservers)) {
            observer.didSelectTab(tab, TabSelectionType.FROM_USER, oldId);
        }
    }

    private void navigate(Tab tab) {
        for (TabObserver observer : new ArrayList<>(mTabObservers.get(tab))) {
            observer.onPageLoadStarted(tab, tab.getUrl());
        }
    }

    private PropertyModel dialog(@Nullable WebContents owner) {
        return dialog(owner, ModalDialogProperties.ALL_KEYS);
    }

    private PropertyModel dialog(@Nullable WebContents owner, PropertyKey[] keys) {
        Controller controller = mock(Controller.class);
        PropertyModel.Builder builder =
                new PropertyModel.Builder(keys)
                        .with(ModalDialogProperties.CONTROLLER, controller)
                        .with(ModalDialogProperties.TITLE, "Request")
                        .with(ModalDialogProperties.POSITIVE_BUTTON_TEXT, "Continue");
        if (Arrays.asList(keys).contains(ModalDialogProperties.TAB_MODAL_OWNER)) {
            builder.with(ModalDialogProperties.TAB_MODAL_OWNER, owner);
        }
        PropertyModel model = builder.build();
        mControllers.put(model, controller);
        return model;
    }

    private ChromeTabModalPresenter show(PropertyModel model) {
        mManager.showDialog(model, ModalDialogType.TAB);
        return (ChromeTabModalPresenter) mManager.getCurrentPresenterForTest();
    }

    private void assertDismissedOnce(PropertyModel model, int cause) {
        verify(mControllers.get(model), times(1)).onDismiss(model, cause);
        verify(mControllers.get(model), times(1)).onDismiss(eq(model), anyInt());
    }

    @Test
    public void inactivePaneKeepsOwnershipAndOnlyActiveOwnerHandlesBack() {
        PropertyModel model = dialog(mFixture.secondContents);
        int originalImportance = mFixture.secondView.getImportantForAccessibility();
        ChromeTabModalPresenter presenter = show(model);

        assertSame(mFixture.secondContents, model.get(ModalDialogProperties.TAB_MODAL_OWNER));
        assertSame(
                mFixture.coordinator.getDialogHost(mFixture.secondContents),
                presenter.getDialogContainerForTest().getParent());
        assertSame(mFixture.first, mFixture.selector.getCurrentTab());
        assertFalse(ChromeTabModalPresenter.isDialogShowing(mFixture.first));
        assertTrue(ChromeTabModalPresenter.isDialogShowing(mFixture.second));
        assertFalse(mObscuring.isTabContentObscured());
        assertTrue(mMenuButton.isEnabled());
        assertEquals(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                mFixture.secondView.getImportantForAccessibility());
        assertFalse(mLifetime.getHandleBackPressChangedSupplier().get());

        select(mFixture.second);
        assertTrue(mLifetime.getHandleBackPressChangedSupplier().get());
        select(mFixture.first);
        assertSame(model, mManager.getCurrentDialogForTest());
        assertFalse(mLifetime.getHandleBackPressChangedSupplier().get());
        mManager.dismissDialog(model, DialogDismissalCause.POSITIVE_BUTTON_CLICKED);
        assertEquals(originalImportance, mFixture.secondView.getImportantForAccessibility());
        assertDismissedOnce(model, DialogDismissalCause.POSITIVE_BUTTON_CLICKED);
    }

    @Test
    public void queuedPeerNavigationCancelsOnlyItsOriginalRequest() {
        PropertyModel first = dialog(mFixture.firstContents);
        PropertyModel second = dialog(mFixture.secondContents);
        show(first);
        show(second);
        select(mFixture.second);
        navigate(mFixture.second);

        assertSame(first, mManager.getCurrentDialogForTest());
        assertDismissedOnce(second, DialogDismissalCause.NAVIGATE);
        verify(mControllers.get(first), never()).onDismiss(eq(first), anyInt());
        mManager.dismissDialog(first, DialogDismissalCause.POSITIVE_BUTTON_CLICKED);
        assertNull(mManager.getCurrentDialogForTest());
    }

    @Test
    public void shownOwnerNavigationPreservesQueuedPeerRequest() {
        PropertyModel first = dialog(mFixture.firstContents);
        PropertyModel second = dialog(mFixture.secondContents);
        show(first);
        show(second);
        navigate(mFixture.first);

        assertDismissedOnce(first, DialogDismissalCause.NAVIGATE);
        assertSame(second, mManager.getCurrentDialogForTest());
        verify(mControllers.get(second), never()).onDismiss(eq(second), anyInt());
        ChromeTabModalPresenter presenter =
                (ChromeTabModalPresenter) mManager.getCurrentPresenterForTest();
        assertSame(
                mFixture.coordinator.getDialogHost(mFixture.secondContents),
                presenter.getDialogContainerForTest().getParent());
    }

    @Test
    public void veryHighPriorityQueuedRequestStillObservesItsOwner() {
        PropertyModel first = dialog(mFixture.firstContents);
        PropertyModel second = dialog(mFixture.secondContents);
        mManager.showDialog(first, ModalDialogType.TAB, ModalDialogPriority.VERY_HIGH);
        mManager.showDialog(second, ModalDialogType.TAB, ModalDialogPriority.VERY_HIGH);
        navigate(mFixture.second);

        assertSame(first, mManager.getCurrentDialogForTest());
        assertDismissedOnce(second, DialogDismissalCause.NAVIGATE);
    }

    @Test
    public void suspendedTabRequestIsCancelledWithoutDismissingAppDialog() {
        PropertyModel tab = dialog(mFixture.secondContents);
        PropertyModel app = dialog(null);
        show(tab);
        mManager.showDialog(app, ModalDialogType.APP);
        navigate(mFixture.second);

        assertSame(app, mManager.getCurrentDialogForTest());
        assertNull(app.get(ModalDialogProperties.TAB_MODAL_OWNER));
        assertDismissedOnce(tab, DialogDismissalCause.NAVIGATE);
        mManager.dismissDialog(app, DialogDismissalCause.POSITIVE_BUTTON_CLICKED);
        assertNull(mManager.getCurrentDialogForTest());
    }

    @Test
    public void appSuspensionPreservesQueuedRequestWhenPeerBecomesActive() {
        PropertyModel tab = dialog(mFixture.firstContents);
        PropertyModel app = dialog(null);
        show(tab);
        mManager.showDialog(app, ModalDialogType.APP);
        select(mFixture.second);

        assertSame(app, mManager.getCurrentDialogForTest());
        verify(mControllers.get(tab), never()).onDismiss(eq(tab), anyInt());
        mManager.dismissDialog(app, DialogDismissalCause.POSITIVE_BUTTON_CLICKED);
        assertSame(tab, mManager.getCurrentDialogForTest());
        ChromeTabModalPresenter presenter =
                (ChromeTabModalPresenter) mManager.getCurrentPresenterForTest();
        assertSame(
                mFixture.coordinator.getDialogHost(mFixture.firstContents),
                presenter.getDialogContainerForTest().getParent());
        assertFalse(mLifetime.getHandleBackPressChangedSupplier().get());
    }

    @Test
    public void queuedAppDialogNeverCapturesOrObservesSelectedTab() {
        PropertyModel currentApp = dialog(null);
        PropertyModel queuedApp = dialog(null);
        mManager.showDialog(currentApp, ModalDialogType.APP);
        mManager.showDialog(queuedApp, ModalDialogType.APP);
        navigate(mFixture.first);

        assertNull(queuedApp.get(ModalDialogProperties.TAB_MODAL_OWNER));
        verify(mControllers.get(queuedApp), never()).onDismiss(eq(queuedApp), anyInt());
        mManager.dismissDialog(currentApp, DialogDismissalCause.POSITIVE_BUTTON_CLICKED);
        assertSame(queuedApp, mManager.getCurrentDialogForTest());
    }

    @Test
    public void queuedContentsReplacementCancelsOnlyOldOwner() {
        PropertyModel first = dialog(mFixture.firstContents);
        PropertyModel second = dialog(mFixture.secondContents);
        show(first);
        show(second);
        when(mFixture.second.getWebContents()).thenReturn(mock(WebContents.class));
        for (TabObserver observer : new ArrayList<>(mTabObservers.get(mFixture.second))) {
            observer.onContentChanged(mFixture.second);
        }

        assertSame(first, mManager.getCurrentDialogForTest());
        assertDismissedOnce(second, DialogDismissalCause.WEB_CONTENTS_DESTROYED);
        // Restore the renderer stub before coordinator teardown; its lifecycle is fixture-owned.
        when(mFixture.second.getWebContents()).thenReturn(mFixture.secondContents);
    }

    @Test
    public void queuedOwnerDestroyedCancelsExactlyOnce() {
        PropertyModel first = dialog(mFixture.firstContents);
        PropertyModel second = dialog(mFixture.secondContents);
        show(first);
        show(second);
        when(mFixture.second.isDestroyed()).thenReturn(true);
        for (TabObserver observer : new ArrayList<>(mTabObservers.get(mFixture.second))) {
            observer.onDestroyed(mFixture.second);
        }

        assertSame(first, mManager.getCurrentDialogForTest());
        assertDismissedOnce(second, DialogDismissalCause.TAB_DESTROYED);
    }

    @Test
    public void legacyQueuedModelCapturesOwnerBeforePeerSelection() {
        PropertyModel current = dialog(mFixture.firstContents);
        PropertyModel legacy = dialog(null);
        show(current);
        show(legacy);
        assertSame(mFixture.firstContents, legacy.get(ModalDialogProperties.TAB_MODAL_OWNER));

        select(mFixture.second);
        mManager.dismissDialog(current, DialogDismissalCause.POSITIVE_BUTTON_CLICKED);
        ChromeTabModalPresenter presenter =
                (ChromeTabModalPresenter) mManager.getCurrentPresenterForTest();
        assertSame(legacy, mManager.getCurrentDialogForTest());
        assertSame(
                mFixture.coordinator.getDialogHost(mFixture.firstContents),
                presenter.getDialogContainerForTest().getParent());
    }

    @Test
    public void customQueuedModelWithoutOwnerKeyKeepsSwitchToDismissBehavior() {
        PropertyModel current = dialog(mFixture.firstContents);
        PropertyKey[] customKeys =
                Arrays.stream(ModalDialogProperties.ALL_KEYS)
                        .filter(key -> key != ModalDialogProperties.TAB_MODAL_OWNER)
                        .toArray(PropertyKey[]::new);
        PropertyModel custom = dialog(null, customKeys);
        show(current);
        show(custom);
        select(mFixture.second);

        assertSame(current, mManager.getCurrentDialogForTest());
        assertDismissedOnce(custom, DialogDismissalCause.TAB_SWITCHED);
    }

    @Test
    public void activityDestroyDrainsCurrentAndQueuedModelsExactlyOnce() {
        PropertyModel first = dialog(mFixture.firstContents);
        PropertyModel second = dialog(mFixture.secondContents);
        show(first);
        show(second);
        mLifetime.onDestroy();
        mLifetimeDestroyed = true;
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNull(mManager.getCurrentDialogForTest());
        assertDismissedOnce(first, DialogDismissalCause.ACTIVITY_DESTROYED);
        assertDismissedOnce(second, DialogDismissalCause.ACTIVITY_DESTROYED);
        assertTrue(mTabObservers.get(mFixture.first).isEmpty());
        assertTrue(mTabObservers.get(mFixture.second).isEmpty());
    }

    @Test
    public void activityDestroyRejectsNewRequestFromDismissalCallback() {
        PropertyModel first = dialog(mFixture.firstContents);
        PropertyModel queued = dialog(mFixture.secondContents);
        PropertyModel reentrant = dialog(mFixture.secondContents);
        doAnswer(
                        call -> {
                            mManager.showDialog(reentrant, ModalDialogType.TAB);
                            return null;
                        })
                .when(mControllers.get(first))
                .onDismiss(eq(first), anyInt());
        show(first);
        show(queued);
        mLifetime.onDestroy();
        mLifetimeDestroyed = true;
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNull(mManager.getCurrentDialogForTest());
        assertDismissedOnce(first, DialogDismissalCause.ACTIVITY_DESTROYED);
        assertDismissedOnce(queued, DialogDismissalCause.ACTIVITY_DESTROYED);
        assertDismissedOnce(reentrant, DialogDismissalCause.ACTIVITY_DESTROYED);
        assertFalse(ChromeTabModalPresenter.isDialogShowing(mFixture.second));
    }

    @Test
    public void ownerOutsideCurrentModelIsDeniedBeforeViewCreation() {
        WebContents foreignContents = mock(WebContents.class);
        Tab foreignTab = mFixture.nativeTabs.createTab(foreignContents);
        when(foreignTab.getId()).thenReturn(91);
        when(foreignTab.getWebContents()).thenReturn(foreignContents);
        PropertyModel model = dialog(foreignContents);
        ChromeTabModalPresenter presenter = show(model);
        assertNull(presenter.getDialogViewForTest());
        // An omnibox update may already be queued ahead of the deferred denial.
        mLifetime.onOmniboxFocusChanged(true);
        mLifetime.onOmniboxFocusChanged(false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNull(mManager.getCurrentDialogForTest());
        assertDismissedOnce(model, DialogDismissalCause.NOT_ATTACHED_TO_WINDOW);
    }

    @Test
    public void ordinaryDialogRestoresDefaultParentAndInitialControlInsetsAfterSplit() {
        PropertyModel split = dialog(mFixture.firstContents);
        ChromeTabModalPresenter presenter = show(split);
        mManager.dismissDialog(split, DialogDismissalCause.POSITIVE_BUTTON_CLICKED);
        mFixture.info = null;
        mFixture.refresh();

        PropertyModel ordinary = dialog(null);
        show(ordinary);
        View container = presenter.getDialogContainerForTest();
        assertSame(mFixture.holder, container.getParent());
        ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) container.getLayoutParams();
        assertEquals(56, params.topMargin);
        assertEquals(24, params.bottomMargin);
        assertTrue(mObscuring.isTabContentObscured());
        assertFalse(mMenuButton.isEnabled());
        View renderedDialog = presenter.getDialogViewForTest();
        mManager.dismissDialog(ordinary, DialogDismissalCause.POSITIVE_BUTTON_CLICKED);
        // The departing view remains attached during its exit animation, but it must
        // immediately stop appearing as an accessible pane.
        assertEquals(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                renderedDialog.getImportantForAccessibility());
        assertFalse(mObscuring.isTabContentObscured());
        assertTrue(mMenuButton.isEnabled());
        assertDismissedOnce(ordinary, DialogDismissalCause.POSITIVE_BUTTON_CLICKED);
    }

    @After
    public void tearDown() {
        if (!mLifetimeDestroyed && mLifetime != null) mLifetime.onDestroy();
        if (mManager != null) mManager.destroy();
        if (mFixture != null) mFixture.destroy();
        if (mActivityController != null) mActivityController.pause().stop().destroy();
    }
}
