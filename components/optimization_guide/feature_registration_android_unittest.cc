// Copyright 2026 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "build/build_config.h"

#if BUILDFLAG(IS_ANDROID)
#include "components/optimization_guide/core/feature_registry/enterprise_policy_registry.h"
#include "components/optimization_guide/core/feature_registry/feature_registration.h"
#include "components/optimization_guide/core/model_execution/model_execution_prefs.h"
#include "components/prefs/pref_registry_simple.h"
#include "components/prefs/testing_pref_service.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace optimization_guide {
namespace {
using PolicyValue = model_execution::prefs::ModelExecutionEnterprisePolicyValue;

TEST(AndroidOrdinaryFeatureRegistrationTest,
     PreservesProductSpecificationsDefaultAndRegistrationFlags) {
  TestingPrefServiceSimple pref_service;
  model_execution::prefs::RegisterProfilePrefs(pref_service.registry());
  const auto* preference = pref_service.FindPreference(
      prefs::kProductSpecificationsEnterprisePolicyAllowed);
  ASSERT_NE(nullptr, preference);
  EXPECT_EQ(base::Value::Type::INTEGER, preference->GetType());
  EXPECT_EQ(static_cast<int>(PolicyValue::kAllow),
            pref_service.GetInteger(
                prefs::kProductSpecificationsEnterprisePolicyAllowed));
  EXPECT_EQ(PrefRegistry::LOSSY_PREF,
            pref_service.registry()->GetRegistrationFlags(
                prefs::kProductSpecificationsEnterprisePolicyAllowed));
  EXPECT_EQ(
      PolicyValue::kAllow,
      EnterprisePolicyPref(prefs::kProductSpecificationsEnterprisePolicyAllowed)
          .GetValue(&pref_service));
}

TEST(AndroidOrdinaryFeatureRegistrationTest,
     IndependentProfilesPreserveTypedPolicyValues) {
  TestingPrefServiceSimple first;
  TestingPrefServiceSimple second;
  model_execution::prefs::RegisterProfilePrefs(first.registry());
  model_execution::prefs::RegisterProfilePrefs(second.registry());
  EnterprisePolicyPref policy(
      prefs::kProductSpecificationsEnterprisePolicyAllowed);
  for (PolicyValue value :
       {PolicyValue::kAllow, PolicyValue::kAllowWithoutLogging,
        PolicyValue::kDisable}) {
    first.SetInteger(policy.name(), static_cast<int>(value));
    EXPECT_EQ(value, policy.GetValue(&first));
    EXPECT_EQ(PolicyValue::kAllow, policy.GetValue(&second));
  }
  first.ClearPref(policy.name());
  EXPECT_EQ(PolicyValue::kAllow, policy.GetValue(&first));
  second.SetInteger(policy.name(), static_cast<int>(PolicyValue::kDisable));
  EXPECT_EQ(PolicyValue::kDisable, policy.GetValue(&second));
  EXPECT_EQ(PolicyValue::kAllow, policy.GetValue(&first));
}

TEST(AndroidOrdinaryFeatureRegistrationTest,
     DoesNotRegisterOptionalEnterprisePreferences) {
  TestingPrefServiceSimple pref_service;
  model_execution::prefs::RegisterProfilePrefs(pref_service.registry());
  // Exact names are bound to raw registration. Their Android declarations and
  // implementation are intentionally absent.
  constexpr const char* kOptionalPrefs[] = {
      "optimization_guide.model_execution.compose_enterprise_policy_allowed",
      "optimization_guide.model_execution.wallpaper_search_enterprise_policy_"
      "allowed",
      "optimization_guide.model_execution.history_search_enterprise_policy_"
      "allowed",
      "optimization_guide.model_execution.autofill_prediction_improvements_"
      "enterprise_policy_allowed",
      "optimization_guide.model_execution.password_change_submission_"
      "enterprise_policy_allowed",
      "optimization_guide.model_execution.notification_content_detection_"
      "enterprise_policy_allowed",
      "optimization_guide.model_execution.bling_prototyping_enterprise_policy_"
      "allowed",
      "optimization_guide.model_execution.contextual_tasks_context_enterprise_"
      "policy_allowed",
      "optimization_guide.model_execution.gemini_antiscam_protection_"
      "enterprise_policy_allowed",
      "optimization_guide.model_execution.finds_enterprise_policy_allowed",
      "contextual_cueing.chrome_suggestions_settings",
      "autofill.personal_context.find_and_fill_with_gemini_settings",
  };
  for (const char* path : kOptionalPrefs) {
    SCOPED_TRACE(path);
    EXPECT_EQ(nullptr, pref_service.FindPreference(path));
  }
  ASSERT_NE(nullptr, pref_service.FindPreference(
                         prefs::kProductSpecificationsEnterprisePolicyAllowed));
}
}  // namespace
}  // namespace optimization_guide
#endif  // BUILDFLAG(IS_ANDROID)
