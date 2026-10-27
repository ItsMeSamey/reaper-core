// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#ifndef BRAVE_BROWSER_EXTENSIONS_REAPER_SELECTION_MENU_ANDROID_H_
#define BRAVE_BROWSER_EXTENSIONS_REAPER_SELECTION_MENU_ANDROID_H_

#include <memory>
#include <string>

#include "base/android/scoped_java_ref.h"
#include "base/scoped_observation.h"
#include "content/public/browser/frame_tree_node_id.h"
#include "content/public/browser/global_routing_id.h"
#include "content/public/browser/weak_document_ptr.h"
#include "content/public/browser/web_contents_observer.h"
#include "extensions/browser/extension_registry_observer.h"
#include "extensions/common/extension_set.h"

namespace content {
struct ContextMenuParams;
class NavigationHandle;
class Page;
}  // namespace content

namespace ui {
class MenuModelBridge;
}  // namespace ui

namespace extensions {
class ExtensionMenuModel;

// Owns one selection's extension menu and its JNI bridges. Java destroys it
// when selection UI closes; a changed document or an unloaded represented
// extension invalidates it first.
class ReaperSelectionMenu final : public content::WebContentsObserver,
                                  public ExtensionRegistryObserver {
 public:
  static std::unique_ptr<ReaperSelectionMenu> Create(
      content::WebContents* web_contents,
      content::RenderFrameHost* frame,
      const std::u16string& selected_text,
      bool editable,
      const base::android::JavaRef<jobject>& java_owner);

  // Forward Java test calls through the sole generated-JNI owner.
  static base::android::ScopedJavaLocalRef<jobject> CreateForTesting(
      JNIEnv* env,
      const base::android::JavaRef<jobject>& tab,
      const std::u16string& selected_text);

  static void DestroyForTesting(
      JNIEnv* env,
      const base::android::JavaRef<jobject>& owner);

  static base::android::ScopedJavaLocalRef<jobject> GetItemForTesting(
      JNIEnv* env,
      const base::android::JavaRef<jobject>& owner,
      const std::u16string& title);

  static bool IsClosedForTesting(
      JNIEnv* env,
      const base::android::JavaRef<jobject>& owner);

  static bool ClickItemForTesting(
      JNIEnv* env,
      const base::android::JavaRef<jobject>& item);

  ReaperSelectionMenu(const ReaperSelectionMenu&) = delete;
  ReaperSelectionMenu& operator=(const ReaperSelectionMenu&) = delete;
  ~ReaperSelectionMenu() override;

  base::android::ScopedJavaLocalRef<jobject> GetMenuModel(JNIEnv* env);
  void Destroy(JNIEnv* env);

 private:
  ReaperSelectionMenu(content::WebContents* web_contents,
                      content::RenderFrameHost& frame,
                      const content::ContextMenuParams& params,
                      const base::android::JavaRef<jobject>& java_owner);

  void RenderFrameDeleted(content::RenderFrameHost* frame) override;
  void RenderFrameHostStateChanged(
      content::RenderFrameHost* frame,
      content::RenderFrameHost::LifecycleState old_state,
      content::RenderFrameHost::LifecycleState new_state) override;
  void DidFinishNavigation(content::NavigationHandle* navigation) override;
  void PrimaryPageChanged(content::Page& page) override;
  void WebContentsDestroyed() override;

  void OnExtensionUnloaded(content::BrowserContext* browser_context,
                           const Extension* extension,
                           UnloadedExtensionReason reason) override;
  void OnShutdown(ExtensionRegistry* registry) override;

  bool IsDocumentValid() const;
  // Notifies Java before deleting this, so popup dismissal cannot
  // double-delete.
  void Invalidate();

  const content::GlobalRenderFrameHostId frame_id_;
  const content::FrameTreeNodeId frame_tree_node_id_;
  const content::WeakDocumentPtr document_;
  base::android::ScopedJavaGlobalRef<jobject> java_owner_;
  // Retain exact loaded instances, not only IDs that a reload may reuse.
  ExtensionSet represented_extensions_;
  base::ScopedObservation<ExtensionRegistry, ExtensionRegistryObserver>
      registry_observation_{this};
  bool invalidated_ = false;
  std::unique_ptr<ExtensionMenuModel> menu_model_;
  // Destroy bridges first so Java cannot call into a deleted model or submenu.
  std::unique_ptr<ui::MenuModelBridge> menu_bridge_;
};

}  // namespace extensions

#endif  // BRAVE_BROWSER_EXTENSIONS_REAPER_SELECTION_MENU_ANDROID_H_
