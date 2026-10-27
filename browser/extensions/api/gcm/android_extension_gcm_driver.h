// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef BRAVE_BROWSER_EXTENSIONS_API_GCM_ANDROID_EXTENSION_GCM_DRIVER_H_
#define BRAVE_BROWSER_EXTENSIONS_API_GCM_ANDROID_EXTENSION_GCM_DRIVER_H_

#include <cstdint>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <vector>

#include "base/functional/callback.h"
#include "base/memory/raw_ptr.h"
#include "base/memory/weak_ptr.h"
#include "base/scoped_observation.h"
#include "base/task/sequenced_task_runner.h"
#include "components/gcm_driver/gcm_app_handler.h"
#include "components/gcm_driver/gcm_driver.h"
#include "components/prefs/pref_change_registrar.h"
#include "extensions/browser/extension_registry.h"
#include "extensions/browser/extension_registry_observer.h"
#include "extensions/browser/permissions_manager.h"

class Profile;

namespace extensions {

// Separate native registration/receive channel. The platform GCM and
// InstanceID services continue to own Android Web Push and instanceID tokens.
class AndroidExtensionGCMDriver : public gcm::GCMAppHandler,
                                  public ExtensionRegistryObserver,
                                  public PermissionsManager::Observer {
 public:
  using DriverFactory =
      base::RepeatingCallback<std::unique_ptr<gcm::GCMDriver>()>;
  using MessageCallback =
      base::RepeatingCallback<void(const std::string&,
                                   const gcm::IncomingMessage&)>;
  using DeletedCallback = base::RepeatingCallback<void(const std::string&)>;

  AndroidExtensionGCMDriver(Profile* profile,
                            MessageCallback message_callback,
                            DeletedCallback deleted_callback,
                            DriverFactory driver_factory_for_testing = {});
  ~AndroidExtensionGCMDriver() override;
  AndroidExtensionGCMDriver(const AndroidExtensionGCMDriver&) = delete;
  AndroidExtensionGCMDriver& operator=(const AndroidExtensionGCMDriver&) =
      delete;

  void Register(const std::string& app_id,
                const std::vector<std::string>& sender_ids,
                gcm::GCMDriver::RegisterCallback callback);
  void Unregister(const std::string& app_id,
                  gcm::GCMDriver::UnregisterCallback callback);
  void Shutdown();

  // GCMAppHandler. This forwarding handler is independent of the platform
  // driver's handler and cannot shut down its JS event router.
  void ShutdownHandler() override;
  void OnStoreReset() override;
  void OnMessage(const std::string& app_id,
                 const gcm::IncomingMessage& message) override;
  void OnMessagesDeleted(const std::string& app_id) override;
  void OnSendError(const std::string& app_id,
                   const gcm::GCMClient::SendErrorDetails& error) override;
  void OnSendAcknowledged(const std::string& app_id,
                          const std::string& message_id) override;

 private:
  struct PendingRegister {
    std::string app_id;
    gcm::GCMDriver::RegisterCallback callback;
  };
  struct PendingUnregister {
    std::string app_id;
    gcm::GCMDriver::UnregisterCallback callback;
  };

  bool IsAllowed(const std::string& app_id) const;
  bool IsEnabled() const;
  void Refresh();
  std::unique_ptr<gcm::GCMDriver> CreateDriver();
  void AddHandler(const std::string& app_id);
  void RemoveHandler(const std::string& app_id);
  void StopDriver(gcm::GCMClient::Result result);
  void CancelRequestsFor(const std::string& app_id);
  void RetryPendingUninstall(const std::string& app_id);
  void ReconcileStoredRegistrations();
  std::set<std::string> ReadRegisteredApplications() const;
  void RememberRegistration(const std::string& app_id);
  void ForgetRegistrationIfFinished(const std::string& app_id);
  bool HasPendingNativeRequest(const std::string& app_id) const;
  void FinishRegister(uint64_t request_id,
                      const std::string& registration_id,
                      gcm::GCMClient::Result result);
  void FinishUnregister(uint64_t request_id, gcm::GCMClient::Result result);
  std::set<std::string> ReadPendingUninstalls() const;
  void SetPendingUninstall(const std::string& app_id, bool pending);
  void UnregisterUninstalled(const std::string& app_id);
  void FinishUninstall(const std::string& app_id,
                       gcm::GCMClient::Result result);

  // ExtensionRegistryObserver:
  void OnExtensionLoaded(content::BrowserContext* context,
                         const Extension* extension) override;
  void OnExtensionUnloaded(content::BrowserContext* context,
                           const Extension* extension,
                           UnloadedExtensionReason reason) override;
  void OnExtensionUninstalled(content::BrowserContext* context,
                              const Extension* extension,
                              UninstallReason reason) override;

  // PermissionsManager::Observer:
  void OnExtensionPermissionsUpdated(
      const Extension& extension,
      const PermissionSet& permissions,
      PermissionsManager::UpdateReason reason) override;
  void OnPermissionsManagerShutdown() override;

  raw_ptr<Profile> profile_;
  MessageCallback message_callback_;
  DeletedCallback deleted_callback_;
  DriverFactory driver_factory_for_testing_;
  scoped_refptr<base::SequencedTaskRunner> blocking_task_runner_;
  PrefChangeRegistrar pref_registrar_;
  base::ScopedObservation<ExtensionRegistry, ExtensionRegistryObserver>
      registry_observation_{this};
  base::ScopedObservation<PermissionsManager, PermissionsManager::Observer>
      permissions_observation_{this};
  std::set<std::string> eligible_extensions_;
  std::set<std::string> handlers_;
  std::set<std::string> uninstalling_;
  std::map<uint64_t, PendingRegister> registrations_;
  std::map<uint64_t, PendingUnregister> unregistrations_;
  uint64_t next_request_id_ = 0;
  bool shutdown_ = false;
  // Explicitly shut down while this forwarding handler is still alive.
  std::unique_ptr<gcm::GCMDriver> driver_;
  base::WeakPtrFactory<AndroidExtensionGCMDriver> driver_weak_factory_{this};
  base::WeakPtrFactory<AndroidExtensionGCMDriver> weak_factory_{this};
};

}  // namespace extensions

#endif  // BRAVE_BROWSER_EXTENSIONS_API_GCM_ANDROID_EXTENSION_GCM_DRIVER_H_
