// Copyright 2026 The Reaper Authors.
// SPDX-License-Identifier: MPL-2.0

#include "chrome/browser/extensions/api/reading_list/reading_list_api.h"

#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "base/functional/bind.h"
#include "base/memory/scoped_refptr.h"
#include "base/memory/weak_ptr.h"
#include "base/test/values_test_util.h"
#include "base/time/default_clock.h"
#include "base/time/time.h"
#include "build/build_config.h"
#include "chrome/browser/extensions/extension_service_test_base.h"
#include "chrome/browser/profiles/profile.h"
#include "chrome/browser/reading_list/reading_list_model_factory.h"
#include "components/keyed_service/core/keyed_service.h"
#include "components/reading_list/core/dual_reading_list_model.h"
#include "components/reading_list/core/fake_reading_list_model_storage.h"
#include "components/reading_list/core/reading_list_entry.h"
#include "components/reading_list/core/reading_list_model_impl.h"
#include "components/sync/base/storage_type.h"
#include "components/sync/model/wipe_model_upon_sync_disabled_behavior.h"
#include "extensions/browser/extension_function_dispatcher.h"
#include "extensions/common/extension_builder.h"
#include "testing/gtest/include/gtest/gtest.h"
#include "url/gurl.h"

static_assert(BUILDFLAG(IS_ANDROID));

namespace extensions {
namespace {

enum class Operation { kAdd, kRemove, kUpdate, kQuery };
constexpr char kUrl[] = "https://example.test/reading-list";
constexpr char kShutdownError[] =
    "The browser context shut down before the reading list loaded.";

class ReadingListAndroidLifecycleTest
    : public ExtensionServiceTestBase,
      public testing::WithParamInterface<Operation> {
 protected:
  void SetUp() override {
    ExtensionServiceTestBase::SetUp();
    InitializeEmptyExtensionService();
    ReadingListModelFactory::GetInstance()->SetTestingFactoryAndUse(
        profile(),
        base::BindRepeating(&ReadingListAndroidLifecycleTest::CreateModel,
                            base::Unretained(this)));
    ASSERT_TRUE(local_storage_);
    ASSERT_TRUE(account_storage_);
    ASSERT_FALSE(Model()->loaded());
  }

  ReadingListModel* Model() {
    return ReadingListModelFactory::GetForBrowserContext(profile());
  }

  scoped_refptr<ExtensionFunction> Start(content::BrowserContext* context) {
    scoped_refptr<ExtensionFunction> function;
    const char* args = nullptr;
    switch (GetParam()) {
      case Operation::kAdd:
        function = base::MakeRefCounted<ReadingListAddEntryFunction>();
        args =
            R"([{"url":"https://example.test/reading-list","title":"new","hasBeenRead":false}])";
        break;
      case Operation::kRemove:
        function = base::MakeRefCounted<ReadingListRemoveEntryFunction>();
        args = R"([{"url":"https://example.test/reading-list"}])";
        break;
      case Operation::kUpdate:
        function = base::MakeRefCounted<ReadingListUpdateEntryFunction>();
        args =
            R"([{"url":"https://example.test/reading-list","title":"updated"}])";
        break;
      case Operation::kQuery:
        function = base::MakeRefCounted<ReadingListQueryFunction>();
        args = R"([{}])";
        break;
    }
    function->set_extension(ExtensionBuilder("Reading List lifecycle")
                                .SetManifestVersion(3)
                                .AddAPIPermission("readingList")
                                .Build());
    function->set_has_callback(true);
    function->set_response_callback(base::BindOnce(
        &ReadingListAndroidLifecycleTest::OnResponse, base::Unretained(this)));
    function->SetArgs(base::test::ParseJsonList(args));
    dispatcher_ = std::make_unique<ExtensionFunctionDispatcher>(context);
    function->SetDispatcher(dispatcher_->AsWeakPtr());
    function->RunWithValidation().Execute();
    return function;
  }

  void LoadLocalStore() {
    std::vector<scoped_refptr<ReadingListEntry>> entries;
    if (GetParam() != Operation::kAdd) {
      entries.push_back(base::MakeRefCounted<ReadingListEntry>(
          GURL(kUrl), "original", base::Time::Now()));
    }
    ASSERT_TRUE(local_storage_->TriggerLoadCompletion(std::move(entries)));
  }

  void FinishLoading() {
    LoadLocalStore();
    ASSERT_TRUE(account_storage_->TriggerLoadCompletion());
    ASSERT_TRUE(Model()->loaded());
  }

  void ExpectSuccess() {
    EXPECT_EQ(response_count_, 1);
    EXPECT_EQ(response_type_, ExtensionFunction::ResponseType::kSucceeded);
    EXPECT_TRUE(error_.empty());
    auto entry = Model()->GetEntryByURL(GURL(kUrl));
    if (GetParam() == Operation::kRemove) {
      EXPECT_FALSE(entry);
    } else {
      ASSERT_TRUE(entry);
      EXPECT_EQ(entry->Title(), GetParam() == Operation::kAdd ? "new"
                                : GetParam() == Operation::kUpdate
                                    ? "updated"
                                    : "original");
    }
  }

  base::WeakPtr<FakeReadingListModelStorage> local_storage_;
  base::WeakPtr<FakeReadingListModelStorage> account_storage_;
  std::unique_ptr<ExtensionFunctionDispatcher> dispatcher_;
  int response_count_ = 0;
  ExtensionFunction::ResponseType response_type_ =
      ExtensionFunction::ResponseType::kBadMessage;
  std::string error_;

 private:
  std::unique_ptr<KeyedService> CreateModel(content::BrowserContext*) {
    auto local = std::make_unique<FakeReadingListModelStorage>();
    auto account = std::make_unique<FakeReadingListModelStorage>();
    local_storage_ = local->AsWeakPtr();
    account_storage_ = account->AsWeakPtr();
    return std::make_unique<reading_list::DualReadingListModel>(
        std::make_unique<ReadingListModelImpl>(
            std::move(local), syncer::StorageType::kUnspecified,
            syncer::WipeModelUponSyncDisabledBehavior::kNever,
            base::DefaultClock::GetInstance()),
        std::make_unique<ReadingListModelImpl>(
            std::move(account), syncer::StorageType::kAccount,
            syncer::WipeModelUponSyncDisabledBehavior::kAlways,
            base::DefaultClock::GetInstance()));
  }

  void OnResponse(ExtensionFunction::ResponseType type,
                  base::ListValue,
                  const std::string& error,
                  mojom::ExtraResponseDataPtr) {
    ++response_count_;
    response_type_ = type;
    error_ = error;
  }
};

TEST_P(ReadingListAndroidLifecycleTest,
       ProfileShutdownBalancesPendingReference) {
  auto function = Start(profile());
  ASSERT_EQ(response_count_, 0);
  EXPECT_FALSE(function->HasOneRef());

  DeleteProfile();

  EXPECT_EQ(response_count_, 1);
  EXPECT_EQ(response_type_, ExtensionFunction::ResponseType::kFailed);
  EXPECT_EQ(error_, kShutdownError);
  EXPECT_TRUE(function->HasOneRef());
  EXPECT_EQ(function->browser_context(), nullptr);
  EXPECT_FALSE(local_storage_);
  EXPECT_FALSE(account_storage_);
  // The function must be safe to destroy after the actual dual model is gone.
  function.reset();
}

TEST_P(ReadingListAndroidLifecycleTest, OtrShutdownDetachesFromSharedModel) {
  auto* otr = profile()->GetOffTheRecordProfile(
      Profile::OTRProfileID::CreateUniqueForTesting(), true);
  ASSERT_EQ(Model(), ReadingListModelFactory::GetForBrowserContext(otr));
  auto function = Start(otr);
  ASSERT_EQ(response_count_, 0);
  LoadLocalStore();
  ASSERT_FALSE(Model()->loaded());
  ASSERT_EQ(response_count_, 0);

  profile()->DestroyOffTheRecordProfile(otr);

  EXPECT_EQ(response_count_, 1);
  EXPECT_EQ(response_type_, ExtensionFunction::ResponseType::kFailed);
  EXPECT_EQ(error_, kShutdownError);
  EXPECT_TRUE(function->HasOneRef());
  EXPECT_EQ(function->browser_context(), nullptr);

  // The original profile and shared model still live. Loading the second store
  // must neither respond again nor perform the cancelled operation.
  ASSERT_TRUE(account_storage_->TriggerLoadCompletion());
  ASSERT_TRUE(Model()->loaded());
  EXPECT_EQ(response_count_, 1);
  EXPECT_TRUE(function->HasOneRef());
  auto entry = Model()->GetEntryByURL(GURL(kUrl));
  if (GetParam() == Operation::kAdd) {
    EXPECT_FALSE(entry);
  } else {
    ASSERT_TRUE(entry);
    EXPECT_EQ(entry->Title(), "original");
  }
}

TEST_P(ReadingListAndroidLifecycleTest, LoadedCallbackBalancesBeforeShutdown) {
  auto function = Start(profile());
  ASSERT_EQ(response_count_, 0);
  EXPECT_FALSE(function->HasOneRef());
  FinishLoading();
  ExpectSuccess();
  EXPECT_TRUE(function->HasOneRef());

  DeleteProfile();

  EXPECT_EQ(response_count_, 1);
  EXPECT_EQ(response_type_, ExtensionFunction::ResponseType::kSucceeded);
  EXPECT_TRUE(function->HasOneRef());
}

TEST_P(ReadingListAndroidLifecycleTest, AlreadyLoadedDoesNotReleaseAnotherRef) {
  FinishLoading();
  auto function = Start(profile());
  ExpectSuccess();
  EXPECT_TRUE(function->HasOneRef());

  DeleteProfile();

  EXPECT_EQ(response_count_, 1);
  EXPECT_EQ(response_type_, ExtensionFunction::ResponseType::kSucceeded);
  EXPECT_TRUE(function->HasOneRef());
}

INSTANTIATE_TEST_SUITE_P(AllOperations,
                         ReadingListAndroidLifecycleTest,
                         testing::Values(Operation::kAdd,
                                         Operation::kRemove,
                                         Operation::kUpdate,
                                         Operation::kQuery));

}  // namespace
}  // namespace extensions
