// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "brave/browser/containers/android/containers_bridge.h"

#include <limits>
#include <memory>
#include <utility>

#include "base/android/callback_android.h"
#include "base/android/jni_android.h"
#include "base/android/jni_array.h"
#include "base/android/jni_string.h"
#include "base/check.h"
#include "base/feature_list.h"
#include "base/functional/bind.h"
#include "base/memory/weak_ptr.h"
#include "base/supports_user_data.h"
#include "base/task/sequenced_task_runner.h"
#include "base/uuid.h"
#include "brave/browser/containers/containers_service_factory.h"
#include "brave/components/containers/content/browser/storage_partition_utils.h"
#include "brave/components/containers/core/browser/containers_service.h"
#include "brave/components/containers/core/browser/containers_settings_handler.h"
#include "brave/components/containers/core/browser/pref_names.h"
#include "brave/components/containers/core/browser/temporary_container.h"
#include "brave/components/containers/core/common/features.h"
#include "brave/components/containers/core/mojom/containers.mojom.h"
#include "chrome/browser/profiles/profile.h"
#include "components/prefs/pref_service.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/security_principal.h"
#include "content/public/browser/site_instance.h"
#include "content/public/browser/web_contents.h"

// After all headers providing JNI type conversions.
#include "brave/browser/containers/android/jni_headers/ContainersBridge_jni.h"

namespace containers::android {
namespace {

constexpr char kPersistentReferencesKey[] = "ReaperContainerReferences";

// This is a transient readiness barrier for the Android persistence owner,
// not a container registry. IDs and metadata remain in ContainersService.
class PersistentReferences : public base::SupportsUserData::Data {
 public:
  using Callback = base::OnceCallback<void(const base::flat_set<std::string>&)>;

  void Invalidate() {
    CHECK_NE(generation_, std::numeric_limits<int64_t>::max());
    ++generation_;
    ready_ = false;
  }

  int64_t generation() const { return generation_; }

  bool PublishIfGenerationMatches(int64_t expected_generation,
                                  base::flat_set<std::string> ids) {
    if (expected_generation != generation_) {
      return false;
    }
    Publish(std::move(ids));
    return true;
  }

  void Publish(base::flat_set<std::string> ids) {
    ids_ = std::move(ids);
    ready_ = true;
    auto callbacks = std::move(callbacks_);
    for (auto& callback : callbacks) {
      Request(std::move(callback));
    }
  }

  void Request(Callback callback) {
    if (!ready_) {
      callbacks_.push_back(std::move(callback));
      return;
    }
    base::SequencedTaskRunner::GetCurrentDefault()->PostTask(
        FROM_HERE,
        base::BindOnce(&PersistentReferences::Reply, weak_factory_.GetWeakPtr(),
                       std::move(callback)));
  }

 private:
  void Reply(Callback callback) {
    // Rescans may invalidate between publication and this asynchronous reply.
    // Never turn incomplete or stale inventory into an empty deletion grant.
    if (!ready_) {
      callbacks_.push_back(std::move(callback));
      return;
    }
    std::move(callback).Run(ids_);
  }

  int64_t generation_ = 1;
  bool ready_ = false;
  base::flat_set<std::string> ids_;
  std::vector<Callback> callbacks_;
  base::WeakPtrFactory<PersistentReferences> weak_factory_{this};
};

PersistentReferences* GetPersistentReferences(Profile* profile) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!profile) {
    return nullptr;
  }
  auto* inventory = static_cast<PersistentReferences*>(
      profile->GetUserData(kPersistentReferencesKey));
  if (!inventory) {
    auto created = std::make_unique<PersistentReferences>();
    inventory = created.get();
    profile->SetUserData(kPersistentReferencesKey, std::move(created));
  }
  return inventory;
}

ContainersService* GetRegularService(Profile* profile) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!profile || profile->IsOffTheRecord() ||
      !base::FeatureList::IsEnabled(features::kContainers)) {
    return nullptr;
  }
  return ContainersServiceFactory::GetForProfile(profile);
}

void CompleteOperation(
    const base::android::ScopedJavaGlobalRef<jobject>& callback,
    std::optional<mojom::ContainerOperationError> error) {
  base::android::RunIntCallbackAndroid(callback,
                                       error ? static_cast<int>(*error) : 0);
}

bool CanEdit(Profile* profile,
             const base::android::JavaRef<jobject>& callback) {
  if (GetRegularService(profile)) {
    return true;
  }
  base::android::RunIntCallbackAndroid(callback, -1);
  return false;
}

}  // namespace

bool IsContainerIdWellFormed(std::string_view id) {
  if (id.empty()) {
    return true;
  }
  if (id.starts_with(kTemporaryContainerIdPrefix)) {
    id.remove_prefix(kTemporaryContainerIdPrefix.size());
  }
  const auto uuid = base::Uuid::ParseLowercase(id);
  return uuid.is_valid() && uuid.AsLowercaseString() == id;
}

std::optional<content::StoragePartitionConfig> ResolveContainerStoragePartition(
    Profile* profile,
    std::string_view id) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!profile || !IsContainerIdWellFormed(id)) {
    return std::nullopt;
  }
  if (id.empty()) {
    return content::StoragePartitionConfig::CreateDefault(profile);
  }
  auto* service = GetRegularService(profile);
  if (!service || service->IsContainerStorageRemovalPending(id) ||
      !service->GetRuntimeContainerById(id)) {
    return std::nullopt;
  }
  return content::StoragePartitionConfig::Create(
      profile, kContainersStoragePartitionDomain, std::string(id),
      /*in_memory=*/false);
}

bool RestoreContainerIdentity(Profile* profile, std::string_view id) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!profile || !IsContainerIdWellFormed(id)) {
    return false;
  }
  if (id.empty()) {
    return true;
  }
  auto* service = GetRegularService(profile);
  if (!service || service->IsContainerStorageRemovalPending(id)) {
    return false;
  }
  service->MarkContainerUsed(id);
  return ResolveContainerStoragePartition(profile, id).has_value();
}

bool WebContentsMatchesContainer(Profile* profile,
                                 content::WebContents* contents,
                                 std::string_view id) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!contents || contents->GetBrowserContext() != profile) {
    return false;
  }
  const auto expected = ResolveContainerStoragePartition(profile, id);
  return expected && contents->GetSiteInstance() &&
         contents->GetSiteInstance()->GetBrowserContext() == profile &&
         contents->GetSiteInstance()
                 ->GetSecurityPrincipal()
                 .GetStoragePartitionConfig() == *expected;
}

void InvalidatePersistentContainerReferences(Profile* profile) {
  if (auto* inventory = GetPersistentReferences(profile)) {
    inventory->Invalidate();
  }
}

int64_t GetPersistentContainerReferencesGeneration(Profile* profile) {
  auto* inventory = GetPersistentReferences(profile);
  return inventory ? inventory->generation() : 0;
}

bool SetPersistentContainerReferencesIfGenerationMatches(
    Profile* profile,
    const std::vector<std::string>& ids,
    int64_t expected_generation) {
  auto* inventory = GetPersistentReferences(profile);
  if (!inventory || expected_generation != inventory->generation()) {
    return false;
  }
  base::flat_set<std::string> validated;
  for (const auto& id : ids) {
    if (!IsContainerIdWellFormed(id)) {
      inventory->Invalidate();
      return false;
    }
    if (!id.empty()) {
      validated.insert(id);
    }
  }
  return inventory->PublishIfGenerationMatches(expected_generation,
                                              std::move(validated));
}

void SetPersistentContainerReferences(Profile* profile,
                                      const std::vector<std::string>& ids) {
  SetPersistentContainerReferencesIfGenerationMatches(
      profile, ids, GetPersistentContainerReferencesGeneration(profile));
}

void GetPersistentContainerReferences(
    Profile* profile,
    base::OnceCallback<void(const base::flat_set<std::string>&)> callback) {
  if (auto* inventory = GetPersistentReferences(profile)) {
    inventory->Request(std::move(callback));
  }
  // A missing/destroyed owner cannot authorize cleanup. Destroy the callback.
}

static bool JNI_ContainersBridge_IsEnabled(JNIEnv* env, Profile* profile) {
  auto* service = GetRegularService(profile);
  return service && service->ShouldShowContainerControls();
}

static bool JNI_ContainersBridge_SetEnabled(JNIEnv* env,
                                            Profile* profile,
                                            bool enabled) {
  if (!GetRegularService(profile)) {
    return false;
  }
  profile->GetPrefs()->SetBoolean(prefs::kContainersEnabled, enabled);
  return true;
}

static bool JNI_ContainersBridge_IsContainerIdValid(JNIEnv* env,
                                                    Profile* profile,
                                                    const std::string& id) {
  return ResolveContainerStoragePartition(profile, id).has_value();
}

static bool JNI_ContainersBridge_RestoreContainerIdentity(
    JNIEnv* env,
    Profile* profile,
    const std::string& id) {
  return RestoreContainerIdentity(profile, id);
}

static bool JNI_ContainersBridge_IsWebContentsInContainer(
    JNIEnv* env,
    Profile* profile,
    content::WebContents* contents,
    const std::string& id) {
  return WebContentsMatchesContainer(profile, contents, id);
}

static std::string JNI_ContainersBridge_GetContainerId(
    JNIEnv* env,
    content::WebContents* contents) {
  if (!base::FeatureList::IsEnabled(features::kContainers) || !contents ||
      !contents->GetSiteInstance()) {
    return {};
  }
  const auto& config = contents->GetSiteInstance()
                           ->GetSecurityPrincipal()
                           .GetStoragePartitionConfig();
  const auto id = GetContainerIdFromStoragePartitionConfig(config);
  return !config.in_memory() && IsContainerIdWellFormed(id) ? std::string(id)
                                                            : std::string();
}

static void JNI_ContainersBridge_GetContainers(
    JNIEnv* env,
    Profile* profile,
    const jni_zero::JavaRef<
        ::java::util::JList<::org::chromium::chrome::browser::containers::
                                JContainersBridge_ContainerInfo>>& output) {
  if (auto* service = GetRegularService(profile)) {
    for (const auto& container : service->GetContainers()) {
      if (!IsContainerIdWellFormed(container->id) || container->id.empty()) {
        continue;
      }
      Java_ContainersBridge_appendContainer(
          env, output, container->id, container->name,
          static_cast<int>(container->icon),
          static_cast<int32_t>(container->background_color));
    }
  }
}

static jni_zero::ScopedJavaLocalRef<
    ::org::chromium::chrome::browser::containers::
        JContainersBridge_ContainerInfo>
JNI_ContainersBridge_GetContainerInfo(JNIEnv* env,
                                      Profile* profile,
                                      const std::string& id) {
  auto* service = GetRegularService(profile);
  if (!service || id.empty() || !IsContainerIdWellFormed(id)) {
    return {};
  }
  auto container = service->GetRuntimeContainerById(id);
  if (!container) {
    return {};
  }
  return ::org::chromium::chrome::browser::containers::ContainersBridgeJni::
      createContainerInfo(env, container->id, container->name,
                          static_cast<int>(container->icon),
                          static_cast<int32_t>(container->background_color));
}

static void JNI_ContainersBridge_AddContainer(
    JNIEnv* env,
    Profile* profile,
    const std::string& name,
    int32_t icon,
    int32_t color,
    const base::android::JavaRef<jobject>& callback) {
  if (!CanEdit(profile, callback)) {
    return;
  }
  ContainersSettingsHandler handler(profile->GetPrefs());
  handler.AddContainer(
      mojom::Container::New("", name, static_cast<mojom::Icon>(icon),
                            static_cast<SkColor>(color)),
      base::BindOnce(&CompleteOperation,
                     base::android::ScopedJavaGlobalRef<jobject>(callback)));
}

static void JNI_ContainersBridge_UpdateContainer(
    JNIEnv* env,
    Profile* profile,
    const std::string& id,
    const std::string& name,
    int32_t icon,
    int32_t color,
    const base::android::JavaRef<jobject>& callback) {
  if (!CanEdit(profile, callback)) {
    return;
  }
  ContainersSettingsHandler handler(profile->GetPrefs());
  handler.UpdateContainer(
      mojom::Container::New(id, name, static_cast<mojom::Icon>(icon),
                            static_cast<SkColor>(color)),
      base::BindOnce(&CompleteOperation,
                     base::android::ScopedJavaGlobalRef<jobject>(callback)));
}

static void JNI_ContainersBridge_RemoveContainer(
    JNIEnv* env,
    Profile* profile,
    const std::string& id,
    const base::android::JavaRef<jobject>& callback) {
  if (!CanEdit(profile, callback)) {
    return;
  }
  ContainersSettingsHandler handler(profile->GetPrefs());
  handler.RemoveContainer(
      id,
      base::BindOnce(&CompleteOperation,
                     base::android::ScopedJavaGlobalRef<jobject>(callback)));
}

static void JNI_ContainersBridge_ReorderContainers(
    JNIEnv* env,
    Profile* profile,
    const std::vector<std::string>& ids,
    const base::android::JavaRef<jobject>& callback) {
  if (!CanEdit(profile, callback)) {
    return;
  }
  ContainersSettingsHandler handler(profile->GetPrefs());
  handler.ReorderContainers(
      ids,
      base::BindOnce(&CompleteOperation,
                     base::android::ScopedJavaGlobalRef<jobject>(callback)));
}

static void JNI_ContainersBridge_InvalidatePersistentContainerReferences(
    JNIEnv* env,
    Profile* profile) {
  InvalidatePersistentContainerReferences(profile);
}

static int64_t JNI_ContainersBridge_GetPersistentContainerReferencesGeneration(
    JNIEnv* env,
    Profile* profile) {
  return GetPersistentContainerReferencesGeneration(profile);
}

static bool JNI_ContainersBridge_SetPersistentContainerReferences(
    JNIEnv* env,
    Profile* profile,
    const std::vector<std::string>& ids,
    int64_t expected_generation) {
  return SetPersistentContainerReferencesIfGenerationMatches(
      profile, ids, expected_generation);
}

}  // namespace containers::android

DEFINE_JNI(ContainersBridge)
