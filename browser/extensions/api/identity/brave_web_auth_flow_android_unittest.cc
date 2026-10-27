/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/browser/extensions/api/identity/brave_web_auth_flow.h"

#include <memory>
#include <set>
#include <string>
#include <utility>

#include "base/functional/bind.h"
#include "base/functional/callback_helpers.h"
#include "base/run_loop.h"
#include "base/test/bind.h"
#include "chrome/browser/extensions/api/identity/identity_api.h"
#include "chrome/browser/extensions/api/identity/identity_get_auth_token_function.h"
#include "chrome/browser/extensions/api/identity/identity_token_cache.h"
#include "chrome/browser/extensions/extension_api_unittest.h"
#include "chrome/test/base/testing_profile.h"
#include "content/public/browser/browser_context.h"
#include "extensions/browser/event_router.h"
#include "extensions/browser/event_router_factory.h"
#include "extensions/browser/extension_prefs.h"
#include "extensions/browser/extension_registry.h"
#include "extensions/browser/unloaded_extension_reason.h"
#include "extensions/common/extension.h"
#include "google_apis/gaia/gaia_urls.h"
#include "services/network/public/cpp/weak_wrapper_shared_url_loader_factory.h"
#include "services/network/test/test_url_loader_factory.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace extensions {
namespace {

// Hold a real mint-queue request without starting a provider window. The fixture
// connects the inherited fallback to the API function's real terminal callbacks.
class PendingAndroidGetAuthTokenFunction : public IdentityGetAuthTokenFunction {
 public:
  PendingAndroidGetAuthTokenFunction() = default;

 private:
  ~PendingAndroidGetAuthTokenFunction() override = default;

  void StartMintToken(IdentityMintRequestQueue::MintType type) override {}
};

}  // namespace

// Seed an already pending flow without opening a provider window. The tests
// invoke the real response parser, completion/cache path and cancellation;
// profile services and the token cache are the normal Chromium implementations.
class BraveWebAuthFlowAndroidTest : public ExtensionApiUnittest {
 protected:
  void SetUp() override {
    ExtensionApiUnittest::SetUp();
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
  }

  ExtensionTokenKey Key(std::set<std::string> scopes = {"read", "write"}) {
    return ExtensionTokenKey(extension()->id(), CoreAccountInfo(), scopes);
  }

  IdentityTokenCache* Cache() {
    return IdentityAPI::GetFactoryInstance()->Get(profile())->token_cache();
  }

  void Begin(BraveWebAuthFlow& flow,
             base::OnceClosure on_queue = base::DoNothing(),
             base::OnceClosure on_result = base::DoNothing()) {
    flow.CancelAndroidWebAuthFlow();
    flow.profile_ = profile();
    flow.token_key_ = Key();
    flow.android_flow_active_ = true;
    flow.android_redirect_url_ = GURL("com.example.client:/");
    flow.redirect_scheme_ = flow.android_redirect_url_.scheme();
    flow.android_oauth_state_ = "this-request-only";
    flow.complete_mint_token_flow_callback_ = base::BindLambdaForTesting(
        [this, callback = std::move(on_queue)]() mutable {
          ++queue_completions_;
          std::move(callback).Run();
        });
    flow.complete_with_result_callback_ = base::BindLambdaForTesting(
        [this, callback = std::move(on_result)](
            const std::string& token,
            const std::set<std::string>& scopes) mutable {
          ++successes_;
          last_token_ = token;
          last_scopes_ = scopes;
          std::move(callback).Run();
        });
    flow.complete_with_error_callback_ = base::BindLambdaForTesting(
        [this](const IdentityGetAuthTokenError& error) {
          ++errors_;
          last_error_ = error.state();
        });
  }

  scoped_refptr<IdentityGetAuthTokenFunction> BeginRequest(bool active_flow) {
    auto function = base::MakeRefCounted<PendingAndroidGetAuthTokenFunction>();
    function->set_extension(extension());
    function->SetBrowserContextForTesting(profile());
    function->set_has_callback(true);
    function->set_response_callback(base::BindLambdaForTesting(
        [this](ExtensionFunction::ResponseType type, base::ListValue results,
               const std::string& error,
               extensions::mojom::ExtraResponseDataPtr response_data) {
          ++request_responses_;
          if (on_request_response_) {
            std::move(on_request_response_).Run();
          }
        }));
    function->preserve_results_for_testing();
    function->token_key_ = Key();
    function->mint_token_flow_type_ =
        IdentityMintRequestQueue::MINT_TYPE_INTERACTIVE;
    function->StartAsyncRun();
    IdentityAPI::GetFactoryInstance()
        ->Get(profile())
        ->mint_queue()
        ->RequestStart(IdentityMintRequestQueue::MINT_TYPE_INTERACTIVE, Key(),
                       function.get());
    if (active_flow) {
      Begin(*function);
      function->complete_mint_token_flow_callback_ = base::BindOnce(
          &IdentityGetAuthTokenFunction::CompleteMintTokenFlow,
          function->weak_ptr_factory_.GetWeakPtr());
      function->complete_with_result_callback_ = base::BindOnce(
          &IdentityGetAuthTokenFunction::CompleteFunctionWithResult,
          function->weak_ptr_factory_.GetWeakPtr());
      function->complete_with_error_callback_ = base::BindOnce(
          &IdentityGetAuthTokenFunction::CompleteFunctionWithError,
          function->weak_ptr_factory_.GetWeakPtr());
    }
    return function;
  }

  void ShutdownRequest(IdentityGetAuthTokenFunction* function) {
    function->OnBrowserContextShutdown();
  }

  void ExpectRequestQueueEmpty() {
    auto* queue = IdentityAPI::GetFactoryInstance()->Get(profile())->mint_queue();
    EXPECT_TRUE(queue->empty(IdentityMintRequestQueue::MINT_TYPE_INTERACTIVE,
                             Key()));
    EXPECT_TRUE(queue->empty(IdentityMintRequestQueue::MINT_TYPE_NONINTERACTIVE,
                             Key()));
  }

  void ExpectUnloadedRequestsRetire(UnloadedExtensionReason reason) {
    auto* registry = ExtensionRegistry::Get(profile());
    registry->AddEnabled(extension());
    auto active = BeginRequest(/*active_flow=*/true);
    auto queued = BeginRequest(/*active_flow=*/false);
    EXPECT_FALSE(active->did_respond());
    EXPECT_FALSE(queued->did_respond());
    EXPECT_FALSE(active->HasOneRef());
    EXPECT_FALSE(queued->HasOneRef());

    ASSERT_TRUE(registry->RemoveEnabled(extension()->id()));
    registry->TriggerOnUnloaded(extension(), reason);
    // Unload retires every old queue entry before any response may reenter.
    ExpectRequestQueueEmpty();
    EXPECT_EQ(0, request_responses_);
    active->OnAuthFlowURLChange(Reply());
    active->OnAuthFlowFailure(WebAuthFlow::WINDOW_CLOSED);
    EXPECT_EQ(IdentityTokenCacheValue::CACHE_STATUS_NOTFOUND,
              Cache()->GetToken(Key()).status());

    base::RunLoop().RunUntilIdle();
    EXPECT_EQ(2, request_responses_);
    EXPECT_TRUE(active->did_respond());
    EXPECT_TRUE(queued->did_respond());
    EXPECT_EQ(ExtensionFunction::ResponseType::kFailed,
              *active->response_type());
    EXPECT_EQ(ExtensionFunction::ResponseType::kFailed,
              *queued->response_type());
    // Only each test reference remains: both StartAsyncRun references balanced.
    EXPECT_TRUE(active->HasOneRef());
    EXPECT_TRUE(queued->HasOneRef());
    active->OnIdentityAPIShutdown();
    queued->OnIdentityAPIShutdown();
    active->OnAuthFlowURLChange(Reply());
    EXPECT_EQ(2, request_responses_);
    EXPECT_EQ(IdentityTokenCacheValue::CACHE_STATUS_NOTFOUND,
              Cache()->GetToken(Key()).status());
    ExpectRequestQueueEmpty();
  }

  GURL Reply(std::string suffix = "") {
    return GURL(
        "com.example.client:/#state=this-request-only&access_token="
        "valid-token&token_type=Bearer&expires_in=3600" +
        suffix);
  }

  std::unique_ptr<TestingProfile> VerificationProfile(
      network::TestURLLoaderFactory& factory) {
    TestingProfile::Builder builder;
    builder.SetSharedURLLoaderFactory(factory.GetSafeWeakWrapper());
    auto verification_profile = builder.Build();
    EventRouterFactory::GetInstance()->SetTestingFactoryAndUse(
        verification_profile.get(),
        base::BindRepeating([](content::BrowserContext* context)
                               -> std::unique_ptr<KeyedService> {
          return std::make_unique<EventRouter>(context,
                                               ExtensionPrefs::Get(context));
        }));
    IdentityAPI::GetFactoryInstance()->SetTestingFactoryAndUse(
        verification_profile.get(),
        base::BindRepeating([](content::BrowserContext* context)
                               -> std::unique_ptr<KeyedService> {
          return std::make_unique<IdentityAPI>(context);
        }));
    return verification_profile;
  }

  void BeginRequestedAccount(BraveWebAuthFlow& flow,
                             Profile* verification_profile,
                             const std::string& subject_scope) {
    Begin(flow);
    flow.profile_ = verification_profile;
    flow.token_key_ = Key({subject_scope});
    ASSERT_TRUE(flow.ConfigureAndroidRequestedAccount("verified-account",
                                                       {subject_scope}));
    flow.android_oauth_client_id_ = "test-client-id";
  }

  base::OnceClosure on_request_response_;
  int request_responses_ = 0;
  int queue_completions_ = 0;
  int successes_ = 0;
  int errors_ = 0;
  std::string last_token_;
  std::set<std::string> last_scopes_;
  IdentityGetAuthTokenError::State last_error_ =
      IdentityGetAuthTokenError::State::kNone;
};

TEST_F(BraveWebAuthFlowAndroidTest, CacheIsReadyBeforeMintQueueAdvances) {
  BraveWebAuthFlow flow;
  Begin(flow, base::BindLambdaForTesting([&] {
          const auto& token = Cache()->GetToken(Key());
          ASSERT_EQ(IdentityTokenCacheValue::CACHE_STATUS_TOKEN,
                    token.status());
          EXPECT_EQ("valid-token", token.token());
          EXPECT_EQ(Key().scopes, token.granted_scopes());
          EXPECT_EQ(0, successes_);
        }));
  flow.OnAuthFlowURLChange(Reply());
  EXPECT_EQ(1, queue_completions_);
  EXPECT_EQ(1, successes_);
  EXPECT_EQ(0, errors_);
  EXPECT_EQ(Key().scopes, last_scopes_);
}

TEST_F(BraveWebAuthFlowAndroidTest,
       PartialGrantDoesNotSatisfyUnapprovedScopes) {
  BraveWebAuthFlow flow;
  Begin(flow, base::BindLambdaForTesting([&] {
          EXPECT_EQ(IdentityTokenCacheValue::CACHE_STATUS_NOTFOUND,
                    Cache()->GetToken(Key()).status());
          const auto& token = Cache()->GetToken(Key({"read"}));
          ASSERT_EQ(IdentityTokenCacheValue::CACHE_STATUS_TOKEN,
                    token.status());
          EXPECT_EQ(std::set<std::string>({"read"}), token.granted_scopes());
        }));
  flow.OnAuthFlowURLChange(Reply("&scope=read"));
  EXPECT_EQ(std::set<std::string>({"read"}), last_scopes_);
  EXPECT_EQ(1, successes_);
}

TEST_F(BraveWebAuthFlowAndroidTest, ProviderNavigationDoesNotConsumeResponse) {
  BraveWebAuthFlow flow;
  Begin(flow);
  flow.OnAuthFlowURLChange(GURL("https://accounts.google.com/consent"));
  EXPECT_EQ(0, queue_completions_);
  flow.OnAuthFlowURLChange(Reply());
  EXPECT_EQ(1, successes_);
}

TEST_F(BraveWebAuthFlowAndroidTest, SameSchemeDifferentEndpointRejectsOnce) {
  BraveWebAuthFlow flow;
  Begin(flow);
  flow.OnAuthFlowURLChange(
      GURL("com.example.client:/"
           "other#state=this-request-only&access_token=valid-token"
           "&token_type=Bearer&expires_in=3600"));
  flow.OnAuthFlowURLChange(Reply());
  EXPECT_EQ(1, queue_completions_);
  EXPECT_EQ(1, errors_);
  EXPECT_EQ(IdentityGetAuthTokenError::State::kInvalidConsentResult,
            last_error_);
  EXPECT_EQ(0, successes_);
  EXPECT_EQ(IdentityTokenCacheValue::CACHE_STATUS_NOTFOUND,
            Cache()->GetToken(Key()).status());
}

TEST_F(BraveWebAuthFlowAndroidTest, MismatchedStateCannotPopulateCache) {
  BraveWebAuthFlow flow;
  Begin(flow);
  flow.OnAuthFlowURLChange(
      GURL("com.example.client:/#state=another-request&access_token=valid-token"
           "&token_type=Bearer&expires_in=3600"));
  EXPECT_EQ(1, errors_);
  EXPECT_EQ(0, successes_);
  EXPECT_EQ(IdentityTokenCacheValue::CACHE_STATUS_NOTFOUND,
            Cache()->GetToken(Key()).status());
}

TEST_F(BraveWebAuthFlowAndroidTest, LateSuccessAndFailureAreIgnored) {
  BraveWebAuthFlow flow;
  Begin(flow);
  flow.OnAuthFlowURLChange(Reply());
  flow.OnAuthFlowURLChange(Reply());
  flow.OnAuthFlowFailure(WebAuthFlow::WINDOW_CLOSED);
  EXPECT_EQ(1, queue_completions_);
  EXPECT_EQ(1, successes_);
  EXPECT_EQ(0, errors_);
}

TEST_F(BraveWebAuthFlowAndroidTest, ExplicitCancellationRetiresAllCallbacks) {
  BraveWebAuthFlow flow;
  Begin(flow);
  flow.CancelAndroidWebAuthFlow();
  flow.CancelAndroidWebAuthFlow();
  flow.OnAuthFlowURLChange(Reply());
  flow.OnAuthFlowFailure(WebAuthFlow::LOAD_FAILED);
  EXPECT_EQ(0, queue_completions_);
  EXPECT_EQ(0, successes_);
  EXPECT_EQ(0, errors_);
}

TEST_F(BraveWebAuthFlowAndroidTest, QueueReentryCancellationSuppressesResult) {
  BraveWebAuthFlow flow;
  Begin(flow,
        base::BindLambdaForTesting([&] { flow.CancelAndroidWebAuthFlow(); }));
  flow.OnAuthFlowURLChange(Reply());
  EXPECT_EQ(1, queue_completions_);
  EXPECT_EQ(0, successes_);
  EXPECT_EQ(0, errors_);
}

TEST_F(BraveWebAuthFlowAndroidTest, QueueReentryDestructionSuppressesResult) {
  auto flow = std::make_unique<BraveWebAuthFlow>();
  Begin(*flow, base::BindLambdaForTesting([&] { flow.reset(); }));
  flow->OnAuthFlowURLChange(Reply());
  EXPECT_FALSE(flow);
  EXPECT_EQ(1, queue_completions_);
  EXPECT_EQ(0, successes_);
  EXPECT_EQ(0, errors_);
}

TEST_F(BraveWebAuthFlowAndroidTest, QueueReentryDestructionSuppressesError) {
  auto flow = std::make_unique<BraveWebAuthFlow>();
  Begin(*flow, base::BindLambdaForTesting([&] { flow.reset(); }));
  flow->OnAuthFlowURLChange(GURL("com.example.client:/#state=wrong"));
  EXPECT_FALSE(flow);
  EXPECT_EQ(1, queue_completions_);
  EXPECT_EQ(0, successes_);
  EXPECT_EQ(0, errors_);
}

TEST_F(BraveWebAuthFlowAndroidTest, ResultCallbackMayDestroyOwner) {
  auto flow = std::make_unique<BraveWebAuthFlow>();
  Begin(*flow, base::DoNothing(),
        base::BindLambdaForTesting([&] { flow.reset(); }));
  flow->OnAuthFlowURLChange(Reply());
  EXPECT_FALSE(flow);
  EXPECT_EQ(1, queue_completions_);
  EXPECT_EQ(1, successes_);
}

TEST_F(BraveWebAuthFlowAndroidTest, ProviderFailureAndLateReplyCompleteOnce) {
  BraveWebAuthFlow flow;
  Begin(flow);
  flow.OnAuthFlowFailure(WebAuthFlow::WINDOW_CLOSED);
  flow.OnAuthFlowURLChange(Reply());
  EXPECT_EQ(1, queue_completions_);
  EXPECT_EQ(1, errors_);
  EXPECT_EQ(IdentityGetAuthTokenError::State::kRemoteConsentFlowRejected,
            last_error_);
  EXPECT_EQ(0, successes_);
}

TEST_F(BraveWebAuthFlowAndroidTest, GetAuthTokenDisableRetiresActiveAndQueued) {
  ExpectUnloadedRequestsRetire(UnloadedExtensionReason::DISABLE);
}

TEST_F(BraveWebAuthFlowAndroidTest, GetAuthTokenUninstallRetiresActiveAndQueued) {
  ExpectUnloadedRequestsRetire(UnloadedExtensionReason::UNINSTALL);
}

TEST_F(BraveWebAuthFlowAndroidTest, GetAuthTokenShutdownRetiresPendingReply) {
  auto* registry = ExtensionRegistry::Get(profile());
  registry->AddEnabled(extension());
  auto function = BeginRequest(/*active_flow=*/true);
  ASSERT_TRUE(registry->RemoveEnabled(extension()->id()));
  registry->TriggerOnUnloaded(extension(), UnloadedExtensionReason::DISABLE);
  EXPECT_EQ(0, request_responses_);
  ShutdownRequest(function.get());
  EXPECT_EQ(1, request_responses_);
  EXPECT_TRUE(function->HasOneRef());
  base::RunLoop().RunUntilIdle();
  function->OnAuthFlowURLChange(Reply());
  function->OnAuthFlowFailure(WebAuthFlow::WINDOW_CLOSED);
  EXPECT_EQ(1, request_responses_);
  EXPECT_EQ(IdentityTokenCacheValue::CACHE_STATUS_NOTFOUND,
            Cache()->GetToken(Key()).status());
  ExpectRequestQueueEmpty();
}

TEST_F(BraveWebAuthFlowAndroidTest, GetAuthTokenResponseReentryCanStartFreshRequest) {
  auto* registry = ExtensionRegistry::Get(profile());
  registry->AddEnabled(extension());
  auto active = BeginRequest(/*active_flow=*/true);
  auto queued = BeginRequest(/*active_flow=*/false);
  scoped_refptr<IdentityGetAuthTokenFunction> fresh;
  on_request_response_ = base::BindLambdaForTesting([&] {
    registry->AddEnabled(extension());
    fresh = BeginRequest(/*active_flow=*/true);
  });
  ASSERT_TRUE(registry->RemoveEnabled(extension()->id()));
  registry->TriggerOnUnloaded(extension(), UnloadedExtensionReason::DISABLE);
  ExpectRequestQueueEmpty();
  base::RunLoop().RunUntilIdle();
  ASSERT_TRUE(fresh);
  EXPECT_EQ(2, request_responses_);
  EXPECT_TRUE(active->HasOneRef());
  EXPECT_TRUE(queued->HasOneRef());
  active->OnAuthFlowURLChange(Reply());
  EXPECT_EQ(IdentityTokenCacheValue::CACHE_STATUS_NOTFOUND,
            Cache()->GetToken(Key()).status());
  fresh->OnAuthFlowURLChange(Reply());
  EXPECT_EQ(3, request_responses_);
  EXPECT_TRUE(fresh->did_respond());
  EXPECT_EQ(ExtensionFunction::ResponseType::kSucceeded,
            *fresh->response_type());
  EXPECT_TRUE(fresh->HasOneRef());
  EXPECT_EQ(IdentityTokenCacheValue::CACHE_STATUS_TOKEN,
            Cache()->GetToken(Key()).status());
  EXPECT_EQ("valid-token", Cache()->GetToken(Key()).token());
  ExpectRequestQueueEmpty();
}

TEST_F(BraveWebAuthFlowAndroidTest,
       RequestedAccountProfileSubjectCompletesWithoutDefaultCacheChange) {
  const std::string scope = "https://www.googleapis.com/auth/userinfo.profile";
  network::TestURLLoaderFactory factory;
  auto verification_profile = VerificationProfile(factory);
  BraveWebAuthFlow flow;
  auto* cache = IdentityAPI::GetFactoryInstance()
                    ->Get(verification_profile.get())
                    ->token_cache();
  cache->SetToken(
      Key({scope}), IdentityTokenCacheValue::CreateToken(
                        "cached-default-account-token", {scope}, base::Hours(1)));
  factory.AddResponse(
      GaiaUrls::GetInstance()->oauth2_token_info_url().spec(),
      "{\"issued_to\":\"test-client-id\",\"audience\":\"test-client-id\","
      "\"scope\":\"https://www.googleapis.com/auth/userinfo.profile\","
      "\"expires_in\":3600,\"user_id\":\"verified-account\"}");
  BeginRequestedAccount(flow, verification_profile.get(), scope);
  flow.OnAuthFlowURLChange(Reply());
  EXPECT_EQ(0, successes_);
  EXPECT_EQ(0, queue_completions_);
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1u, factory.total_requests());
  EXPECT_EQ(1, queue_completions_);
  EXPECT_EQ(1, successes_);
  EXPECT_EQ(0, errors_);
  EXPECT_EQ("valid-token", last_token_);
  EXPECT_EQ(std::set<std::string>({scope}), last_scopes_);
  const auto& default_token = cache->GetToken(Key({scope}));
  ASSERT_EQ(IdentityTokenCacheValue::CACHE_STATUS_TOKEN, default_token.status());
  EXPECT_EQ("cached-default-account-token", default_token.token());
}

TEST_F(BraveWebAuthFlowAndroidTest,
       RequestedAccountEmailSubjectUsesTrustedUserIdWithoutDefaultCacheChange) {
  const std::string scope = "https://www.googleapis.com/auth/userinfo.email";
  network::TestURLLoaderFactory factory;
  auto verification_profile = VerificationProfile(factory);
  BraveWebAuthFlow flow;
  auto* cache = IdentityAPI::GetFactoryInstance()
                    ->Get(verification_profile.get())
                    ->token_cache();
  cache->SetToken(
      Key({scope}), IdentityTokenCacheValue::CreateToken(
                        "cached-default-account-token", {scope}, base::Hours(1)));
  factory.AddResponse(
      GaiaUrls::GetInstance()->oauth2_token_info_url().spec(),
      "{\"issued_to\":\"test-client-id\",\"audience\":\"test-client-id\","
      "\"scope\":\"https://www.googleapis.com/auth/userinfo.email\","
      "\"expires_in\":3600}");
  factory.AddResponse(GaiaUrls::GetInstance()->oauth_user_info_url().spec(),
                      R"({"id":"verified-account"})");
  BeginRequestedAccount(flow, verification_profile.get(), scope);
  flow.OnAuthFlowURLChange(Reply());
  EXPECT_EQ(0, successes_);
  EXPECT_EQ(0, queue_completions_);
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(2u, factory.total_requests());
  EXPECT_EQ(1, queue_completions_);
  EXPECT_EQ(1, successes_);
  EXPECT_EQ(0, errors_);
  EXPECT_EQ("valid-token", last_token_);
  EXPECT_EQ(std::set<std::string>({scope}), last_scopes_);
  const auto& default_token = cache->GetToken(Key({scope}));
  ASSERT_EQ(IdentityTokenCacheValue::CACHE_STATUS_TOKEN, default_token.status());
  EXPECT_EQ("cached-default-account-token", default_token.token());
}

}  // namespace extensions
