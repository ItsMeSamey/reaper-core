/* Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */
package org.chromium.chrome.browser;

import org.chromium.base.JavaUtils;
import org.chromium.base.Log;
import org.chromium.build.annotations.NullMarked;
import org.chromium.chrome.browser.base.SplitCompatApplication;
import org.chromium.chrome.browser.profiles.OriginalProfileSupplier;
import org.chromium.chrome.browser.settings.BraveSearchEngineUtils;
import org.chromium.components.safe_browsing.BraveSafeBrowsingApiHandler;
import org.chromium.components.safe_browsing.SafeBrowsingApiBridge;
import org.chromium.mojo.bindings.BadMessageException;
import org.chromium.mojo.bindings.ExceptionHandler;

@NullMarked
public class BraveApplicationImplBase extends SplitCompatApplication.Impl {
    private static final String TAG = "BraveApp";

    @Override
    public void onCreate() {
        super.onCreate();
        if (SplitCompatApplication.isBrowserProcess()) {
            // Handle Mojo BadMessageException gracefully by closing the pipe instead of
            // crashing. This matches the C++ behavior in Connector::DispatchMessage
            // (mojo/public/cpp/bindings/lib/connector.cc) where a failed Accept() calls
            // HandleError() to reset the pipe — no crash, just clean pipe closure.
            // Upstream Chromium introduced BadMessageException in Java bindings in 146
            // (crbug.com/469861566,
            // https://github.com/chromium/chromium/commit/c04b8552deeef) but left the
            // default handler as a rethrow, causing crashes in race conditions during
            // teardown. When upstream finishes their TODO in Connector.java they will
            // likely handle BadMessageException directly in the catch block rather than
            // via this delegate, at which point this handler becomes a harmless no-op
            // and can be removed.
            ExceptionHandler.DefaultExceptionHandler.getInstance()
                    .setDelegate(
                            (Throwable e) -> {
                                if (e instanceof BadMessageException) {
                                    Log.w(TAG, "Mojo BadMessageException, closing pipe", e);
                                    return false;
                                }
                                throw JavaUtils.throwUnchecked(e);
                            });
            // Set a handler for SafeBrowsing. It has to be done only once for a process lifetime.
            SafeBrowsingApiBridge.setSafeBrowsingApiHandler(
                    BraveSafeBrowsingApiHandler.getInstance());

            // Apply Brave's default search engine as soon as the profile exists, instead of
            // waiting for BraveActivity. Entry points such as the search widget resolve queries
            // through SearchActivity, which never runs BraveActivity and would otherwise search
            // with whatever engine the country default picked.
            new OriginalProfileSupplier()
                    .onAvailable(BraveSearchEngineUtils::initializeOnProfileAdded);

        }
    }
}
