// Copyright (c) 2025 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "brave/browser/android/brave_tab_features.h"

#include "chrome/browser/profiles/profile.h"
#include "content/public/browser/web_contents.h"

namespace tabs {

BraveTabFeatures::BraveTabFeatures(content::WebContents* web_contents,
                                   Profile* profile)
    : TabFeatures_Chromium(web_contents, profile) {

}

BraveTabFeatures::~BraveTabFeatures() = default;

TabFeatures::TabFeatures(content::WebContents* web_contents, Profile* profile)
    : brave_tab_features_(
          std::make_unique<BraveTabFeatures>(web_contents, profile)) {}

TabFeatures::~TabFeatures() = default;

NewTabPagePreloadPipelineManager*
TabFeatures::new_tab_page_preload_pipeline_manager() {
  return brave_tab_features_->new_tab_page_preload_pipeline_manager();
}

enterprise_data_protection::DataProtectionNavigationController*
TabFeatures::data_protection_controller() {
  return brave_tab_features_->data_protection_controller();
}

// static
BraveTabFeatures* BraveTabFeatures::FromTabFeatures(TabFeatures* tab_features) {
  return static_cast<BraveTabFeatures*>(
      tab_features->brave_tab_features_.get());
}

}  // namespace tabs
