// Copyright 2026 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/extensions/extension_disabled_ui.h"

#include <memory>
#include <utility>

#include "base/android/jni_android.h"
#include "base/android/scoped_java_ref.h"
#include "base/base_paths.h"
#include "base/check.h"
#include "base/files/scoped_temp_dir.h"
#include "base/functional/bind.h"
#include "base/memory/raw_ptr.h"
#include "base/path_service.h"
#include "base/test/scoped_path_override.h"
#include "chrome/browser/browser_process.h"
#include "chrome/browser/extensions/extension_service_test_base.h"
#include "chrome/browser/global_features.h"
#include "chrome/browser/global_features_test_support.h"
#include "chrome/browser/ui/browser_window/public/global_browser_collection.h"
#include "chrome/browser/ui/browser_window/test/fake_global_browser_collection.h"
#include "chrome/browser/ui/browser_window/test/mock_browser_window_interface.h"
#include "chrome/common/chrome_paths.h"
#include "chrome/common/webui_url_constants.h"
#include "chrome/grit/generated_resources.h"
#include "chrome/test/base/testing_browser_process.h"
#include "components/messages/android/message_dispatcher_bridge.h"
#include "components/messages/android/message_wrapper.h"
#include "components/messages/android/mock_message_dispatcher_bridge.h"
#include "components/sync/model/string_ordinal.h"
#include "content/public/browser/page_navigator.h"
#include "content/public/test/browser_task_environment.h"
#include "extensions/browser/disable_reason.h"
#include "extensions/browser/extension_prefs.h"
#include "extensions/browser/extension_registry.h"
#include "extensions/common/extension_builder.h"
#include "extensions/common/permissions/permission_set.h"
#include "testing/gmock/include/gmock/gmock.h"
#include "testing/gtest/include/gtest/gtest.h"
#include "ui/android/window_android.h"
#include "ui/base/l10n/l10n_util.h"
#include "ui/base/test/mock_base_window.h"
#include "ui/base/window_open_disposition.h"
#include "url/gurl.h"

namespace extensions {
namespace {

using testing::_;
using testing::NiceMock;
using testing::Return;

class DisabledMessageGlobalFeatures : public GlobalFeatures {
 protected:
  std::unique_ptr<GlobalBrowserCollection> CreateGlobalBrowserCollection()
      override {
    return std::make_unique<FakeGlobalBrowserCollection>();
  }
};

std::unique_ptr<GlobalFeatures> CreateDisabledMessageGlobalFeatures() {
  return std::make_unique<DisabledMessageGlobalFeatures>();
}

// Complete the Android window interface for this fixture only.
class DisabledMessageBaseWindow : public ui::MockBaseWindow {
 public:
  MOCK_METHOD(bool,
              CanResize,
              (ui::WindowResizePrecheckResult&),
              (const, override));
};

// Use existing public virtual provider methods without installing a Java UI.
class DisabledMessageDispatcher : public messages::MockMessageDispatcherBridge {
 public:
  bool available = true;
  base::android::ScopedJavaGlobalRef<jobject> replacement;

  base::android::ScopedJavaLocalRef<jobject> GetMessageDispatcher(
      const base::android::JavaRef<jobject>& java_window) override {
    if (!available) {
      return {};
    }
    return replacement.is_null()
               ? base::android::ScopedJavaLocalRef<jobject>(java_window)
               : base::android::ScopedJavaLocalRef<jobject>(replacement);
  }

 protected:
  void SetDispatcherNotificationsEnabled(bool) override {}
};

// The isolated APK has no shared chrome/test/data tree. Give this fixture a
// fresh app-cache directory before ExtensionServiceTestBase looks it up, and
// keep the override alive until that base has finished destruction.
class DisabledMessageTestData {
 protected:
  DisabledMessageTestData()
      : test_data_override_(chrome::DIR_TEST_DATA, CreateTestDataDir()) {}

 private:
  base::FilePath CreateTestDataDir() {
    CHECK(test_data_dir_.CreateUniqueTempDirUnderPath(
        base::PathService::CheckedGet(base::DIR_CACHE)));
    return test_data_dir_.GetPath();
  }

  base::ScopedTempDir test_data_dir_;
  base::ScopedPathOverride test_data_override_;
};

class ExtensionDisabledUiAndroidTest : private DisabledMessageTestData,
                                       public ExtensionServiceTestBase {
 public:
  ExtensionDisabledUiAndroidTest()
      : DisabledMessageTestData(),
        ExtensionServiceTestBase(
            std::make_unique<content::BrowserTaskEnvironment>()),
        features_override_(
            base::BindRepeating(&CreateDisabledMessageGlobalFeatures)) {}

  void SetUp() override {
    // This is the existing test-process feature override route. Do not replace
    // a collection containing another fixture's windows or create a process.
    ASSERT_TRUE(GlobalBrowserCollection::GetInstance()->IsEmpty());
    TestingBrowserProcess::GetGlobal()->SetUpGlobalFeaturesForTesting(
        /*profile_manager=*/false);
    owns_features_ = true;
    ExtensionServiceTestBase::SetUp();
    InitializeEmptyExtensionService();
    window_ = ui::WindowAndroid::CreateForTesting();
    ASSERT_TRUE(window_);
    ASSERT_FALSE(window_->get()->GetJavaObject().is_null());
    ON_CALL(browser_, GetProfile()).WillByDefault([this] { return profile(); });
    ON_CALL(browser_, GetType())
        .WillByDefault(Return(BrowserWindowInterface::TYPE_NORMAL));
    ON_CALL(browser_, IsDeleteScheduled()).WillByDefault(Return(false));
    ON_CALL(browser_, GetWindow()).WillByDefault(Return(&base_window_));
    ON_CALL(base_window_, IsActive())
        .WillByDefault([this] { return active_; });
    ON_CALL(base_window_, GetNativeWindow())
        .WillByDefault([this] { return window_->get(); });
    previous_dispatcher_ = messages::MessageDispatcherBridge::Get();
    messages::MessageDispatcherBridge::SetInstanceForTesting(&dispatcher_);
    owns_dispatcher_ = true;
    dispatcher_.SetMessagesEnabledForEmbedder(true);
    ON_CALL(dispatcher_, EnqueueWindowScopedMessage(_, _, _))
        .WillByDefault([this](messages::MessageWrapper* message,
                              ui::WindowAndroid* window,
                              messages::MessagePriority priority) {
          ++enqueue_attempts_;
          EXPECT_EQ(window_->get(), window);
          EXPECT_EQ(messages::MessagePriority::kNormal, priority);
          if (!enqueue_succeeds_) {
            return false;
          }
          EXPECT_EQ(nullptr, message_);
          message_ = message;
          message->SetMessageEnqueued(window->GetJavaObject());
          return true;
        });
    ON_CALL(dispatcher_, DismissMessage(_, _))
        .WillByDefault([this](messages::MessageWrapper* message,
                              messages::DismissReason reason) {
          EXPECT_EQ(messages::DismissReason::DISMISSED_BY_FEATURE, reason);
          ++feature_dismissals_;
          if (message_ == message) {
            message_ = nullptr;
          }
          message->HandleDismissCallback(base::android::AttachCurrentThread(),
                                         static_cast<int>(reason));
        });
    extension_ = ExtensionBuilder("Permission update")
                     .SetManifestVersion(3)
                     .SetVersion("2.0")
                     .AddAPIPermission("tabs")
                     .Build();
    ASSERT_TRUE(extension_);
    ExtensionPrefs* prefs = ExtensionPrefs::Get(profile());
    prefs->OnExtensionInstalled(
        extension_.get(), {disable_reason::DISABLE_PERMISSIONS_INCREASE},
        syncer::StringOrdinal(), "");
    // The earlier version had no API grants. This update is still awaiting
    // ordinary user review; the alert must never grant its new tabs permission.
    prefs->AddGrantedPermissions(extension_->id(), PermissionSet());
    granted_before_ = prefs->GetGrantedPermissions(extension_->id());
    ASSERT_TRUE(granted_before_);
    ASSERT_TRUE(registry()->AddDisabled(extension_));
    ExpectStillDisabled();
  }

  void TearDown() override {
    if (registered_) {
      collection()->SimulateBrowserClosed(&browser_);
      registered_ = false;
    }
    if (owns_features_) {
      // Destroy the profile-owned observer/message before restoring the bridge.
      ExtensionServiceTestBase::TearDown();
      task_environment()->RunUntilIdle();
      EXPECT_EQ(nullptr, message_);
      if (owns_dispatcher_) {
        messages::MessageDispatcherBridge::SetInstanceForTesting(
            previous_dispatcher_);
      }
      window_.reset();
      TestingBrowserProcess::GetGlobal()->TearDownGlobalFeaturesForTesting();
    }
  }

 protected:
  FakeGlobalBrowserCollection* collection() {
    return static_cast<FakeGlobalBrowserCollection*>(
        g_browser_process->GetFeatures()->global_browser_collection());
  }

  void ActivateHost() {
    active_ = true;
    if (!registered_) {
      collection()->SimulateBrowserCreated(&browser_);
      registered_ = true;
    }
    collection()->SimulateBrowserActivated(&browser_);
    task_environment()->RunUntilIdle();
  }

  void AddNotice() {
    AddExtensionDisabledError(profile(), extension_.get(), false);
    task_environment()->RunUntilIdle();
  }

  void DismissCaptured(messages::DismissReason reason) {
    ASSERT_NE(nullptr, message_);
    auto* message = std::exchange(message_, nullptr).get();
    message->HandleDismissCallback(base::android::AttachCurrentThread(),
                                   static_cast<int>(reason));
    task_environment()->RunUntilIdle();
  }

  void ExpectStillDisabled() {
    ASSERT_TRUE(profile());
    EXPECT_EQ(extension_.get(),
              registry()->disabled_extensions().GetByID(extension_->id()));
    EXPECT_FALSE(registry()->enabled_extensions().Contains(extension_->id()));
    ExpectPermissionsUnchanged();
  }

  void ExpectPermissionsUnchanged() {
    ASSERT_TRUE(profile());
    EXPECT_TRUE(ExtensionPrefs::Get(profile())
                    ->GetDisableReasons(extension_->id())
                    .contains(disable_reason::DISABLE_PERMISSIONS_INCREASE));
    auto granted =
        ExtensionPrefs::Get(profile())->GetGrantedPermissions(extension_->id());
    ASSERT_TRUE(granted);
    ASSERT_TRUE(granted_before_);
    EXPECT_TRUE(*granted == *granted_before_);
  }

  NiceMock<MockBrowserWindowInterface> browser_;
  NiceMock<DisabledMessageBaseWindow> base_window_;
  NiceMock<DisabledMessageDispatcher> dispatcher_;
  std::unique_ptr<ui::WindowAndroid::ScopedWindowAndroidForTesting> window_;
  scoped_refptr<const Extension> extension_;
  std::unique_ptr<PermissionSet> granted_before_;
  raw_ptr<messages::MessageWrapper> message_ = nullptr;
  raw_ptr<messages::MessageDispatcherBridge> previous_dispatcher_ = nullptr;
  bool active_ = true;
  bool registered_ = false;
  bool enqueue_succeeds_ = true;
  bool owns_features_ = false;
  bool owns_dispatcher_ = false;
  int enqueue_attempts_ = 0;
  int feature_dismissals_ = 0;

 private:
  test::ScopedGlobalFeaturesOverride features_override_;
};

TEST_F(ExtensionDisabledUiAndroidTest, ReviewRequestsDetailsWithoutEnabling) {
  ActivateHost();
  AddNotice();
  ASSERT_NE(nullptr, message_);
  EXPECT_EQ(l10n_util::GetStringUTF16(IDS_EXTENSIONS_ITEM_DETAILS),
            message_->GetPrimaryButtonText());
  EXPECT_CALL(browser_, OpenURL(_, _))
      .WillOnce([this](const content::OpenURLParams& params,
                       base::OnceCallback<void(content::NavigationHandle&)>) {
        EXPECT_EQ(GURL(std::string(chrome::kChromeUIExtensionsURL) + "?id=" +
                       extension_->id()),
                  params.url);
        EXPECT_EQ(WindowOpenDisposition::NEW_FOREGROUND_TAB,
                  params.disposition);
        // Android can dispatch a valid asynchronous request and return null.
        return nullptr;
      });
  message_->HandleActionClick(base::android::AttachCurrentThread());
  DismissCaptured(messages::DismissReason::PRIMARY_ACTION);
  ActivateHost();
  EXPECT_EQ(1, enqueue_attempts_);
  ExpectStillDisabled();
}

TEST_F(ExtensionDisabledUiAndroidTest, GestureCancelDoesNotChangeDisabledState) {
  ActivateHost();
  AddNotice();
  EXPECT_CALL(browser_, OpenURL(_, _)).Times(0);
  DismissCaptured(messages::DismissReason::GESTURE);
  ActivateHost();
  EXPECT_EQ(nullptr, message_);
  EXPECT_EQ(1, enqueue_attempts_);
  ExpectStillDisabled();
}

TEST_F(ExtensionDisabledUiAndroidTest, NoWindowOrProviderDefersNotice) {
  dispatcher_.available = false;
  AddNotice();
  EXPECT_EQ(0, enqueue_attempts_);
  ActivateHost();
  EXPECT_EQ(0, enqueue_attempts_);
  dispatcher_.available = true;
  dispatcher_.NotifyDispatcherChanged();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, enqueue_attempts_);
  ASSERT_NE(nullptr, message_);
  ExpectStillDisabled();
}

TEST_F(ExtensionDisabledUiAndroidTest, FailedEnqueueRetriesOnProviderChange) {
  ActivateHost();
  enqueue_succeeds_ = false;
  AddNotice();
  EXPECT_EQ(1, enqueue_attempts_);
  EXPECT_EQ(nullptr, message_);
  EXPECT_EQ(0, feature_dismissals_);
  enqueue_succeeds_ = true;
  dispatcher_.NotifyDispatcherChanged();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(2, enqueue_attempts_);
  ASSERT_NE(nullptr, message_);
  ExpectStillDisabled();
}

TEST_F(ExtensionDisabledUiAndroidTest, StaleHostActionRetainsNoticeForRetry) {
  ActivateHost();
  AddNotice();
  ASSERT_NE(nullptr, message_);
  EXPECT_CALL(browser_, OpenURL(_, _)).Times(0);
  // No task is pumped between losing the active host and the queued Java click.
  active_ = false;
  message_->HandleActionClick(base::android::AttachCurrentThread());
  DismissCaptured(messages::DismissReason::PRIMARY_ACTION);
  EXPECT_EQ(nullptr, message_);
  EXPECT_EQ(1, enqueue_attempts_);
  ActivateHost();
  ASSERT_NE(nullptr, message_);
  EXPECT_EQ(2, enqueue_attempts_);
  ExpectStillDisabled();
}

TEST_F(ExtensionDisabledUiAndroidTest,
       ChangedProviderActionRetainsNoticeForRetry) {
  ActivateHost();
  AddNotice();
  ASSERT_NE(nullptr, message_);
  EXPECT_CALL(browser_, OpenURL(_, _)).Times(0);
  auto replacement_window = ui::WindowAndroid::CreateForTesting();
  dispatcher_.replacement.Reset(replacement_window->get()->GetJavaObject());
  message_->HandleActionClick(base::android::AttachCurrentThread());
  DismissCaptured(messages::DismissReason::PRIMARY_ACTION);
  // The validated next update can use the new provider but not lose the notice.
  ASSERT_NE(nullptr, message_);
  EXPECT_EQ(2, enqueue_attempts_);
  ExpectStillDisabled();
}

TEST_F(ExtensionDisabledUiAndroidTest, WindowCloseDefersUntilBrowserReturns) {
  ActivateHost();
  AddNotice();
  ASSERT_NE(nullptr, message_);
  collection()->SimulateBrowserClosed(&browser_);
  registered_ = false;
  task_environment()->RunUntilIdle();
  EXPECT_EQ(nullptr, message_);
  EXPECT_EQ(1, feature_dismissals_);
  ActivateHost();
  ASSERT_NE(nullptr, message_);
  EXPECT_EQ(2, enqueue_attempts_);
  ExpectStillDisabled();
}

TEST_F(ExtensionDisabledUiAndroidTest, RegistryShutdownDismissesAndUnsubscribes) {
  ActivateHost();
  AddNotice();
  ASSERT_NE(nullptr, message_);
  registry()->Shutdown();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(nullptr, message_);
  EXPECT_EQ(1, feature_dismissals_);
  dispatcher_.NotifyDispatcherChanged();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, enqueue_attempts_);
  EXPECT_EQ(0u, registry()->disabled_extensions().size());
  EXPECT_EQ(0u, registry()->enabled_extensions().size());
  ExpectPermissionsUnchanged();
}

}  // namespace
}  // namespace extensions
