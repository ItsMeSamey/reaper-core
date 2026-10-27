/* Copyright (c) 2023 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/browser/extensions/api/identity/brave_web_auth_flow.h"

#include <algorithm>
#include <optional>
#include <utility>
#include <vector>

#include "base/notreached.h"
#include "base/strings/string_number_conversions.h"
#include "base/strings/string_split.h"
#include "base/strings/string_util.h"
#include "build/build_config.h"
#include "chrome/browser/extensions/api/identity/identity_api.h"
#include "chrome/browser/profiles/profile.h"
#include "google_apis/google_api_keys.h"
#include "net/base/url_util.h"

#if BUILDFLAG(IS_ANDROID)
#include "base/base64url.h"
#include "base/functional/bind.h"
#include "base/location.h"
#include "base/rand_util.h"
#include "base/timer/timer.h"
#include "base/values.h"
#include "google_apis/gaia/gaia_oauth_client.h"
#endif

namespace extensions {

namespace {
constexpr char kGoogleOauth2Url[] =
    "https://accounts.google.com/o/oauth2/v2/auth";
#if BUILDFLAG(IS_ANDROID)
constexpr char kAndroidUserinfoProfileScope[] =
    "https://www.googleapis.com/auth/userinfo.profile";
constexpr char kAndroidUserinfoEmailScope[] =
    "https://www.googleapis.com/auth/userinfo.email";
#endif
}  // namespace

#if BUILDFLAG(IS_ANDROID)
class BraveWebAuthFlow::AndroidAccountVerifier
    : public gaia::GaiaOAuthClient::Delegate {
 public:
  using ResultCallback =
      base::OnceCallback<void(std::optional<base::TimeDelta>)>;

  AndroidAccountVerifier(
      scoped_refptr<network::SharedURLLoaderFactory> url_loader_factory,
      std::string requested_gaia_id,
      std::string oauth_client_id,
      std::set<std::string> granted_scopes,
      ResultCallback callback)
      : client_(std::move(url_loader_factory)),
        requested_gaia_id_(std::move(requested_gaia_id)),
        oauth_client_id_(std::move(oauth_client_id)),
        granted_scopes_(std::move(granted_scopes)),
        callback_(std::move(callback)) {}

  void Start(const std::string& access_token) {
    access_token_ = access_token;
    timer_.Start(FROM_HERE, base::Seconds(30),
                 base::BindOnce(&AndroidAccountVerifier::Fail,
                                base::Unretained(this)));
    client_.GetTokenInfo(access_token_, /*max_retries=*/0, this);
  }

  void OnGetTokenInfoResponse(const base::DictValue& token_info) override {
    const std::string* issued_to = token_info.FindString("issued_to");
    const std::string* audience = token_info.FindString("audience");
    const std::string* scope = token_info.FindString("scope");
    const std::optional<int> expires_in = token_info.FindInt("expires_in");
    if (!issued_to || *issued_to != oauth_client_id_ || !audience ||
        *audience != oauth_client_id_ || !scope || !expires_in ||
        *expires_in <= 0) {
      Fail();
      return;
    }
    const auto scopes = base::SplitString(
        *scope, " ", base::TRIM_WHITESPACE, base::SPLIT_WANT_NONEMPTY);
    if (std::set<std::string>(scopes.begin(), scopes.end()) != granted_scopes_) {
      Fail();
      return;
    }
    token_info_expiration_ =
        base::TimeTicks::Now() + base::Seconds(*expires_in);
    if (granted_scopes_.contains(kAndroidUserinfoProfileScope)) {
      const std::string* user_id = token_info.FindString("user_id");
      if (!user_id || user_id->empty() || *user_id != requested_gaia_id_) {
        Fail();
        return;
      }
      Succeed();
      return;
    }
    // GetUserId is supported only by a token already granted userinfo.email.
    if (!granted_scopes_.contains(kAndroidUserinfoEmailScope)) {
      Fail();
      return;
    }
    client_.GetUserId(access_token_, /*max_retries=*/0, this);
  }

  void OnGetUserIdResponse(const std::string& user_id) override {
    if (user_id.empty() || user_id != requested_gaia_id_) {
      Fail();
      return;
    }
    Succeed();
  }

  void OnOAuthError() override { Fail(); }
  void OnNetworkError(int /*response_code*/) override { Fail(); }

 private:
  void Succeed() {
    const base::TimeDelta remaining =
        token_info_expiration_ - base::TimeTicks::Now();
    Finish(remaining.is_positive() ? std::make_optional(remaining)
                                   : std::nullopt);
  }

  void Fail() { Finish(std::nullopt); }

  void Finish(std::optional<base::TimeDelta> remaining_lifetime) {
    timer_.Stop();
    access_token_.clear();
    auto callback = std::move(callback_);
    // Completion may delete this verifier. Do not access members afterward.
    std::move(callback).Run(remaining_lifetime);
  }

  gaia::GaiaOAuthClient client_;
  std::string requested_gaia_id_;
  std::string oauth_client_id_;
  std::set<std::string> granted_scopes_;
  std::string access_token_;
  base::TimeTicks token_info_expiration_;
  ResultCallback callback_;
  base::OneShotTimer timer_;
};
#endif

BraveWebAuthFlow::BraveWebAuthFlow() {}
BraveWebAuthFlow::~BraveWebAuthFlow() {
#if BUILDFLAG(IS_ANDROID)
  CancelAndroidWebAuthFlow();
#endif
}

// static
// TODO(https://github.com/brave/brave-browser/issues/48713): This is a case of
// `-Wexit-time-destructors` violation and `[[clang::no_destroy]]` has been
// added in the meantime to fix the build error. Remove this attribute and
// provide a proper fix.
[[clang::no_destroy]] std::optional<std::string>
    BraveWebAuthFlow::token_for_testing_;
// static
void BraveWebAuthFlow::SetTokenForTesting(const std::string& token) {
  token_for_testing_ = token;
}

void BraveWebAuthFlow::StartWebAuthFlow(
    Profile* profile,
    base::OnceClosure complete_mint_token_flow_callback,
    CompleteFunctionWithErrorCallback complete_with_error_callback,
    CompleteFunctionWithResultCallback complete_with_result_callback,
    const std::string& oauth2_client_id,
    ExtensionTokenKey token_key,
    bool interactive,
    bool user_gesture) {
#if BUILDFLAG(IS_ANDROID)
  auto requested_gaia_id = std::move(android_requested_gaia_id_);
  CancelAndroidWebAuthFlow();
  android_requested_gaia_id_ = std::move(requested_gaia_id);
  android_oauth_client_id_ = oauth2_client_id;
  android_flow_active_ = true;
#endif
  profile_ = profile;
  complete_with_error_callback_ = std::move(complete_with_error_callback);
  complete_with_result_callback_ = std::move(complete_with_result_callback);
  complete_mint_token_flow_callback_ =
      std::move(complete_mint_token_flow_callback);
  token_key_ = token_key;

  if (token_for_testing_.has_value()) {
#if BUILDFLAG(IS_ANDROID)
    internal::ParsedAndroidOAuthResponse response{
        token_for_testing_.value(), token_key_.scopes, base::Hours(1)};
    if (HasAndroidRequestedAccount()) {
      BeginAndroidAccountVerification(std::move(response));
    } else {
      FinishAndroidWebAuthFlow(std::move(response));
    }
#else
    std::move(complete_mint_token_flow_callback_).Run();
    std::move(complete_with_result_callback_)
        .Run(token_for_testing_.value(), token_key_.scopes);
#endif
    return;
  }
  // Compute the reverse DNS notation of the client ID and use it as a custom
  // URI scheme.
  std::vector<std::string> client_id_components = base::SplitString(
      oauth2_client_id, ".", base::TRIM_WHITESPACE, base::SPLIT_WANT_NONEMPTY);
  std::reverse(client_id_components.begin(), client_id_components.end());
  GURL redirect_url = GURL(base::JoinString(client_id_components, ".") + ":/");
  redirect_scheme_ = redirect_url.scheme();
#if BUILDFLAG(IS_ANDROID)
  android_redirect_url_ = redirect_url;
  base::Base64UrlEncode(base::RandBytesAsString(32),
                        base::Base64UrlEncodePolicy::OMIT_PADDING,
                        &android_oauth_state_);
#endif
  GURL google_oauth_url = GURL(kGoogleOauth2Url);
  google_oauth_url = net::AppendQueryParameter(google_oauth_url, "client_id",
                                               oauth2_client_id);
  google_oauth_url = net::AppendQueryParameter(google_oauth_url, "redirect_uri",
                                               redirect_url.spec());
  google_oauth_url =
      net::AppendQueryParameter(google_oauth_url, "response_type", "token");
  google_oauth_url = net::AppendQueryParameter(
      google_oauth_url, "scope",
      base::JoinString(std::vector<std::string>(token_key_.scopes.begin(),
                                                token_key_.scopes.end()),
                       " "));
#if BUILDFLAG(IS_ANDROID)
  google_oauth_url = net::AppendQueryParameter(google_oauth_url, "state",
                                               android_oauth_state_);
#endif
  web_auth_flow_ = std::make_unique<WebAuthFlow>(
      this, profile_, google_oauth_url,
      interactive ? WebAuthFlow::INTERACTIVE : WebAuthFlow::SILENT,
      user_gesture);
  web_auth_flow_->Start();
}

void BraveWebAuthFlow::OnAuthFlowFailure(WebAuthFlow::Failure failure) {
#if BUILDFLAG(IS_ANDROID)
  if (!android_flow_active_) {
    return;
  }
#endif
  IdentityGetAuthTokenError error;
  switch (failure) {
    case WebAuthFlow::WINDOW_CLOSED:
      error = IdentityGetAuthTokenError(
          IdentityGetAuthTokenError::State::kRemoteConsentFlowRejected);
      break;
    case WebAuthFlow::INTERACTION_REQUIRED:
      error = IdentityGetAuthTokenError(
          IdentityGetAuthTokenError::State::kGaiaConsentInteractionRequired);
      break;
#if BUILDFLAG(IS_ANDROID)
    case WebAuthFlow::TIMED_OUT:
#endif
    case WebAuthFlow::LOAD_FAILED:
      error = IdentityGetAuthTokenError(
          IdentityGetAuthTokenError::State::kRemoteConsentPageLoadFailure);
      break;
#if BUILDFLAG(IS_ANDROID)
    case WebAuthFlow::CANNOT_CREATE_WINDOW:
      error = IdentityGetAuthTokenError(
          IdentityGetAuthTokenError::State::kCannotCreateWindow);
      break;
#endif
    default:
      NOTREACHED() << "Unexpected error from web auth flow: " << failure;
  }
#if BUILDFLAG(IS_ANDROID)
  FinishAndroidWebAuthFlow(base::unexpected(error));
#else
  if (web_auth_flow_) {
    web_auth_flow_.release()->DetachDelegateAndDelete();
  }
  std::move(complete_mint_token_flow_callback_).Run();
  std::move(complete_with_error_callback_).Run(error);
#endif
}

void BraveWebAuthFlow::OnAuthFlowURLChange(const GURL& redirect_url) {
#if BUILDFLAG(IS_ANDROID)
  if (!android_flow_active_ || android_account_verifier_ ||
      !redirect_url.SchemeIs(redirect_scheme_)) {
    return;
  }
  auto result = internal::ParseAndroidOAuthResponse(
      redirect_url, android_redirect_url_, android_oauth_state_,
      token_key_.scopes);
  if (result.has_value() && HasAndroidRequestedAccount()) {
    BeginAndroidAccountVerification(std::move(*result));
    return;
  }
  FinishAndroidWebAuthFlow(std::move(result));
#else
  if (!redirect_url.SchemeIs(redirect_scheme_)) {
    return;
  }

  if (web_auth_flow_) {
    web_auth_flow_.release()->DetachDelegateAndDelete();
  }

  std::move(complete_mint_token_flow_callback_).Run();

  base::StringPairs response_pairs;
  if (!base::SplitStringIntoKeyValuePairs(redirect_url.ref(), '=', '&',
                                          &response_pairs)) {
    std::move(complete_with_error_callback_)
        .Run(IdentityGetAuthTokenError(
            IdentityGetAuthTokenError::State::kNoGrant));
    return;
  }

  auto access_token_it =
      std::find_if(response_pairs.begin(), response_pairs.end(),
                   [](const auto& key_value_pair) {
                     return key_value_pair.first == "access_token";
                   });
  if (access_token_it == response_pairs.end()) {
    std::move(complete_with_error_callback_)
        .Run(IdentityGetAuthTokenError(
            IdentityGetAuthTokenError::State::kNoGrant));
    return;
  }
  std::string access_token = access_token_it->second;

  auto expires_in_it =
      std::find_if(response_pairs.begin(), response_pairs.end(),
                   [](const auto& key_value_pair) {
                     return key_value_pair.first == "expires_in";
                   });
  int time_to_live_seconds;
  if (expires_in_it == response_pairs.end() ||
      !base::StringToInt(expires_in_it->second, &time_to_live_seconds)) {
    std::move(complete_with_error_callback_)
        .Run(IdentityGetAuthTokenError(
            IdentityGetAuthTokenError::State::kNoGrant));
    return;
  }

  // `token_key_` doesn't have information about the account being used, so only
  // the last used token will be cached.
  IdentityTokenCacheValue token = IdentityTokenCacheValue::CreateToken(
      access_token, token_key_.scopes, base::Seconds(time_to_live_seconds));
  IdentityAPI::GetFactoryInstance()->Get(profile_)->token_cache()->SetToken(
      token_key_, token);

  std::move(complete_with_result_callback_)
      .Run(access_token, token_key_.scopes);
#endif
}

#if BUILDFLAG(IS_ANDROID)
bool BraveWebAuthFlow::ConfigureAndroidRequestedAccount(
    const std::string& gaia_id,
    const std::set<std::string>& requested_scopes) {
  if (gaia_id.empty() ||
      (!requested_scopes.contains(kAndroidUserinfoProfileScope) &&
       !requested_scopes.contains(kAndroidUserinfoEmailScope))) {
    return false;
  }
  android_requested_gaia_id_ = gaia_id;
  return true;
}

bool BraveWebAuthFlow::HasAndroidRequestedAccount() const {
  return !android_requested_gaia_id_.empty();
}

void BraveWebAuthFlow::BeginAndroidAccountVerification(
    internal::ParsedAndroidOAuthResponse response) {
  if (!android_flow_active_ || !profile_ || profile_->ShutdownStarted() ||
      android_oauth_client_id_.empty() ||
      (!response.granted_scopes.contains(kAndroidUserinfoProfileScope) &&
       !response.granted_scopes.contains(kAndroidUserinfoEmailScope))) {
    FinishAndroidWebAuthFlow(base::unexpected(
        IdentityGetAuthTokenError::FromMintTokenAuthError(
            "The returned OAuth token could not verify the requested account.")));
    return;
  }
  // The response is parsed, but the token is not yet accepted. Keep the mint
  // queue entry owned until its trusted subject check completes or is canceled.
  if (web_auth_flow_) {
    web_auth_flow_.release()->DetachDelegateAndDelete();
  }
  android_pending_oauth_response_ = std::move(response);
  android_account_verification_started_ = base::TimeTicks::Now();
  android_account_verifier_ = std::make_unique<AndroidAccountVerifier>(
      profile_->GetURLLoaderFactory(), android_requested_gaia_id_,
      android_oauth_client_id_, android_pending_oauth_response_->granted_scopes,
      base::BindOnce(&BraveWebAuthFlow::OnAndroidAccountVerified,
                     android_weak_factory_.GetWeakPtr()));
  android_account_verifier_->Start(
      android_pending_oauth_response_->access_token);
}

void BraveWebAuthFlow::OnAndroidAccountVerified(
    std::optional<base::TimeDelta> remaining_lifetime) {
  android_account_verifier_.reset();
  if (!android_flow_active_ || !android_pending_oauth_response_) {
    return;
  }
  auto response = std::move(*android_pending_oauth_response_);
  android_pending_oauth_response_.reset();
  const base::TimeDelta original_remaining =
      response.time_to_live -
      (base::TimeTicks::Now() - android_account_verification_started_);
  if (!remaining_lifetime || !remaining_lifetime->is_positive() ||
      !original_remaining.is_positive()) {
    FinishAndroidWebAuthFlow(base::unexpected(
        IdentityGetAuthTokenError::FromMintTokenAuthError(
            "The returned OAuth token could not verify the requested account.")));
    return;
  }
  response.time_to_live = std::min(original_remaining, *remaining_lifetime);
  FinishAndroidWebAuthFlow(std::move(response));
}

void BraveWebAuthFlow::CancelAndroidWebAuthFlow() {
  android_flow_active_ = false;
  android_weak_factory_.InvalidateWeakPtrs();
  android_account_verifier_.reset();
  android_pending_oauth_response_.reset();
  android_requested_gaia_id_.clear();
  android_oauth_client_id_.clear();
  android_account_verification_started_ = base::TimeTicks();
  android_oauth_state_.clear();
  android_redirect_url_ = GURL();
  redirect_scheme_.clear();
  profile_ = nullptr;
  complete_mint_token_flow_callback_.Reset();
  complete_with_result_callback_.Reset();
  complete_with_error_callback_.Reset();
  if (web_auth_flow_) {
    web_auth_flow_.release()->DetachDelegateAndDelete();
  }
}

void BraveWebAuthFlow::FinishAndroidWebAuthFlow(
    base::expected<internal::ParsedAndroidOAuthResponse,
                   IdentityGetAuthTokenError> result) {
  if (!android_flow_active_) {
    return;
  }
  const bool cache_default_account = !HasAndroidRequestedAccount();
  android_flow_active_ = false;
  android_account_verifier_.reset();
  android_pending_oauth_response_.reset();
  android_requested_gaia_id_.clear();
  android_oauth_client_id_.clear();
  android_account_verification_started_ = base::TimeTicks();
  android_oauth_state_.clear();
  android_redirect_url_ = GURL();
  redirect_scheme_.clear();
  if (web_auth_flow_) {
    web_auth_flow_.release()->DetachDelegateAndDelete();
  }

  // The next queued request may run synchronously from the mint callback.
  // Publish only the actual granted scopes for the default account before
  // advancing that queue. Explicit-account fallback tokens are never cached.
  if (result.has_value() && cache_default_account) {
    IdentityAPI::GetFactoryInstance()->Get(profile_)->token_cache()->SetToken(
        token_key_, IdentityTokenCacheValue::CreateToken(result->access_token,
                                                         result->granted_scopes,
                                                         result->time_to_live));
  }
  profile_ = nullptr;
  auto complete_mint = std::move(complete_mint_token_flow_callback_);
  auto complete_result = std::move(complete_with_result_callback_);
  auto complete_error = std::move(complete_with_error_callback_);
  auto alive = android_weak_factory_.GetWeakPtr();
  std::move(complete_mint).Run();
  // Advancing the queue can destroy the owner or shut down its profile. Never
  // access members after that callback; cancellation also invalidates `alive`.
  if (!alive) {
    return;
  }
  if (result.has_value()) {
    std::move(complete_result)
        .Run(result->access_token, result->granted_scopes);
  } else {
    std::move(complete_error).Run(result.error());
  }
}
#endif

}  // namespace extensions
