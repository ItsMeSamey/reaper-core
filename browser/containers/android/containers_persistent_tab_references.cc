// Copyright 2026 The Reaper Authors
// SPDX-License-Identifier: MPL-2.0
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "base/android/callback_android.h"
#include "base/android/jni_android.h"
#include "base/android/jni_array.h"
#include "base/functional/bind.h"
#include "brave/browser/containers/android/containers_bridge.h"
#include "chrome/browser/android/tab_state_storage_service_factory.h"
#include "chrome/browser/profiles/profile.h"
#include "chrome/browser/tab/tab_state_storage_service.h"
#include "content/public/browser/browser_thread.h"

// Must follow Profile JNI conversions and array specializations.
#include "chrome/android/chrome_jni_headers/ContainersPersistentTabReferences_jni.h"

static void JNI_ContainersPersistentTabReferences_LoadDatabaseContainerIds(
    JNIEnv* env,
    Profile* profile,
    const jni_zero::JavaRef<jobject>& callback) {
  auto java_callback = jni_zero::ScopedJavaGlobalRef<jobject>(callback);
  if (!profile || profile->IsOffTheRecord() || profile->ShutdownStarted()) {
    base::android::RunObjectCallbackAndroid(
        java_callback, jni_zero::ScopedJavaLocalRef<jobject>());
    return;
  }
  auto* service = tabs::TabStateStorageServiceFactory::GetForProfile(profile);
  if (!service) {
    base::android::RunObjectCallbackAndroid(
        java_callback, jni_zero::ScopedJavaLocalRef<jobject>());
    return;
  }
  service->GetPersistedContainerIds(base::BindOnce(
      [](base::WeakPtr<Profile> owner,
         jni_zero::ScopedJavaGlobalRef<jobject> java_callback,
         std::optional<std::vector<std::string>> ids) {
        JNIEnv* env = base::android::AttachCurrentThread();
        if (!owner || owner->ShutdownStarted() || !ids) {
          base::android::RunObjectCallbackAndroid(
              java_callback, jni_zero::ScopedJavaLocalRef<jobject>());
          return;
        }
        auto java_ids = base::android::ToJavaArrayOfStrings(env, *ids);
        base::android::RunObjectCallbackAndroid(java_callback, java_ids);
      },
      profile->GetWeakPtr(), std::move(java_callback)));
}

namespace containers::android {

void RefreshPersistentContainerReferences(Profile* profile) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (profile && !profile->IsOffTheRecord()) {
    Java_ContainersPersistentTabReferences_refresh(
        base::android::AttachCurrentThread(), profile);
  }
}

}  // namespace containers::android

DEFINE_JNI(ContainersPersistentTabReferences)
