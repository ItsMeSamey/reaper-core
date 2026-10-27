/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/browser/extensions/api/identity/android_oauth_response_parser.h"

#include <algorithm>
#include <functional>
#include <iterator>
#include <map>
#include <utility>

#include "base/strings/escape.h"
#include "base/strings/string_number_conversions.h"
#include "base/strings/string_split.h"
#include "base/strings/string_util.h"
#include "url/gurl.h"
#include "url/third_party/mozilla/url_parse.h"

namespace extensions::internal {
namespace {

using ParseResult =
    base::expected<ParsedAndroidOAuthResponse, IdentityGetAuthTokenError>;
using ErrorState = IdentityGetAuthTokenError::State;

constexpr std::string_view kRecognizedParameters[] = {
    "state", "access_token", "token_type",        "expires_in",
    "scope", "error",        "error_description", "error_uri"};

ParseResult InvalidResponse() {
  return base::unexpected(
      IdentityGetAuthTokenError(ErrorState::kInvalidConsentResult));
}

bool HasValidEscapes(std::string_view encoded) {
  for (size_t i = 0; i < encoded.size(); ++i) {
    if (encoded[i] != '%') {
      continue;
    }
    if (encoded.size() - i < 3 || !base::IsHexDigit(encoded[i + 1]) ||
        !base::IsHexDigit(encoded[i + 2])) {
      return false;
    }
    i += 2;
  }
  return true;
}

std::string DecodeFormComponent(std::string_view encoded) {
  return base::UnescapeBinaryURLComponent(
      encoded,
      base::UnescapeRule::NORMAL | base::UnescapeRule::REPLACE_PLUS_WITH_SPACE);
}

// RFC 6750 section 2.1: b64token, including optional trailing '=' padding.
// A percent-encoded '+' is token data; a literal form '+' decodes to a space
// and must not be accepted as token data.
bool IsBearerToken(std::string_view token) {
  const size_t padding = token.find('=');
  const auto data = token.substr(0, padding);
  return !data.empty() &&
         std::ranges::all_of(data,
                             [](char c) {
                               return base::IsAsciiAlphaNumeric(c) ||
                                      c == '-' || c == '.' || c == '_' ||
                                      c == '~' || c == '+' || c == '/';
                             }) &&
         (padding == std::string_view::npos ||
          std::ranges::all_of(token.substr(padding),
                              [](char c) { return c == '='; }));
}

bool HasScopeCharacters(std::string_view scope) {
  // RFC 6749 section 3.3: ASCII spaces separate case-sensitive scope tokens.
  // Quotes, backslashes, controls and non-ASCII bytes are not scope-token data.
  return std::ranges::all_of(scope, [](unsigned char c) {
    return c == ' ' || c == 0x21 || (c >= 0x23 && c <= 0x5b) ||
           (c >= 0x5d && c <= 0x7e);
  });
}

}  // namespace

ParseResult ParseAndroidOAuthResponse(
    const GURL& actual_redirect,
    const GURL& expected_redirect,
    std::string_view expected_state,
    const std::set<std::string>& requested_scopes) {
  if (!actual_redirect.is_valid() || !expected_redirect.is_valid() ||
      expected_redirect.has_ref() || expected_state.empty() ||
      requested_scopes.empty() ||
      std::ranges::any_of(requested_scopes,
                          [](const auto& scope) {
                            return scope.empty() ||
                                   scope.find(' ') != std::string::npos ||
                                   !HasScopeCharacters(scope);
                          }) ||
      actual_redirect.GetWithoutRef() != expected_redirect) {
    return InvalidResponse();
  }

  const std::string_view fragment = actual_redirect.ref();
  if (fragment.empty() || fragment.front() == '&' || fragment.back() == '&') {
    return InvalidResponse();
  }

  std::map<std::string, std::string, std::less<>> parameters;
  const std::string_view spec = actual_redirect.spec();
  url::Component remaining =
      actual_redirect.parsed_for_possibly_invalid_spec().ref;
  url::Component key;
  url::Component value;
  while (url::ExtractQueryKeyValue(spec, &remaining, &key, &value)) {
    // ExtractQueryKeyValue also accepts fields without '='. Require a proper
    // form pair here, including for unknown fields, before ignoring them.
    if (key.len == 0 || static_cast<size_t>(key.end()) >= spec.size() ||
        spec[key.end()] != '=') {
      return InvalidResponse();
    }
    const auto encoded_key = spec.substr(key.begin, key.len);
    const auto encoded_value = spec.substr(value.begin, value.len);
    if (!HasValidEscapes(encoded_key) || !HasValidEscapes(encoded_value)) {
      return InvalidResponse();
    }
    std::string name = DecodeFormComponent(encoded_key);
    if (std::ranges::find(kRecognizedParameters, name) ==
        std::end(kRecognizedParameters)) {
      // RFC 6749 section 4.2.2 requires ignoring unknown response parameters.
      continue;
    }
    std::string decoded = DecodeFormComponent(encoded_value);
    if (!std::ranges::all_of(
            decoded, [](char c) { return base::IsAsciiPrintable(c); }) ||
        !parameters.emplace(std::move(name), std::move(decoded)).second) {
      return InvalidResponse();
    }
  }

  const auto state = parameters.find("state");
  if (state == parameters.end() || state->second != expected_state) {
    return InvalidResponse();
  }

  const auto error = parameters.find("error");
  if (error != parameters.end()) {
    if (error->second.empty() || parameters.contains("access_token") ||
        parameters.contains("token_type") ||
        parameters.contains("expires_in") || parameters.contains("scope")) {
      return InvalidResponse();
    }
    return base::unexpected(
        IdentityGetAuthTokenError(error->second == "access_denied"
                                      ? ErrorState::kRemoteConsentFlowRejected
                                      : ErrorState::kNoGrant));
  }
  if (parameters.contains("error_description") ||
      parameters.contains("error_uri")) {
    return InvalidResponse();
  }

  const auto token = parameters.find("access_token");
  const auto token_type = parameters.find("token_type");
  const auto expiry = parameters.find("expires_in");
  int seconds = 0;
  if (token == parameters.end() || !IsBearerToken(token->second) ||
      token_type == parameters.end() ||
      !base::EqualsCaseInsensitiveASCII(token_type->second, "Bearer") ||
      expiry == parameters.end() || expiry->second.empty() ||
      !std::ranges::all_of(expiry->second,
                           [](char c) { return base::IsAsciiDigit(c); }) ||
      !base::StringToInt(expiry->second, &seconds) || seconds <= 0) {
    // The RFC permits omitted expiry if established by other means. This
    // adapter has no trusted default and retains its required-expiry policy.
    return InvalidResponse();
  }

  std::set<std::string> granted_scopes;
  const auto scope = parameters.find("scope");
  if (scope == parameters.end()) {
    // RFC 6749 section 4.2.2 permits omission only for an identical scope.
    granted_scopes = requested_scopes;
  } else {
    if (!HasScopeCharacters(scope->second)) {
      return InvalidResponse();
    }
    const auto scopes = base::SplitString(
        scope->second, " ", base::TRIM_WHITESPACE, base::SPLIT_WANT_NONEMPTY);
    if (scopes.empty()) {
      return InvalidResponse();
    }
    granted_scopes.insert(scopes.begin(), scopes.end());
  }

  return ParsedAndroidOAuthResponse{token->second, std::move(granted_scopes),
                                    base::Seconds(seconds)};
}

}  // namespace extensions::internal
