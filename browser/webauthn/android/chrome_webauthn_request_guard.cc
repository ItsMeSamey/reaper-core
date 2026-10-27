// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
#include "brave/browser/webauthn/android/chrome_webauthn_request_guard.h"

#include <utility>

#include "base/callback_list.h"
#include "base/functional/bind.h"
#include "base/functional/callback.h"
#include "base/memory/raw_ptr.h"
#include "base/memory/weak_ptr.h"
#include "base/task/sequenced_task_runner.h"
#include "chrome/browser/profiles/profile.h"
#include "chrome/browser/tab_list/tab_list_interface.h"
#include "chrome/browser/ui/android/tab_model/tab_model.h"
#include "chrome/browser/ui/android/tab_model/tab_model_list.h"
#include "chrome/browser/ui/android/tab_model/tab_model_observer.h"
#include "chrome/browser/ui/browser_window/public/browser_window_interface.h"
#include "components/tabs/public/tab_interface.h"
#include "content/public/browser/navigation_handle.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/weak_document_ptr.h"
#include "content/public/browser/web_contents.h"
#include "content/public/browser/web_contents_observer.h"
#include "extensions/buildflags/buildflags.h"
#include "ui/android/view_android.h"
#include "ui/base/base_window.h"

#if BUILDFLAG(ENABLE_EXTENSIONS_CORE)
#include "chrome/browser/extensions/extension_view_host.h"
#include "chrome/browser/extensions/window_controller.h"
#include "extensions/browser/extension_host_registry.h"
#include "extensions/browser/extension_registry.h"
#include "extensions/browser/extension_util.h"
#include "extensions/common/extension.h"
#include "extensions/common/manifest_handlers/incognito_info.h"
#include "extensions/common/mojom/view_type.mojom.h"
#endif

namespace {

BrowserWindowInterface* GetExtensionRequestBrowser(
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
  auto* controller = static_cast<extensions::ExtensionViewHost*>(host)
                         ->GetExtensionWindowController();
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

class ChromeWebAuthnRequestGuard final : public webauthn::WebAuthnRequestGuard,
                                         public content::WebContentsObserver,
                                         public TabModelObserver {
 public:
  ChromeWebAuthnRequestGuard(content::RenderFrameHost* frame,
                             bool conditional,
                             base::OnceClosure on_cancel)
      : content::WebContentsObserver(
            frame ? content::WebContents::FromRenderFrameHost(frame) : nullptr),
        document_(frame ? frame->GetWeakDocumentPtr()
                        : content::WeakDocumentPtr()),
        on_cancel_(std::move(on_cancel)) {
    auto* contents = web_contents();
    auto* tab =
        contents ? tabs::TabInterface::MaybeGetFromContents(contents) : nullptr;
#if BUILDFLAG(ENABLE_EXTENSIONS_CORE)
    if (!tab && contents) {
      if (auto* browser = GetExtensionRequestBrowser(contents)) {
        extension_host_ = true;
        owner_browser_ = browser->GetWeakPtr();
        auto* tab_list = TabListInterface::From(browser);
        tab = tab_list ? tab_list->GetActiveTab() : nullptr;
      }
    }
#endif
    if (tab)
      owner_tab_ = tab->GetWeakPtr();
    model_ = tab && tab->GetContents()
                 ? TabModelList::GetTabModelForWebContents(tab->GetContents())
                 : nullptr;
    if (model_)
      model_->AddObserver(this);
    if (!IsValid()) {
      Invalidate();
      return;
    }
    deactivation_ = tab->RegisterWillDeactivate(base::BindRepeating(
        [](ChromeWebAuthnRequestGuard* self, tabs::TabInterface*) {
          if (self->interactive_)
            self->Invalidate();
        },
        base::Unretained(this)));
    detachment_ = tab->RegisterWillDetach(base::BindRepeating(
        [](ChromeWebAuthnRequestGuard* self, tabs::TabInterface*,
           tabs::TabInterface::DetachReason) { self->Invalidate(); },
        base::Unretained(this)));
    discard_ = tab->RegisterWillDiscardContents(
        base::BindRepeating([](ChromeWebAuthnRequestGuard* self,
                               tabs::TabInterface*, content::WebContents*,
                               content::WebContents*) { self->Invalidate(); },
                            base::Unretained(this)));
    if (!conditional)
      BeginInteraction();
  }

  ~ChromeWebAuthnRequestGuard() override {
    weak_factory_.InvalidateWeakPtrs();
    StopObservingModel();
  }

  bool IsValid() const override {
    auto* frame = document_.AsRenderFrameHostIfValid();
    auto* contents = web_contents();
    auto* model = model_.get();
    auto* tab = owner_tab_.get();
    if (invalidated_ || !frame || !frame->IsActive() || !contents ||
        contents->IsBeingDestroyed() || !model || !tab || !tab->GetContents() ||
        tab->GetContents()->IsBeingDestroyed() ||
        content::WebContents::FromRenderFrameHost(frame) != contents ||
        TabModelList::GetTabModelForWebContents(tab->GetContents()) != model ||
        tabs::TabInterface::MaybeGetFromContents(tab->GetContents()) != tab ||
        tab->GetProfile() != model->GetProfile() ||
        model->GetProfile() != Profile::FromBrowserContext(
                                   tab->GetContents()->GetBrowserContext())) {
      return false;
    }
    if (extension_host_) {
      return owner_browser_ &&
             GetExtensionRequestBrowser(contents) == owner_browser_.get() &&
             owner_browser_->GetProfile() == tab->GetProfile();
    }
    return tab->GetContents() == contents;
  }

  bool BeginInteraction() override {
    if (!CanCompleteRequest() || !owner_tab_->IsVisible()) {
      Invalidate();
      return false;
    }
#if BUILDFLAG(ENABLE_EXTENSIONS_CORE)
    if (extension_host_) {
      auto* view = web_contents()->GetNativeView();
      // Extension WebContents can outlive their actual popup/panel container.
      // Ask the Android delegate's real view at UI launch, never at OS return.
      if (!owner_browser_->GetWindow()->IsActive() || !view ||
          !view->IsAttachedAndVisibleWithWindowFocus()) {
        Invalidate();
        return false;
      }
    }
#endif
    interactive_ = true;
    return true;
  }

  bool CanCompleteRequest() const override {
    if (!IsValid())
      return false;
#if BUILDFLAG(ENABLE_EXTENSIONS_CORE)
    if (extension_host_) {
      auto* tab_list = TabListInterface::From(owner_browser_.get());
      if (!tab_list || tab_list->GetActiveTab() != owner_tab_.get())
        return false;
    }
#endif
    auto* contents = web_contents();
    // An Android authenticator activity can hide the browser while returning a
    // valid result. Actual pane/model switches are independently latched.
    return model_.get()->IsActiveModel() && owner_tab_->IsActivated() &&
           contents->GetNativeView() &&
           contents->GetNativeView()->GetWindowAndroid();
  }

 private:
  void Invalidate() {
    if (invalidated_)
      return;
    invalidated_ = true;
    deactivation_ = {};
    detachment_ = {};
    discard_ = {};
    StopObservingModel();
    // Do not call Java while a JNI request guard is still being constructed or
    // while tab/model observers are dispatching. Latch denial immediately.
    if (on_cancel_) {
      base::SequencedTaskRunner::GetCurrentDefault()->PostTask(
          FROM_HERE,
          base::BindOnce(&ChromeWebAuthnRequestGuard::RunCancellation,
                         weak_factory_.GetWeakPtr()));
    }
  }

  void StopObservingModel() {
    TabModel* model = model_;
    model_ = nullptr;
    if (model)
      model->RemoveObserver(this);
  }

  void RunCancellation() {
    // The owner can synchronously destroy this guard. No accesses follow.
    std::move(on_cancel_).Run();
  }

  bool IsRequestOrAncestor(content::RenderFrameHost* frame) const {
    for (auto* current = document_.AsRenderFrameHostIfValid(); current;
         current = current->GetParentOrOuterDocument()) {
      if (current == frame)
        return true;
    }
    return false;
  }

  void DidStartNavigation(content::NavigationHandle* navigation) override {
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

  void DidFinishNavigation(content::NavigationHandle* navigation) override {
    if (!IsValid())
      Invalidate();
  }

  void RenderFrameDeleted(content::RenderFrameHost* frame) override {
    if (IsRequestOrAncestor(frame) || !IsValid())
      Invalidate();
  }

  void RenderFrameHostStateChanged(
      content::RenderFrameHost* frame,
      content::RenderFrameHost::LifecycleState old_state,
      content::RenderFrameHost::LifecycleState new_state) override {
    if (!IsValid() ||
        (IsRequestOrAncestor(frame) &&
         new_state != content::RenderFrameHost::LifecycleState::kActive)) {
      Invalidate();
    }
  }

  void WebContentsDestroyed() override { Invalidate(); }

  void OnWillActiveStateChange(TabModel& model, bool active) override {
    if (interactive_ && !active)
      Invalidate();
  }

  void OnTabModelDestroyed(TabModel& model) override {
    // The dying source owns its observer cleanup. Forget it before cancellation
    // so no later destructor or posted callback accesses that model.
    model_ = nullptr;
    Invalidate();
  }

  const content::WeakDocumentPtr document_;
  base::OnceClosure on_cancel_;
  bool interactive_ = false;
  bool invalidated_ = false;
  bool extension_host_ = false;
  base::WeakPtr<BrowserWindowInterface> owner_browser_;
  base::WeakPtr<tabs::TabInterface> owner_tab_;
  base::CallbackListSubscription deactivation_;
  base::CallbackListSubscription detachment_;
  base::CallbackListSubscription discard_;
  raw_ptr<TabModel> model_ = nullptr;
  base::WeakPtrFactory<ChromeWebAuthnRequestGuard> weak_factory_{this};
};

}  // namespace

std::unique_ptr<webauthn::WebAuthnRequestGuard>
CreateChromeWebAuthnRequestGuard(content::RenderFrameHost* frame,
                                 bool conditional,
                                 base::OnceClosure on_cancel) {
  return std::make_unique<ChromeWebAuthnRequestGuard>(frame, conditional,
                                                      std::move(on_cancel));
}
