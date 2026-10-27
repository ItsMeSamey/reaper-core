// Copyright 2026 The Reaper Authors.
// SPDX-License-Identifier: MPL-2.0

#include "brave/browser/extensions/android/power/reaper_power_delegate_android.h"

#include <optional>
#include <string>

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
#include "chrome/browser/profiles/profile.h"
#include "extensions/browser/api/extensions_api_client.h"

// Must follow Profile's JNI conversion declarations.
#include "chrome/android/chrome_jni_headers/ReaperExtensionPowerManager_jni.h"

namespace extensions {
namespace {

class ReaperAndroidPowerDelegate final : public AndroidPowerDelegate {
 public:
  explicit ReaperAndroidPowerDelegate(content::BrowserContext* context) {
    manager_.Reset(Java_ReaperExtensionPowerManager_create(
        base::android::AttachCurrentThread(),
        Profile::FromBrowserContext(context)
            ->GetOriginalProfile()
            ->GetJavaObject()));
  }

  ~ReaperAndroidPowerDelegate() override {
    Java_ReaperExtensionPowerManager_destroy(
        base::android::AttachCurrentThread(), manager_);
  }

  std::optional<std::string> Apply(bool system,
                                   bool display,
                                   bool require_foreground_display,
                                   bool transactional) override {
    auto error = Java_ReaperExtensionPowerManager_apply(
        base::android::AttachCurrentThread(), manager_, system, display,
        require_foreground_display, transactional);
    std::string message = base::android::ConvertJavaStringToUTF8(error);
    if (!message.empty())
      return message;
    return std::nullopt;
  }

  void Cancel() override {
    Java_ReaperExtensionPowerManager_cancel(
        base::android::AttachCurrentThread(), manager_);
  }

 private:
  base::android::ScopedJavaGlobalRef<jobject> manager_;
};

}  // namespace

std::unique_ptr<AndroidPowerDelegate> CreateReaperAndroidPowerDelegate(
    content::BrowserContext* context) {
  return std::make_unique<ReaperAndroidPowerDelegate>(context);
}

}  // namespace extensions
