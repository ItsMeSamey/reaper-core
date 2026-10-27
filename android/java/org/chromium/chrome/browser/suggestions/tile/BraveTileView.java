/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.suggestions.tile;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.VisibleForTesting;

import org.chromium.build.annotations.NullMarked;
import org.chromium.chrome.browser.ntp.NtpUtil;
import org.chromium.components.browser_ui.widget.tile.TileView;

@NullMarked
public class BraveTileView extends TileView {
    public BraveTileView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @VisibleForTesting
    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
    }

    @Override
    public void togglePinnedShortcutBadge(boolean isVisible) {
        // In "Show shortcuts" mode every tile is a custom link, so the badge that distinguishes
        // manually-added shortcuts from frequently-visited ones is redundant on every tile. Only
        // show it in the mode where both kinds of tile can appear together.
        super.togglePinnedShortcutBadge(
                isVisible && NtpUtil.getTopSitesDisplayMode() != NtpUtil.TOP_SITES_MODE_SHORTCUTS);
    }

}
