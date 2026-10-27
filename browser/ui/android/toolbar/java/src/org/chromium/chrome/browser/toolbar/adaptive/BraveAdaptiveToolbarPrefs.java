/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.toolbar.adaptive;

import static org.chromium.chrome.browser.preferences.ChromePreferenceKeys.ADAPTIVE_TOOLBAR_CUSTOMIZATION_ENABLED;
import static org.chromium.chrome.browser.preferences.ChromePreferenceKeys.ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS;

import org.chromium.base.ContextUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.ui.base.DeviceFormFactor;

@NullMarked
public class BraveAdaptiveToolbarPrefs {
    public static @AdaptiveToolbarButtonVariant int getCustomizationSetting() {
        int defaultVariant =
                DeviceFormFactor.isNonMultiDisplayContextOnTablet(
                                ContextUtils.getApplicationContext())
                        ? AdaptiveToolbarButtonVariant.SHARE
                        : AdaptiveToolbarButtonVariant.NEW_TAB;
        int variant =
                ChromeSharedPreferences.getInstance()
                        .readInt(ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS, defaultVariant);
        switch (variant) {
            case AdaptiveToolbarButtonVariant.PLAYLIST:
            case AdaptiveToolbarButtonVariant.LEO:
            case AdaptiveToolbarButtonVariant.WALLET:
            case AdaptiveToolbarButtonVariant.NEWS:
            case AdaptiveToolbarButtonVariant.GLIC:
                // Keep historical numeric values for upgrade compatibility, but never expose a
                // removed feature to the toolbar predictor or persist it as the active choice.
                ChromeSharedPreferences.getInstance()
                        .writeInt(ADAPTIVE_TOOLBAR_CUSTOMIZATION_SETTINGS, defaultVariant);
                return defaultVariant;
            default:
                return variant;
        }
    }

    /**
     * Returns whether the customization preference toggle is enabled. Returns false if no value has
     * been set. The value returned is orthogonal to whether the corresponding feature flag is
     * enabled.
     */
    public static boolean isCustomizationPreferenceEnabled() {
        return ChromeSharedPreferences.getInstance()
                .readBoolean(ADAPTIVE_TOOLBAR_CUSTOMIZATION_ENABLED, false);
    }
}
