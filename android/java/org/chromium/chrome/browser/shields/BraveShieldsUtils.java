/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.shields;

import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;

public class BraveShieldsUtils {
    public static final String PREF_SHIELDS_TOOLTIP = "shields_tooltip";
    public static boolean isTooltipShown;

    public static boolean hasShieldsTooltipShown(String tooltipType) {
        return ChromeSharedPreferences.getInstance().readBoolean(tooltipType, false);
    }

    public static void setShieldsTooltipShown(String tooltipType, boolean isShown) {
        ChromeSharedPreferences.getInstance().writeBoolean(tooltipType, isShown);
    }

}
