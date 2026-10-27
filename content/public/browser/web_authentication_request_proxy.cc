// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "content/public/browser/web_authentication_request_proxy.h"

#include "content/public/browser/content_browser_client.h"
#include "content/public/browser/web_authentication_delegate.h"
#include "content/public/common/content_client.h"

namespace content {

WebAuthenticationRequestProxy::WebAuthenticationRequestProxy() = default;
WebAuthenticationRequestProxy::~WebAuthenticationRequestProxy() = default;

// static
WebAuthenticationRequestProxy*
WebAuthenticationRequestProxy::MaybeGetRequestProxy(
    BrowserContext* browser_context,
    const url::Origin& caller_origin) {
  return GetContentClient()
      ->browser()
      ->GetWebAuthenticationDelegate()
      ->MaybeGetRequestProxy(browser_context, caller_origin);
}

// static
bool WebAuthenticationRequestProxy::IsSecurityLevelAcceptableForWebAuthn(
    RenderFrameHost* render_frame_host,
    const url::Origin& caller_origin) {
  return GetContentClient()->browser()->IsSecurityLevelAcceptableForWebAuthn(
      render_frame_host, caller_origin);
}

}  // namespace content
