// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
#include "brave/browser/ui/android/device_chooser/active_device_chooser_request.h"

#include <utility>

#include "base/functional/bind.h"
#include "base/task/sequenced_task_runner.h"
#include "chrome/browser/profiles/profile.h"
#include "chrome/browser/tab_list/tab_list_interface.h"
#include "chrome/browser/ui/browser_window/public/browser_window_interface.h"
#include "extensions/buildflags/buildflags.h"
#include "ui/base/base_window.h"
#if BUILDFLAG(ENABLE_EXTENSIONS_CORE)
#include "chrome/browser/extensions/window_controller.h"
#include "extensions/browser/extension_host.h"
#include "extensions/browser/extension_host_registry.h"
#include "extensions/browser/extension_registry.h"
#include "extensions/browser/extension_util.h"
#include "extensions/common/extension.h"
#include "extensions/common/manifest_handlers/incognito_info.h"
#include "extensions/common/mojom/view_type.mojom.h"
#endif
#include "chrome/browser/ui/android/tab_model/tab_model.h"
#include "chrome/browser/ui/android/tab_model/tab_model_list.h"
#include "components/tabs/public/tab_interface.h"
#include "content/public/browser/navigation_handle.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/web_contents.h"
#include "ui/android/view_android.h"

namespace {
BrowserWindowInterface* GetChooserExtensionBrowser(
    content::WebContents* contents) {
#if BUILDFLAG(ENABLE_EXTENSIONS_CORE)
  auto* profile = Profile::FromBrowserContext(contents->GetBrowserContext());
  auto* registry = extensions::ExtensionHostRegistry::Get(profile);
  auto* host = registry ? registry->GetExtensionHostForPrimaryMainFrame(
                              contents->GetPrimaryMainFrame())
                        : nullptr;
  if (!host || host->host_contents() != contents || !host->extension() ||
      (host->extension_host_type() !=
           extensions::mojom::ViewType::kExtensionPopup &&
       host->extension_host_type() !=
           extensions::mojom::ViewType::kExtensionSidePanel) ||
      !extensions::ExtensionRegistry::Get(profile)
           ->enabled_extensions()
           .Contains(host->extension()->id())) {
    return nullptr;
  }
  // These view hosts keep the exact weak browser/tab supplied to their factory.
  // A last-focused browser is never a replacement for a vanished owner.
  auto* controller = host->GetExtensionWindowController();
  auto* browser =
      controller ? controller->GetBrowserWindowInterface() : nullptr;
  if (!browser || !browser->GetWindow())
    return nullptr;
  Profile* owner_profile = browser->GetProfile();
  if (owner_profile != profile &&
      (!owner_profile->IsOffTheRecord() ||
       owner_profile->GetOriginalProfile() != profile ||
       extensions::IncognitoInfo::IsSplitMode(host->extension()) ||
       !extensions::util::IsIncognitoEnabled(host->extension()->id(),
                                             owner_profile))) {
    return nullptr;
  }
  return browser;
#else
  return nullptr;
#endif
}

}  // namespace

ActiveDeviceChooserRequest::ActiveDeviceChooserRequest(
    content::RenderFrameHost* frame,
    base::OnceClosure on_invalidated)
    : content::WebContentsObserver(
          frame ? content::WebContents::FromRenderFrameHost(frame) : nullptr),
      document_(frame ? frame->GetWeakDocumentPtr()
                      : content::WeakDocumentPtr()),
      on_invalidated_(std::move(on_invalidated)) {
  auto* contents = web_contents();
  auto* tab =
      contents ? tabs::TabInterface::MaybeGetFromContents(contents) : nullptr;
  if (!tab && contents) {
    if (auto* browser = GetChooserExtensionBrowser(contents)) {
      extension_host_ = true;
      owner_browser_ = browser->GetWeakPtr();
      auto* list = TabListInterface::From(browser);
      tab = list ? list->GetActiveTab() : nullptr;
    }
  }
  if (tab)
    owner_tab_ = tab->GetWeakPtr();
  if (!IsValid() ||
      (extension_host_ &&
       (!owner_browser_->GetWindow()->IsActive() ||
        !contents->GetNativeView()->IsAttachedAndVisibleWithWindowFocus()))) {
    Invalidate();
    return;
  }
  deactivation_ = tab->RegisterWillDeactivate(
      base::BindRepeating([](ActiveDeviceChooserRequest* self,
                             tabs::TabInterface*) { self->Invalidate(); },
                          base::Unretained(this)));
  hidden_ = tab->RegisterWillBecomeHidden(
      base::BindRepeating([](ActiveDeviceChooserRequest* self,
                             tabs::TabInterface*) { self->Invalidate(); },
                          base::Unretained(this)));
  detachment_ = tab->RegisterWillDetach(base::BindRepeating(
      [](ActiveDeviceChooserRequest* self, tabs::TabInterface*,
         tabs::TabInterface::DetachReason) { self->Invalidate(); },
      base::Unretained(this)));
  discard_ = tab->RegisterWillDiscardContents(base::BindRepeating(
      [](ActiveDeviceChooserRequest* self, tabs::TabInterface*,
         content::WebContents*, content::WebContents*) { self->Invalidate(); },
      base::Unretained(this)));
}

ActiveDeviceChooserRequest::~ActiveDeviceChooserRequest() = default;

bool ActiveDeviceChooserRequest::IsValid() const {
  auto* frame = document_.AsRenderFrameHostIfValid();
  auto* contents = web_contents();
  if (invalidated_ || !frame || !frame->IsActive() || !contents ||
      contents->IsBeingDestroyed() ||
      content::WebContents::FromRenderFrameHost(frame) != contents ||
      !contents->GetNativeView() ||
      !contents->GetNativeView()->GetWindowAndroid()) {
    return false;
  }
  auto* tab = owner_tab_.get();
  auto* owner_contents = tab ? tab->GetContents() : nullptr;
  auto* model = owner_contents
                    ? TabModelList::GetTabModelForWebContents(owner_contents)
                    : nullptr;
  if (!tab || !owner_contents || owner_contents->IsBeingDestroyed() ||
      tabs::TabInterface::MaybeGetFromContents(owner_contents) != tab ||
      !tab->IsActivated() || !tab->IsVisible() || !model ||
      !model->IsActiveModel() ||
      model->GetProfile() !=
          Profile::FromBrowserContext(owner_contents->GetBrowserContext()) ||
      tab->GetProfile() != model->GetProfile())
    return false;
  if (!extension_host_)
    return owner_contents == contents;
  if (!owner_browser_ ||
      GetChooserExtensionBrowser(contents) != owner_browser_.get() ||
      owner_browser_->GetProfile() != tab->GetProfile())
    return false;
  auto* list = TabListInterface::From(owner_browser_.get());
  // The chooser may suspend the popup's Android view. Its exact document,
  // factory owner and original selected tab must still own the final grant.
  return list && list->GetActiveTab() == tab;
}

void ActiveDeviceChooserRequest::ClearCancellationCallback() {
  on_invalidated_.Reset();
  weak_factory_.InvalidateWeakPtrs();
}

void ActiveDeviceChooserRequest::Invalidate() {
  if (invalidated_)
    return;
  invalidated_ = true;
  // Notification can happen while the Java dialog is being constructed. Latch
  // rejection now, but let the caller acquire ownership before closing its UI.
  if (on_invalidated_) {
    base::SequencedTaskRunner::GetCurrentDefault()->PostTask(
        FROM_HERE, base::BindOnce(&ActiveDeviceChooserRequest::RunCancellation,
                                  weak_factory_.GetWeakPtr()));
  }
}

void ActiveDeviceChooserRequest::RunCancellation() {
  // The callback may destroy this request and its chooser. No accesses follow.
  std::move(on_invalidated_).Run();
}

bool ActiveDeviceChooserRequest::IsRequestOrAncestor(
    content::RenderFrameHost* frame) const {
  for (auto* current = document_.AsRenderFrameHostIfValid(); current;
       current = current->GetParentOrOuterDocument()) {
    if (current == frame)
      return true;
  }
  return false;
}

void ActiveDeviceChooserRequest::DidStartNavigation(
    content::NavigationHandle* navigation) {
  if (navigation->IsSameDocument())
    return;
  for (auto* frame = document_.AsRenderFrameHostIfValid(); frame;
       frame = frame->GetParentOrOuterDocument()) {
    if (navigation->GetFrameTreeNodeId() == frame->GetFrameTreeNodeId()) {
      Invalidate();
      return;
    }
  }
}

void ActiveDeviceChooserRequest::DidFinishNavigation(
    content::NavigationHandle* navigation) {
  if (!IsValid())
    Invalidate();
}

void ActiveDeviceChooserRequest::RenderFrameDeleted(
    content::RenderFrameHost* frame) {
  if (IsRequestOrAncestor(frame) || !IsValid())
    Invalidate();
}

void ActiveDeviceChooserRequest::RenderFrameHostStateChanged(
    content::RenderFrameHost* frame,
    content::RenderFrameHost::LifecycleState old_state,
    content::RenderFrameHost::LifecycleState new_state) {
  if (!IsValid() ||
      (IsRequestOrAncestor(frame) &&
       new_state != content::RenderFrameHost::LifecycleState::kActive)) {
    Invalidate();
  }
}

void ActiveDeviceChooserRequest::WebContentsDestroyed() {
  Invalidate();
}
