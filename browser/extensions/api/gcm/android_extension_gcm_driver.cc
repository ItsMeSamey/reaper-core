// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "brave/browser/extensions/api/gcm/android_extension_gcm_driver.h"

#include <utility>

#include "base/files/file_path.h"
#include "base/functional/bind.h"
#include "base/location.h"
#include "base/one_shot_event.h"
#include "base/task/bind_post_task.h"
#include "base/task/thread_pool.h"
#include "base/values.h"
#include "brave/components/constants/pref_names.h"
#include "chrome/browser/browser_process.h"
#include "chrome/browser/profiles/profile.h"
#include "chrome/common/channel_info.h"
#include "components/crx_file/id_util.h"
#include "components/gcm_driver/gcm_client_factory.h"
#include "components/gcm_driver/gcm_desktop_utils.h"
#include "components/prefs/pref_service.h"
#include "content/public/browser/browser_task_traits.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/network_service_instance.h"
#include "content/public/browser/storage_partition.h"
#include "extensions/browser/extension_system.h"
#include "extensions/browser/unloaded_extension_reason.h"
#include "extensions/common/extension.h"
#include "extensions/common/permissions/permissions_data.h"
#include "mojo/public/cpp/bindings/pending_receiver.h"
#include "services/network/public/cpp/shared_url_loader_factory.h"
#include "services/network/public/mojom/network_context.mojom.h"
#include "services/network/public/mojom/proxy_resolving_socket.mojom.h"

namespace extensions {
namespace {

constexpr base::FilePath::CharType kStoreDirectory[] =
    FILE_PATH_LITERAL("Extension GCM Store");
// Native GCM registration uses the exact extension ID as category. This
// distinct fixed category is used only by the native client's subtype trust
// check; no platform/GMS registration is moved to this driver.
constexpr char kSubtypeCategory[] = "org.reaper.android.extensiongcm";

bool HasGCM(const Extension& extension) {
  return extension.permissions_data()->HasAPIPermission(
      mojom::APIPermissionID::kGcm);
}

void BindSocketFactoryOnUI(
    base::WeakPtr<Profile> profile,
    base::WeakPtr<AndroidExtensionGCMDriver> owner,
    mojo::PendingReceiver<network::mojom::ProxyResolvingSocketFactory>
        receiver) {
  if (!profile || !owner) {
    return;
  }
  profile->GetDefaultStoragePartition()
      ->GetNetworkContext()
      ->CreateProxyResolvingSocketFactory(std::move(receiver));
}

void RequestSocketFactory(
    base::WeakPtr<Profile> profile,
    base::WeakPtr<AndroidExtensionGCMDriver> owner,
    mojo::PendingReceiver<network::mojom::ProxyResolvingSocketFactory>
        receiver) {
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE, base::BindOnce(&BindSocketFactoryOnUI, std::move(profile),
                                std::move(owner), std::move(receiver)));
}

}  // namespace

AndroidExtensionGCMDriver::AndroidExtensionGCMDriver(
    Profile* profile,
    MessageCallback message_callback,
    DeletedCallback deleted_callback,
    DriverFactory driver_factory_for_testing)
    : profile_(profile),
      message_callback_(std::move(message_callback)),
      deleted_callback_(std::move(deleted_callback)),
      driver_factory_for_testing_(std::move(driver_factory_for_testing)) {
  if (profile_->IsOffTheRecord()) {
    shutdown_ = true;
    return;
  }
  auto* registry = ExtensionRegistry::Get(profile_);
  registry_observation_.Observe(registry);
  permissions_observation_.Observe(PermissionsManager::Get(profile_));
  for (const auto& extension : registry->enabled_extensions()) {
    if (HasGCM(*extension)) {
      eligible_extensions_.insert(extension->id());
    }
  }
  pref_registrar_.Init(profile_->GetPrefs());
  pref_registrar_.Add(kBraveGCMChannelStatus,
                      base::BindRepeating(&AndroidExtensionGCMDriver::Refresh,
                                          base::Unretained(this)));
  // Eager profile services can be constructed before extension loading. Only
  // reconcile absent/changed installed IDs after that startup has completed.
  ExtensionSystem::Get(profile_)->ready().Post(
      FROM_HERE,
      base::BindOnce(&AndroidExtensionGCMDriver::ReconcileStoredRegistrations,
                     weak_factory_.GetWeakPtr()));
  Refresh();
}

AndroidExtensionGCMDriver::~AndroidExtensionGCMDriver() {
  Shutdown();
}

bool AndroidExtensionGCMDriver::IsEnabled() const {
  return !shutdown_ && profile_->GetPrefs()->GetBoolean(kBraveGCMChannelStatus);
}

bool AndroidExtensionGCMDriver::IsAllowed(const std::string& app_id) const {
  if (!IsEnabled() || !eligible_extensions_.contains(app_id)) {
    return false;
  }
  const Extension* extension =
      ExtensionRegistry::Get(profile_)->enabled_extensions().GetByID(app_id);
  return extension && HasGCM(*extension);
}

std::unique_ptr<gcm::GCMDriver> AndroidExtensionGCMDriver::CreateDriver() {
  if (driver_factory_for_testing_) {
    return driver_factory_for_testing_.Run();
  }
  if (!blocking_task_runner_) {
    // Recreated drivers must close and reopen their persistent store on the
    // same sequence, including asynchronous IOWorker/backend destruction.
    blocking_task_runner_ = base::ThreadPool::CreateSequencedTaskRunner(
        {base::MayBlock(), base::TaskPriority::BEST_EFFORT,
         base::TaskShutdownBehavior::SKIP_ON_SHUTDOWN});
  }
  return gcm::CreateGCMDriverDesktop(
      std::make_unique<gcm::GCMClientFactory>(), profile_->GetPrefs(),
      profile_->GetPath().Append(kStoreDirectory),
      base::BindRepeating(&RequestSocketFactory, profile_->GetWeakPtr(),
                          driver_weak_factory_.GetWeakPtr()),
      profile_->GetDefaultStoragePartition()
          ->GetURLLoaderFactoryForBrowserProcess(),
      content::GetNetworkConnectionTracker(), chrome::GetChannel(),
      kSubtypeCategory, content::GetUIThreadTaskRunner({}),
      content::GetIOThreadTaskRunner({}), blocking_task_runner_,
      g_browser_process->os_crypt_async());
}

void AndroidExtensionGCMDriver::Refresh() {
  if (!IsEnabled()) {
    StopDriver(gcm::GCMClient::GCM_DISABLED);
    return;
  }
  const auto pending = ReadPendingUninstalls();
  if (!driver_ && (!eligible_extensions_.empty() || !pending.empty())) {
    driver_ = CreateDriver();
    if (!driver_) {
      return;
    }
    // Only the explicit effective profile preference reaches this branch.
    driver_->SetEnabled(true);
  }
  if (!driver_) {
    return;
  }
  for (const auto& app_id : eligible_extensions_) {
    AddHandler(app_id);
  }
  for (const auto& app_id : pending) {
    if (!eligible_extensions_.contains(app_id)) {
      AddHandler(app_id);
      UnregisterUninstalled(app_id);
    } else {
      SetPendingUninstall(app_id, false);
    }
  }
}

void AndroidExtensionGCMDriver::AddHandler(const std::string& app_id) {
  if (driver_ && handlers_.insert(app_id).second) {
    // Desktop AddAppHandler performs DELAYED_START and restores saved
    // registration/MCS state even when no new register() call occurs.
    driver_->AddAppHandler(app_id, this);
  }
}

void AndroidExtensionGCMDriver::RemoveHandler(const std::string& app_id) {
  if (driver_ && handlers_.erase(app_id)) {
    driver_->RemoveAppHandler(app_id);
    if (handlers_.empty()) {
      // Desktop Stop invalidates its derived weak pointers, but asynchronous
      // base-class encryption callbacks also exist. End the entire generation
      // before another registration can reuse an app ID. This also covers
      // removal before GCMReady creates the internal account-mapper handler.
      StopDriver(gcm::GCMClient::GCM_DISABLED);
    }
  }
}

void AndroidExtensionGCMDriver::Register(
    const std::string& app_id,
    const std::vector<std::string>& sender_ids,
    gcm::GCMDriver::RegisterCallback callback) {
  if (!IsAllowed(app_id)) {
    std::move(callback).Run({}, gcm::GCMClient::GCM_DISABLED);
    return;
  }
  Refresh();
  if (!driver_) {
    std::move(callback).Run({}, gcm::GCMClient::UNKNOWN_ERROR);
    return;
  }
  const uint64_t request_id = ++next_request_id_;
  registrations_.emplace(request_id,
                         PendingRegister{app_id, std::move(callback)});
  RememberRegistration(app_id);
  driver_->Register(app_id, sender_ids,
                    base::BindPostTaskToCurrentDefault(base::BindOnce(
                        &AndroidExtensionGCMDriver::FinishRegister,
                        driver_weak_factory_.GetWeakPtr(), request_id)));
}

void AndroidExtensionGCMDriver::Unregister(
    const std::string& app_id,
    gcm::GCMDriver::UnregisterCallback callback) {
  if (!IsAllowed(app_id)) {
    std::move(callback).Run(gcm::GCMClient::GCM_DISABLED);
    return;
  }
  Refresh();
  if (!driver_) {
    std::move(callback).Run(gcm::GCMClient::UNKNOWN_ERROR);
    return;
  }
  const uint64_t request_id = ++next_request_id_;
  unregistrations_.emplace(request_id,
                           PendingUnregister{app_id, std::move(callback)});
  driver_->Unregister(app_id,
                      base::BindPostTaskToCurrentDefault(base::BindOnce(
                          &AndroidExtensionGCMDriver::FinishUnregister,
                          driver_weak_factory_.GetWeakPtr(), request_id)));
}

void AndroidExtensionGCMDriver::FinishRegister(
    uint64_t request_id,
    const std::string& registration_id,
    gcm::GCMClient::Result result) {
  auto it = registrations_.find(request_id);
  if (it == registrations_.end()) {
    return;
  }
  auto request = std::move(it->second);
  registrations_.erase(it);
  RetryPendingUninstall(request.app_id);
  if (!request.callback) {
    return;
  }
  if (!IsAllowed(request.app_id)) {
    result = gcm::GCMClient::GCM_DISABLED;
  }
  std::move(request.callback)
      .Run(result == gcm::GCMClient::SUCCESS ? registration_id : std::string(),
           result);
}

void AndroidExtensionGCMDriver::FinishUnregister(
    uint64_t request_id,
    gcm::GCMClient::Result result) {
  auto it = unregistrations_.find(request_id);
  if (it == unregistrations_.end()) {
    return;
  }
  auto request = std::move(it->second);
  unregistrations_.erase(it);
  if (result == gcm::GCMClient::SUCCESS ||
      result == gcm::GCMClient::INVALID_PARAMETER) {
    ForgetRegistrationIfFinished(request.app_id);
  }
  RetryPendingUninstall(request.app_id);
  if (!request.callback) {
    return;
  }
  if (!IsAllowed(request.app_id)) {
    result = gcm::GCMClient::GCM_DISABLED;
  }
  std::move(request.callback).Run(result);
}

void AndroidExtensionGCMDriver::CancelRequestsFor(const std::string& app_id) {
  std::vector<gcm::GCMDriver::RegisterCallback> registrations;
  std::vector<gcm::GCMDriver::UnregisterCallback> unregistrations;
  // Another extension may keep this native generation alive after an unload.
  // Preserve native operation identity even for UPDATE/disable, so a later
  // uninstall can retry cleanup when that original operation actually finishes.
  for (auto& [id, request] : registrations_) {
    if (request.app_id == app_id && request.callback) {
      registrations.push_back(std::move(request.callback));
    }
  }
  for (auto& [id, request] : unregistrations_) {
    if (request.app_id == app_id && request.callback) {
      unregistrations.push_back(std::move(request.callback));
    }
  }
  // No member access after the first external completion; all requests were
  // retired before a callback may disable the channel or destroy the profile.
  for (auto& callback : registrations) {
    std::move(callback).Run({}, gcm::GCMClient::GCM_DISABLED);
  }
  for (auto& callback : unregistrations) {
    std::move(callback).Run(gcm::GCMClient::GCM_DISABLED);
  }
}

void AndroidExtensionGCMDriver::StopDriver(gcm::GCMClient::Result result) {
  auto registrations = std::move(registrations_);
  registrations_.clear();
  auto unregistrations = std::move(unregistrations_);
  unregistrations_.clear();
  driver_weak_factory_.InvalidateWeakPtrs();
  handlers_.clear();
  uninstalling_.clear();
  auto driver = std::move(driver_);
  if (driver) {
    driver->Shutdown();
    driver.reset();
  }
  for (auto& [id, request] : registrations) {
    if (request.callback) {
      std::move(request.callback).Run({}, result);
    }
  }
  for (auto& [id, request] : unregistrations) {
    if (request.callback) {
      std::move(request.callback).Run(result);
    }
  }
}

std::set<std::string> AndroidExtensionGCMDriver::ReadRegisteredApplications()
    const {
  std::set<std::string> result;
  for (const auto& value : profile_->GetPrefs()->GetList(
           kBraveExtensionGCMRegisteredApplications)) {
    if (value.is_string() && crx_file::id_util::IdIsValid(value.GetString())) {
      result.insert(value.GetString());
    }
  }
  return result;
}

void AndroidExtensionGCMDriver::RememberRegistration(
    const std::string& app_id) {
  auto ids = ReadRegisteredApplications();
  ids.insert(app_id);
  base::ListValue list;
  for (const auto& id : ids) {
    list.Append(id);
  }
  profile_->GetPrefs()->SetList(kBraveExtensionGCMRegisteredApplications,
                                std::move(list));
}

void AndroidExtensionGCMDriver::ForgetRegistrationIfFinished(
    const std::string& app_id) {
  // The real base driver can queue a new Register behind an Unregister. Its
  // old completion must not clear the new operation's durable identity.
  for (const auto& [id, request] : registrations_) {
    if (request.app_id == app_id) {
      return;
    }
  }
  auto ids = ReadRegisteredApplications();
  ids.erase(app_id);
  base::ListValue list;
  for (const auto& id : ids) {
    list.Append(id);
  }
  profile_->GetPrefs()->SetList(kBraveExtensionGCMRegisteredApplications,
                                std::move(list));
}

void AndroidExtensionGCMDriver::ReconcileStoredRegistrations() {
  if (shutdown_) {
    return;
  }
  auto* registry = ExtensionRegistry::Get(profile_);
  for (const auto& app_id : ReadRegisteredApplications()) {
    const Extension* extension = registry->GetInstalledExtension(app_id);
    // Disabled/terminated installed extensions retain their registrations
    // while still holding GCM permission; only the eligible set can deliver.
    if (!extension || !HasGCM(*extension)) {
      SetPendingUninstall(app_id, true);
    }
  }
  Refresh();
}

std::set<std::string> AndroidExtensionGCMDriver::ReadPendingUninstalls() const {
  std::set<std::string> result;
  for (const auto& value : profile_->GetPrefs()->GetList(
           kBraveExtensionGCMPendingUnregistrations)) {
    if (value.is_string() && crx_file::id_util::IdIsValid(value.GetString())) {
      result.insert(value.GetString());
    }
  }
  return result;
}

void AndroidExtensionGCMDriver::SetPendingUninstall(const std::string& app_id,
                                                    bool pending) {
  auto ids = ReadPendingUninstalls();
  if (pending) {
    ids.insert(app_id);
  } else {
    ids.erase(app_id);
  }
  base::ListValue list;
  for (const auto& id : ids) {
    list.Append(id);
  }
  profile_->GetPrefs()->SetList(kBraveExtensionGCMPendingUnregistrations,
                                std::move(list));
}

bool AndroidExtensionGCMDriver::HasPendingNativeRequest(
    const std::string& app_id) const {
  for (const auto& [id, request] : registrations_) {
    if (request.app_id == app_id) {
      return true;
    }
  }
  for (const auto& [id, request] : unregistrations_) {
    if (request.app_id == app_id) {
      return true;
    }
  }
  return false;
}

void AndroidExtensionGCMDriver::RetryPendingUninstall(
    const std::string& app_id) {
  if (driver_ && IsEnabled() && !eligible_extensions_.contains(app_id) &&
      ReadPendingUninstalls().contains(app_id)) {
    AddHandler(app_id);
    UnregisterUninstalled(app_id);
  }
}

void AndroidExtensionGCMDriver::UnregisterUninstalled(
    const std::string& app_id) {
  if (HasPendingNativeRequest(app_id) || !uninstalling_.insert(app_id).second) {
    return;
  }
  driver_->Unregister(app_id, base::BindPostTaskToCurrentDefault(base::BindOnce(
                                  &AndroidExtensionGCMDriver::FinishUninstall,
                                  driver_weak_factory_.GetWeakPtr(), app_id)));
}

void AndroidExtensionGCMDriver::FinishUninstall(const std::string& app_id,
                                                gcm::GCMClient::Result result) {
  uninstalling_.erase(app_id);
  if (result == gcm::GCMClient::ASYNC_OPERATION_PENDING) {
    // Keep the cleanup-only handler and retry when the original native
    // register/unregister completes. No polling or synthetic success.
    return;
  }
  // The existing native driver may remove its local record before a network
  // error. INVALID_PARAMETER therefore means no local registration remains,
  // not proof that a remote registration was revoked.
  if (result == gcm::GCMClient::SUCCESS ||
      result == gcm::GCMClient::INVALID_PARAMETER) {
    SetPendingUninstall(app_id, false);
    ForgetRegistrationIfFinished(app_id);
  }
  if (!eligible_extensions_.contains(app_id)) {
    RemoveHandler(app_id);
  }
}

void AndroidExtensionGCMDriver::OnExtensionLoaded(
    content::BrowserContext* context,
    const Extension* extension) {
  if (shutdown_) {
    return;
  }
  const std::string app_id = extension->id();
  if (HasGCM(*extension)) {
    eligible_extensions_.insert(app_id);
    SetPendingUninstall(app_id, false);
    Refresh();
  } else if (ReadRegisteredApplications().contains(app_id)) {
    // A manifest update can remove GCM without a PermissionsManager event.
    SetPendingUninstall(app_id, true);
    Refresh();
  }
}

void AndroidExtensionGCMDriver::OnExtensionUnloaded(
    content::BrowserContext* context,
    const Extension* extension,
    UnloadedExtensionReason reason) {
  if (shutdown_) {
    return;
  }
  const std::string app_id = extension->id();
  eligible_extensions_.erase(app_id);
  const bool uninstalling = reason == UnloadedExtensionReason::UNINSTALL &&
                            (ReadRegisteredApplications().contains(app_id) ||
                             ReadPendingUninstalls().contains(app_id));
  auto alive = weak_factory_.GetWeakPtr();
  CancelRequestsFor(app_id);
  if (!alive) {
    return;
  }
  if (!uninstalling) {
    RemoveHandler(app_id);
  }
}

void AndroidExtensionGCMDriver::OnExtensionUninstalled(
    content::BrowserContext* context,
    const Extension* extension,
    UninstallReason reason) {
  if (shutdown_) {
    return;
  }
  const std::string app_id = extension->id();
  if (!ReadRegisteredApplications().contains(app_id) &&
      !ReadPendingUninstalls().contains(app_id)) {
    return;
  }
  eligible_extensions_.erase(app_id);
  SetPendingUninstall(app_id, true);
  Refresh();
}

void AndroidExtensionGCMDriver::OnExtensionPermissionsUpdated(
    const Extension& extension,
    const PermissionSet& permissions,
    PermissionsManager::UpdateReason reason) {
  if (shutdown_) {
    return;
  }
  const std::string app_id = extension.id();
  auto* registry = ExtensionRegistry::Get(profile_);
  const Extension* current = registry->GetInstalledExtension(app_id);
  if (!current) {
    return;
  }
  if (HasGCM(*current)) {
    if (registry->enabled_extensions().Contains(app_id)) {
      eligible_extensions_.insert(app_id);
      SetPendingUninstall(app_id, false);
      Refresh();
    }
    return;
  }
  eligible_extensions_.erase(app_id);
  // Optional permissions can also change while an extension is disabled.
  // Only this native channel's own registration attempts justify cleanup.
  const bool registered = ReadRegisteredApplications().contains(app_id);
  if (registered) {
    SetPendingUninstall(app_id, true);
  }
  auto alive = weak_factory_.GetWeakPtr();
  CancelRequestsFor(app_id);
  if (!alive) {
    return;
  }
  if (registered) {
    Refresh();
  } else {
    RemoveHandler(app_id);
  }
}

void AndroidExtensionGCMDriver::OnPermissionsManagerShutdown() {
  Shutdown();
}

void AndroidExtensionGCMDriver::Shutdown() {
  if (shutdown_) {
    return;
  }
  shutdown_ = true;
  pref_registrar_.RemoveAll();
  registry_observation_.Reset();
  permissions_observation_.Reset();
  weak_factory_.InvalidateWeakPtrs();
  eligible_extensions_.clear();
  StopDriver(gcm::GCMClient::GCM_DISABLED);
}

void AndroidExtensionGCMDriver::ShutdownHandler() {}
void AndroidExtensionGCMDriver::OnStoreReset() {}
void AndroidExtensionGCMDriver::OnSendError(
    const std::string& app_id,
    const gcm::GCMClient::SendErrorDetails& error) {}
void AndroidExtensionGCMDriver::OnSendAcknowledged(
    const std::string& app_id,
    const std::string& message_id) {}

void AndroidExtensionGCMDriver::OnMessage(const std::string& app_id,
                                          const gcm::IncomingMessage& message) {
  if (IsAllowed(app_id)) {
    message_callback_.Run(app_id, message);
  }
}

void AndroidExtensionGCMDriver::OnMessagesDeleted(const std::string& app_id) {
  if (IsAllowed(app_id)) {
    deleted_callback_.Run(app_id);
  }
}

}  // namespace extensions
