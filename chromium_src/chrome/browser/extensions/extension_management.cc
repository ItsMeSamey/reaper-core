/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "build/build_config.h"
#if BUILDFLAG(IS_ANDROID)
#include "chrome/browser/extensions/extension_management.h"
#define BraveExtensionManagement ExtensionManagement
#else
#include "brave/browser/extensions/brave_extension_management.h"
#endif

#include <chrome/browser/extensions/extension_management.cc>

#if BUILDFLAG(IS_ANDROID)
#undef BraveExtensionManagement
#endif
