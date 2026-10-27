// Copyright 2026 The Reaper Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.ntp;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import androidx.annotation.IntDef;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.chromium.base.Callback;
import org.chromium.base.supplier.MonotonicObservableSupplier;
import org.chromium.base.supplier.NonNullObservableSupplier;
import org.chromium.base.supplier.ObservableSuppliers;
import org.chromium.base.supplier.SettableNonNullObservableSupplier;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.ntp_customization.NtpCustomizationConfigManager;
import org.chromium.chrome.browser.ntp_customization.NtpCustomizationConfigManager.HomepageStateListener;
import org.chromium.chrome.browser.ntp_customization.NtpCustomizationUtils;
import org.chromium.chrome.browser.ntp_customization.NtpCustomizationUtils.NtpBackgroundType;
import org.chromium.chrome.browser.ntp_customization.theme.NtpBackgroundImageCoordinator;
import org.chromium.chrome.browser.ntp_customization.theme.chrome_colors.NtpThemeColorInfo;
import org.chromium.chrome.browser.ntp_customization.theme.upload_image.BackgroundImageInfo;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabObserver;
import org.chromium.chrome.browser.ui.edge_to_edge.EdgeToEdgeController;
import org.chromium.chrome.browser.ui.edge_to_edge.EdgeToEdgeControllerFactory;
import org.chromium.chrome.browser.ui.native_page.TouchEnabledDelegate;
import org.chromium.chrome.browser.ui.theme.ChromeSemanticColorUtils;
import org.chromium.components.browser_ui.widget.displaystyle.UiConfig;
import org.chromium.components.embedder_support.util.UrlUtilities;
import org.chromium.content_public.browser.NavigationController;
import org.chromium.content_public.browser.NavigationEntry;
import org.chromium.content_public.browser.NavigationHandle;
import org.chromium.ui.base.ViewUtils;
import org.chromium.ui.edge_to_edge.EdgeToEdgePadAdjuster;
import org.chromium.url.GURL;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Owns the local NTP header's scrolling, viewport and navigation-entry state. */
@NullMarked
public final class StaticNtpSurfaceCoordinator implements NtpScrollDelegate {
    static final String SCROLL_STATE_KEY = "ReaperStaticNtpScrollY";

    @IntDef({
        RestoringState.WAITING_TO_RESTORE,
        RestoringState.RESTORED,
        RestoringState.NO_STATE_TO_RESTORE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RestoringState {
        int WAITING_TO_RESTORE = 0;
        int RESTORED = 1;
        int NO_STATE_TO_RESTORE = 2;
    }

    /** The NTP owns input attribution and omnibox interception. */
    public interface Delegate {
        void sendMotionEventForInputTracking(MotionEvent event);

        boolean onInterceptTouchEvent(MotionEvent event);
    }

    private final Tab mTab;
    private final View mHeader;
    private final Delegate mDelegate;
    private final SnapScrollHelperImpl mSnapHelper;
    private final RootView mRoot;
    private final RecyclerView mRecycler;
    private final LinearLayoutManager mLayoutManager;
    private final UiConfig mUiConfig;
    private final SettableNonNullObservableSupplier<Integer> mRestoringState =
            ObservableSuppliers.createNonNull(RestoringState.NO_STATE_TO_RESTORE);
    private final TabObserver mTabObserver;
    private final RecyclerView.OnScrollListener mScrollListener;
    private final Runnable mFinishRestore = this::finishRestore;
    private boolean mTouchEnabled = true;
    private boolean mDestroyed;
    private boolean mSnapshotDirty = true;
    private int mPendingScroll = -1;
    private boolean mRestoreApplied;
    private @Nullable NavigationEntry mEntry;
    private @Nullable EdgeToEdgePadAdjuster mEdgePadAdjuster;
    private @Nullable NonNullObservableSupplier<Integer> mTabStripHeight;
    private @Nullable Callback<Integer> mTabStripObserver;
    private @Nullable NtpBackgroundImageCoordinator mBackground;
    private @Nullable HomepageStateListener mHomepageListener;

    public StaticNtpSurfaceCoordinator(
            Context context,
            Tab tab,
            View header,
            SnapScrollHelperImpl snapHelper,
            Delegate delegate) {
        mTab = tab;
        mHeader = header;
        mDelegate = delegate;
        mSnapHelper = snapHelper;
        mRoot = new RootView(context);
        mRoot.setLayoutParams(
                new ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mUiConfig = new UiConfig(mRoot);
        mRecycler = new LocalRecyclerView(context);
        mRecycler.setItemAnimator(null);
        mRecycler.setClipToPadding(false);
        mLayoutManager = new LinearLayoutManager(context);
        mRecycler.setLayoutManager(mLayoutManager);
        mRecycler.setAdapter(new HeaderAdapter());
        mRoot.addView(
                mRecycler,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mScrollListener =
                new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrolled(RecyclerView recycler, int dx, int dy) {
                        if (mDestroyed) return;
                        mSnapshotDirty = true;
                        mSnapHelper.handleScroll();
                    }
                };
        mRecycler.addOnScrollListener(mScrollListener);
        mSnapHelper.setView(mRecycler);
        mTabObserver =
                new TabObserver() {
                    @Override
                    public void onHidden(Tab hidden, int reason) {
                        saveScroll();
                    }

                    @Override
                    public void onPageLoadStarted(Tab loading, GURL url) {
                        saveScroll();
                    }

                    @Override
                    public void onShown(Tab shown, int reason) {
                        readScroll();
                    }

                    @Override
                    public void onDidFinishNavigationInPrimaryMainFrame(
                            Tab tab, NavigationHandle navigation) {
                        if (navigation.hasCommitted()) bindCommittedEntry();
                    }
                };
        readScroll();
        mTab.addObserver(mTabObserver);
    }

    /** Attach only window UI policies; this creates no content service or remote surface. */
    public void initializeWindow(
            Activity activity,
            NonNullObservableSupplier<Integer> tabStripHeight,
            MonotonicObservableSupplier<EdgeToEdgeController> edgeToEdgeControllerSupplier) {
        mTabStripHeight = tabStripHeight;
        mTabStripObserver = height -> mRoot.setPadding(0, height, 0, 0);
        tabStripHeight.addSyncObserverAndPostIfNonNull(mTabStripObserver);
        mEdgePadAdjuster =
                EdgeToEdgeControllerFactory.createForViewAndObserveSupplier(
                        mRecycler, edgeToEdgeControllerSupplier);
        int defaultColor = ChromeSemanticColorUtils.getHomeSurfaceBackgroundColor(activity);
        setBackgroundColor(defaultColor);
        if (!NtpCustomizationUtils.isNtpThemeCustomizationEnabled()) return;
        mBackground = new NtpBackgroundImageCoordinator(activity, mRoot, mUiConfig, defaultColor);
        // The background coordinator inserts its real image view; keep scrolling content above it.
        mRecycler.bringToFront();
        mHomepageListener =
                new HomepageStateListener() {
                    @Override
                    public void onBackgroundImageChanged(
                            Bitmap bitmap,
                            BackgroundImageInfo info,
                            boolean fromInitialization,
                            @NtpBackgroundType int oldType,
                            @NtpBackgroundType int newType) {
                        if (mDestroyed || mBackground == null) return;
                        mHeader.setBackgroundColor(Color.TRANSPARENT);
                        mRecycler.setBackgroundColor(Color.TRANSPARENT);
                        mBackground.setBackground(bitmap, info, newType);
                        mSnapshotDirty = true;
                    }

                    @Override
                    public void onBackgroundColorChanged(
                            @Nullable NtpThemeColorInfo info,
                            int color,
                            boolean fromInitialization,
                            @NtpBackgroundType int oldType,
                            @NtpBackgroundType int newType) {
                        if (!mDestroyed) setBackgroundColor(color);
                    }

                    @Override
                    public void onBackgroundReset(@NtpBackgroundType int oldType) {
                        if (!mDestroyed) setBackgroundColor(defaultColor);
                    }
                };
        NtpCustomizationConfigManager.getInstance().addListener(mHomepageListener, activity, false);
    }

    private void setBackgroundColor(int color) {
        mRoot.setBackgroundColor(color);
        mRecycler.setBackgroundColor(color);
        mHeader.setBackgroundColor(Color.TRANSPARENT);
        if (mBackground != null) mBackground.clearBackground();
        mSnapshotDirty = true;
    }

    public ViewGroup getView() {
        return mRoot;
    }

    public RecyclerView getRecyclerView() {
        return mRecycler;
    }

    public UiConfig getUiConfig() {
        return mUiConfig;
    }

    public NtpScrollDelegate getScrollDelegate() {
        return this;
    }

    public TouchEnabledDelegate getTouchEnabledDelegate() {
        return enabled -> mTouchEnabled = enabled;
    }

    public NonNullObservableSupplier<Integer> getRestoringStateSupplier() {
        return mRestoringState;
    }

    public void reload() {
        if (mDestroyed) return;
        mSnapshotDirty = true;
        mHeader.requestLayout();
        mRoot.invalidate();
    }

    public boolean shouldCaptureThumbnail() {
        return !mDestroyed && mSnapshotDirty;
    }

    public void captureThumbnail(Canvas canvas) {
        if (mDestroyed) return;
        ViewUtils.captureBitmap(mRoot, canvas);
        mSnapshotDirty = false;
    }

    @Override
    public boolean isScrollViewInitialized() {
        return !mDestroyed && mRecycler.getWidth() > 0 && mLayoutManager.getChildCount() != 0;
    }

    @Override
    public boolean isChildVisibleAtPosition(int position) {
        return isScrollViewInitialized()
                && position == 0
                && mLayoutManager.findFirstVisibleItemPosition() == 0;
    }

    @Override
    public int getVerticalScrollOffset() {
        View child = mLayoutManager.findViewByPosition(0);
        return child == null
                ? 0
                : Math.max(0, mRecycler.getPaddingTop() - mLayoutManager.getDecoratedTop(child));
    }

    @Override
    public void snapScroll() {
        if (!isScrollViewInitialized() || mPendingScroll >= 0) return;
        int current = getVerticalScrollOffset();
        mRecycler.smoothScrollBy(0, mSnapHelper.calculateSnapPosition(current) - current);
    }

    private @Nullable NavigationController navigationController() {
        var contents = mTab.getWebContents();
        return contents == null || contents.isDestroyed()
                ? null
                : contents.getNavigationController();
    }

    private @Nullable NavigationEntry ntpEntry(NavigationController controller) {
        int index = controller.getLastCommittedEntryIndex();
        if (index < 0) return null;
        NavigationEntry entry = controller.getEntryAtIndex(index);
        return entry != null && UrlUtilities.isNtpUrl(entry.getUrl()) ? entry : null;
    }

    private void bindCommittedEntry() {
        if (mDestroyed) return;
        var page = mTab.getNativePage();
        if (page == null || page.getView() != mRoot) return;
        NavigationController controller = navigationController();
        if (controller == null || ntpEntry(controller) == null || isCurrentEntry()) return;
        // NativePageFactory can reuse this same NTP for another history entry. Retire
        // the previous entry's pending work before associating with the new commit.
        mRecycler.removeCallbacks(mFinishRestore);
        mPendingScroll = -1;
        mRestoreApplied = false;
        mEntry = null;
        readScroll();
        if (mDestroyed) return;
        if (mPendingScroll < 0) {
            mLayoutManager.scrollToPositionWithOffset(0, 0);
            mRestoringState.set(RestoringState.NO_STATE_TO_RESTORE);
        }
        if (!mDestroyed) mRecycler.requestLayout();
    }

    private void readScroll() {
        if (mDestroyed || mEntry != null) return;
        NavigationController controller = navigationController();
        if (controller == null) return;
        NavigationEntry entry = ntpEntry(controller);
        if (entry == null) return;
        mEntry = entry;
        String state =
                controller.getEntryExtraData(
                        controller.getLastCommittedEntryIndex(), SCROLL_STATE_KEY);
        if (state == null || state.isEmpty()) return;
        try {
            int scroll = Integer.parseInt(state);
            if (scroll < 0) return;
            mPendingScroll = scroll;
            mRestoringState.set(RestoringState.WAITING_TO_RESTORE);
        } catch (NumberFormatException ignored) {
            // Old or malformed entry data has no applicable local scroll state.
        }
    }

    private void restoreAfterLayout() {
        readScroll();
        if (mDestroyed
                || mPendingScroll < 0
                || !isScrollViewInitialized()
                || mRecycler.getHeight() == 0) return;
        if (!isCurrentEntry()) {
            mPendingScroll = -1;
            mRestoringState.set(RestoringState.NO_STATE_TO_RESTORE);
            return;
        }
        if (mRestoreApplied) {
            mRecycler.removeCallbacks(mFinishRestore);
            mRecycler.post(mFinishRestore);
            return;
        }
        int viewport =
                mRecycler.getHeight() - mRecycler.getPaddingTop() - mRecycler.getPaddingBottom();
        int scroll = Math.min(mPendingScroll, Math.max(0, mHeader.getHeight() - viewport));
        mRestoreApplied = true;
        mLayoutManager.scrollToPositionWithOffset(0, -scroll);
        mRecycler.removeCallbacks(mFinishRestore);
        mRecycler.post(mFinishRestore);
    }

    private void finishRestore() {
        if (mDestroyed || mPendingScroll < 0) return;
        if (!isCurrentEntry()) {
            mPendingScroll = -1;
            mRestoringState.set(RestoringState.NO_STATE_TO_RESTORE);
            return;
        }
        if (mRecycler.isLayoutRequested() || mRecycler.isComputingLayout()) {
            // The next layout callback completes restoration; do not spin while detached.
            return;
        }
        mPendingScroll = -1;
        mRestoringState.set(RestoringState.RESTORED);
    }

    private boolean isCurrentEntry() {
        NavigationController controller = navigationController();
        if (controller == null || mEntry == null) return false;
        NavigationEntry entry = ntpEntry(controller);
        return entry != null
                && entry.getIndex() == mEntry.getIndex()
                && entry.getTimestamp() == mEntry.getTimestamp()
                && entry.getUrl().equals(mEntry.getUrl());
    }

    private void saveScroll() {
        if (mDestroyed || mPendingScroll >= 0 || !isScrollViewInitialized()) return;
        NavigationController controller = navigationController();
        if (controller == null || !isCurrentEntry()) return;
        controller.setEntryExtraData(
                controller.getLastCommittedEntryIndex(),
                SCROLL_STATE_KEY,
                Integer.toString(getVerticalScrollOffset()));
    }

    public void destroy() {
        if (mDestroyed) return;
        saveScroll();
        mDestroyed = true;
        mTab.removeObserver(mTabObserver);
        mRecycler.removeCallbacks(mFinishRestore);
        mRecycler.removeOnScrollListener(mScrollListener);
        mSnapHelper.destroy();
        if (mEdgePadAdjuster != null) mEdgePadAdjuster.destroy();
        if (mTabStripHeight != null && mTabStripObserver != null) {
            mTabStripHeight.removeObserver(mTabStripObserver);
        }
        if (mHomepageListener != null) {
            NtpCustomizationConfigManager.getInstance().removeListener(mHomepageListener);
        }
        if (mBackground != null) mBackground.destroy();
        mRecycler.setAdapter(null);
        mRoot.removeAllViews();
        mUiConfig.destroy();
    }

    private final class LocalRecyclerView extends RecyclerView {
        LocalRecyclerView(Context context) {
            super(context);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            // Recycler child layout can change without this view's bounds changing.
            restoreAfterLayout();
        }
    }

    private final class HeaderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            mHeader.setLayoutParams(
                    new RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(mHeader) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {}

        @Override
        public int getItemCount() {
            return 1;
        }
    }

    private final class RootView extends FrameLayout {
        RootView(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        protected void onConfigurationChanged(Configuration configuration) {
            super.onConfigurationChanged(configuration);
            if (!mDestroyed) mUiConfig.updateDisplayStyle();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            mSnapshotDirty = true;
            if (!mDestroyed) mUiConfig.updateDisplayStyle();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (mDestroyed) return true;
            mDelegate.sendMotionEventForInputTracking(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) requestFocus();
            return !mTouchEnabled
                    || super.onInterceptTouchEvent(event)
                    || mDelegate.onInterceptTouchEvent(event);
        }

        @Override
        public void focusableViewAvailable(View view) {
            if (!hasFocus()) super.focusableViewAvailable(view);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            // Give text fields/tiles their normal keys before handling unconsumed page scrolling.
            if (super.dispatchKeyEvent(event)) return true;
            if (event.getAction() != KeyEvent.ACTION_DOWN
                    || event.isAltPressed()
                    || event.isCtrlPressed()
                    || event.isMetaPressed()) return false;
            int key = event.getKeyCode();
            if (key != KeyEvent.KEYCODE_DPAD_UP
                    && key != KeyEvent.KEYCODE_DPAD_DOWN
                    && key != KeyEvent.KEYCODE_SPACE) return false;
            int direction =
                    key == KeyEvent.KEYCODE_DPAD_UP
                                    || (key == KeyEvent.KEYCODE_SPACE && event.isShiftPressed())
                            ? -1
                            : 1;
            if (!mRecycler.canScrollVertically(direction)) return false;
            mRecycler.smoothScrollBy(0, direction * Math.max(1, mRecycler.getHeight() / 2));
            return true;
        }

        @Override
        public @Nullable InputConnection onCreateInputConnection(EditorInfo attrs) {
            attrs.imeOptions |= EditorInfo.IME_FLAG_NO_FULLSCREEN;
            return super.onCreateInputConnection(attrs);
        }
    }
}
