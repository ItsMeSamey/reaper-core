// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.

package org.chromium.chrome.browser.containers;

import android.content.Context;
import android.view.View;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.tab.Tab;

/** Persistent current-tab color identity, including lazy and restored container tabs. */
@NullMarked
public final class ContainerToolbarIndicator extends View {
    public ContainerToolbarIndicator(Context context) {
        super(context);
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void update(@Nullable Tab tab) {
        ContainerTabBadge.Info info = ContainerTabBadge.forTab(getContext(), tab);
        if (info == null) {
            setVisibility(GONE);
            setContentDescription(null);
            setTooltipText(null);
            return;
        }
        setBackgroundColor(info.color);
        setContentDescription(info.description);
        setTooltipText(info.description);
        setVisibility(VISIBLE);
    }
}
