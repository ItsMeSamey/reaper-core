// Copyright 2026 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.compositor;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.FrameLayout;

import org.chromium.base.Token;
import org.chromium.base.UserDataHost;
import org.chromium.base.supplier.ObservableSuppliers;
import org.chromium.base.supplier.SettableNullableObservableSupplier;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.tab.SplitTabNativeTestHelper;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tabmodel.SplitInfo;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.components.embedder_support.view.ContentView;
import org.chromium.components.url_formatter.UrlFormatter;
import org.chromium.components.url_formatter.UrlFormatterJni;
import org.chromium.content_public.browser.WebContents;
import org.chromium.content_public.browser.WebContentsAccessibility;
import org.chromium.ui.base.EventForwarder;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.url.GURL;

import java.lang.ref.WeakReference;
import java.util.List;

/** Real Android pane/view hierarchy; browser model and native content callbacks are mocked. */
public final class SplitTabTestFixture {
    public final Activity activity;
    public final FrameLayout holder;
    public final TabModelSelector selector = mock(TabModelSelector.class);
    public final TabModel model = mock(TabModel.class);
    public final WebContents firstContents = mock(WebContents.class);
    public final WebContents secondContents = mock(WebContents.class);
    public final SplitTabNativeTestHelper nativeTabs = new SplitTabNativeTestHelper();
    public final Tab first = nativeTabs.createTab(firstContents);
    public final Tab second = nativeTabs.createTab(secondContents);
    public final TestContentView firstView;
    public final TestContentView secondView;
    public final SplitTabCoordinator coordinator;
    public final Profile profile = mock(Profile.class);
    public final WindowAndroid window = mock(WindowAndroid.class);
    public final SettableNullableObservableSupplier<Tab> currentTab =
            ObservableSuppliers.createNullable();
    public SplitInfo info = new SplitInfo(new Token(1, 2), 11, 12, SplitInfo.SIDE_BY_SIDE, 0.5);
    private int mWidth = 800;
    private int mHeight = 600;

    public SplitTabTestFixture(Activity activity) {
        this.activity = activity;
        UrlFormatter.Natives formatter = mock(UrlFormatter.Natives.class);
        UrlFormatterJni.setInstanceForTesting(formatter);
        when(formatter.formatUrlForSecurityDisplay(org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenAnswer(call -> ((GURL) call.getArgument(0)).getSpec());
        firstView = new TestContentView(activity, firstContents);
        secondView = new TestContentView(activity, secondContents);
        setupTab(first, firstContents, firstView, 11, "https://first.test");
        setupTab(second, secondContents, secondView, 12, "https://second.test");
        when(window.getActivity()).thenReturn(new WeakReference<>(activity));
        when(selector.getModels()).thenReturn(List.of(model));
        when(selector.getCurrentModel()).thenReturn(model);
        when(selector.getCurrentTabModelSupplier())
                .thenReturn(ObservableSuppliers.createNonNull(model));
        when(selector.getCurrentTabSupplier()).thenReturn(currentTab);
        when(selector.getCurrentTab()).thenAnswer(call -> currentTab.get());
        when(selector.getModel(false)).thenReturn(model);
        when(model.getProfile()).thenReturn(profile);
        when(model.getCount()).thenReturn(2);
        when(model.getTabAt(0)).thenReturn(first);
        when(model.getTabAt(1)).thenReturn(second);
        when(model.getTabById(11)).thenReturn(first);
        when(model.getTabById(12)).thenReturn(second);
        when(model.indexOf(first)).thenReturn(0);
        when(model.indexOf(second)).thenReturn(1);
        when(model.getSplitInfoForTab(anyInt())).thenAnswer(call -> info);
        doAnswer(
                        call -> {
                            select(call.getArgument(0, Integer.class) == 0 ? first : second);
                            return null;
                        })
                .when(model)
                .setIndex(anyInt(), anyInt());
        currentTab.set(first);
        holder = new FrameLayout(activity);
        // Mirrors the real holder's first compositor child, without creating a native surface.
        holder.addView(new View(activity));
        activity.setContentView(holder);
        coordinator =
                new SplitTabCoordinator(
                        activity,
                        selector,
                        new SplitTabCoordinator.Host() {
                            @Override
                            public void onSplitStateChanged() {
                                refresh();
                            }

                            @Override
                            public void onSplitGeometryChanged() {}
                        });
        refresh();
        layout(mWidth, mHeight);
    }

    private void setupTab(Tab tab, WebContents contents, ContentView view, int id, String urlText) {
        GURL url = new GURL(urlText);
        when(contents.getLastCommittedUrl()).thenReturn(url);
        when(contents.getEventForwarder()).thenReturn(mock(EventForwarder.class));
        when(tab.getId()).thenReturn(id);
        when(tab.getUserDataHost()).thenReturn(new UserDataHost());
        when(tab.getProfile()).thenReturn(profile);
        when(tab.getWindowAndroid()).thenReturn(window);
        when(tab.getView()).thenReturn(view);
        when(tab.getContentView()).thenReturn(view);
        when(tab.getWebContents()).thenReturn(contents);
        when(tab.getUrl()).thenReturn(url);
        when(tab.getAlertState()).thenReturn(-1);
    }

    public void select(Tab tab) {
        currentTab.set(tab);
        if (coordinator != null) refresh();
    }

    public void refresh() {
        coordinator.update(currentTab.get());
        if (coordinator.hasSplit()) coordinator.showIn(holder);
    }

    public void layout(int width, int height) {
        mWidth = width;
        mHeight = height;
        coordinator.setViewport(new Rect(0, 0, width, height));
        holder.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        holder.layout(0, 0, width, height);
    }

    public void destroy() {
        coordinator.destroy();
    }

    /** Suppresses native event sinks only; focus, dispatch, measure and accessibility stay real. */
    public static final class TestContentView extends ContentView {
        public final WebContentsAccessibility accessibility = mock(WebContentsAccessibility.class);
        public final AccessibilityNodeProvider provider = mock(AccessibilityNodeProvider.class);

        TestContentView(Context context, WebContents contents) {
            super(context, contents);
            when(accessibility.getAccessibilityNodeProvider()).thenReturn(provider);
        }

        @Override
        protected WebContentsAccessibility getWebContentsAccessibility() {
            return accessibility;
        }

        @Override
        protected void onAttachedToWindow() {}

        @Override
        protected void onDetachedFromWindow() {}

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {}

        @Override
        protected void onFocusChanged(boolean gain, int direction, Rect previous) {}
    }
}
