/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.tabmodel;

import android.app.Activity;

import org.chromium.base.supplier.OneshotSupplier;
import org.chromium.chrome.browser.compositor.CompositorViewHolder;
import org.chromium.chrome.browser.profiles.ProfileProvider;
import org.chromium.chrome.browser.tab.TabDelegateFactory;
import org.chromium.ui.base.WindowAndroid;

import java.util.function.Supplier;

public class BraveTabCreator extends ChromeTabCreator {
    public BraveTabCreator(
            Activity activity,
            WindowAndroid nativeWindow,
            Supplier<TabDelegateFactory> tabDelegateFactory,
            OneshotSupplier<ProfileProvider> profileProviderSupplier,
            boolean incognito,
            AsyncTabParamsManager asyncTabParamsManager,
            Supplier<TabModelSelector> tabModelSelectorSupplier,
            Supplier<CompositorViewHolder> compositorViewHolderSupplier) {
        super(
                activity,
                nativeWindow,
                tabDelegateFactory,
                profileProviderSupplier,
                incognito,
                asyncTabParamsManager,
                tabModelSelectorSupplier,
                compositorViewHolderSupplier);
    }

}
