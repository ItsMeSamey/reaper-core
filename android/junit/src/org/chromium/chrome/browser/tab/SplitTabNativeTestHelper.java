// Copyright 2026 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.tab;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.robolectric.util.ReflectionHelpers;

import org.chromium.base.ObserverList;
import org.chromium.base.supplier.ObservableSuppliers;
import org.chromium.content_public.browser.WebContents;

/** Keeps the package-private TabImpl JNI test boundary within its owning package. */
public final class SplitTabNativeTestHelper {
    private final TabImpl.Natives mNative = mock(TabImpl.Natives.class);

    public SplitTabNativeTestHelper() {
        TabImplJni.setInstanceForTesting(mNative);
    }

    /** Initializes only the constructor state needed by the unmockable final hide() method. */
    public void prepareForRealHide(Tab tab) {
        TabImpl implementation = (TabImpl) tab;
        ReflectionHelpers.setField(implementation, "mObservers", new ObserverList<TabObserver>());
        ReflectionHelpers.setField(
                implementation,
                "mIsOffscreenRenderingSupplier",
                ObservableSuppliers.createNonNull(false));
        ReflectionHelpers.setField(implementation, "mIsHidden", false);
        when(implementation.isHidden()).thenCallRealMethod();
    }

    public Tab createTab(WebContents contents) {
        TabImpl tab = mock(TabImpl.class);
        when(mNative.fromWebContents(contents)).thenReturn(tab);
        return tab;
    }
}
