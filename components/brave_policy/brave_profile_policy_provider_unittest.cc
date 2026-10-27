/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/components/brave_policy/brave_profile_policy_provider.h"

#include "build/build_config.h"

#include <memory>

#include "base/test/task_environment.h"
#if BUILDFLAG(IS_ANDROID)
#include "base/test/scoped_feature_list.h"
#include "brave/components/brave_policy/ad_block_only_mode/buildflags/buildflags.h"
#include "brave/components/brave_shields/core/common/features.h"
#include "brave/components/brave_shields/core/common/pref_names.h"
#include "components/content_settings/core/common/content_settings.h"
#include "components/policy/policy_constants.h"
#include "components/prefs/pref_registry_simple.h"
#endif
#if !BUILDFLAG(IS_ANDROID)
#include "brave/components/brave_origin/brave_origin_policy_manager.h"
#include "brave/components/brave_origin/brave_origin_prefs.h"
#endif  // !BUILDFLAG(IS_ANDROID)
#include "components/policy/core/common/policy_bundle.h"
#include "components/policy/core/common/policy_namespace.h"
#include "components/policy/core/common/policy_types.h"
#include "components/policy/core/common/schema_registry.h"
#include "components/prefs/testing_pref_service.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace brave_policy {

class BraveProfilePolicyProviderTest : public ::testing::Test {
 public:
  BraveProfilePolicyProviderTest() {
#if !BUILDFLAG(IS_ANDROID)
    brave_origin::RegisterLocalStatePrefs(local_state_.registry());
#endif  // !BUILDFLAG(IS_ANDROID)
  }
  ~BraveProfilePolicyProviderTest() override = default;

  void TearDown() override {
    provider_.Shutdown();
#if BUILDFLAG(IS_ANDROID)
    AdBlockOnlyModePolicyManager::GetInstance()->Shutdown();
#endif
#if !BUILDFLAG(IS_ANDROID)
    // Reset the process-wide singleton so its state does not leak between
    // tests. `BraveOriginPolicyManager::Shutdown()` is safe to call even if
    // `Init()` was never called.
    brave_origin::BraveOriginPolicyManager::GetInstance()->Shutdown();
#endif  // !BUILDFLAG(IS_ANDROID)
  }

 protected:
  base::test::TaskEnvironment task_environment_;
  policy::SchemaRegistry schema_registry_;
  TestingPrefServiceSimple local_state_;
  BraveProfilePolicyProvider provider_;
};

TEST_F(BraveProfilePolicyProviderTest, InitAndPolicyLoadComplete) {
  // Initially, policies should not be loaded
  EXPECT_FALSE(
      provider_.IsFirstPolicyLoadComplete(policy::POLICY_DOMAIN_CHROME));

  // Initialize the provider
  provider_.Init(&schema_registry_);

  // Policies should still not be loaded until observer event fires
  EXPECT_FALSE(
      provider_.IsFirstPolicyLoadComplete(policy::POLICY_DOMAIN_CHROME));

  // Fire the observer event and set profile ID to trigger policy loading
  provider_.OnBravePoliciesReady();
  provider_.SetProfileID("test-profile-id");

  // Now policies should be loaded
  EXPECT_TRUE(
      provider_.IsFirstPolicyLoadComplete(policy::POLICY_DOMAIN_CHROME));
}

#if BUILDFLAG(IS_ANDROID)
TEST_F(BraveProfilePolicyProviderTest, NoOriginInitializationGate) {
  base::test::ScopedFeatureList features;
  features.InitAndEnableFeature(brave_shields::features::kAdblockOnlyMode);
  local_state_.registry()->RegisterBooleanPref(
      brave_shields::prefs::kAdBlockOnlyModeEnabled, false);
  auto* manager = AdBlockOnlyModePolicyManager::GetInstance();
  manager->Init(&local_state_);

  EXPECT_TRUE(provider_.IsInitializationComplete(policy::POLICY_DOMAIN_CHROME));
  provider_.Init(&schema_registry_);
  EXPECT_FALSE(provider_.IsFirstPolicyLoadComplete(policy::POLICY_DOMAIN_CHROME));
  provider_.SetProfileID("test-profile-id");
  EXPECT_TRUE(provider_.IsInitializationComplete(policy::POLICY_DOMAIN_CHROME));
  EXPECT_TRUE(provider_.IsFirstPolicyLoadComplete(policy::POLICY_DOMAIN_CHROME));

  const policy::PolicyNamespace chrome_namespace(policy::POLICY_DOMAIN_CHROME,
                                                  std::string());
  EXPECT_TRUE(provider_.policies().Get(chrome_namespace).empty());
  local_state_.SetBoolean(brave_shields::prefs::kAdBlockOnlyModeEnabled, true);
#if BUILDFLAG(ENABLE_AD_BLOCK_ONLY_MODE_POLICIES)
  const auto* value = provider_.policies().Get(chrome_namespace).GetValue(
      policy::key::kDefaultBraveAdblockSetting, base::Value::Type::INTEGER);
  ASSERT_TRUE(value);
  EXPECT_EQ(value->GetInt(), CONTENT_SETTING_BLOCK);
#endif
  local_state_.SetBoolean(brave_shields::prefs::kAdBlockOnlyModeEnabled, false);
  EXPECT_TRUE(provider_.policies().Get(chrome_namespace).empty());
}
#else
TEST_F(BraveProfilePolicyProviderTest, IsInitializationCompleteGated) {
  auto* manager = brave_origin::BraveOriginPolicyManager::GetInstance();
  // On desktop/Android the override short-circuits to true when the manager
  // has not been declared as expected-to-initialize (see the `#if !IS_IOS`
  // block in `IsInitializationComplete`). Set the flag so the gating path
  // below is exercised on all platforms.
  manager->SetExpectedToBeInitialized();

  // Before the manager is initialized, the override gates initialisation on
  // `IsInitialized()` so consumers do not observe an empty managed pref
  // store before Brave Origin policies have been merged.
  EXPECT_FALSE(provider_.IsInitializationComplete(policy::POLICY_DOMAIN_CHROME))
      << "Before provider Init: manager not initialised, no refresh";

  provider_.Init(&schema_registry_);
  EXPECT_FALSE(provider_.IsInitializationComplete(policy::POLICY_DOMAIN_CHROME))
      << "After provider Init: manager still not initialised";

  // Initializing the manager flips `IsInitialized()` and synchronously
  // notifies our observer, but `RefreshPolicies` is gated on a non-empty
  // `profile_id_`, so `first_policies_loaded_` is still false.
  manager->Init(/*browser_policy_definitions=*/{},
                /*profile_policy_definitions=*/{}, &local_state_);
  EXPECT_FALSE(provider_.IsInitializationComplete(policy::POLICY_DOMAIN_CHROME))
      << "After manager Init: profile_id_ still empty, refresh has not run";

  // Setting the profile ID drives the first refresh.
  provider_.SetProfileID("test-profile-id");
  EXPECT_TRUE(provider_.IsInitializationComplete(policy::POLICY_DOMAIN_CHROME))
      << "After SetProfileID: first refresh has run and manager is "
         "initialised";
}

#endif

TEST_F(BraveProfilePolicyProviderTest, EmptyPolicyBundle) {
  // Initialize the provider
  provider_.Init(&schema_registry_);

  // Fire the observer event and set profile ID to trigger policy loading
  provider_.OnBravePoliciesReady();
  provider_.SetProfileID("test-profile-id");

  // Get the policy bundle
  const policy::PolicyBundle& bundle = provider_.policies();

  // Currently the provider returns an empty bundle since no policies are
  // implemented yet
  const policy::PolicyMap& chrome_policies = bundle.Get(
      policy::PolicyNamespace(policy::POLICY_DOMAIN_CHROME, std::string()));

  EXPECT_TRUE(chrome_policies.empty())
      << "Policy bundle should be empty as no policies are implemented yet";
}

TEST_F(BraveProfilePolicyProviderTest, RefreshPolicies) {
  // Initialize the provider
  provider_.Init(&schema_registry_);

  // Fire the observer event and set profile ID to trigger policy loading
  provider_.OnBravePoliciesReady();
  provider_.SetProfileID("test-profile-id");

  // Policies should be loaded after observer event
  EXPECT_TRUE(
      provider_.IsFirstPolicyLoadComplete(policy::POLICY_DOMAIN_CHROME));

  // Refresh policies with different reasons
  provider_.RefreshPolicies(policy::PolicyFetchReason::kUnspecified);
  EXPECT_TRUE(
      provider_.IsFirstPolicyLoadComplete(policy::POLICY_DOMAIN_CHROME));

  provider_.RefreshPolicies(policy::PolicyFetchReason::kTest);
  EXPECT_TRUE(
      provider_.IsFirstPolicyLoadComplete(policy::POLICY_DOMAIN_CHROME));
}

TEST_F(BraveProfilePolicyProviderTest, ShutdownHandling) {
  // Initialize the provider
  provider_.Init(&schema_registry_);

  // Fire the observer event and set profile ID to trigger policy loading
  provider_.OnBravePoliciesReady();
  provider_.SetProfileID("test-profile-id");

  // Verify initialized state
  EXPECT_TRUE(
      provider_.IsFirstPolicyLoadComplete(policy::POLICY_DOMAIN_CHROME));

  // Shutdown should complete without errors
  provider_.Shutdown();

  // Provider should still report policies as loaded even after shutdown
  EXPECT_TRUE(
      provider_.IsFirstPolicyLoadComplete(policy::POLICY_DOMAIN_CHROME));
}

TEST_F(BraveProfilePolicyProviderTest, PolicyDomainHandling) {
  // Initialize the provider
  provider_.Init(&schema_registry_);

  // Fire the observer event and set profile ID to trigger policy loading
  provider_.OnBravePoliciesReady();
  provider_.SetProfileID("test-profile-id");

  // Test policy load complete for different domains
  EXPECT_TRUE(
      provider_.IsFirstPolicyLoadComplete(policy::POLICY_DOMAIN_CHROME));
  EXPECT_TRUE(
      provider_.IsFirstPolicyLoadComplete(policy::POLICY_DOMAIN_EXTENSIONS));
  EXPECT_TRUE(provider_.IsFirstPolicyLoadComplete(
      policy::POLICY_DOMAIN_SIGNIN_EXTENSIONS));

  // The provider should report policies as loaded for all domains since it uses
  // first_policies_loaded_ flag uniformly
}

TEST_F(BraveProfilePolicyProviderTest, OnProfilePolicyChanged_MatchingProfile) {
  // Initialize the provider
  provider_.Init(&schema_registry_);

  // Fire the observer event and set profile ID to trigger policy loading
  provider_.OnBravePoliciesReady();
  provider_.SetProfileID("test-profile-id");

  // Call OnProfilePolicyChanged with matching profile ID
  provider_.OnProfilePolicyChanged("test.pref.policy", "test-profile-id");

  // Provider should still report policies as loaded
  EXPECT_TRUE(
      provider_.IsFirstPolicyLoadComplete(policy::POLICY_DOMAIN_CHROME));
}

TEST_F(BraveProfilePolicyProviderTest,
       OnProfilePolicyChanged_DifferentProfile) {
  // Initialize the provider
  provider_.Init(&schema_registry_);

  // Fire the observer event and set profile ID to trigger policy loading
  provider_.OnBravePoliciesReady();
  provider_.SetProfileID("test-profile-id");

  // Call OnProfilePolicyChanged with different profile ID
  provider_.OnProfilePolicyChanged("test.pref.policy", "different-profile-id");

  // Provider should still report policies as loaded (no action taken)
  EXPECT_TRUE(
      provider_.IsFirstPolicyLoadComplete(policy::POLICY_DOMAIN_CHROME));
}

TEST_F(BraveProfilePolicyProviderTest, OnProfilePolicyChanged_EmptyProfileId) {
  // Initialize the provider without setting profile ID
  provider_.Init(&schema_registry_);
  provider_.OnBravePoliciesReady();

  // Call OnProfilePolicyChanged (should not crash or affect anything)
  provider_.OnProfilePolicyChanged("test.pref.policy", "some-profile-id");

  // Provider should still report no policies loaded since no profile ID set
  EXPECT_FALSE(
      provider_.IsFirstPolicyLoadComplete(policy::POLICY_DOMAIN_CHROME));
}

}  // namespace brave_policy
