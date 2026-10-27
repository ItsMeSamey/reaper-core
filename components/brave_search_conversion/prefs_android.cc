// Copyright 2026 The Reaper Authors
// SPDX-License-Identifier: MPL-2.0

#include "brave/components/brave_search_conversion/utils.h"

#include "brave/components/brave_search_conversion/pref_names.h"
#include "components/prefs/pref_registry_simple.h"

namespace brave_search_conversion {

// These settings belong to ordinary NTP/search behavior, not search promotion.
void RegisterPrefs(PrefRegistrySimple* registry) {
  registry->RegisterBooleanPref(prefs::kShowNTPSearchBox, true);
  registry->RegisterBooleanPref(prefs::kShowNTPChatInput, true);
  registry->RegisterBooleanPref(prefs::kMigratedNTPChatInputFromSearch, false);
  registry->RegisterStringPref(prefs::kLastUsedNTPSearchEngine,
                               "search.brave.com");
  registry->RegisterBooleanPref(prefs::kPromptEnableSuggestions, true);
}

}  // namespace brave_search_conversion
