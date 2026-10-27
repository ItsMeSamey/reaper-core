// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#ifndef BRAVE_BROWSER_EXTENSIONS_ANDROID_EXTENSION_TEST_UTILS_H_
#define BRAVE_BROWSER_EXTENSIONS_ANDROID_EXTENSION_TEST_UTILS_H_

#include <string>
#include <vector>

#include "base/android/scoped_java_ref.h"

namespace content {
class WebContents;
}  // namespace content

namespace extensions::android_test {

// Keep generated JNI calls in ExtensionTestUtils's existing native owner.
// Returned Java handles retain the exact dialog identity used by the tests.
base::android::ScopedJavaLocalRef<jobject> CaptureInstallDialogForTesting(
    JNIEnv* env,
    content::WebContents* sender);

std::vector<std::u16string> GetInstallDialogPermissionsForTesting(
    JNIEnv* env,
    const base::android::JavaRef<jobject>& dialog);

bool ClickInstallDialogForTesting(
    JNIEnv* env,
    const base::android::JavaRef<jobject>& dialog,
    bool accept);

void DismissInstallDialogForTesting(
    JNIEnv* env,
    const base::android::JavaRef<jobject>& dialog);

base::android::ScopedJavaLocalRef<jobject> CaptureDownloadOpenDialogForTesting(
    JNIEnv* env,
    content::WebContents* sender,
    const std::u16string& expected_title,
    bool include_pending);

std::vector<std::u16string> GetDownloadOpenDialogParagraphsForTesting(
    JNIEnv* env,
    const base::android::JavaRef<jobject>& dialog);

bool ClickDownloadOpenDialogForTesting(
    JNIEnv* env,
    const base::android::JavaRef<jobject>& dialog,
    bool accept);

void DismissDownloadOpenDialogForTesting(
    JNIEnv* env,
    const base::android::JavaRef<jobject>& dialog);

}  // namespace extensions::android_test

#endif  // BRAVE_BROWSER_EXTENSIONS_ANDROID_EXTENSION_TEST_UTILS_H_
