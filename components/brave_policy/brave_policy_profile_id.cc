/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "build/build_config.h"

#include "base/base64url.h"
#include "base/files/file_path.h"
#if !BUILDFLAG(IS_ANDROID)
#include "brave/components/brave_origin/profile_id.h"
#endif  // !BUILDFLAG(IS_ANDROID)
#include "brave/components/brave_policy/brave_profile_policy_provider.h"
#include "components/policy/core/common/configuration_policy_provider.h"

namespace brave_policy {

// This is the definition of a declaration a chromium_src for setting
// the profile ID on a profile's BraveProfilePolicyProvider.
// The declaration is in
// chromium_src/chrome/browser/policy/profile_policy_connector_builder.cc
void SetBraveProfilePolicyProviderProfileID(
    policy::ConfigurationPolicyProvider* provider,
    const base::FilePath& profile_path) {
#if BUILDFLAG(IS_ANDROID)
  // Preserve the policy profile key without linking the Origin product.
  std::string profile_name = profile_path.BaseName().AsUTF8Unsafe();
  if (profile_name.empty()) {
    profile_name = "Default";
  }
  std::string profile_id;
  base::Base64UrlEncode(profile_name,
                        base::Base64UrlEncodePolicy::OMIT_PADDING, &profile_id);
#else
  auto profile_id = ::brave_origin::GetProfileId(profile_path);
#endif
  static_cast<BraveProfilePolicyProvider*>(provider)->SetProfileID(profile_id);
}

}  // namespace brave_policy
