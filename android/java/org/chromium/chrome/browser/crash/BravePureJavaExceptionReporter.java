/* Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.crash;

import org.chromium.build.annotations.UsedByReflection;

import java.io.File;

@UsedByReflection("SplitCompatApplication.java")
public class BravePureJavaExceptionReporter extends ChromePureJavaExceptionReporter {
    @UsedByReflection("SplitCompatApplication.java")
    public BravePureJavaExceptionReporter() {
        super();
    }

    @Override
    protected void uploadMinidump(File minidump) {
        // The inherited reporter already preserved the local dump and sanitized logcat.
    }
}
