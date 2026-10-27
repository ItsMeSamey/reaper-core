// Copyright 2026 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.compositor;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabBrowserControlsOffsetHelper;
import org.chromium.chrome.browser.tab.TabHidingType;
import org.chromium.chrome.browser.tab.TabObserver;
import org.chromium.chrome.browser.tab.TabSelectionType;
import org.chromium.chrome.browser.tab.TabUtils;
import org.chromium.chrome.browser.tabmodel.SplitInfo;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tabmodel.TabModelSelectorTabModelObserver;
import org.chromium.components.embedder_support.view.ContentView;
import org.chromium.components.url_formatter.SchemeDisplay;
import org.chromium.components.url_formatter.UrlFormatter;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.UiUtils;
import org.chromium.ui.base.EventOffsetHandler;
import org.chromium.ui.base.WindowAndroid;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Owns the two Android viewports of a real split in a single browser window. */
@NullMarked
public final class SplitTabCoordinator extends FrameLayout {
    public interface Host {
        /** Requery model membership, update holder state, and schedule composition. */
        void onSplitStateChanged();

        /** Resize the actual renderer surfaces after pane bounds change. */
        void onSplitGeometryChanged();
    }

    private static final WeakHashMap<WebContents, WeakReference<SplitTabCoordinator>> sOwners =
            new WeakHashMap<>();
    private final TabModelSelector mSelector;
    private final Host mHost;
    private final Pane mFirst;
    private final Pane mSecond;
    private final int mDividerWidth;
    private final int mHeaderHeight;
    private final Rect mViewport = new Rect();
    private final Rect mFirstMeasureBounds = new Rect();
    private final Rect mSecondMeasureBounds = new Rect();
    private final ArrayList<WebContents> mRegisteredContents = new ArrayList<>();
    private final Map<WebContents, ArrayList<Runnable>> mPaneObservers = new HashMap<>();
    private final TabModelSelectorTabModelObserver mModelObserver;
    private final TabObserver mTabObserver =
            new TabObserver() {
                @Override
                public void onShown(Tab tab, int type) {
                    if (mDestroying || tab != mSelector.getCurrentTab()) return;
                    mSuspended = false;
                    Tab peer = tab == mFirst.tab ? mSecond.tab : mFirst.tab;
                    if (peer != null && !peer.isDestroyed() && !peer.isClosing()) peer.show(type);
                    mHost.onSplitStateChanged();
                }

                @Override
                public void onHidden(Tab tab, int type) {
                    if (mDestroying
                            || type != TabHidingType.ACTIVITY_HIDDEN
                            || tab != mSelector.getCurrentTab()) return;
                    mSuspended = true;
                    setShown(false);
                    Tab peer = tab == mFirst.tab ? mSecond.tab : mFirst.tab;
                    if (peer != null && !peer.isDestroyed() && !peer.isClosing()) peer.hide(type);
                    mHost.onSplitStateChanged();
                }

                @Override
                public void onContentChanged(Tab tab) {
                    mHost.onSplitStateChanged();
                }

                @Override
                public void onDestroyed(Tab tab) {
                    mHost.onSplitStateChanged();
                }

                @Override
                public void onActivityAttachmentChanged(Tab tab, @Nullable WindowAndroid window) {
                    mHost.onSplitStateChanged();
                }

                @Override
                public void onBrowserControlsOffsetChanged(
                        Tab tab, int top, int bottom, int content, int topMin, int bottomMin) {
                    Pane pane = paneFor(tab);
                    if (pane != null && pane.contentOffset != Math.max(0, content)) {
                        pane.contentOffset = Math.max(0, content);
                        pane.requestLayout();
                        mHost.onSplitGeometryChanged();
                    }
                }

                @Override
                public void onUrlUpdated(Tab tab) {
                    updateHeaders();
                }

                @Override
                public void onAlertStateChanged(Tab tab, int alertState) {
                    updateHeaders();
                }
            };
    private @Nullable SplitInfo mInfo;
    private boolean mShown;
    private boolean mDestroying;
    private boolean mSuspended;
    private boolean mBlockedGesture;
    private boolean mSelecting;
    private boolean mRestoringFocus;

    private final ViewTreeObserver.OnGlobalFocusChangeListener mFocusListener =
            this::onPaneFocusChanged;

    private void onPaneFocusChanged(@Nullable View oldFocus, @Nullable View newFocus) {
        if (mDestroying || !mShown || newFocus == null) return;
        Pane pane = paneContaining(newFocus);
        if (pane == null || pane.tab == null || pane.tab == mSelector.getCurrentTab()) return;
        // Only a browser-owned header may select a pane through normal keyboard
        // focus traversal. DOM/window.focus and dialog autofocus cannot activate it.
        if (newFocus == pane.origin) {
            activate(pane.tab);
            return;
        }
        if (mRestoringFocus) return;
        mRestoringFocus = true;
        try {
            newFocus.clearFocus();
            Pane oldPane = oldFocus == null ? null : paneContaining(oldFocus);
            if (oldFocus != null
                    && oldFocus.isAttachedToWindow()
                    && (oldPane == null || oldPane.tab == mSelector.getCurrentTab())) {
                oldFocus.requestFocus();
            } else {
                Pane active = paneFor(mSelector.getCurrentTab());
                if (active != null) active.origin.requestFocus();
            }
        } finally {
            mRestoringFocus = false;
        }
    }

    public SplitTabCoordinator(Context context, TabModelSelector selector, Host host) {
        super(context);
        mSelector = selector;
        mHost = host;
        mDividerWidth = Math.max(1, Math.round(4 * getResources().getDisplayMetrics().density));
        mHeaderHeight = Math.round(36 * getResources().getDisplayMetrics().density);
        setClipChildren(true);
        setClipToPadding(true);
        setMotionEventSplittingEnabled(false);
        mFirst = new Pane(context);
        mSecond = new Pane(context);
        addView(mFirst);
        addView(mSecond);
        mModelObserver =
                new TabModelSelectorTabModelObserver(selector) {
                    @Override
                    protected void onRegistrationComplete() {
                        if (mDestroying) return;
                        for (TabModel model : mSelector.getModels())
                            model.setSplitViewAvailable(true);
                    }

                    @Override
                    public void didChangeSplit(int[] affectedTabIds, boolean membershipChanged) {
                        if (!mDestroying) mHost.onSplitStateChanged();
                    }

                    @Override
                    public void onDestroy() {
                        if (!mDestroying) mHost.onSplitStateChanged();
                    }
                };
    }

    public static @Nullable SplitTabCoordinator fromWebContents(WebContents contents) {
        WeakReference<SplitTabCoordinator> owner = sOwners.get(contents);
        SplitTabCoordinator coordinator = owner == null ? null : owner.get();
        return coordinator != null && coordinator.contains(contents) ? coordinator : null;
    }

    public void addPaneUnavailableObserver(WebContents owner, Runnable observer) {
        if (!contains(owner) || !mShown) {
            observer.run();
            return;
        }
        mPaneObservers.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(observer);
    }

    public void removePaneUnavailableObserver(WebContents owner, Runnable observer) {
        List<Runnable> observers = mPaneObservers.get(owner);
        if (observers != null) observers.remove(observer);
    }

    public void setDialogShowing(WebContents owner, boolean showing) {
        Pane pane = paneFor(owner);
        if (pane != null) pane.setDialogShowing(owner, showing);
    }

    private void invalidatePane(WebContents owner) {
        List<Runnable> observers = mPaneObservers.remove(owner);
        if (observers != null) for (Runnable observer : List.copyOf(observers)) observer.run();
    }

    public boolean hasSplit() {
        return mInfo != null && !mDestroying;
    }

    @Override
    public boolean isShown() {
        return hasSplit() && mShown;
    }

    public @Nullable SplitInfo getSplitInfo() {
        return mInfo;
    }

    public @Nullable Tab getFirstTab() {
        return mFirst.tab;
    }

    public @Nullable Tab getSecondTab() {
        return mSecond.tab;
    }

    public boolean contains(WebContents contents) {
        return !mDestroying && !contents.isDestroyed() && paneFor(contents) != null;
    }

    public boolean isActive(WebContents contents) {
        Tab current = mSelector.getCurrentTab();
        return isShown() && current != null && current.getWebContents() == contents;
    }

    public @Nullable ViewGroup getDialogHost(WebContents contents) {
        Pane pane = paneFor(contents);
        return isShown()
                        && isAttachedToWindow()
                        && !contents.isDestroyed()
                        && pane != null
                        && !pane.contentBounds.isEmpty()
                ? pane.dialogHost
                : null;
    }

    /** Coordinates are physical pixels relative to CompositorViewHolder. */
    public @Nullable Rect getContentBounds(WebContents contents) {
        Pane pane = paneFor(contents);
        return isShown() && pane != null ? new Rect(pane.contentBounds) : null;
    }

    public @Nullable Rect getContentBounds(Tab tab) {
        Pane pane = paneFor(tab);
        return hasSplit() && pane != null ? new Rect(pane.contentBounds) : null;
    }

    public int getContentOffset(Tab tab) {
        Pane pane = paneFor(tab);
        return pane == null ? 0 : pane.contentOffset;
    }

    /** Requery by actual selected tab; no model state or synthetic identity is created here. */
    public boolean update(@Nullable Tab selected) {
        if (mDestroying) return false;
        TabModel model = mSelector.getCurrentModel();
        SplitInfo info = selected == null ? null : model.getSplitInfoForTab(selected.getId());
        Tab first = info == null ? null : model.getTabById(info.getFirstTabId());
        Tab second = info == null ? null : model.getTabById(info.getSecondTabId());
        if (info != null
                && (first == null
                        || second == null
                        || first == second
                        || (selected != first && selected != second)
                        || first.isClosing()
                        || second.isClosing()
                        || first.isDestroyed()
                        || second.isDestroyed()
                        || first.isDetachedFromActivity()
                        || second.isDetachedFromActivity()
                        || first.getProfile() != second.getProfile()
                        || first.getWindowAndroid() == null
                        || first.getWindowAndroid() != second.getWindowAndroid()
                        || !Double.isFinite(info.getRatio())
                        || info.getRatio() <= 0
                        || info.getRatio() >= 1
                        || (info.getLayout() != SplitInfo.SIDE_BY_SIDE
                                && info.getLayout() != SplitInfo.STACKED))) {
            info = null;
            first = null;
            second = null;
        }
        boolean viewChanged =
                mShown
                        && ((mFirst.tab != null && mFirst.tab.getView() != mFirst.tabView)
                                || (mSecond.tab != null
                                        && mSecond.tab.getView() != mSecond.tabView));
        boolean pairChanged = mFirst.tab != first || mSecond.tab != second;
        boolean visualChanged =
                pairChanged
                        || (mInfo == null) != (info == null)
                        || (mInfo != null
                                && info != null
                                && (mInfo.getLayout() != info.getLayout()
                                        || mInfo.getRatio() != info.getRatio()));
        if (pairChanged) {
            setShown(false);
            if (mDestroying) return true;
            clearRegistrations();
            if (mDestroying) return true;
            mSuspended = selected != null && selected.isHidden();
            mFirst.setTab(first);
            mSecond.setTab(second);
        }
        mInfo = info;
        refreshRegistrations();
        updateHeaders();
        if (visualChanged) requestLayout();
        return visualChanged || viewChanged;
    }

    public void setViewport(Rect viewport) {
        Rect normalized =
                new Rect(
                        viewport.left,
                        viewport.top,
                        Math.max(viewport.left, viewport.right),
                        Math.max(viewport.top, viewport.bottom));
        if (mViewport.equals(normalized)) return;
        mViewport.set(normalized);
        requestLayout();
    }

    /** Attach through the holder only; the compositor SurfaceView remains its first child. */
    public void showIn(FrameLayout holder) {
        if (!hasSplit() || mSuspended) return;
        if (getParent() != holder) {
            UiUtils.removeViewFromParent(this);
            holder.addView(
                    this,
                    1,
                    new FrameLayout.LayoutParams(
                            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        setShown(true);
        mFirst.attachTabView();
        mSecond.attachTabView();
        updateHeaders();
    }

    public void setShown(boolean shown) {
        shown &= hasSplit() && !mSuspended;
        if (mShown == shown) return;
        mShown = shown;
        if (!shown)
            for (WebContents owner : List.copyOf(mRegisteredContents)) invalidatePane(owner);
        if (shown) {
            getViewTreeObserver().addOnGlobalFocusChangeListener(mFocusListener);
            setVisibility(VISIBLE);
        } else {
            if (getViewTreeObserver().isAlive()) {
                getViewTreeObserver().removeOnGlobalFocusChangeListener(mFocusListener);
            }
            mFirst.detachTabView();
            mSecond.detachTabView();
            UiUtils.removeViewFromParent(this);
            setVisibility(GONE);
        }
    }

    public void destroy() {
        if (mDestroying) return;
        mDestroying = true;
        setShown(false);
        clearRegistrations();
        mModelObserver.destroy();
        for (TabModel model : mSelector.getModels()) model.setSplitViewAvailable(false);
        mFirst.setTab(null);
        mSecond.setTab(null);
        mInfo = null;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            Pane target = paneAt(event.getX(), event.getY());
            mBlockedGesture = target == null || target.tab == null || !activate(target.tab);
            if (!mBlockedGesture
                    && target != null
                    && !target.dialogShowing
                    && target.tabView != null
                    && event.getY() >= target.contentBounds.top) {
                target.tabView.requestFocus();
            }
        }
        boolean handled = mBlockedGesture || super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) mBlockedGesture = false;
        return handled;
    }

    @Override
    public boolean dispatchDragEvent(DragEvent event) {
        if (event.getAction() == DragEvent.ACTION_DROP) {
            Pane target = paneAt(event.getX(), event.getY());
            if (target == null || target.tab == null || !activate(target.tab)) return true;
            if (!target.dialogShowing && target.tabView != null) target.tabView.requestFocus();
        }
        return super.dispatchDragEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL
                || event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS) {
            Pane target = paneAt(event.getX(), event.getY());
            if (target == null || target.tab == null || !activate(target.tab)) return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_TAB) {
            View focused = findFocus();
            View next = null;
            if (focused != null) {
                ViewParent parent = focused.getParent();
                if (parent != null) {
                    next = parent.focusSearch(
                            focused, event.isShiftPressed() ? FOCUS_BACKWARD : FOCUS_FORWARD);
                }
            }
            Pane target = next == null ? null : paneContaining(next);
            if (target != null && target.tab != null && !activate(target.tab)) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean activate(Tab tab) {
        Pane target = paneFor(tab);
        if (mDestroying
                || !mShown
                || tab.isDestroyed()
                || tab.isClosing()
                || target == null
                || target.contentBounds.isEmpty()) return false;
        if (mSelector.getCurrentTab() == tab) return true;
        if (mSelecting) return false;
        TabModel model = mSelector.getCurrentModel();
        int index = model.indexOf(tab);
        if (index < 0) return false;
        Pane previous = paneFor(mSelector.getCurrentTab());
        ContentView previousView = previous == null ? null : previous.contentView;
        mSelecting = true;
        try {
            model.setIndex(index, TabSelectionType.FROM_USER);
            boolean selected =
                    !mDestroying
                            && mShown
                            && !tab.isDestroyed()
                            && !tab.isClosing()
                            && mSelector.getCurrentTab() == tab;
            if (selected && previousView != null) {
                // Keep selection guarded while blur can synchronously focus the old header.
                // Real renderer blur releases pointer/keyboard locks and the old IME
                // connection even if a holder observer defers its view refresh.
                previousView.clearFocus();
            }
            updateHeaders();
            return selected;
        } finally {
            mSelecting = false;
        }
    }

    private @Nullable Pane paneFor(@Nullable Tab tab) {
        if (tab == null) return null;
        return mFirst.tab == tab ? mFirst : mSecond.tab == tab ? mSecond : null;
    }

    private @Nullable Pane paneFor(WebContents contents) {
        return mFirst.tab != null && mFirst.tab.getWebContents() == contents
                ? mFirst
                : mSecond.tab != null && mSecond.tab.getWebContents() == contents ? mSecond : null;
    }

    private @Nullable Pane paneContaining(View view) {
        View current = view;
        while (current != this) {
            if (current == mFirst) return mFirst;
            if (current == mSecond) return mSecond;
            if (!(current.getParent() instanceof View parent)) return null;
            current = parent;
        }
        return null;
    }

    private @Nullable Pane paneAt(float x, float y) {
        if (x >= mFirst.getLeft()
                && x < mFirst.getRight()
                && y >= mFirst.getTop()
                && y < mFirst.getBottom()) return mFirst;
        if (x >= mSecond.getLeft()
                && x < mSecond.getRight()
                && y >= mSecond.getTop()
                && y < mSecond.getBottom()) return mSecond;
        return null;
    }

    private void clearRegistrations() {
        for (WebContents contents : List.copyOf(mRegisteredContents)) {
            invalidatePane(contents);
            WeakReference<SplitTabCoordinator> owner = sOwners.get(contents);
            if (owner != null && owner.get() == this) sOwners.remove(contents);
        }
        mRegisteredContents.clear();
    }

    private void refreshRegistrations() {
        for (WebContents contents : List.copyOf(mRegisteredContents)) {
            if (paneFor(contents) != null && !contents.isDestroyed()) continue;
            invalidatePane(contents);
            WeakReference<SplitTabCoordinator> owner = sOwners.get(contents);
            if (owner != null && owner.get() == this) sOwners.remove(contents);
            mRegisteredContents.remove(contents);
        }
        for (Pane pane : List.of(mFirst, mSecond)) {
            WebContents contents = pane.tab == null ? null : pane.tab.getWebContents();
            if (contents != null && !contents.isDestroyed()) {
                sOwners.put(contents, new WeakReference<>(this));
                if (!mRegisteredContents.contains(contents)) mRegisteredContents.add(contents);
            }
        }
    }

    private void updateHeaders() {
        mFirst.updateHeader();
        mSecond.updateHeader();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
        if (mInfo == null) return;
        Rect first = mFirstMeasureBounds;
        Rect second = mSecondMeasureBounds;
        first.set(mViewport);
        second.set(mViewport);
        if (mInfo.getLayout() == SplitInfo.STACKED) {
            int divider = Math.min(mDividerWidth, mViewport.height());
            int available = mViewport.height() - divider;
            first.bottom = mViewport.top + (int) Math.round(available * mInfo.getRatio());
            second.top = first.bottom + divider;
        } else {
            int divider = Math.min(mDividerWidth, mViewport.width());
            int available = mViewport.width() - divider;
            first.right = mViewport.left + (int) Math.round(available * mInfo.getRatio());
            second.left = first.right + divider;
        }
        mFirst.measurePane(first);
        mSecond.measurePane(second);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        boolean firstGeometryChanged = mFirst.layoutPane();
        boolean secondGeometryChanged = mSecond.layoutPane();
        boolean geometryChanged = firstGeometryChanged || secondGeometryChanged;
        if (geometryChanged && !mDestroying) mHost.onSplitGeometryChanged();
    }

    private final class Pane extends FrameLayout {
        final LinearLayout header;
        final TextView origin;
        final ImageView alert;
        final FrameLayout contentHost;
        final FrameLayout dialogHost;
        final Rect bounds = new Rect();
        final Rect contentBounds = new Rect();
        @Nullable Tab tab;
        @Nullable View tabView;
        @Nullable ContentView contentView;
        boolean dialogShowing;
        int contentOffset;
        int savedA11yImportance;
        int savedDescendantFocusability;
        @Nullable View dialogContentView;
        final EventOffsetHandler offsets =
                new EventOffsetHandler(
                        new EventOffsetHandler.EventOffsetHandlerDelegate() {
                            @Override
                            public float getLeft() {
                                return 0;
                            }

                            @Override
                            public float getTop() {
                                return contentOffset;
                            }

                            @Override
                            public void setCurrentTouchEventOffsets(float left, float top) {
                                WebContents contents = tab == null ? null : tab.getWebContents();
                                if (contents != null && !contents.isDestroyed()) {
                                    contents.getEventForwarder().setCurrentTouchOffsetX(left);
                                    contents.getEventForwarder().setCurrentTouchOffsetY(top);
                                }
                            }

                            @Override
                            public void setCurrentDragEventOffsets(float x, float y) {
                                WebContents contents = tab == null ? null : tab.getWebContents();
                                if (contents != null && !contents.isDestroyed())
                                    contents.getEventForwarder().setDragDispatchingOffset(x, y);
                            }
                        });

        Pane(Context context) {
            super(context);
            setClipChildren(true);
            header = new LinearLayout(context);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TypedValue background = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.colorBackground, background, true);
            header.setBackgroundColor(
                    background.type >= TypedValue.TYPE_FIRST_COLOR_INT
                                    && background.type <= TypedValue.TYPE_LAST_COLOR_INT
                            ? background.data
                            : Color.WHITE);
            origin = new TextView(context);
            origin.setSingleLine(true);
            origin.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            origin.setGravity(Gravity.CENTER_VERTICAL);
            origin.setPadding(mDividerWidth, 0, mDividerWidth, 0);
            origin.setTextDirection(View.TEXT_DIRECTION_LTR);
            origin.setFocusable(true);
            origin.setOnClickListener(
                    v -> {
                        if (tab != null) activate(tab);
                    });
            alert = new ImageView(context);
            header.addView(origin, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
            header.addView(alert, new LinearLayout.LayoutParams(mHeaderHeight, mHeaderHeight));
            contentHost =
                    new FrameLayout(context) {
                        @Override
                        public boolean dispatchTouchEvent(MotionEvent event) {
                            return dialogShowing || super.dispatchTouchEvent(event);
                        }

                        @Override
                        public boolean dispatchDragEvent(DragEvent event) {
                            return dialogShowing || super.dispatchDragEvent(event);
                        }

                        @Override
                        public boolean dispatchGenericMotionEvent(MotionEvent event) {
                            return dialogShowing || super.dispatchGenericMotionEvent(event);
                        }
                    };
            contentHost.setClipChildren(true);
            dialogHost = new FrameLayout(context);
            dialogHost.setClipChildren(true);
            addView(contentHost);
            addView(dialogHost);
            addView(header);
        }

        void setTab(@Nullable Tab next) {
            if (tab == next) return;
            detachTabView();
            if (tab != null) tab.removeObserver(mTabObserver);
            tab = next;
            contentOffset =
                    tab == null
                            ? 0
                            : Math.max(0, TabBrowserControlsOffsetHelper.get(tab).contentOffset());
            if (tab != null) tab.addObserver(mTabObserver);
        }

        void attachTabView() {
            if (tab == null || tab.isDestroyed()) return;
            View next = tab.getView();
            if (next == tabView) {
                installInputHooks();
                return;
            }
            detachTabView();
            tabView = next;
            if (next == null) return;
            UiUtils.removeViewFromParent(next);
            contentHost.addView(
                    next,
                    new FrameLayout.LayoutParams(
                            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            if (next instanceof ContentView cv) {
                contentView = cv;
                cv.setContentOffsetXPix(0);
                installInputHooks();
            }
            requestLayout();
        }

        void installInputHooks() {
            if (contentView == null) return;
            contentView.setEventOffsetHandlerForDragDrop(null);
            contentView.setEventOffsetHandlerForDragDrop(offsets);
            contentView.setUserActivationDelegate(
                    () -> !dialogShowing && tab != null && activate(tab),
                    () ->
                            !dialogShowing
                                    && tab != null
                                    && tab == mSelector.getCurrentTab()
                                    && !contentBounds.isEmpty()
                                    && isShown());
        }

        void detachTabView() {
            restoreDialogState();
            if (contentView != null) {
                contentView.setUserActivationDelegate(null, null);
                contentView.setEventOffsetHandlerForDragDrop(null);
                contentView.setDesiredMeasureSpec(
                        ContentView.DEFAULT_MEASURE_SPEC, ContentView.DEFAULT_MEASURE_SPEC);
                WebContents contents = contentView.getWebContents();
                if (contents != null && !contents.isDestroyed()) {
                    contents.getEventForwarder().setCurrentTouchOffsetX(0);
                    contents.getEventForwarder().setCurrentTouchOffsetY(0);
                    contents.getEventForwarder().setDragDispatchingOffset(0, 0);
                }
                contentView = null;
            }
            if (tabView != null && tabView.getParent() == contentHost)
                contentHost.removeView(tabView);
            tabView = null;
        }

        void setDialogShowing(WebContents owner, boolean showing) {
            if (!showing) {
                restoreDialogState();
                return;
            }
            if (dialogShowing || tab == null || tab.getWebContents() != owner || tabView == null)
                return;
            dialogShowing = true;
            dialogContentView = tabView;
            savedA11yImportance = tabView.getImportantForAccessibility();
            savedDescendantFocusability = contentHost.getDescendantFocusability();
            tabView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            contentHost.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            tabView.clearFocus();
        }

        void restoreDialogState() {
            if (!dialogShowing) return;
            dialogShowing = false;
            if (dialogContentView != null)
                dialogContentView.setImportantForAccessibility(savedA11yImportance);
            contentHost.setDescendantFocusability(savedDescendantFocusability);
            dialogContentView = null;
        }

        void updateHeader() {
            if (tab == null || tab.isDestroyed()) return;
            WebContents contents = tab.getWebContents();
            org.chromium.url.GURL visibleUrl =
                    contents != null && !contents.isDestroyed() && !tab.isNativePage()
                            ? contents.getLastCommittedUrl()
                            : tab.getUrl();
            String url = UrlFormatter.formatUrlForSecurityDisplay(visibleUrl, SchemeDisplay.SHOW);
            origin.setText(url);
            origin.setEllipsize(
                    "http".equals(visibleUrl.getScheme()) || "https".equals(visibleUrl.getScheme())
                            ? TextUtils.TruncateAt.START
                            : TextUtils.TruncateAt.END);
            origin.setContentDescription(url);
            origin.setTooltipText(visibleUrl.getSpec());
            boolean active = tab == mSelector.getCurrentTab();
            origin.setSelected(active);
            int drawable = TabUtils.getTabAlertDrawable(tab.getAlertState());
            alert.setVisibility(drawable == Resources.ID_NULL ? GONE : VISIBLE);
            if (drawable != Resources.ID_NULL) {
                alert.setImageResource(drawable);
                alert.setColorFilter(
                        TabUtils.getTabAlertTintColor(
                                getContext(), tab.getAlertState(), origin.getCurrentTextColor()));
                int description = TabUtils.getTabAlertDescriptionRes(tab.getAlertState());
                alert.setContentDescription(
                        description == Resources.ID_NULL
                                ? null
                                : getContext().getString(description));
            }
            GradientDrawable outline = new GradientDrawable();
            outline.setColor(Color.TRANSPARENT);
            outline.setStroke(
                    active ? mDividerWidth : Math.max(1, mDividerWidth / 4),
                    active ? origin.getCurrentTextColor() : Color.GRAY);
            setForeground(outline);
        }

        void measurePane(Rect nextBounds) {
            bounds.set(nextBounds);
            measure(
                    MeasureSpec.makeMeasureSpec(Math.max(0, bounds.width()), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, bounds.height()), MeasureSpec.EXACTLY));
        }

        boolean layoutPane() {
            Rect previous = new Rect(contentBounds);
            Rect next =
                    new Rect(
                            bounds.left,
                            Math.min(bounds.bottom, bounds.top + mHeaderHeight),
                            bounds.right,
                            bounds.bottom);
            if (!previous.isEmpty() && next.isEmpty() && tab != null) {
                if (tabView != null) tabView.clearFocus();
                WebContents contents = tab.getWebContents();
                if (contents != null) invalidatePane(contents);
            }
            contentBounds.set(next);
            layout(bounds.left, bounds.top, bounds.right, bounds.bottom);
            return !previous.equals(contentBounds);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(width, height);
            int headerHeight = Math.min(mHeaderHeight, height);
            header.measure(
                    widthSpec, MeasureSpec.makeMeasureSpec(headerHeight, MeasureSpec.EXACTLY));
            int contentHeight = Math.max(0, height - headerHeight);
            int spec = MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY);
            contentHost.measure(widthSpec, spec);
            dialogHost.measure(widthSpec, spec);
            if (tabView != null)
                tabView.measure(
                        widthSpec,
                        MeasureSpec.makeMeasureSpec(
                                contentHeight + (contentView == null ? 0 : contentOffset),
                                MeasureSpec.EXACTLY));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int height = b - t;
            int headerHeight = Math.min(mHeaderHeight, height);
            header.layout(0, 0, width, headerHeight);
            contentHost.layout(0, headerHeight, width, height);
            dialogHost.layout(0, headerHeight, width, height);
            if (tabView != null)
                tabView.layout(
                        0, contentView == null ? 0 : -contentOffset, width, height - headerHeight);
        }

        @Override
        public boolean onInterceptHoverEvent(MotionEvent event) {
            offsets.onInterceptHoverEvent(event);
            return super.onInterceptHoverEvent(event);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                offsets.onInterceptTouchDownEvent(event);
            }
            return super.onInterceptTouchEvent(event);
        }
    }
}
