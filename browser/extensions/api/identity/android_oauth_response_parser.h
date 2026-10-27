/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef BRAVE_BROWSER_EXTENSIONS_API_IDENTITY_ANDROID_OAUTH_RESPONSE_PARSER_H_
#define BRAVE_BROWSER_EXTENSIONS_API_IDENTITY_ANDROID_OAUTH_RESPONSE_PARSER_H_

#include <set>
#include <string>
#include <string_view>

#include "base/time/time.h"
#include "base/types/expected.h"
#include "chrome/browser/extensions/api/identity/identity_get_auth_token_error.h"

class GURL;

namespace extensions::internal {

struct ParsedAndroidOAuthResponse {
  std::string access_token;
  std::set<std::string> granted_scopes;
  base::TimeDelta time_to_live;
};

// Parses this flow's implicit OAuth response, without performing browser,
// network, cache, or callback operations. The caller consumes its one-use
// state. expected_redirect must be the complete requested redirect URL without
// a fragment. requested_scopes must be a nonempty set of RFC 6749 scope tokens
// actually requested.
base::expected<ParsedAndroidOAuthResponse, IdentityGetAuthTokenError>
ParseAndroidOAuthResponse(const GURL& actual_redirect,
                          const GURL& expected_redirect,
                          std::string_view expected_state,
                          const std::set<std::string>& requested_scopes);

}  // namespace extensions::internal

#endif  // BRAVE_BROWSER_EXTENSIONS_API_IDENTITY_ANDROID_OAUTH_RESPONSE_PARSER_H_
