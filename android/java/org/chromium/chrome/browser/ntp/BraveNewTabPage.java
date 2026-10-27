/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp;

import static org.chromium.build.NullUtil.assertNonNull;

import android.app.Activity;

import org.chromium.base.supplier.MonotonicObservableSupplier;
import org.chromium.base.supplier.NonNullObservableSupplier;
import org.chromium.base.supplier.OneshotSupplier;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.back_press.BackPressManager;
import org.chromium.chrome.browser.browser_controls.BrowserControlsStateProvider;
import org.chromium.chrome.browser.lifecycle.ActivityLifecycleDispatcher;
import org.chromium.chrome.browser.magic_stack.ModuleRegistry;
import org.chromium.chrome.browser.metrics.StartupMetricsTracker;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.search_engines.TemplateUrlServiceFactory;
import org.chromium.chrome.browser.share.ShareDelegate;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tasks.HomeSurfaceTracker;
import org.chromium.chrome.browser.toolbar.top.Toolbar;
import org.chromium.chrome.browser.ui.edge_to_edge.EdgeToEdgeController;
import org.chromium.chrome.browser.ui.edge_to_edge.TopInsetProvider;
import org.chromium.chrome.browser.ui.messages.snackbar.SnackbarManager;
import org.chromium.chrome.browser.ui.native_page.NativePageHost;
import org.chromium.chrome.browser.ui.side_ui.SideUiStateProvider;
import org.chromium.components.browser_ui.bottomsheet.BottomSheetController;
import org.chromium.components.search_engines.TemplateUrlService;
import org.chromium.ui.base.ActivityResultTracker;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.ui.modaldialog.ModalDialogManager;

import java.util.function.Supplier;

@NullMarked
public class BraveNewTabPage extends NewTabPage {
    // Rebound to the real parent layout by the existing constructor adapter.
    private @Nullable NewTabPageLayout mNewTabPageLayout;

    public BraveNewTabPage(
            Activity activity,
            BrowserControlsStateProvider browserControlsStateProvider,
            Supplier<@Nullable Tab> activityTabProvider,
            ModalDialogManager modalDialogManager,
            SnackbarManager snackbarManager,
            ActivityLifecycleDispatcher lifecycleDispatcher,
            TabModelSelector tabModelSelector,
            boolean isTablet,
            NewTabPageCreationTracker tabCreationTracker,
            boolean isInNightMode,
            NativePageHost nativePageHost,
            Tab tab,
            String url,
            BottomSheetController bottomSheetController,
            Supplier<@Nullable ShareDelegate> shareDelegateSupplier,
            WindowAndroid windowAndroid,
            Supplier<Toolbar> toolbarSupplier,
            @Nullable HomeSurfaceTracker homeSurfaceTracker,
            ActivityResultTracker activityResultTracker,
            NonNullObservableSupplier<Integer> tabStripHeightSupplier,
            OneshotSupplier<ModuleRegistry> moduleRegistrySupplier,
            MonotonicObservableSupplier<EdgeToEdgeController> edgeToEdgeControllerSupplier,
            TopInsetProvider topInsetProvider,
            OneshotSupplier<SideUiStateProvider> sideUiStateProviderSupplier,
            StartupMetricsTracker startupMetricsTracker,
            BackPressManager backPressManager) {
        super(
                activity,
                browserControlsStateProvider,
                activityTabProvider,
                modalDialogManager,
                snackbarManager,
                lifecycleDispatcher,
                tabModelSelector,
                isTablet,
                tabCreationTracker,
                isInNightMode,
                nativePageHost,
                tab,
                url,
                bottomSheetController,
                shareDelegateSupplier,
                windowAndroid,
                toolbarSupplier,
                homeSurfaceTracker,
                activityResultTracker,
                tabStripHeightSupplier,
                moduleRegistrySupplier,
                edgeToEdgeControllerSupplier,
                topInsetProvider,
                sideUiStateProviderSupplier,
                startupMetricsTracker,
                backPressManager);

        assertNonNull(mNewTabPageLayout);
        assert mNewTabPageLayout instanceof BraveNewTabPageLayout;

        // We have no way to know exactly which service the observer is added to, so try
        // remove on
        // both
        if (tabModelSelector != null) {
            for (TabModel tabModel : tabModelSelector.getModels()) {
                if (tabModel.getProfile() != null) {
                    TemplateUrlService templateUrlService =
                            TemplateUrlServiceFactory.getForProfile(tabModel.getProfile());
                    templateUrlService.removeObserver(this);
                }
            }
        }
        // Re-add to the new tab's profile
        TemplateUrlService templateUrlService =
                TemplateUrlServiceFactory.getForProfile(
                        Profile.fromWebContents(assertNonNull(mTab.getWebContents())));
        templateUrlService.addObserver(this);
    }

    public void updateSearchProvider() {
        // Search provider logo is not used in Brave's NTP.
        mSearchProviderHasLogo = false;
    }
}
