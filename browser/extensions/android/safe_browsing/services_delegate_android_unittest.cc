// Copyright 2026 The Reaper Authors.
// SPDX-License-Identifier: MPL-2.0

#include "chrome/browser/safe_browsing/android/services_delegate_android.h"

#include "base/memory/ref_counted.h"
#include "base/test/scoped_path_override.h"
#include "chrome/browser/safe_browsing/safe_browsing_service.h"
#include "chrome/common/chrome_paths.h"
#include "components/safe_browsing/core/browser/db/sb_protocol_config.h"
#include "components/safe_browsing/core/browser/db/test_database_manager.h"
#include "components/safe_browsing/core/browser/db/v4_protocol_manager_util.h"
#include "content/public/browser/browser_task_traits.h"
#include "content/public/test/browser_task_environment.h"
#include "services/network/public/cpp/weak_wrapper_shared_url_loader_factory.h"
#include "services/network/test/test_url_loader_factory.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace safe_browsing {
namespace {

// Skip unrelated download/incident services. The extension database remains
// the production shared local backend, with all network traffic captured.
class NoAuxiliaryServices : public ServicesDelegate::ServicesCreator {
public:
  bool CanCreateDatabaseManager() override { return false; }
  bool CanCreateDownloadProtectionService() override { return true; }
  bool CanCreateIncidentReportingService() override { return false; }
  SafeBrowsingDatabaseManager *CreateDatabaseManager() override {
    return nullptr;
  }
  DownloadProtectionService *CreateDownloadProtectionService() override {
    return nullptr;
  }
  IncidentReportingService *CreateIncidentReportingService() override {
    return nullptr;
  }
};

class AndroidExtensionBlocklistDelegateTest : public testing::Test {
public:
  void SetUp() override {
    owner_ = base::MakeRefCounted<SafeBrowsingService>();
    delegate_ = ServicesDelegate::CreateForTest(owner_.get(), &creator_);
    remote_ = base::MakeRefCounted<TestSafeBrowsingDatabaseManager>(
        content::GetUIThreadTaskRunner({}));
    delegate_->SetDatabaseManagerForTest(remote_.get());
    delegate_->Initialize();
    loader_ = base::MakeRefCounted<network::WeakWrapperSharedURLLoaderFactory>(
        &network_);
  }

  void TearDown() override {
    delegate_->StopOnUIThread(/*shutdown=*/true);
    delegate_->ShutdownServices();
    delegate_.reset();
    owner_.reset();
    task_environment_.RunUntilIdle();
  }

protected:
  content::BrowserTaskEnvironment task_environment_;
  base::ScopedPathOverride user_data_dir_{chrome::DIR_USER_DATA};
  NoAuxiliaryServices creator_;
  network::TestURLLoaderFactory network_;
  scoped_refptr<network::SharedURLLoaderFactory> loader_;
  scoped_refptr<SafeBrowsingService> owner_;
  scoped_refptr<TestSafeBrowsingDatabaseManager> remote_;
  std::unique_ptr<ServicesDelegate> delegate_;
};

TEST_F(AndroidExtensionBlocklistDelegateTest,
       SeparateManagersFollowEnableState) {
  auto extensions = delegate_->extension_database_manager();
  ASSERT_TRUE(extensions);
  EXPECT_EQ(remote_, delegate_->database_manager());
  EXPECT_NE(remote_.get(), extensions.get());
  EXPECT_FALSE(extensions->IsDatabaseReady());

  delegate_->StartOnUIThread(loader_, owner_->GetSBProtocolConfig());
  task_environment_.RunUntilIdle();
  EXPECT_TRUE(remote_->IsDatabaseReady());
  ASSERT_TRUE(extensions->IsDatabaseReady());
  auto states = extensions->GetStoreStateMap();
  EXPECT_EQ(1u, states->size());
  EXPECT_TRUE(states->contains(GetChromeExtMalwareId()));

  delegate_->StopOnUIThread(/*shutdown=*/false);
  EXPECT_FALSE(remote_->IsDatabaseReady());
  EXPECT_FALSE(extensions->IsDatabaseReady());
  delegate_->StartOnUIThread(loader_, owner_->GetSBProtocolConfig());
  task_environment_.RunUntilIdle();
  EXPECT_TRUE(remote_->IsDatabaseReady());
  EXPECT_TRUE(extensions->IsDatabaseReady());
}

TEST_F(AndroidExtensionBlocklistDelegateTest,
       StopCancelsPendingInitialization) {
  auto extensions = delegate_->extension_database_manager();
  delegate_->StartOnUIThread(loader_, owner_->GetSBProtocolConfig());
  // Stop before the disk thread can publish the database to the UI thread.
  delegate_->StopOnUIThread(/*shutdown=*/true);
  task_environment_.RunUntilIdle();
  EXPECT_FALSE(remote_->IsDatabaseReady());
  EXPECT_FALSE(extensions->IsDatabaseReady());
}

} // namespace
} // namespace safe_browsing
