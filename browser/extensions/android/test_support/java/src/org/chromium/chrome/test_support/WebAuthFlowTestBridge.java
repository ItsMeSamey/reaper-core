// Copyright 2026 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.test_support;

import org.jni_zero.CalledByNative;

import org.chromium.components.signin.AccountManagerFacade;
import org.chromium.components.signin.AccountManagerFacadeProvider;

/** Fixture-only bridge for preserving the effective facade around TestingProfile creation. */
public final class WebAuthFlowTestBridge {
    private WebAuthFlowTestBridge() {}

    @CalledByNative
    public static Object getAccountManagerFacade() {
        return AccountManagerFacadeProvider.getInstance();
    }

    @CalledByNative
    public static void restoreAccountManagerFacade(Object facade) {
        AccountManagerFacadeProvider.setInstanceForTests((AccountManagerFacade) facade);
    }
}
