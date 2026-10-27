/* Copyright (c) 2023 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "build/build_config.h"

#include "brave/components/request_otr/browser/request_otr_service.h"

#include <utility>

#include "base/containers/flat_set.h"
#include "base/logging.h"
#include "brave/components/request_otr/browser/request_otr_component_installer.h"
#if !BUILDFLAG(IS_ANDROID)
#include "brave/components/request_otr/browser/request_otr_p3a.h"
#endif  // !BUILDFLAG(IS_ANDROID)
#include "brave/components/request_otr/common/pref_names.h"
#include "components/prefs/pref_registry_simple.h"
#include "components/prefs/pref_service.h"
#include "net/base/registry_controlled_domains/registry_controlled_domain.h"
#include "url/gurl.h"
#include "url/origin.h"

namespace request_otr {

namespace {

#if !BUILDFLAG(IS_ANDROID)
constexpr base::TimeDelta kP3AUpdateInterval = base::Days(1);
#endif  // !BUILDFLAG(IS_ANDROID)

}  // namespace

RequestOTRService::RequestOTRService(PrefService* profile_prefs)
#if !BUILDFLAG(IS_ANDROID)
    : profile_prefs_(profile_prefs)
#endif  // !BUILDFLAG(IS_ANDROID)
{
#if !BUILDFLAG(IS_ANDROID)
  UpdateP3AMetrics();
#endif  // !BUILDFLAG(IS_ANDROID)
}

RequestOTRService::~RequestOTRService() = default;

void RequestOTRService::OnRulesReady(const std::string& json_content) {
  auto parsed_rules = RequestOTRRule::ParseRules(json_content);
  if (!parsed_rules.has_value()) {
    DVLOG(1) << "Error: no rules parsed. " << parsed_rules.error();
    return;
  }
  rules_.clear();
  host_cache_.clear();
  rules_ = std::move(parsed_rules.value().first);
  host_cache_ = std::move(parsed_rules).value().second;
  DVLOG(1) << host_cache_.size() << " unique hosts, " << rules_.size()
           << " rules parsed from " << kRequestOTRConfigFile;
}

bool RequestOTRService::ShouldBlock(const GURL& url) const {
  // Check host cache
  const std::string etldp1 = RequestOTRRule::GetETLDForRequestOTR(url.host());
  if (!host_cache_.contains(etldp1)) {
    return false;
  }

  for (const std::unique_ptr<RequestOTRRule>& rule : rules_) {
    if (rule->ShouldBlock(url)) {
      return true;
    }
  }

  return false;
}

#if !BUILDFLAG(IS_ANDROID)
void RequestOTRService::UpdateP3AMetrics() {
  p3a::UpdateMetrics(profile_prefs_);
  p3a_timer_.Start(FROM_HERE, base::Time::Now() + kP3AUpdateInterval,
                   base::BindRepeating(&RequestOTRService::UpdateP3AMetrics,
                                       base::Unretained(this)));
}
#endif  // !BUILDFLAG(IS_ANDROID)

// static
void RequestOTRService::RegisterProfilePrefs(PrefRegistrySimple* registry) {
  registry->RegisterIntegerPref(kRequestOTRActionOption,
                                static_cast<int>(RequestOTRActionOption::kAsk));
#if !BUILDFLAG(IS_ANDROID)
  p3a::RegisterProfilePrefs(registry);
#endif  // !BUILDFLAG(IS_ANDROID)
}

}  // namespace request_otr
