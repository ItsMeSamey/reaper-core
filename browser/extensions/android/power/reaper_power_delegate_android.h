// Copyright 2026 The Reaper Authors.
// SPDX-License-Identifier: MPL-2.0

#ifndef BRAVE_BROWSER_EXTENSIONS_ANDROID_POWER_REAPER_POWER_DELEGATE_ANDROID_H_
#define BRAVE_BROWSER_EXTENSIONS_ANDROID_POWER_REAPER_POWER_DELEGATE_ANDROID_H_

#include <memory>

namespace content {
class BrowserContext;
}
namespace extensions {
class AndroidPowerDelegate;
std::unique_ptr<AndroidPowerDelegate> CreateReaperAndroidPowerDelegate(
    content::BrowserContext* context);
}  // namespace extensions

#endif  // BRAVE_BROWSER_EXTENSIONS_ANDROID_POWER_REAPER_POWER_DELEGATE_ANDROID_H_
