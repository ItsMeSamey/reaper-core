/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "build/build_config.h"
#if BUILDFLAG(IS_ANDROID)
#include "chrome/browser/extensions/api/chrome_extensions_api_client.h"
#define BraveExtensionsAPIClient ChromeExtensionsAPIClient
#else
#include "brave/browser/extensions/api/brave_extensions_api_client.h"
#endif
#include "brave/browser/extensions/updater/brave_update_client_config.h"
#include "extensions/common/extension_features.h"

#include <chrome/browser/extensions/chrome_extensions_browser_client.cc>

#if BUILDFLAG(IS_ANDROID)
#undef BraveExtensionsAPIClient
#endif
