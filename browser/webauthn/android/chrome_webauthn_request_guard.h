// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
#ifndef BRAVE_BROWSER_WEBAUTHN_ANDROID_CHROME_WEBAUTHN_REQUEST_GUARD_H_
#define BRAVE_BROWSER_WEBAUTHN_ANDROID_CHROME_WEBAUTHN_REQUEST_GUARD_H_

#include <memory>

#include "base/functional/callback_forward.h"
#include "components/webauthn/android/webauthn_client_android.h"

// Binds one request to its original document and owning Android tab/model.
// This supplements, and never replaces, WebAuthn origin/RP security checks.
std::unique_ptr<webauthn::WebAuthnRequestGuard>
CreateChromeWebAuthnRequestGuard(content::RenderFrameHost* frame,
                                 bool conditional,
                                 base::OnceClosure on_cancel);

#endif  // BRAVE_BROWSER_WEBAUTHN_ANDROID_CHROME_WEBAUTHN_REQUEST_GUARD_H_
