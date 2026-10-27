// Copyright (c) 2025 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "components/omnibox/browser/omnibox_text_util.h"

#include "build/build_config.h"
#include "url/gurl.h"

#if !BUILDFLAG(IS_IOS) && !BUILDFLAG(IS_ANDROID)
#include "content/public/common/url_constants.h"
#endif

namespace {
void BraveAdjustTextForCopy(GURL* url) {
#if !BUILDFLAG(IS_IOS) && !BUILDFLAG(IS_ANDROID)
  if (url->scheme() == content::kChromeUIScheme) {
    GURL::Replacements replacements;
    replacements.SetSchemeStr(content::kBraveUIScheme);
    *url = url->ReplaceComponents(replacements);
  }
#endif
}

}  // namespace

#define BRAVE_ADJUST_TEXT_FOR_COPY BraveAdjustTextForCopy(url_from_text);

#include <components/omnibox/browser/omnibox_text_util.cc>

#undef BRAVE_ADJUST_TEXT_FOR_COPY
