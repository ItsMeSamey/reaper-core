// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <android/input.h>

#include <memory>
#include <utility>

#include "base/android/jni_android.h"
#include "base/android/scoped_java_ref.h"
#include "base/functional/bind.h"
#include "base/memory/raw_ptr.h"
#include "base/memory/weak_ptr.h"
#include "base/scoped_observation.h"
#include "chrome/browser/profiles/profile.h"
#include "chrome/browser/profiles/profile_observer.h"
#include "content/public/browser/browser_thread.h"
#include "ui/base/accelerators/accelerator.h"
#include "ui/base/accelerators/global_accelerator_listener/global_accelerator_listener_android.h"
#include "ui/events/android/event_flags_android.h"
#include "ui/events/android/key_event_android.h"
#include "ui/events/keycodes/keyboard_code_conversion_android.h"

// Profile and KeyEvent conversion specializations must precede generated JNI.
#include "brave/browser/extensions/android/global_shortcuts/global_shortcuts_jni/ExtensionGlobalShortcutsBridge_jni.h"

namespace extensions {
namespace {

ui::GlobalAcceleratorListenerAndroid* Listener() {
  return static_cast<ui::GlobalAcceleratorListenerAndroid*>(
      ui::GlobalAcceleratorListener::GetInstance());
}

class ExtensionGlobalShortcutsBridge final : public ProfileObserver {
 public:
  static ExtensionGlobalShortcutsBridge* Create(
      Profile* profile,
      const base::android::JavaRef<jobject>& java_bridge) {
    DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
    if (!profile || profile->IsOffTheRecord() || java_bridge.is_null())
      return nullptr;
    auto bridge = std::unique_ptr<ExtensionGlobalShortcutsBridge>(
        new ExtensionGlobalShortcutsBridge(profile, java_bridge));
    if (!Listener()->AttachBackend(
            bridge.get(),
            base::BindRepeating(
                [](base::WeakPtr<ExtensionGlobalShortcutsBridge> weak_bridge,
                   const ui::Accelerator& accelerator, bool registered) {
                  auto* current = weak_bridge.get();
                  return current &&
                         current->SetRegistration(accelerator, registered);
                },
                bridge->weak_factory_.GetWeakPtr())) ||
        !bridge->profile_) {
      return nullptr;
    }
    return bridge.release();
  }

  ~ExtensionGlobalShortcutsBridge() override {
    DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
    weak_factory_.InvalidateWeakPtrs();
    profile_observation_.Reset();
    Listener()->DetachBackend(this);
  }

  bool OnKeyDown(const ui::KeyEventAndroid& key_event) {
    DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
    if (!profile_ || key_event.Action() != AKEY_EVENT_ACTION_DOWN)
      return false;
    // Android's generic native event conversion has no repeat/time/code
    // implementation. The Java matcher admits only initial matched DOWNs;
    // convert only the actual key and supported modifiers here.
    if (key_event.MetaState() & AMETA_FUNCTION_ON)
      return false;
    const ui::Accelerator accelerator(
        ui::KeyboardCodeFromAndroidKeyCode(key_event.key_code()),
        ui::EventFlagsFromAndroidMetaState(key_event.MetaState()));
    // This reaches ExtensionCommandsGlobalRegistry, which has no active tab,
    // checks the enabled extension and dispatches its existing worker event.
    return Listener()->DispatchAccelerator(this, accelerator);
  }

 private:
  ExtensionGlobalShortcutsBridge(
      Profile* profile,
      const base::android::JavaRef<jobject>& java_bridge)
      : profile_(profile), java_bridge_(java_bridge) {
    profile_observation_.Observe(profile);
  }

  bool SetRegistration(const ui::Accelerator& accelerator, bool registered) {
    if (!profile_)
      return false;
    constexpr int kSupportedModifiers = ui::EF_SHIFT_DOWN |
                                        ui::EF_CONTROL_DOWN | ui::EF_ALT_DOWN |
                                        ui::EF_COMMAND_DOWN;
    const int modifiers =
        ui::Accelerator::MaskOutKeyEventFlags(accelerator.modifiers());
    const int key_code =
        ui::AndroidKeyCodeFromKeyboardCode(accelerator.key_code());
    if (accelerator.key_state() != ui::Accelerator::KeyState::PRESSED ||
        (modifiers & ~kSupportedModifiers) != 0 ||
        key_code == AKEYCODE_UNKNOWN ||
        ui::KeyboardCodeFromAndroidKeyCode(key_code) !=
            accelerator.key_code()) {
      return false;
    }
    const int meta_state =
        ((modifiers & ui::EF_SHIFT_DOWN) ? AMETA_SHIFT_ON : 0) |
        ((modifiers & ui::EF_CONTROL_DOWN) ? AMETA_CTRL_ON : 0) |
        ((modifiers & ui::EF_ALT_DOWN) ? AMETA_ALT_ON : 0) |
        ((modifiers & ui::EF_COMMAND_DOWN) ? AMETA_META_ON : 0);
    return Java_ExtensionGlobalShortcutsBridge_setRegistration(
        base::android::AttachCurrentThread(), java_bridge_, key_code,
        meta_state, registered);
  }

  void OnProfileWillBeDestroyed(Profile* profile) override {
    DCHECK_EQ(profile, profile_);
    profile_ = nullptr;
    profile_observation_.Reset();
    Listener()->DetachBackend(this);
    // Java retires acceptance first and may destroy this bridge reentrantly.
    Java_ExtensionGlobalShortcutsBridge_onNativeUnavailable(
        base::android::AttachCurrentThread(), java_bridge_);
  }

  raw_ptr<Profile> profile_;
  base::android::ScopedJavaGlobalRef<jobject> java_bridge_;
  base::ScopedObservation<Profile, ProfileObserver> profile_observation_{this};
  base::WeakPtrFactory<ExtensionGlobalShortcutsBridge> weak_factory_{this};
};

}  // namespace

static int64_t JNI_ExtensionGlobalShortcutsBridge_Init(
    JNIEnv* env,
    Profile* profile,
    const base::android::JavaRef<jobject>& java_bridge) {
  return reinterpret_cast<int64_t>(
      ExtensionGlobalShortcutsBridge::Create(profile, java_bridge));
}

static void JNI_ExtensionGlobalShortcutsBridge_Destroy(JNIEnv* env,
                                                       int64_t native_bridge) {
  delete reinterpret_cast<ExtensionGlobalShortcutsBridge*>(native_bridge);
}

static bool JNI_ExtensionGlobalShortcutsBridge_OnKeyDown(
    JNIEnv* env,
    int64_t native_bridge,
    const ui::KeyEventAndroid& key_event) {
  return reinterpret_cast<ExtensionGlobalShortcutsBridge*>(native_bridge)
      ->OnKeyDown(key_event);
}

}  // namespace extensions

DEFINE_JNI(ExtensionGlobalShortcutsBridge)
