/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "build/build_config.h"
#if BUILDFLAG(IS_ANDROID)
#include "chrome/browser/extensions/component_loader.h"
#define BraveComponentLoader ComponentLoader
#else
#include "brave/browser/extensions/brave_component_loader.h"
#endif

#include <chrome/browser/extensions/component_loader_factory.cc>

#if BUILDFLAG(IS_ANDROID)
#undef BraveComponentLoader
#endif
