// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "brave/browser/extensions/api/gcm/android_extension_gcm_driver.h"

#include <algorithm>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "base/functional/bind.h"
#include "base/memory/raw_ptr.h"
#include "base/values.h"
#include "brave/components/constants/pref_names.h"
#include "chrome/browser/extensions/test_extension_system.h"
#include "chrome/browser/profiles/profile.h"
#include "chrome/test/base/testing_profile.h"
#include "components/gcm_driver/fake_gcm_driver.h"
#include "components/prefs/pref_service.h"
#include "components/sync_preferences/testing_pref_service_syncable.h"
#include "content/public/test/browser_task_environment.h"
#include "extensions/browser/extension_registry.h"
#include "extensions/browser/extension_system.h"
#include "extensions/browser/permissions_manager.h"
#include "extensions/browser/uninstall_reason.h"
#include "extensions/browser/unloaded_extension_reason.h"
#include "extensions/common/extension.h"
#include "extensions/common/extension_builder.h"
#include "extensions/common/manifest_handlers/permissions_parser.h"
#include "extensions/common/permissions/permission_set.h"
#include "extensions/common/permissions/permissions_data.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace extensions {
namespace {

using Result = gcm::GCMClient::Result;

struct RegisterResult {
  int calls = 0;
  std::string registration_id;
  Result result = gcm::GCMClient::UNKNOWN_ERROR;
};

struct UnregisterResult {
  int calls = 0;
  Result result = gcm::GCMClient::UNKNOWN_ERROR;
};

gcm::GCMDriver::RegisterCallback CaptureRegister(
    std::shared_ptr<RegisterResult> result) {
  return base::BindOnce(
      [](std::shared_ptr<RegisterResult> result, const std::string& id,
         Result status) {
        ++result->calls;
        result->registration_id = id;
        result->result = status;
      },
      std::move(result));
}

gcm::GCMDriver::UnregisterCallback CaptureUnregister(
    std::shared_ptr<UnregisterResult> result) {
  return base::BindOnce(
      [](std::shared_ptr<UnregisterResult> result, Result status) {
        ++result->calls;
        result->result = status;
      },
      std::move(result));
}

class ControlledGCMDriver;

struct DriverState {
  raw_ptr<ControlledGCMDriver> driver = nullptr;
  bool enabled = false;
  int shutdowns = 0;
  int destructions = 0;
  std::vector<std::pair<std::string, std::vector<std::string>>> registrations;
  std::vector<std::string> unregistrations;
};

// Only the platform operations are controlled. Register/Unregister, their
// callback maps and RegisterAfterUnregister are the real GCMDriver methods.
class ControlledGCMDriver : public gcm::FakeGCMDriver {
 public:
  explicit ControlledGCMDriver(std::shared_ptr<DriverState> state)
      : state_(std::move(state)) {
    state_->driver = this;
  }

  ~ControlledGCMDriver() override {
    state_->driver = nullptr;
    ++state_->destructions;
  }

  void SetEnabled(bool enabled) override { state_->enabled = enabled; }

  void Shutdown() override {
    ++state_->shutdowns;
    gcm::FakeGCMDriver::Shutdown();
  }

  void CompleteRegister(const std::string& app_id,
                        const std::string& registration_id,
                        Result result = gcm::GCMClient::SUCCESS) {
    RegisterFinished(app_id, registration_id, result);
  }

  void CompleteUnregister(const std::string& app_id,
                          Result result = gcm::GCMClient::SUCCESS) {
    UnregisterFinished(app_id, result);
  }

 protected:
  Result EnsureStarted(gcm::GCMClient::StartMode start_mode) override {
    return state_->enabled ? gcm::GCMClient::SUCCESS
                           : gcm::GCMClient::GCM_DISABLED;
  }

  void RegisterImpl(const std::string& app_id,
                    const std::vector<std::string>& sender_ids) override {
    state_->registrations.emplace_back(app_id, sender_ids);
  }

  void UnregisterImpl(const std::string& app_id) override {
    state_->unregistrations.push_back(app_id);
  }

 private:
  std::shared_ptr<DriverState> state_;
};

class AndroidExtensionGCMDriverTest : public testing::Test {
 protected:
  void SetUp() override {
    // Exercise the production profile-pref registration; do not install a
    // synthetic default that could hide a missing registration hunk.
    ASSERT_TRUE(profile_.GetPrefs()->FindPreference(kBraveGCMChannelStatus));
    ASSERT_TRUE(profile_.GetPrefs()->FindPreference(
        kBraveExtensionGCMPendingUnregistrations));
    ASSERT_TRUE(profile_.GetPrefs()->FindPreference(
        kBraveExtensionGCMRegisteredApplications));
  }

  void TearDown() override {
    wrapper_.reset();
    task_environment_.RunUntilIdle();
  }

  void CreateWrapper(Profile* profile = nullptr) {
    ASSERT_FALSE(wrapper_);
    wrapper_ = std::make_unique<AndroidExtensionGCMDriver>(
        profile ? profile : &profile_,
        base::BindRepeating(&AndroidExtensionGCMDriverTest::OnMessage,
                            base::Unretained(this)),
        base::BindRepeating(&AndroidExtensionGCMDriverTest::OnMessagesDeleted,
                            base::Unretained(this)),
        base::BindRepeating(&AndroidExtensionGCMDriverTest::CreateDriver,
                            base::Unretained(this)));
  }

  std::unique_ptr<gcm::GCMDriver> CreateDriver() {
    auto state = std::make_shared<DriverState>();
    generations_.push_back(state);
    return std::make_unique<ControlledGCMDriver>(std::move(state));
  }

  scoped_refptr<const Extension> AddExtension(const std::string& name,
                                              bool notify = true,
                                              bool has_gcm = true) {
    ExtensionBuilder builder(name);
    builder.SetManifestVersion(3);
    if (has_gcm) {
      builder.AddAPIPermission("gcm");
    }
    auto extension = builder.Build();
    EXPECT_TRUE(registry()->AddEnabled(extension));
    if (notify) {
      registry()->TriggerOnLoaded(extension.get());
    }
    return extension;
  }

  scoped_refptr<const Extension> AddOptionalGcmExtension() {
    auto extension = ExtensionBuilder("optional gcm")
                         .SetManifestVersion(3)
                         .AddOptionalAPIPermission("gcm")
                         .Build();
    EXPECT_TRUE(registry()->AddEnabled(extension));
    registry()->TriggerOnLoaded(extension.get());
    return extension;
  }

  void SetOptionalGcmGranted(const Extension& extension, bool granted) {
    // Exercise the permission-state/manager-observer boundary used by
    // PermissionsUpdater, without renderer or persisted permission updates.
    const auto& optional =
        PermissionsParser::GetOptionalPermissions(&extension);
    ASSERT_TRUE(optional.HasAPIPermission("gcm"));
    const auto* data = extension.permissions_data();
    auto active = granted ? PermissionSet::CreateUnion(
                                data->active_permissions(), optional)
                          : PermissionSet::CreateDifference(
                                data->active_permissions(), optional);
    data->SetPermissions(std::move(active),
                         data->withheld_permissions().Clone());
    PermissionsManager::Get(&profile_)->NotifyExtensionPermissionsUpdated(
        extension, optional,
        granted ? PermissionsManager::UpdateReason::kAdded
                : PermissionsManager::UpdateReason::kRemoved);
  }

  void Unload(const Extension& extension, UnloadedExtensionReason reason) {
    EXPECT_TRUE(registry()->RemoveEnabled(extension.id()));
    registry()->TriggerOnUnloaded(&extension, reason);
  }

  void Uninstall(const Extension& extension) {
    Unload(extension, UnloadedExtensionReason::UNINSTALL);
    registry()->TriggerOnUninstalled(&extension, UNINSTALL_REASON_FOR_TESTING);
  }

  bool HasPendingUninstall(const std::string& app_id) {
    for (const auto& value : profile_.GetPrefs()->GetList(
             kBraveExtensionGCMPendingUnregistrations)) {
      if (value.is_string() && value.GetString() == app_id) {
        return true;
      }
    }
    return false;
  }

  void RegisterSuccessfully(const Extension& extension) {
    auto result = std::make_shared<RegisterResult>();
    wrapper_->Register(extension.id(), {"123"}, CaptureRegister(result));
    ASSERT_FALSE(generations_.empty());
    auto state = generations_.back();
    ASSERT_TRUE(state->driver);
    state->driver->CompleteRegister(extension.id(), "known-token");
    task_environment_.RunUntilIdle();
    ASSERT_EQ(1, result->calls);
    ASSERT_EQ(gcm::GCMClient::SUCCESS, result->result);
    ASSERT_TRUE(HasKnownApplication(extension.id()));
  }

  void SeedKnownApplication(const std::string& app_id) {
    base::ListValue known;
    known.Append(app_id);
    profile_.GetPrefs()->SetList(kBraveExtensionGCMRegisteredApplications,
                                 std::move(known));
  }

  bool HasKnownApplication(const std::string& app_id) {
    for (const auto& value : profile_.GetPrefs()->GetList(
             kBraveExtensionGCMRegisteredApplications)) {
      if (value.is_string() && value.GetString() == app_id) {
        return true;
      }
    }
    return false;
  }

  void SetExtensionSystemReady() {
    static_cast<TestExtensionSystem*>(ExtensionSystem::Get(&profile_))
        ->SetReady();
    task_environment_.RunUntilIdle();
  }

  void SetEnabled(bool enabled) {
    profile_.GetPrefs()->SetBoolean(kBraveGCMChannelStatus, enabled);
  }

  ExtensionRegistry* registry() { return ExtensionRegistry::Get(&profile_); }

  void OnMessage(const std::string& app_id,
                 const gcm::IncomingMessage& message) {
    messages_.emplace_back(app_id, message.raw_data);
  }

  void OnMessagesDeleted(const std::string& app_id) {
    deleted_messages_.push_back(app_id);
  }

  content::BrowserTaskEnvironment task_environment_;
  TestingProfile profile_;
  std::vector<std::shared_ptr<DriverState>> generations_;
  std::vector<std::pair<std::string, std::string>> messages_;
  std::vector<std::string> deleted_messages_;
  std::unique_ptr<AndroidExtensionGCMDriver> wrapper_;
};

TEST_F(AndroidExtensionGCMDriverTest, DefaultOffDoesNotCreateDriver) {
  auto extension = AddExtension("existing", false);
  ASSERT_FALSE(profile_.GetPrefs()->GetBoolean(kBraveGCMChannelStatus));
  CreateWrapper();
  auto result = std::make_shared<RegisterResult>();
  wrapper_->Register(extension->id(), {"123"}, CaptureRegister(result));
  EXPECT_EQ(1, result->calls);
  EXPECT_EQ(gcm::GCMClient::GCM_DISABLED, result->result);
  EXPECT_TRUE(result->registration_id.empty());
  EXPECT_TRUE(generations_.empty());
}

TEST_F(AndroidExtensionGCMDriverTest, StartupSnapshotRestoresReceiveHandler) {
  auto extension = AddExtension("already installed", false);
  SetEnabled(true);
  CreateWrapper();
  ASSERT_EQ(1u, generations_.size());
  auto state = generations_.front();
  ASSERT_TRUE(state->driver);
  EXPECT_TRUE(state->enabled);
  EXPECT_EQ(wrapper_.get(), state->driver->GetAppHandler(extension->id()));
  EXPECT_TRUE(state->registrations.empty());

  gcm::IncomingMessage message;
  message.raw_data = "restored receive";
  state->driver->GetAppHandler(extension->id())
      ->OnMessage(extension->id(), message);
  state->driver->GetAppHandler(extension->id())
      ->OnMessagesDeleted(extension->id());
  ASSERT_EQ(1u, messages_.size());
  EXPECT_EQ(extension->id(), messages_[0].first);
  EXPECT_EQ(message.raw_data, messages_[0].second);
  EXPECT_EQ(std::vector<std::string>{extension->id()}, deleted_messages_);
}

TEST_F(AndroidExtensionGCMDriverTest, ForwardsAllHundredSenderIds) {
  auto extension = AddExtension("multi sender", false);
  SetEnabled(true);
  CreateWrapper();
  std::vector<std::string> senders;
  for (int i = 100; i > 0; --i) {
    senders.push_back(std::to_string(i));
  }
  auto result = std::make_shared<RegisterResult>();
  wrapper_->Register(extension->id(), senders, CaptureRegister(result));
  auto state = generations_.front();
  ASSERT_EQ(1u, state->registrations.size());
  std::sort(senders.begin(), senders.end());
  EXPECT_EQ(extension->id(), state->registrations[0].first);
  EXPECT_EQ(senders, state->registrations[0].second);
  state->driver->CompleteRegister(extension->id(), "native-registration");
  EXPECT_EQ(0, result->calls);
  task_environment_.RunUntilIdle();
  EXPECT_EQ(1, result->calls);
  EXPECT_EQ(gcm::GCMClient::SUCCESS, result->result);
  EXPECT_EQ("native-registration", result->registration_id);
}

TEST_F(AndroidExtensionGCMDriverTest, DisableCompletesPendingRequestsOnce) {
  auto registering = AddExtension("registering", false);
  auto unregistering = AddExtension("unregistering", false);
  SetEnabled(true);
  CreateWrapper();
  auto registration = std::make_shared<RegisterResult>();
  auto unregistration = std::make_shared<UnregisterResult>();
  wrapper_->Register(registering->id(), {"123"}, CaptureRegister(registration));
  wrapper_->Unregister(unregistering->id(), CaptureUnregister(unregistration));
  auto state = generations_.front();
  SetEnabled(false);
  EXPECT_EQ(1, state->shutdowns);
  EXPECT_EQ(1, state->destructions);
  EXPECT_EQ(1, registration->calls);
  EXPECT_EQ(1, unregistration->calls);
  EXPECT_EQ(gcm::GCMClient::GCM_DISABLED, registration->result);
  EXPECT_EQ(gcm::GCMClient::GCM_DISABLED, unregistration->result);
  wrapper_->Shutdown();
  task_environment_.RunUntilIdle();
  EXPECT_EQ(1, registration->calls);
  EXPECT_EQ(1, unregistration->calls);
}

TEST_F(AndroidExtensionGCMDriverTest, DisableDropsAlreadyPostedCompletion) {
  auto extension = AddExtension("posted", false);
  SetEnabled(true);
  CreateWrapper();
  auto result = std::make_shared<RegisterResult>();
  wrapper_->Register(extension->id(), {"123"}, CaptureRegister(result));
  generations_.front()->driver->CompleteRegister(extension->id(), "too late");
  EXPECT_EQ(0, result->calls);
  SetEnabled(false);
  EXPECT_EQ(1, result->calls);
  EXPECT_EQ(gcm::GCMClient::GCM_DISABLED, result->result);
  task_environment_.RunUntilIdle();
  EXPECT_EQ(1, result->calls);
  EXPECT_TRUE(result->registration_id.empty());
}

TEST_F(AndroidExtensionGCMDriverTest,
       ReentrantDisableWaitsForNativeRegisterAfterUnregister) {
  auto extension = AddExtension("reentrant", false);
  SetEnabled(true);
  CreateWrapper();
  auto registration = std::make_shared<RegisterResult>();
  auto unregistration = std::make_shared<UnregisterResult>();
  wrapper_->Unregister(
      extension->id(),
      base::BindOnce(
          [](TestingProfile* profile, std::shared_ptr<UnregisterResult> result,
             Result status) {
            ++result->calls;
            result->result = status;
            profile->GetPrefs()->SetBoolean(kBraveGCMChannelStatus, false);
          },
          base::Unretained(&profile_), unregistration));
  wrapper_->Register(extension->id(), {"123"}, CaptureRegister(registration));
  auto state = generations_.front();
  EXPECT_TRUE(state->registrations.empty());
  state->driver->CompleteUnregister(extension->id());
  // Real GCMDriver::RegisterAfterUnregister has continued while the driver is
  // alive. The external callback can only disable it after this stack unwinds.
  EXPECT_EQ(0, unregistration->calls);
  EXPECT_EQ(0, state->destructions);
  ASSERT_EQ(1u, state->registrations.size());
  task_environment_.RunUntilIdle();
  EXPECT_EQ(1, state->destructions);
  EXPECT_EQ(1, unregistration->calls);
  EXPECT_EQ(gcm::GCMClient::SUCCESS, unregistration->result);
  EXPECT_EQ(1, registration->calls);
  EXPECT_EQ(gcm::GCMClient::GCM_DISABLED, registration->result);
}

TEST_F(AndroidExtensionGCMDriverTest,
       UninstallDuringRegisterRetriesCleanupWithoutDispatch) {
  auto extension = AddExtension("uninstall registering", false);
  SetEnabled(true);
  CreateWrapper();
  auto result = std::make_shared<RegisterResult>();
  wrapper_->Register(extension->id(), {"123"}, CaptureRegister(result));
  auto state = generations_.front();
  Uninstall(*extension);
  EXPECT_EQ(1, result->calls);
  EXPECT_EQ(gcm::GCMClient::GCM_DISABLED, result->result);
  EXPECT_TRUE(HasPendingUninstall(extension->id()));
  EXPECT_TRUE(state->unregistrations.empty());
  ASSERT_TRUE(state->driver);
  ASSERT_TRUE(state->driver->GetAppHandler(extension->id()));
  gcm::IncomingMessage message;
  state->driver->GetAppHandler(extension->id())
      ->OnMessage(extension->id(), message);
  state->driver->GetAppHandler(extension->id())
      ->OnMessagesDeleted(extension->id());
  EXPECT_TRUE(messages_.empty());
  EXPECT_TRUE(deleted_messages_.empty());

  state->driver->CompleteRegister(extension->id(), "uninstalled-token");
  task_environment_.RunUntilIdle();
  EXPECT_EQ(std::vector<std::string>{extension->id()}, state->unregistrations);
  EXPECT_EQ(1, result->calls);
  state->driver->CompleteUnregister(extension->id());
  task_environment_.RunUntilIdle();
  EXPECT_FALSE(HasPendingUninstall(extension->id()));
  EXPECT_EQ(1, state->destructions);
}

TEST_F(AndroidExtensionGCMDriverTest,
       UninstallDuringUnregisterRetriesAfterNativeCompletion) {
  auto extension = AddExtension("uninstall unregistering", false);
  SetEnabled(true);
  CreateWrapper();
  RegisterSuccessfully(*extension);
  auto result = std::make_shared<UnregisterResult>();
  wrapper_->Unregister(extension->id(), CaptureUnregister(result));
  auto state = generations_.front();
  Uninstall(*extension);
  EXPECT_EQ(1, result->calls);
  EXPECT_EQ(gcm::GCMClient::GCM_DISABLED, result->result);
  ASSERT_EQ(1u, state->unregistrations.size());
  state->driver->CompleteUnregister(extension->id());
  task_environment_.RunUntilIdle();
  ASSERT_EQ(2u, state->unregistrations.size());
  EXPECT_EQ(extension->id(), state->unregistrations[1]);
  EXPECT_TRUE(HasPendingUninstall(extension->id()));
  // No local record after the first native unregister is also terminal for
  // local cleanup; it is not evidence of successful remote revocation.
  state->driver->CompleteUnregister(extension->id(),
                                    gcm::GCMClient::INVALID_PARAMETER);
  task_environment_.RunUntilIdle();
  EXPECT_FALSE(HasPendingUninstall(extension->id()));
  EXPECT_EQ(1, result->calls);
  EXPECT_EQ(1, state->destructions);
}

TEST_F(AndroidExtensionGCMDriverTest,
       LastHandlerReloadUsesNewGenerationAndDropsOldCompletion) {
  auto extension = AddExtension("reload", false);
  SetEnabled(true);
  CreateWrapper();
  auto old_result = std::make_shared<RegisterResult>();
  wrapper_->Register(extension->id(), {"123"}, CaptureRegister(old_result));
  auto old_state = generations_.front();
  old_state->driver->CompleteRegister(extension->id(), "old-token");
  Unload(*extension, UnloadedExtensionReason::UPDATE);
  EXPECT_EQ(1, old_state->destructions);
  EXPECT_EQ(1, old_result->calls);
  EXPECT_EQ(gcm::GCMClient::GCM_DISABLED, old_result->result);
  EXPECT_TRUE(registry()->AddEnabled(extension));
  registry()->TriggerOnLoaded(extension.get());
  ASSERT_EQ(2u, generations_.size());
  auto new_result = std::make_shared<RegisterResult>();
  wrapper_->Register(extension->id(), {"456"}, CaptureRegister(new_result));
  task_environment_.RunUntilIdle();
  EXPECT_EQ(0, new_result->calls);
  EXPECT_EQ(1, old_result->calls);
  generations_.back()->driver->CompleteRegister(extension->id(), "new-token");
  task_environment_.RunUntilIdle();
  EXPECT_EQ(1, new_result->calls);
  EXPECT_EQ("new-token", new_result->registration_id);
}

TEST_F(AndroidExtensionGCMDriverTest,
       OtherExtensionKeepsPendingRegisterAcrossReloadAndUninstall) {
  auto extension = AddExtension("reload pending register", false);
  auto other = AddExtension("keep native driver alive", false);
  SetEnabled(true);
  CreateWrapper();
  auto result = std::make_shared<RegisterResult>();
  wrapper_->Register(extension->id(), {"123"}, CaptureRegister(result));
  auto state = generations_.front();
  Unload(*extension, UnloadedExtensionReason::UPDATE);
  EXPECT_EQ(1, result->calls);
  EXPECT_EQ(gcm::GCMClient::GCM_DISABLED, result->result);
  EXPECT_EQ(0, state->destructions);
  ASSERT_TRUE(state->driver);
  EXPECT_TRUE(state->driver->GetAppHandler(other->id()));
  EXPECT_TRUE(registry()->AddEnabled(extension));
  registry()->TriggerOnLoaded(extension.get());
  Uninstall(*extension);
  EXPECT_TRUE(HasPendingUninstall(extension->id()));
  EXPECT_TRUE(state->unregistrations.empty());

  state->driver->CompleteRegister(extension->id(), "removed-token");
  task_environment_.RunUntilIdle();
  EXPECT_EQ(std::vector<std::string>{extension->id()}, state->unregistrations);
  EXPECT_EQ(1, result->calls);
  ASSERT_TRUE(state->driver->GetAppHandler(extension->id()));
  state->driver->GetAppHandler(extension->id())
      ->OnMessage(extension->id(), gcm::IncomingMessage());
  EXPECT_TRUE(messages_.empty());
  state->driver->CompleteUnregister(extension->id());
  task_environment_.RunUntilIdle();
  EXPECT_FALSE(HasPendingUninstall(extension->id()));
  EXPECT_EQ(0, state->destructions);
  EXPECT_TRUE(state->driver->GetAppHandler(other->id()));
  EXPECT_EQ(nullptr, state->driver->GetAppHandler(extension->id()));
}

TEST_F(AndroidExtensionGCMDriverTest,
       OtherExtensionKeepsPendingUnregisterAcrossReloadAndUninstall) {
  auto extension = AddExtension("reload pending unregister", false);
  auto other = AddExtension("keep native driver alive", false);
  SetEnabled(true);
  CreateWrapper();
  RegisterSuccessfully(*extension);
  auto result = std::make_shared<UnregisterResult>();
  wrapper_->Unregister(extension->id(), CaptureUnregister(result));
  auto state = generations_.front();
  Unload(*extension, UnloadedExtensionReason::UPDATE);
  EXPECT_EQ(1, result->calls);
  EXPECT_EQ(gcm::GCMClient::GCM_DISABLED, result->result);
  EXPECT_EQ(0, state->destructions);
  ASSERT_TRUE(state->driver);
  EXPECT_TRUE(state->driver->GetAppHandler(other->id()));
  EXPECT_TRUE(registry()->AddEnabled(extension));
  registry()->TriggerOnLoaded(extension.get());
  Uninstall(*extension);
  EXPECT_TRUE(HasPendingUninstall(extension->id()));
  ASSERT_EQ(1u, state->unregistrations.size());

  state->driver->CompleteUnregister(extension->id());
  task_environment_.RunUntilIdle();
  ASSERT_EQ(2u, state->unregistrations.size());
  EXPECT_EQ(extension->id(), state->unregistrations.back());
  EXPECT_EQ(1, result->calls);
  ASSERT_TRUE(state->driver->GetAppHandler(extension->id()));
  state->driver->GetAppHandler(extension->id())
      ->OnMessagesDeleted(extension->id());
  EXPECT_TRUE(deleted_messages_.empty());
  state->driver->CompleteUnregister(extension->id(),
                                    gcm::GCMClient::INVALID_PARAMETER);
  task_environment_.RunUntilIdle();
  EXPECT_FALSE(HasPendingUninstall(extension->id()));
  EXPECT_EQ(0, state->destructions);
  EXPECT_TRUE(state->driver->GetAppHandler(other->id()));
  EXPECT_EQ(nullptr, state->driver->GetAppHandler(extension->id()));
}

TEST_F(AndroidExtensionGCMDriverTest, OffTheRecordNeverCreatesNativeDriver) {
  auto extension = AddExtension("regular profile", false);
  SetEnabled(true);
  Profile* otr = profile_.GetPrimaryOTRProfile(/*create_if_needed=*/true);
  ASSERT_TRUE(otr->IsOffTheRecord());
  CreateWrapper(otr);
  auto result = std::make_shared<RegisterResult>();
  wrapper_->Register(extension->id(), {"123"}, CaptureRegister(result));
  EXPECT_EQ(1, result->calls);
  EXPECT_EQ(gcm::GCMClient::GCM_DISABLED, result->result);
  EXPECT_TRUE(generations_.empty());
}

TEST_F(AndroidExtensionGCMDriverTest,
       PendingUninstallSurvivesWrapperRestartWhileOff) {
  auto extension = AddExtension("off uninstall", false);
  SetEnabled(true);
  CreateWrapper();
  RegisterSuccessfully(*extension);
  SetEnabled(false);
  ASSERT_EQ(1u, generations_.size());
  EXPECT_EQ(1, generations_.front()->destructions);
  Uninstall(*extension);
  EXPECT_TRUE(HasPendingUninstall(extension->id()));
  EXPECT_EQ(1u, generations_.size());
  wrapper_.reset();
  CreateWrapper();
  EXPECT_EQ(1u, generations_.size());
  EXPECT_TRUE(HasPendingUninstall(extension->id()));
  SetEnabled(true);
  ASSERT_EQ(2u, generations_.size());
  auto state = generations_.back();
  EXPECT_EQ(std::vector<std::string>{extension->id()}, state->unregistrations);
  state->driver->CompleteUnregister(extension->id());
  task_environment_.RunUntilIdle();
  EXPECT_FALSE(HasPendingUninstall(extension->id()));
  EXPECT_EQ(1, state->destructions);
}

TEST_F(AndroidExtensionGCMDriverTest, ReinstallDropsStaleUninstallIntent) {
  auto extension = AddExtension("reinstalled", false);
  base::ListValue pending;
  pending.Append(extension->id());
  profile_.GetPrefs()->SetList(kBraveExtensionGCMPendingUnregistrations,
                               std::move(pending));
  SetEnabled(true);
  CreateWrapper();
  ASSERT_EQ(1u, generations_.size());
  EXPECT_FALSE(HasPendingUninstall(extension->id()));
  EXPECT_TRUE(generations_.front()->unregistrations.empty());
  EXPECT_EQ(wrapper_.get(),
            generations_.front()->driver->GetAppHandler(extension->id()));
}

TEST_F(AndroidExtensionGCMDriverTest,
       MalformedUninstallIntentDoesNotStartDriver) {
  base::ListValue pending;
  pending.Append("not-an-extension-id");
  pending.Append(42);
  profile_.GetPrefs()->SetList(kBraveExtensionGCMPendingUnregistrations,
                               std::move(pending));
  SetEnabled(true);
  CreateWrapper();
  EXPECT_TRUE(generations_.empty());
}

TEST_F(AndroidExtensionGCMDriverTest, ManagedFalseOverridesUserOptIn) {
  auto extension = AddExtension("managed", false);
  profile_.GetTestingPrefService()->SetManagedPref(kBraveGCMChannelStatus,
                                                   base::Value(false));
  SetEnabled(true);
  CreateWrapper();
  EXPECT_TRUE(generations_.empty());
  profile_.GetTestingPrefService()->RemoveManagedPref(kBraveGCMChannelStatus);
  ASSERT_EQ(1u, generations_.size());
  EXPECT_EQ(wrapper_.get(),
            generations_.front()->driver->GetAppHandler(extension->id()));
}

TEST_F(AndroidExtensionGCMDriverTest,
       GrantOptionalGcmWhileLoadedAttachesHandlerAndRegisters) {
  auto extension = AddOptionalGcmExtension();
  ASSERT_FALSE(extension->permissions_data()->HasAPIPermission("gcm"));
  SetEnabled(true);
  CreateWrapper();
  EXPECT_TRUE(generations_.empty());
  auto denied = std::make_shared<RegisterResult>();
  wrapper_->Register(extension->id(), {"123"}, CaptureRegister(denied));
  EXPECT_EQ(1, denied->calls);
  EXPECT_EQ(gcm::GCMClient::GCM_DISABLED, denied->result);

  SetOptionalGcmGranted(*extension, true);
  ASSERT_TRUE(extension->permissions_data()->HasAPIPermission("gcm"));
  ASSERT_EQ(1u, generations_.size());
  auto state = generations_.front();
  ASSERT_TRUE(state->driver);
  EXPECT_EQ(wrapper_.get(), state->driver->GetAppHandler(extension->id()));
  auto granted = std::make_shared<RegisterResult>();
  wrapper_->Register(extension->id(), {"123"}, CaptureRegister(granted));
  ASSERT_EQ(1u, state->registrations.size());
  state->driver->CompleteRegister(extension->id(), "optional-token");
  task_environment_.RunUntilIdle();
  EXPECT_EQ(1, granted->calls);
  EXPECT_EQ(gcm::GCMClient::SUCCESS, granted->result);
  EXPECT_EQ("optional-token", granted->registration_id);
}

TEST_F(AndroidExtensionGCMDriverTest,
       RevokeOptionalGcmDuringRegisterCancelsAndCleansUp) {
  auto extension = AddOptionalGcmExtension();
  SetEnabled(true);
  CreateWrapper();
  SetOptionalGcmGranted(*extension, true);
  ASSERT_EQ(1u, generations_.size());
  auto state = generations_.front();
  auto result = std::make_shared<RegisterResult>();
  wrapper_->Register(extension->id(), {"123"}, CaptureRegister(result));

  SetOptionalGcmGranted(*extension, false);
  ASSERT_FALSE(extension->permissions_data()->HasAPIPermission("gcm"));
  EXPECT_TRUE(registry()->enabled_extensions().Contains(extension->id()));
  EXPECT_EQ(1, result->calls);
  EXPECT_EQ(gcm::GCMClient::GCM_DISABLED, result->result);
  EXPECT_TRUE(HasPendingUninstall(extension->id()));
  EXPECT_TRUE(state->unregistrations.empty());
  ASSERT_TRUE(state->driver);
  ASSERT_TRUE(state->driver->GetAppHandler(extension->id()));
  state->driver->GetAppHandler(extension->id())
      ->OnMessage(extension->id(), gcm::IncomingMessage());
  state->driver->GetAppHandler(extension->id())
      ->OnMessagesDeleted(extension->id());
  EXPECT_TRUE(messages_.empty());
  EXPECT_TRUE(deleted_messages_.empty());

  state->driver->CompleteRegister(extension->id(), "revoked-token");
  task_environment_.RunUntilIdle();
  EXPECT_EQ(std::vector<std::string>{extension->id()}, state->unregistrations);
  EXPECT_EQ(1, result->calls);
  state->driver->CompleteUnregister(extension->id());
  task_environment_.RunUntilIdle();
  EXPECT_FALSE(HasPendingUninstall(extension->id()));
  EXPECT_EQ(1, state->destructions);
  EXPECT_EQ(1, result->calls);
  EXPECT_TRUE(result->registration_id.empty());
}

TEST_F(AndroidExtensionGCMDriverTest,
       UpdateRemovingGcmPermissionCleansUpKnownRegistration) {
  auto extension = AddExtension("old manifest", false);
  SetEnabled(true);
  CreateWrapper();
  auto result = std::make_shared<RegisterResult>();
  wrapper_->Register(extension->id(), {"123"}, CaptureRegister(result));
  auto old_state = generations_.front();
  old_state->driver->CompleteRegister(extension->id(), "previous-token");
  task_environment_.RunUntilIdle();
  ASSERT_EQ(gcm::GCMClient::SUCCESS, result->result);
  ASSERT_TRUE(HasKnownApplication(extension->id()));
  Unload(*extension, UnloadedExtensionReason::UPDATE);
  EXPECT_EQ(1, old_state->destructions);

  auto updated = ExtensionBuilder("new manifest without gcm")
                     .SetManifestVersion(3)
                     .SetVersion("2.0")
                     .SetID(extension->id())
                     .Build();
  EXPECT_TRUE(registry()->AddEnabled(updated));
  registry()->TriggerOnLoaded(updated.get());
  ASSERT_EQ(2u, generations_.size());
  auto cleanup = generations_.back();
  EXPECT_EQ(std::vector<std::string>{extension->id()},
            cleanup->unregistrations);
  EXPECT_TRUE(HasPendingUninstall(extension->id()));
  ASSERT_TRUE(cleanup->driver->GetAppHandler(extension->id()));
  cleanup->driver->GetAppHandler(extension->id())
      ->OnMessage(extension->id(), gcm::IncomingMessage());
  EXPECT_TRUE(messages_.empty());
  cleanup->driver->CompleteUnregister(extension->id());
  task_environment_.RunUntilIdle();
  EXPECT_FALSE(HasKnownApplication(extension->id()));
  EXPECT_FALSE(HasPendingUninstall(extension->id()));
  EXPECT_EQ(1, cleanup->destructions);
}

TEST_F(AndroidExtensionGCMDriverTest,
       StartupMissingKnownApplicationWaitsForExtensionSystemReady) {
  auto absent = ExtensionBuilder("not installed").SetManifestVersion(3).Build();
  SeedKnownApplication(absent->id());
  SetEnabled(true);
  CreateWrapper();
  task_environment_.RunUntilIdle();
  EXPECT_TRUE(generations_.empty());
  EXPECT_TRUE(HasKnownApplication(absent->id()));
  EXPECT_FALSE(HasPendingUninstall(absent->id()));

  SetExtensionSystemReady();
  ASSERT_EQ(1u, generations_.size());
  auto cleanup = generations_.front();
  EXPECT_EQ(std::vector<std::string>{absent->id()}, cleanup->unregistrations);
  EXPECT_TRUE(HasPendingUninstall(absent->id()));
  cleanup->driver->CompleteUnregister(absent->id());
  task_environment_.RunUntilIdle();
  EXPECT_FALSE(HasKnownApplication(absent->id()));
  EXPECT_FALSE(HasPendingUninstall(absent->id()));
}

TEST_F(AndroidExtensionGCMDriverTest,
       StartupDisabledPermissionHolderRetainsKnownRegistrationWithoutDriver) {
  auto extension = AddExtension("disabled permission holder", false);
  EXPECT_TRUE(registry()->RemoveEnabled(extension->id()));
  EXPECT_TRUE(registry()->AddDisabled(extension));
  SeedKnownApplication(extension->id());
  SetEnabled(true);
  CreateWrapper();
  EXPECT_TRUE(generations_.empty());
  SetExtensionSystemReady();
  EXPECT_TRUE(generations_.empty());
  EXPECT_TRUE(HasKnownApplication(extension->id()));
  EXPECT_FALSE(HasPendingUninstall(extension->id()));
}

TEST_F(AndroidExtensionGCMDriverTest,
       RevokingOptionalGcmWhileDisabledCleansUpKnownRegistration) {
  auto extension = AddOptionalGcmExtension();
  SetEnabled(true);
  CreateWrapper();
  SetOptionalGcmGranted(*extension, true);
  auto result = std::make_shared<RegisterResult>();
  wrapper_->Register(extension->id(), {"123"}, CaptureRegister(result));
  auto old_state = generations_.front();
  old_state->driver->CompleteRegister(extension->id(), "disabled-token");
  task_environment_.RunUntilIdle();
  ASSERT_TRUE(HasKnownApplication(extension->id()));
  EXPECT_TRUE(registry()->RemoveEnabled(extension->id()));
  EXPECT_TRUE(registry()->AddDisabled(extension));
  registry()->TriggerOnUnloaded(extension.get(),
                                UnloadedExtensionReason::DISABLE);
  EXPECT_EQ(1, old_state->destructions);

  SetOptionalGcmGranted(*extension, false);
  EXPECT_TRUE(registry()->disabled_extensions().Contains(extension->id()));
  ASSERT_FALSE(extension->permissions_data()->HasAPIPermission("gcm"));
  ASSERT_EQ(2u, generations_.size());
  auto cleanup = generations_.back();
  EXPECT_EQ(std::vector<std::string>{extension->id()},
            cleanup->unregistrations);
  EXPECT_TRUE(HasPendingUninstall(extension->id()));
  ASSERT_TRUE(cleanup->driver->GetAppHandler(extension->id()));
  cleanup->driver->GetAppHandler(extension->id())
      ->OnMessagesDeleted(extension->id());
  EXPECT_TRUE(deleted_messages_.empty());
  cleanup->driver->CompleteUnregister(extension->id());
  task_environment_.RunUntilIdle();
  EXPECT_FALSE(HasKnownApplication(extension->id()));
  EXPECT_FALSE(HasPendingUninstall(extension->id()));
  EXPECT_EQ(1, result->calls);
  EXPECT_EQ(1, cleanup->destructions);
}

TEST_F(AndroidExtensionGCMDriverTest, NoGcmPermissionDoesNotCreateChannel) {
  auto extension = AddExtension("no permission", false, false);
  SetEnabled(true);
  CreateWrapper();
  auto result = std::make_shared<RegisterResult>();
  wrapper_->Register(extension->id(), {"123"}, CaptureRegister(result));
  EXPECT_EQ(1, result->calls);
  EXPECT_EQ(gcm::GCMClient::GCM_DISABLED, result->result);
  EXPECT_TRUE(generations_.empty());
  wrapper_->OnMessage(extension->id(), gcm::IncomingMessage());
  EXPECT_TRUE(messages_.empty());
}

}  // namespace
}  // namespace extensions
