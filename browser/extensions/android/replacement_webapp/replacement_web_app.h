// Copyright 2026 The Reaper Authors.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef BRAVE_BROWSER_EXTENSIONS_ANDROID_REPLACEMENT_WEBAPP_REPLACEMENT_WEB_APP_H_
#define BRAVE_BROWSER_EXTENSIONS_ANDROID_REPLACEMENT_WEBAPP_REPLACEMENT_WEB_APP_H_

#include "extensions/browser/api/management/management_api_delegate.h"

namespace extensions {

bool CanInstallAndroidReplacementWebApp(content::BrowserContext* context);
void InstallAndroidReplacementWebApp(
    content::BrowserContext* context,
    const GURL& url,
    ManagementAPIDelegate::InstallOrLaunchWebAppCallback callback);

}  // namespace extensions

#endif  // BRAVE_BROWSER_EXTENSIONS_ANDROID_REPLACEMENT_WEBAPP_REPLACEMENT_WEB_APP_H_
