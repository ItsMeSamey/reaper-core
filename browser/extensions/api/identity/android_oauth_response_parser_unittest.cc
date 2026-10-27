/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/browser/extensions/api/identity/android_oauth_response_parser.h"

#include <set>
#include <string>
#include <string_view>

#include "base/strings/strcat.h"
#include "base/time/time.h"
#include "testing/gtest/include/gtest/gtest.h"
#include "url/gurl.h"

namespace extensions::internal {
namespace {

using ErrorState = IdentityGetAuthTokenError::State;
constexpr std::string_view kRedirect = "com.example.oauth:/";
constexpr std::string_view kState = "fresh-flow-state";
constexpr std::string_view kSuccess =
    "state=fresh-flow-state&access_token=token&token_type=Bearer&expires_in="
    "3600";

base::expected<ParsedAndroidOAuthResponse, IdentityGetAuthTokenError> Parse(
    std::string_view fragment,
    const std::set<std::string>& requested_scopes = {"read", "write"},
    std::string_view expected_state = kState) {
  return ParseAndroidOAuthResponse(
      GURL(base::StrCat({kRedirect, "#", fragment})), GURL(kRedirect),
      expected_state, requested_scopes);
}

void ExpectInvalid(std::string_view fragment) {
  const auto result = Parse(fragment);
  ASSERT_FALSE(result.has_value());
  EXPECT_EQ(ErrorState::kInvalidConsentResult, result.error().state());
}

TEST(AndroidOAuthResponseParserTest, OmittedScopeMeansRequestedGrant) {
  const auto result = Parse(kSuccess);
  ASSERT_TRUE(result.has_value());
  EXPECT_EQ("token", result->access_token);
  EXPECT_EQ((std::set<std::string>{"read", "write"}), result->granted_scopes);
  EXPECT_EQ(base::Seconds(3600), result->time_to_live);
}

TEST(AndroidOAuthResponseParserTest, PartialGrantIsNotFilledFromRequest) {
  const auto result = Parse(base::StrCat({kSuccess, "&scope=read"}));
  ASSERT_TRUE(result.has_value());
  EXPECT_EQ((std::set<std::string>{"read"}), result->granted_scopes);
  EXPECT_FALSE(result->granted_scopes.contains("write"));
}

TEST(AndroidOAuthResponseParserTest, ActualScopesAreNotFilteredOrNormalized) {
  const auto result = Parse(base::StrCat({kSuccess, "&scope=READ+extra+READ"}));
  ASSERT_TRUE(result.has_value());
  EXPECT_EQ((std::set<std::string>{"READ", "extra"}), result->granted_scopes);
}

TEST(AndroidOAuthResponseParserTest, DecodesFormKeysValuesAndUriScopes) {
  const auto result = Parse(
      "st%61te=fresh-flow-state&access%5Ftoken=ab%2Bcd%2Fef%3D%3D&"
      "token_type=bEaReR&expires_in=3600&"
      "sc%6Fpe=https%3A%2F%2Fexample.test%2Fread+write%20literal%2Bplus");
  ASSERT_TRUE(result.has_value());
  EXPECT_EQ("ab+cd/ef==", result->access_token);
  EXPECT_EQ((std::set<std::string>{"https://example.test/read", "write",
                                   "literal+plus"}),
            result->granted_scopes);
}

TEST(AndroidOAuthResponseParserTest, DoesNotDecodeTwice) {
  const auto result =
      Parse(base::StrCat({kSuccess, "&scope=literal%2520scope"}));
  ASSERT_TRUE(result.has_value());
  EXPECT_EQ((std::set<std::string>{"literal%20scope"}), result->granted_scopes);
}

TEST(AndroidOAuthResponseParserTest, RejectsEmptyAndInvalidScopeValues) {
  for (const std::string_view scope :
       {"", "+", "%20%20", "read%09write", "read%0Awrite", "read%00write",
        "read%22write", "read%5Cwrite", "%C3%A9"}) {
    SCOPED_TRACE(scope);
    ExpectInvalid(base::StrCat({kSuccess, "&scope=", scope}));
  }
}

TEST(AndroidOAuthResponseParserTest, RejectsDuplicateRecognizedParameters) {
  for (const std::string_view extra :
       {"state=fresh-flow-state", "st%61te=fresh-flow-state",
        "access_token=other", "access%5Ftoken=other", "token_type=Bearer",
        "expires_in=1", "scope=read&sc%6Fpe=write", "scope=read&scope=read"}) {
    SCOPED_TRACE(extra);
    ExpectInvalid(base::StrCat({kSuccess, "&", extra}));
  }
  ExpectInvalid(
      "state=fresh-flow-state&error=access_denied&error=access_denied");
  ExpectInvalid(
      "state=fresh-flow-state&error=access_denied&"
      "error_description=one&error%5Fdescription=two");
  ExpectInvalid(
      "state=fresh-flow-state&error=access_denied&"
      "error_uri=https%3A%2F%2Fa.test&error_uri=https%3A%2F%2Fb.test");
}

TEST(AndroidOAuthResponseParserTest, IgnoresUnknownWellFormedParameters) {
  const auto result =
      Parse(base::StrCat({kSuccess, "&future=one&future=two&empty="}));
  ASSERT_TRUE(result.has_value());
  EXPECT_EQ((std::set<std::string>{"read", "write"}), result->granted_scopes);
}

TEST(AndroidOAuthResponseParserTest, RejectsMalformedFormData) {
  for (const std::string_view extra :
       {"scope=%", "scope=%1", "scope=%GG", "sc%ope=read", "future=%GG",
        "=value", "missing_equals", "&scope=read"}) {
    SCOPED_TRACE(extra);
    ExpectInvalid(base::StrCat({kSuccess, "&", extra}));
  }
  ExpectInvalid(base::StrCat({"&", kSuccess}));
  ExpectInvalid(base::StrCat({kSuccess, "&"}));
  ExpectInvalid("");
}

TEST(AndroidOAuthResponseParserTest, RequiresCurrentStateForSuccessAndError) {
  ExpectInvalid("access_token=token&token_type=Bearer&expires_in=3600");
  ExpectInvalid("state=&access_token=token&token_type=Bearer&expires_in=3600");
  ExpectInvalid(
      "state=old-state&access_token=token&token_type=Bearer&"
      "expires_in=3600");
  ExpectInvalid(
      "state=fresh-flow-state%00&access_token=token&"
      "token_type=Bearer&expires_in=3600");
  ExpectInvalid("error=access_denied");
  ExpectInvalid("state=old-state&error=access_denied");
  const auto replay = Parse(kSuccess, {"read", "write"}, "next-flow-state");
  ASSERT_FALSE(replay.has_value());
  EXPECT_EQ(ErrorState::kInvalidConsentResult, replay.error().state());
  const auto no_active_flow = Parse(kSuccess, {"read", "write"}, "");
  ASSERT_FALSE(no_active_flow.has_value());
}

TEST(AndroidOAuthResponseParserTest, DecodesStateExactlyOnce) {
  const auto result = Parse(
      "state=nonce%2Bpart%2520&access_token=token&token_type=Bearer&expires_in="
      "1",
      {"read"}, "nonce+part%20");
  EXPECT_TRUE(result.has_value());
  const auto wrong = Parse(
      "state=nonce+part%2520&access_token=token&token_type=Bearer&expires_in=1",
      {"read"}, "nonce+part%20");
  EXPECT_FALSE(wrong.has_value());
}

TEST(AndroidOAuthResponseParserTest, RequiresExactEndpointBeforeParsing) {
  for (const std::string_view endpoint :
       {"com.example.oauth:/different", "com.example.oauth://host/",
        "com.example.oauth:/?alternate=1", "com.other.oauth:/",
        "https://example.test/"}) {
    SCOPED_TRACE(endpoint);
    const auto result =
        ParseAndroidOAuthResponse(GURL(base::StrCat({endpoint, "#", kSuccess})),
                                  GURL(kRedirect), kState, {"read", "write"});
    ASSERT_FALSE(result.has_value());
    EXPECT_EQ(ErrorState::kInvalidConsentResult, result.error().state());
  }
  EXPECT_FALSE(
      ParseAndroidOAuthResponse(GURL(), GURL(kRedirect), kState, {"read"})
          .has_value());
  EXPECT_FALSE(
      ParseAndroidOAuthResponse(GURL(kRedirect), GURL(), kState, {"read"})
          .has_value());
  EXPECT_FALSE(
      ParseAndroidOAuthResponse(GURL(base::StrCat({kRedirect, "#", kSuccess})),
                                GURL(base::StrCat({kRedirect, "#unexpected"})),
                                kState, {"read"})
          .has_value());
}

TEST(AndroidOAuthResponseParserTest, RejectsInvalidRequestScopes) {
  const auto empty = Parse(kSuccess, {});
  ASSERT_FALSE(empty.has_value());
  EXPECT_EQ(ErrorState::kInvalidConsentResult, empty.error().state());
  for (const std::string_view scope : {"", "read write", "read\twrite"}) {
    SCOPED_TRACE(scope);
    const auto result = Parse(kSuccess, {std::string(scope)});
    ASSERT_FALSE(result.has_value());
    EXPECT_EQ(ErrorState::kInvalidConsentResult, result.error().state());
  }
}

TEST(AndroidOAuthResponseParserTest,
     MapsValidatedProviderErrorsWithoutLeakage) {
  const auto denied = Parse(
      "state=fresh-flow-state&error=access_denied&"
      "error_description=private+provider+text");
  ASSERT_FALSE(denied.has_value());
  EXPECT_EQ(ErrorState::kRemoteConsentFlowRejected, denied.error().state());
  EXPECT_EQ(std::string::npos, denied.error().ToString().find("private"));
  const auto other = Parse(
      "state=fresh-flow-state&error=server_error&"
      "error_uri=https%3A%2F%2Fprivate.test%2F");
  ASSERT_FALSE(other.has_value());
  EXPECT_EQ(ErrorState::kNoGrant, other.error().state());
  EXPECT_EQ(std::string::npos, other.error().ToString().find("private"));
}

TEST(AndroidOAuthResponseParserTest, RejectsMixedOrMalformedErrorResponses) {
  ExpectInvalid("state=fresh-flow-state&error=");
  ExpectInvalid("state=fresh-flow-state&error=access_denied%00");
  for (const std::string_view extra :
       {"access_token=token", "token_type=Bearer", "expires_in=3600",
        "scope=read"}) {
    SCOPED_TRACE(extra);
    ExpectInvalid(
        base::StrCat({"state=fresh-flow-state&error=access_denied&", extra}));
  }
  ExpectInvalid(base::StrCat({kSuccess, "&error_description=unexpected"}));
  ExpectInvalid(base::StrCat({kSuccess, "&error_uri=https%3A%2F%2Fa.test"}));
}

TEST(AndroidOAuthResponseParserTest, RequiresNonemptyBearerTokenAndTokenType) {
  ExpectInvalid("state=fresh-flow-state&token_type=Bearer&expires_in=3600");
  ExpectInvalid("state=fresh-flow-state&access_token=token&expires_in=3600");
  for (const std::string_view type : {"", "MAC", "%20Bearer", "Bearer%00"}) {
    SCOPED_TRACE(type);
    ExpectInvalid(base::StrCat({"state=fresh-flow-state&access_token=token&",
                                "expires_in=3600&token_type=", type}));
  }
  for (const std::string_view token :
       {"", "a+b", "%20token", "%00token", "%0Atoken", "token%7F", "%C3%A9",
        "=token", "a=b", "token%25"}) {
    SCOPED_TRACE(token);
    ExpectInvalid(base::StrCat({"state=fresh-flow-state&access_token=", token,
                                "&token_type=Bearer&expires_in=3600"}));
  }
}

TEST(AndroidOAuthResponseParserTest, RequiresPositiveBoundedDecimalExpiry) {
  ExpectInvalid("state=fresh-flow-state&access_token=token&token_type=Bearer");
  for (const std::string_view expiry :
       {"", "0", "-1", "%2B1", "+1", "%201", "1%20", "1.5", "1e3", "2147483648",
        "999999999999999999999999"}) {
    SCOPED_TRACE(expiry);
    ExpectInvalid(base::StrCat({"state=fresh-flow-state&access_token=token&",
                                "token_type=Bearer&expires_in=", expiry}));
  }
  for (const std::string_view expiry : {"1", "0001", "2147483647"}) {
    SCOPED_TRACE(expiry);
    const auto result =
        Parse(base::StrCat({"state=fresh-flow-state&access_token=token&token_"
                            "type=Bearer&expires_in=",
                            expiry}));
    EXPECT_TRUE(result.has_value());
  }
}

}  // namespace
}  // namespace extensions::internal
