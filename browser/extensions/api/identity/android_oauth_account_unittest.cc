/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include <memory>
#include <set>
#include <string>
#include <utility>

#include "base/functional/bind.h"
#include "base/functional/callback_helpers.h"
#include "base/memory/scoped_refptr.h"
#include "base/time/time.h"
#include "base/values.h"
#include "build/build_config.h"
#include "chrome/browser/extensions/api/identity/extension_token_key.h"
#include "chrome/browser/extensions/api/identity/identity_api.h"
#include "chrome/browser/extensions/api/identity/identity_get_auth_token_function.h"
#include "chrome/browser/extensions/api/identity/identity_mint_queue.h"
#include "chrome/browser/extensions/api/identity/identity_token_cache.h"
#include "chrome/browser/extensions/extension_api_unittest.h"
#include "chrome/browser/signin/identity_test_environment_profile_adaptor.h"
#include "components/keyed_service/core/keyed_service.h"
#include "components/signin/public/base/consent_level.h"
#include "components/signin/public/identity_manager/account_info.h"
#include "extensions/browser/event_router.h"
#include "extensions/browser/event_router_factory.h"
#include "extensions/browser/extension_function.h"
#include "extensions/browser/extension_prefs.h"
#include "extensions/common/extension.h"
#include "extensions/common/extension_builder.h"
#include "google_apis/google_api_keys.h"
#include "testing/gtest/include/gtest/gtest.h"

static_assert(BUILDFLAG(IS_ANDROID));

namespace extensions {
namespace {

constexpr char kCachedToken[] = "cached-default-account-token";
constexpr char kUnsupportedAccountError[] =
    "OAuth2 request failed: Account selection is not supported by Android "
    "browser-based OAuth.";

std::set<std::string> RequestedScopes() {
  return {"read", "write"};
}

class AndroidOAuthAccountTest : public ExtensionApiUnittest {
 protected:
  void SetUp() override {
    ExtensionApiUnittest::SetUp();
    ASSERT_FALSE(google_apis::IsGoogleChromeAPIKeyUsed());

    // The base fixture installs IdentityTestEnvironment's IdentityManager and
    // TestSigninClient factories. Use their existing profile-backed services.
    identity_environment_ =
        std::make_unique<IdentityTestEnvironmentProfileAdaptor>(profile());
    const auto primary_account =
        identity_environment_->identity_test_env()->MakePrimaryAccountAvailable(
            "primary@example.test", signin::ConsentLevel::kSignin);
    primary_gaia_id_ = primary_account.GetGaiaId().ToString();

    // Both services normally return null while testing. Construct their real
    // implementations through the same factories used by the API function.
    EventRouterFactory::GetInstance()->SetTestingFactoryAndUse(
        profile(), base::BindRepeating([](content::BrowserContext* context)
                                           -> std::unique_ptr<KeyedService> {
          return std::make_unique<EventRouter>(context,
                                               ExtensionPrefs::Get(context));
        }));
    IdentityAPI::GetFactoryInstance()->SetTestingFactoryAndUse(
        profile(), base::BindRepeating([](content::BrowserContext* context)
                                           -> std::unique_ptr<KeyedService> {
          return std::make_unique<IdentityAPI>(context);
        }));
    ASSERT_NE(nullptr, identity_api());

    set_extension(
        ExtensionBuilder("Android OAuth account boundary")
            .SetManifestVersion(3)
            .AddAPIPermission("identity")
            .SetManifestKey(
                "oauth2",
                base::DictValue()
                    .Set("client_id", "test-client-id")
                    .Set("scopes",
                         base::ListValue().Append("read").Append("write")))
            .Build());
    ASSERT_NE(nullptr, extension());

    // Browser-based OAuth uses an empty account key, including when Chrome has
    // a primary account. A real cache hit avoids provider requests or UI even
    // if the explicit-account regression causes the function to ignore its ID.
    identity_api()->token_cache()->SetToken(
        DefaultTokenKey(),
        IdentityTokenCacheValue::CreateToken(kCachedToken, RequestedScopes(),
                                             base::Hours(1)));
  }

  void TearDown() override {
    // The factory-owned profile and IdentityManager must outlive the adaptor.
    identity_environment_.reset();
    ExtensionApiUnittest::TearDown();
  }

  IdentityAPI* identity_api() {
    return IdentityAPI::GetFactoryInstance()->Get(profile());
  }

  ExtensionTokenKey DefaultTokenKey() {
    return ExtensionTokenKey(extension()->id(), CoreAccountInfo(),
                             RequestedScopes());
  }

  scoped_refptr<IdentityGetAuthTokenFunction> RunSynchronously(
      base::ListValue arguments) {
    auto function = base::MakeRefCounted<IdentityGetAuthTokenFunction>();
    function->set_extension(extension());
    function->SetBrowserContextForTesting(profile());
    function->SetArgs(std::move(arguments));
    function->set_has_callback(true);
    function->set_response_callback(base::DoNothing());
    function->preserve_results_for_testing();
    function->RunWithValidation().Execute();
    // Do not pump tasks: account validation and the seeded cache path must
    // respond in this call. The callback owns no stack state if this fails.
    EXPECT_TRUE(function->did_respond());
    return function;
  }

  void ExpectUnchangedCacheAndEmptyQueue() {
    const auto key = DefaultTokenKey();
    const auto& entry = identity_api()->token_cache()->GetToken(key);
    ASSERT_EQ(IdentityTokenCacheValue::CACHE_STATUS_TOKEN, entry.status());
    EXPECT_EQ(kCachedToken, entry.token());
    EXPECT_EQ(RequestedScopes(), entry.granted_scopes());
    EXPECT_TRUE(identity_api()->mint_queue()->empty(
        IdentityMintRequestQueue::MINT_TYPE_NONINTERACTIVE, key));
    EXPECT_TRUE(identity_api()->mint_queue()->empty(
        IdentityMintRequestQueue::MINT_TYPE_INTERACTIVE, key));
  }

  void ExpectAccountRejected(const std::string& account_id) {
    auto function =
        RunSynchronously(base::ListValue().Append(base::DictValue().Set(
            "account", base::DictValue().Set("id", account_id))));
    ASSERT_TRUE(function->did_respond());
    ASSERT_NE(nullptr, function->response_type());
    EXPECT_EQ(ExtensionFunction::ResponseType::kFailed,
              *function->response_type());
    EXPECT_EQ(kUnsupportedAccountError, function->GetError());
    ExpectUnchangedCacheAndEmptyQueue();
  }

  void ExpectCachedToken(base::ListValue arguments) {
    auto function = RunSynchronously(std::move(arguments));
    ASSERT_TRUE(function->did_respond());
    ASSERT_NE(nullptr, function->response_type());
    ASSERT_EQ(ExtensionFunction::ResponseType::kSucceeded,
              *function->response_type())
        << function->GetError();
    const base::ListValue* results = function->GetResultListForTest();
    ASSERT_NE(nullptr, results);
    ASSERT_EQ(1u, results->size());
    ASSERT_TRUE((*results)[0].is_dict());
    const base::DictValue& result = (*results)[0].GetDict();
    const std::string* token = result.FindString("token");
    ASSERT_NE(nullptr, token);
    EXPECT_EQ(kCachedToken, *token);
    const base::ListValue* granted_scopes = result.FindList("grantedScopes");
    ASSERT_NE(nullptr, granted_scopes);
    EXPECT_EQ(base::ListValue().Append("read").Append("write"),
              *granted_scopes);
    ExpectUnchangedCacheAndEmptyQueue();
  }

  std::string primary_gaia_id_;

 private:
  std::unique_ptr<IdentityTestEnvironmentProfileAdaptor> identity_environment_;
};

TEST_F(AndroidOAuthAccountTest, ExplicitPrimaryAccountIsRejectedSynchronously) {
  // Matching Chrome's primary ID must not be mistaken for binding the account
  // chosen in the separate browser-based OAuth flow.
  ExpectAccountRejected(primary_gaia_id_);
}

TEST_F(AndroidOAuthAccountTest, ExplicitOtherAccountIsRejectedSynchronously) {
  ExpectAccountRejected("different-explicit-account-id");
}

TEST_F(AndroidOAuthAccountTest, OmittedDetailsUseDefaultAccountCache) {
  ExpectCachedToken(base::ListValue());
}

TEST_F(AndroidOAuthAccountTest, OmittedAccountUsesDefaultAccountCache) {
  ExpectCachedToken(base::ListValue().Append(base::DictValue()));
}

TEST_F(AndroidOAuthAccountTest, EmptyAccountIdUsesDefaultAccountCache) {
  ExpectCachedToken(base::ListValue().Append(
      base::DictValue().Set("account", base::DictValue().Set("id", ""))));
}

}  // namespace
}  // namespace extensions
