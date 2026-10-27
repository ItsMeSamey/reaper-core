/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp;

import static org.chromium.build.NullUtil.assertNonNull;

import android.app.Activity;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.build.annotations.Initializer;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.suggestions.tile.BraveMostVisitedTilesLayoutBase;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tabmodel.TabModelUtils;
import org.chromium.ui.base.WindowAndroid;


/** Android NTP with local most-visited tiles and no remote content services. */
@NullMarked
public class BraveNewTabPageLayout extends NewTabPageLayout {
    private @Nullable ViewGroup mMvTilesContainerLayout;
    private Activity mActivity;

    public BraveNewTabPageLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    protected void initializeSiteSectionView() {
        mMvTilesContainerLayout =
                (ViewGroup) ((ViewStub) findViewById(R.id.mv_tiles_layout_stub)).inflate();
        mMvTilesContainerLayout.setVisibility(View.VISIBLE);
        findViewById(R.id.ntp_main_layout).setVisibility(View.VISIBLE);
        setVisibility(View.VISIBLE);
    }

    @Initializer
    public void initialize(
            NewTabPageManager manager,
            Activity activity,
            Profile profile,
            WindowAndroid windowAndroid) {
        mActivity = activity;
        assert mMvTilesContainerLayout != null;
        if (mMvTilesContainerLayout != null) {
            ViewGroup tilesLayout = mMvTilesContainerLayout.findViewById(R.id.mv_tiles_layout);
            if (tilesLayout instanceof BraveMostVisitedTilesLayoutBase) {
                ((BraveMostVisitedTilesLayoutBase) tilesLayout).setUseFixedLayout(true);
            }
        }
        ((BraveActivity) mActivity).dismissShieldsTooltip();
        ((BraveActivity) mActivity).setNewTabPageManager(manager);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        maybeShowRecentTabsDialog();
    }

    /**
     * Shows the recent tabs snackbar if variant B is active, and either OPTION_NEW_TAB or
     * OPTION_NEW_TAB_AFTER_INACTIVITY is selected. For OPTION_NEW_TAB_AFTER_INACTIVITY, only shows
     * when app was returned from background after inactivity threshold.
     */
    private void maybeShowRecentTabsDialog() {
        if (!BraveFreshNtpHelper.isEnabled() || !BraveFreshNtpHelper.getVariant().equals("B")) {
            return;
        }

        // Check if OPTION_NEW_TAB or OPTION_NEW_TAB_AFTER_INACTIVITY is selected
        int openingScreenOption =
                ChromeSharedPreferences.getInstance()
                        .readInt(BravePreferenceKeys.BRAVE_NEW_TAB_PAGE_OPENING_SCREEN, 1);
        if (openingScreenOption
                        != BravePreferenceKeys.BRAVE_OPENING_SCREEN_OPTION_NEW_TAB_AFTER_INACTIVITY
                && openingScreenOption != BravePreferenceKeys.BRAVE_OPENING_SCREEN_OPTION_NEW_TAB) {
            return;
        }

        // Check the flag set by BraveReturnToChromeUtil when NTP was shown
        // For OPTION_NEW_TAB_AFTER_INACTIVITY, flag is set only after inactivity threshold
        // For OPTION_NEW_TAB, flag is set whenever NTP is shown at startup
        boolean shouldShowSnackbar =
                ChromeSharedPreferences.getInstance()
                        .readBoolean(BravePreferenceKeys.BRAVE_SHOW_RECENT_TABS_SNACKBAR, false);
        if (!shouldShowSnackbar) {
            return;
        }

        // Clear the flag so snackbar is only shown once
        ChromeSharedPreferences.getInstance()
                .writeBoolean(BravePreferenceKeys.BRAVE_SHOW_RECENT_TABS_SNACKBAR, false);

        // Wait for tab state to be initialized before showing snackbar.
        // On cold start, tabs are restored asynchronously, so we need to wait
        // for them to be fully loaded before searching for the last active tab.
        BraveActivity braveActivity = (BraveActivity) mActivity;
        TabModelSelector tabModelSelector = braveActivity.getTabModelSelectorSupplier().get();
        assertNonNull(tabModelSelector);
        TabModelUtils.runOnTabStateInitialized(
                tabModelSelector,
                (selector) -> {
                    BraveRecentTabsSnackbarHelper snackbarHelper =
                            new BraveRecentTabsSnackbarHelper();
                    snackbarHelper.showSnackbar(braveActivity);
                });
    }
}
