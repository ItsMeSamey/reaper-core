// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "brave/browser/extensions/reaper_selection_menu_android.h"

#include <cstdint>
#include <utility>

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/memory/scoped_refptr.h"
#include "brave/browser/extensions/android/selection/selection_context_menu_params.h"
#include "chrome/browser/extensions/extension_menu_model_android.h"
#include "chrome/browser/extensions/menu_manager.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/context_menu_params.h"
#include "content/public/browser/navigation_handle.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/web_contents.h"
#include "extensions/browser/extension_registry.h"
#include "extensions/common/extension.h"
#include "ui/menus/android/menu_model_bridge.h"

// Must follow headers specializing FromJniType() / ToJniType().
#include "brave/browser/extensions/android/selection/jni_headers/ReaperSelectionMenu_jni.h"

namespace extensions {
namespace {

constexpr size_t kMaxSelectionLength = 64 * 1024;

// ExtensionMenuModel stores a raw frame pointer. Its delegate is shared by all
// nested menus; guard every command against replacement or inactive documents.
class DocumentExtensionMenuModel final : public ExtensionMenuModel {
 public:
  DocumentExtensionMenuModel(content::RenderFrameHost& frame,
                             const content::ContextMenuParams& params)
      : ExtensionMenuModel(frame, params),
        document_(frame.GetWeakDocumentPtr()) {}

  bool IsCommandIdEnabled(int command_id) const override {
    return IsDocumentValid() &&
           ExtensionMenuModel::IsCommandIdEnabled(command_id);
  }

  void ExecuteCommand(int command_id, int event_flags) override {
    if (!IsCommandIdEnabled(command_id)) {
      return;
    }
    ExtensionMenuModel::ExecuteCommand(command_id, event_flags);
  }

 private:
  bool IsDocumentValid() const {
    auto* frame = document_.AsRenderFrameHostIfValid();
    return frame && frame->IsActive() && frame->IsRenderFrameLive();
  }

  const content::WeakDocumentPtr document_;
};

}  // namespace

std::unique_ptr<ReaperSelectionMenu> ReaperSelectionMenu::Create(
    content::WebContents* web_contents,
    content::RenderFrameHost* frame,
    const std::u16string& selected_text,
    bool editable,
    const base::android::JavaRef<jobject>& java_owner) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!web_contents || web_contents->IsBeingDestroyed() || !frame ||
      java_owner.is_null() || selected_text.empty() ||
      selected_text.size() > kMaxSelectionLength || !frame->IsActive() ||
      !frame->IsRenderFrameLive() ||
      content::WebContents::FromRenderFrameHost(frame) != web_contents ||
      web_contents->GetFocusedFrame() != frame ||
      !MenuManager::Get(web_contents->GetBrowserContext()) ||
      !ExtensionRegistry::Get(web_contents->GetBrowserContext())) {
    return nullptr;
  }

  const content::ContextMenuParams params =
      BuildSelectionContextMenuParams(*frame, selected_text, editable);
  auto menu = std::unique_ptr<ReaperSelectionMenu>(
      new ReaperSelectionMenu(web_contents, *frame, params, java_owner));
  if (!menu->menu_bridge_ || !menu->IsDocumentValid()) {
    return nullptr;
  }
  auto* registry = ExtensionRegistry::Get(web_contents->GetBrowserContext());
  for (const auto& extension : menu->represented_extensions_) {
    if (registry->enabled_extensions().GetByID(extension->id()) !=
        extension.get()) {
      return nullptr;
    }
  }
  // Observe only a fully constructed owner. AddObserver does not run callbacks;
  // no task can intervene before the caller publishes this handle to Java.
  menu->registry_observation_.Observe(registry);
  return menu;
}

ReaperSelectionMenu::ReaperSelectionMenu(
    content::WebContents* web_contents,
    content::RenderFrameHost& frame,
    const content::ContextMenuParams& params,
    const base::android::JavaRef<jobject>& java_owner)
    : content::WebContentsObserver(web_contents),
      frame_id_(frame.GetGlobalId()),
      frame_tree_node_id_(frame.GetFrameTreeNodeId()),
      document_(frame.GetWeakDocumentPtr()),
      java_owner_(java_owner),
      menu_model_(std::make_unique<DocumentExtensionMenuModel>(frame, params)) {
  menu_model_->PopulateModel();
  auto* registry = ExtensionRegistry::Get(web_contents->GetBrowserContext());
  for (const auto& id : menu_model_->GetVisibleExtensionIds()) {
    const auto* extension = registry->enabled_extensions().GetByID(id);
    if (!extension) {
      return;
    }
    represented_extensions_.Insert(base::WrapRefCounted(extension));
  }
  if (represented_extensions_.empty()) {
    return;
  }
  for (size_t i = 0; i < menu_model_->GetItemCount(); ++i) {
    if (menu_model_->IsVisibleAt(i) &&
        menu_model_->GetTypeAt(i) != ui::MenuModel::TYPE_SEPARATOR) {
      menu_bridge_ =
          std::make_unique<ui::MenuModelBridge>(menu_model_->AsWeakPtr());
      break;
    }
  }
}

ReaperSelectionMenu::~ReaperSelectionMenu() {
  invalidated_ = true;
  registry_observation_.Reset();
  Observe(nullptr);
}

base::android::ScopedJavaLocalRef<jobject> ReaperSelectionMenu::GetMenuModel(
    JNIEnv* env) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!IsDocumentValid() || !menu_bridge_) {
    return nullptr;
  }
  return base::android::ScopedJavaLocalRef<jobject>(
      menu_bridge_->GetJavaObject());
}

void ReaperSelectionMenu::Destroy(JNIEnv* env) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!invalidated_) {
    delete this;
  }
}

void ReaperSelectionMenu::RenderFrameDeleted(content::RenderFrameHost* frame) {
  if (frame->GetGlobalId() == frame_id_) {
    Invalidate();
  }
}

void ReaperSelectionMenu::RenderFrameHostStateChanged(
    content::RenderFrameHost* frame,
    content::RenderFrameHost::LifecycleState old_state,
    content::RenderFrameHost::LifecycleState new_state) {
  if (frame->GetGlobalId() == frame_id_ &&
      new_state != content::RenderFrameHost::LifecycleState::kActive) {
    Invalidate();
  }
}

void ReaperSelectionMenu::DidFinishNavigation(
    content::NavigationHandle* navigation) {
  // Also dismiss on same-document navigation: the captured URL and selection
  // should never be attributed to a later state of the frame.
  if (navigation->HasCommitted() &&
      (navigation->IsInPrimaryMainFrame() ||
       navigation->GetFrameTreeNodeId() == frame_tree_node_id_)) {
    Invalidate();
  }
}

void ReaperSelectionMenu::PrimaryPageChanged(content::Page& page) {
  Invalidate();
}

void ReaperSelectionMenu::WebContentsDestroyed() {
  Invalidate();
}

void ReaperSelectionMenu::OnExtensionUnloaded(
    content::BrowserContext* browser_context,
    const Extension* extension,
    UnloadedExtensionReason reason) {
  if (represented_extensions_.GetByID(extension->id()) == extension) {
    Invalidate();
  }
}

void ReaperSelectionMenu::OnShutdown(ExtensionRegistry* registry) {
  Invalidate();
}

bool ReaperSelectionMenu::IsDocumentValid() const {
  auto* frame = document_.AsRenderFrameHostIfValid();
  return !invalidated_ && web_contents() &&
         !web_contents()->IsBeingDestroyed() && frame && frame->IsActive() &&
         frame->IsRenderFrameLive();
}

void ReaperSelectionMenu::Invalidate() {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (invalidated_) {
    return;
  }
  invalidated_ = true;
  registry_observation_.Reset();
  Observe(nullptr);
  // Retire all native command handles before Java dismissal can reenter. The
  // Java item lists and native models stay alive until that callback returns.
  menu_bridge_.reset();
  Java_ReaperSelectionMenu_onNativeDestroyed(
      base::android::AttachCurrentThread(), java_owner_);
  delete this;
}

base::android::ScopedJavaLocalRef<jobject> ReaperSelectionMenu::CreateForTesting(
    JNIEnv* env,
    const base::android::JavaRef<jobject>& tab,
    const std::u16string& selected_text) {
  return Java_ReaperSelectionMenu_createForTesting(env, tab, selected_text);
}

void ReaperSelectionMenu::DestroyForTesting(
    JNIEnv* env,
    const base::android::JavaRef<jobject>& owner) {
  Java_ReaperSelectionMenu_destroyForTesting(env, owner);
}

base::android::ScopedJavaLocalRef<jobject> ReaperSelectionMenu::GetItemForTesting(
    JNIEnv* env,
    const base::android::JavaRef<jobject>& owner,
    const std::u16string& title) {
  return Java_ReaperSelectionMenu_getItemForTesting(env, owner, title);
}

bool ReaperSelectionMenu::IsClosedForTesting(
    JNIEnv* env,
    const base::android::JavaRef<jobject>& owner) {
  return Java_ReaperSelectionMenu_isClosedForTesting(env, owner);
}

bool ReaperSelectionMenu::ClickItemForTesting(
    JNIEnv* env,
    const base::android::JavaRef<jobject>& item) {
  return Java_ReaperSelectionMenu_clickItemForTesting(env, item);
}

static int64_t JNI_ReaperSelectionMenu_Init(
    JNIEnv* env,
    content::WebContents* web_contents,
    content::RenderFrameHost* frame,
    const std::u16string& selected_text,
    bool editable,
    const base::android::JavaRef<jobject>& java_owner) {
  auto menu = ReaperSelectionMenu::Create(web_contents, frame, selected_text,
                                          editable, java_owner);
  return reinterpret_cast<int64_t>(menu.release());
}

}  // namespace extensions

DEFINE_JNI(ReaperSelectionMenu)
