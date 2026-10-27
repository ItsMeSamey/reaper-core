// Copyright 2026 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.tabmodel;

import androidx.annotation.IntDef;

import org.chromium.base.Token;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/** Immutable snapshot of one real native split collection, in tab-strip order. */
@NullMarked
public final class SplitInfo {
    public static final int SIDE_BY_SIDE = 0;
    public static final int STACKED = 1;

    @IntDef({SIDE_BY_SIDE, STACKED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Layout {}

    private final Token mToken;
    private final int mFirstTabId;
    private final int mSecondTabId;
    private final @Layout int mLayout;
    private final double mRatio;

    public SplitInfo(Token token, int firstTabId, int secondTabId, int layout, double ratio) {
        if (token.isZero() || firstTabId == secondTabId || firstTabId < 0 || secondTabId < 0
                || (layout != SIDE_BY_SIDE && layout != STACKED)
                || !Double.isFinite(ratio) || ratio <= 0 || ratio >= 1) {
            throw new IllegalArgumentException("Invalid split state");
        }
        mToken = token;
        mFirstTabId = firstTabId;
        mSecondTabId = secondTabId;
        mLayout = layout == STACKED ? STACKED : SIDE_BY_SIDE;
        mRatio = ratio;
    }

    public Token getToken() { return mToken; }
    public int getFirstTabId() { return mFirstTabId; }
    public int getSecondTabId() { return mSecondTabId; }
    public @Layout int getLayout() { return mLayout; }
    public double getRatio() { return mRatio; }
    public boolean contains(int tabId) { return tabId == mFirstTabId || tabId == mSecondTabId; }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof SplitInfo)) return false;
        SplitInfo split = (SplitInfo) other;
        return mToken.equals(split.mToken) && mFirstTabId == split.mFirstTabId
                && mSecondTabId == split.mSecondTabId && mLayout == split.mLayout
                && Double.compare(mRatio, split.mRatio) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mToken, mFirstTabId, mSecondTabId, mLayout, mRatio);
    }
}
