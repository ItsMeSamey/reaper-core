// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
#ifndef BRAVE_BROWSER_UI_ANDROID_DEVICE_CHOOSER_ACTIVE_DEVICE_CHOOSER_REQUEST_H_
#define BRAVE_BROWSER_UI_ANDROID_DEVICE_CHOOSER_ACTIVE_DEVICE_CHOOSER_REQUEST_H_

#include "base/callback_list.h"
#include "base/functional/callback.h"
#include "base/memory/weak_ptr.h"
#include "content/public/browser/weak_document_ptr.h"
#include "content/public/browser/web_contents_observer.h"

class BrowserWindowInterface;
namespace tabs {
class TabInterface;
}

// A chooser belongs to the requesting document in the activated tab, not merely
// to a visible pane. Once invalidated it cannot revive, including after BFCache
// restoration or switching back before a posted result is delivered.
class ActiveDeviceChooserRequest final : public content::WebContentsObserver {
 public:
  explicit ActiveDeviceChooserRequest(content::RenderFrameHost* frame,
                                      base::OnceClosure on_invalidated = {});
  ~ActiveDeviceChooserRequest() override;
  bool IsValid() const;
  void ClearCancellationCallback();

 private:
  void Invalidate();
  void RunCancellation();
  bool IsRequestOrAncestor(content::RenderFrameHost* frame) const;
  void DidStartNavigation(content::NavigationHandle* navigation) override;
  void DidFinishNavigation(content::NavigationHandle* navigation) override;
  void RenderFrameDeleted(content::RenderFrameHost* frame) override;
  void RenderFrameHostStateChanged(
      content::RenderFrameHost* frame,
      content::RenderFrameHost::LifecycleState old_state,
      content::RenderFrameHost::LifecycleState new_state) override;
  void WebContentsDestroyed() override;

  const content::WeakDocumentPtr document_;
  base::OnceClosure on_invalidated_;
  bool invalidated_ = false;
  bool extension_host_ = false;
  base::WeakPtr<BrowserWindowInterface> owner_browser_;
  base::WeakPtr<tabs::TabInterface> owner_tab_;
  base::CallbackListSubscription deactivation_;
  base::CallbackListSubscription detachment_;
  base::CallbackListSubscription hidden_;
  base::CallbackListSubscription discard_;
  base::WeakPtrFactory<ActiveDeviceChooserRequest> weak_factory_{this};
};
#endif  // BRAVE_BROWSER_UI_ANDROID_DEVICE_CHOOSER_ACTIVE_DEVICE_CHOOSER_REQUEST_H_
