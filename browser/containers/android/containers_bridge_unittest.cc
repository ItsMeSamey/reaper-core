// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "brave/browser/containers/android/containers_bridge.h"

#include <algorithm>
#include <memory>
#include <utility>

#include "base/functional/bind.h"
#include "base/memory/raw_ptr.h"
#include "base/test/scoped_feature_list.h"
#include "base/values.h"
#include "brave/browser/containers/containers_service_factory.h"
#include "brave/components/containers/content/browser/containers_web_contents_user_data.h"
#include "brave/components/containers/content/browser/storage_partition_utils.h"
#include "brave/components/containers/core/browser/containers_service.h"
#include "brave/components/containers/core/browser/containers_settings_handler.h"
#include "brave/components/containers/core/browser/containers_test_utils.h"
#include "brave/components/containers/core/browser/pref_names.h"
#include "brave/components/containers/core/browser/prefs.h"
#include "brave/components/containers/core/browser/temporary_container.h"
#include "brave/components/containers/core/common/features.h"
#include "brave/components/containers/core/mojom/containers.mojom.h"
#include "chrome/test/base/testing_profile.h"
#include "components/prefs/pref_service.h"
#include "content/public/browser/site_instance.h"
#include "content/public/browser/web_contents.h"
#include "content/public/test/browser_task_environment.h"
#include "testing/gmock/include/gmock/gmock.h"
#include "testing/gtest/include/gtest/gtest.h"
#include "third_party/skia/include/core/SkColor.h"
#include "url/gurl.h"

namespace containers::android {
namespace {

constexpr char kRestoredId[] = "4a4d13b6-c971-4c61-ae11-e7a864f58165";
constexpr char kRestoredTemporaryId[] =
    "t-4a4d13b6-c971-4c61-ae11-e7a864f58165";

class AndroidContainersBridgeTest : public testing::Test {
 protected:
  void SetUp() override {
    features_.InitAndEnableFeature(features::kContainers);
    ContainersServiceFactory::GetInstance();
    TestingProfile::Builder builder;
    builder.AddTestingFactory(
        ContainersServiceFactory::GetInstance(),
        base::BindRepeating(&AndroidContainersBridgeTest::CreateService,
                            base::Unretained(this)));
    profile_ = builder.Build();
    service_ = ContainersServiceFactory::GetForProfile(profile_.get());
    ASSERT_TRUE(service_);
  }

  std::unique_ptr<KeyedService> CreateService(
      content::BrowserContext* context) {
    // Unit tests use the real profile service and partitions. Its mocked
    // persistence owner defers cleanup; Java scanner/device cleanup needs
    // separate integration acceptance.
    auto delegate =
        std::make_unique<testing::NiceMock<MockContainersServiceDelegate>>();
    delegate_ = delegate.get();
    delegate->set_defer_referenced_container_ids_callback(true);
    delegate->set_defer_delete_container_storage_callback(true);
    return std::make_unique<ContainersService>(
        Profile::FromBrowserContext(context)->GetPrefs(), false,
        std::move(delegate));
  }

  std::unique_ptr<content::WebContents> NewContents(Profile* profile,
                                                    std::string_view id) {
    auto config = ResolveContainerStoragePartition(profile, id);
    if (!config) {
      return nullptr;
    }
    content::WebContents::CreateParams params(profile);
    params.site_instance =
        content::SiteInstance::CreateForFixedStoragePartition(profile, GURL(),
                                                              *config);
    return content::WebContents::Create(params);
  }

  content::BrowserTaskEnvironment task_environment_;
  base::test::ScopedFeatureList features_;
  std::unique_ptr<TestingProfile> profile_;
  raw_ptr<ContainersService> service_ = nullptr;
  raw_ptr<MockContainersServiceDelegate> delegate_ = nullptr;
};

TEST_F(AndroidContainersBridgeTest, CanonicalNamedAndTemporaryIdsOnly) {
  EXPECT_TRUE(IsContainerIdWellFormed(""));
  EXPECT_TRUE(IsContainerIdWellFormed(kRestoredId));
  EXPECT_TRUE(IsContainerIdWellFormed(kRestoredTemporaryId));
  for (const auto* bad :
       {"t-", "4a4d13b6", "T-4a4d13b6-c971-4c61-ae11-e7a864f58165",
        "4A4D13B6-C971-4C61-AE11-E7A864F58165", "../jar", "jar name",
        "t-t-4a4d13b6-c971-4c61-ae11-e7a864f58165"}) {
    EXPECT_FALSE(IsContainerIdWellFormed(bad)) << bad;
    EXPECT_FALSE(ResolveContainerStoragePartition(profile_.get(), bad));
  }
}

TEST_F(AndroidContainersBridgeTest, DefaultIsEngagedAndUnknownNeverDefaults) {
  auto config = ResolveContainerStoragePartition(profile_.get(), "");
  ASSERT_TRUE(config);
  EXPECT_EQ(*config,
            content::StoragePartitionConfig::CreateDefault(profile_.get()));
  EXPECT_FALSE(ResolveContainerStoragePartition(nullptr, ""));
  EXPECT_FALSE(ResolveContainerStoragePartition(profile_.get(), kRestoredId));
  EXPECT_FALSE(
      ResolveContainerStoragePartition(profile_.get(), kRestoredTemporaryId));
}

TEST_F(AndroidContainersBridgeTest,
       ConfiguredIdUsesExactPersistentDomainAndName) {
  const auto containers = service_->GetContainers();
  ASSERT_GE(containers.size(), 2u);
  const auto config =
      ResolveContainerStoragePartition(profile_.get(), containers[0]->id);
  ASSERT_TRUE(config);
  EXPECT_EQ(config->partition_domain(), kContainersStoragePartitionDomain);
  EXPECT_EQ(config->partition_name(), containers[0]->id);
  EXPECT_FALSE(config->in_memory());
  const auto other =
      ResolveContainerStoragePartition(profile_.get(), containers[1]->id);
  ASSERT_TRUE(other);
  EXPECT_NE(*config, *other);
}

TEST_F(AndroidContainersBridgeTest,
       TrustedRestorePreservesUnknownNamedAndTemporaryIdentity) {
  EXPECT_TRUE(RestoreContainerIdentity(profile_.get(), kRestoredId));
  EXPECT_TRUE(RestoreContainerIdentity(profile_.get(), kRestoredTemporaryId));
  EXPECT_EQ(ResolveContainerStoragePartition(profile_.get(), kRestoredId)
                ->partition_name(),
            kRestoredId);
  EXPECT_EQ(
      ResolveContainerStoragePartition(profile_.get(), kRestoredTemporaryId)
          ->partition_name(),
      kRestoredTemporaryId);
  EXPECT_TRUE(IsTemporaryContainerId(kRestoredTemporaryId));
  EXPECT_FALSE(IsTemporaryContainerId(kRestoredId));
  const auto ids = service_->GetRuntimeContainerIds();
  EXPECT_NE(std::ranges::find(ids, kRestoredId), ids.end());
  EXPECT_NE(std::ranges::find(ids, kRestoredTemporaryId), ids.end());
}

TEST_F(AndroidContainersBridgeTest,
       IncognitoCannotResolveOrRestoreRegularContainers) {
  auto* otr = profile_->GetPrimaryOTRProfile(/*create_if_needed=*/true);
  ASSERT_TRUE(ResolveContainerStoragePartition(otr, ""));
  EXPECT_TRUE(ResolveContainerStoragePartition(otr, "")->in_memory());
  const auto containers = service_->GetContainers();
  ASSERT_FALSE(containers.empty());
  EXPECT_FALSE(ResolveContainerStoragePartition(otr, containers[0]->id));
  EXPECT_FALSE(RestoreContainerIdentity(otr, kRestoredId));
  EXPECT_FALSE(RestoreContainerIdentity(otr, kRestoredTemporaryId));
}

TEST_F(AndroidContainersBridgeTest,
       TwoNamedAndTemporaryContentsCannotBeCrossAdopted) {
  const auto containers = service_->GetContainers();
  ASSERT_GE(containers.size(), 2u);
  auto first = NewContents(profile_.get(), containers[0]->id);
  auto second = NewContents(profile_.get(), containers[1]->id);
  auto temporary = service_->CreateAndPersistTemporaryContainer();
  ASSERT_TRUE(IsContainerIdWellFormed(temporary->id));
  auto temporary_contents = NewContents(profile_.get(), temporary->id);
  ASSERT_TRUE(first);
  ASSERT_TRUE(second);
  ASSERT_TRUE(temporary_contents);
  EXPECT_TRUE(WebContentsMatchesContainer(profile_.get(), first.get(),
                                          containers[0]->id));
  EXPECT_TRUE(WebContentsMatchesContainer(profile_.get(), second.get(),
                                          containers[1]->id));
  EXPECT_TRUE(WebContentsMatchesContainer(
      profile_.get(), temporary_contents.get(), temporary->id));
  EXPECT_FALSE(WebContentsMatchesContainer(profile_.get(), first.get(),
                                           containers[1]->id));
  EXPECT_FALSE(
      WebContentsMatchesContainer(profile_.get(), second.get(), temporary->id));
  EXPECT_FALSE(WebContentsMatchesContainer(profile_.get(),
                                           temporary_contents.get(), ""));
}

TEST_F(AndroidContainersBridgeTest,
       MatchingPartitionNameInAnotherProfileIsNotAdoptionProof) {
  const auto containers = service_->GetContainers();
  ASSERT_FALSE(containers.empty());
  auto other_profile = TestingProfile::Builder().Build();
  auto contents = NewContents(profile_.get(), containers[0]->id);
  ASSERT_TRUE(contents);
  EXPECT_FALSE(WebContentsMatchesContainer(other_profile.get(), contents.get(),
                                           containers[0]->id));
  EXPECT_FALSE(
      WebContentsMatchesContainer(other_profile.get(), contents.get(), ""));
}

TEST_F(AndroidContainersBridgeTest, UserDataLabelDoesNotProveRealPartition) {
  const auto containers = service_->GetContainers();
  ASSERT_FALSE(containers.empty());
  auto contents = NewContents(profile_.get(), "");
  ASSERT_TRUE(contents);
  ContainersWebContentsUserData::CreateForWebContents(contents.get(),
                                                      containers[0]->id);
  EXPECT_EQ(GetContainerIdForWebContents(contents.get()), containers[0]->id);
  EXPECT_FALSE(WebContentsMatchesContainer(profile_.get(), contents.get(),
                                           containers[0]->id));
  EXPECT_TRUE(WebContentsMatchesContainer(profile_.get(), contents.get(), ""));
}

TEST_F(AndroidContainersBridgeTest,
       RemovingSelectorRetainsReferencedRuntimeSnapshot) {
  const auto containers = service_->GetContainers();
  ASSERT_FALSE(containers.empty());
  const auto id = containers[0]->id;
  service_->MarkContainerUsed(id);
  ContainersSettingsHandler handler(profile_->GetPrefs());
  bool complete = false;
  handler.RemoveContainer(
      id, base::BindOnce(
              [](bool* complete,
                 std::optional<mojom::ContainerOperationError> error) {
                EXPECT_FALSE(error);
                *complete = true;
              },
              &complete));
  EXPECT_TRUE(complete);
  auto snapshot = service_->GetRuntimeContainerById(id);
  ASSERT_TRUE(snapshot);
  EXPECT_EQ(snapshot->name, containers[0]->name);
  EXPECT_EQ(snapshot->icon, containers[0]->icon);
  EXPECT_EQ(snapshot->background_color, containers[0]->background_color);
  EXPECT_TRUE(ResolveContainerStoragePartition(profile_.get(), id));
  const auto ids = service_->GetRuntimeContainerIds();
  EXPECT_NE(std::ranges::find(ids, id), ids.end());
}

TEST_F(AndroidContainersBridgeTest, RemovalPendingCannotAdmitANewTabOrRestore) {
  const auto containers = service_->GetContainers();
  ASSERT_FALSE(containers.empty());
  const auto id = containers[0]->id;
  service_->MarkContainerUsed(id);
  ContainersSettingsHandler handler(profile_->GetPrefs());
  handler.RemoveContainer(
      id,
      base::BindOnce([](std::optional<mojom::ContainerOperationError> error) {
        EXPECT_FALSE(error);
      }));
  service_->ScheduleOrphanedContainersCleanupForTesting();
  delegate_->SetReferencedContainersIds({});
  delegate_->RunDeferredReferencedContainerIdsCallback();
  EXPECT_TRUE(service_->IsContainerStorageRemovalPending(id));
  EXPECT_FALSE(ResolveContainerStoragePartition(profile_.get(), id));
  EXPECT_FALSE(RestoreContainerIdentity(profile_.get(), id));
  const auto ids = service_->GetRuntimeContainerIds();
  EXPECT_EQ(std::ranges::find(ids, id), ids.end());
  delegate_->RunDeferredDeleteContainerStorageCallback(id);
  EXPECT_FALSE(service_->GetRuntimeContainerById(id));
  EXPECT_FALSE(ResolveContainerStoragePartition(profile_.get(), id));
}

TEST_F(AndroidContainersBridgeTest,
       CrudRetainsNativeValidationAndUuidGeneration) {
  ContainersSettingsHandler handler(profile_->GetPrefs());
  auto add = [&](std::string name, mojom::Icon icon, SkColor color) {
    std::optional<mojom::ContainerOperationError> result;
    bool complete = false;
    handler.AddContainer(
        mojom::Container::New("", name, icon, color),
        base::BindOnce(
            [](std::optional<mojom::ContainerOperationError>* result,
               bool* complete,
               std::optional<mojom::ContainerOperationError> error) {
              *result = error;
              *complete = true;
            },
            &result, &complete));
    EXPECT_TRUE(complete);
    return result;
  };
  EXPECT_EQ(add(" ", mojom::Icon::kWork, SK_ColorBLUE),
            mojom::ContainerOperationError::kInvalidName);
  EXPECT_EQ(add("Work", static_cast<mojom::Icon>(11), SK_ColorBLUE),
            mojom::ContainerOperationError::kInvalidIcon);
  EXPECT_EQ(add("Work", mojom::Icon::kWork, SkColorSetARGB(254, 0, 0, 255)),
            mojom::ContainerOperationError::kInvalidBackgroundColor);
  const auto old_size = service_->GetContainers().size();
  EXPECT_FALSE(add("Work phone", mojom::Icon::kWork, SK_ColorBLUE));
  auto containers = service_->GetContainers();
  ASSERT_EQ(containers.size(), old_size + 1);
  EXPECT_TRUE(IsContainerIdWellFormed(containers.back()->id));
  EXPECT_FALSE(IsTemporaryContainerId(containers.back()->id));
  EXPECT_EQ(containers.back()->name, "Work phone");
}

TEST_F(AndroidContainersBridgeTest,
       HidingControlsDoesNotChangeExistingPartition) {
  const auto containers = service_->GetContainers();
  ASSERT_FALSE(containers.empty());
  auto contents = NewContents(profile_.get(), containers[0]->id);
  ASSERT_TRUE(contents);
  profile_->GetPrefs()->SetBoolean(prefs::kContainersEnabled, false);
  EXPECT_FALSE(service_->ShouldShowContainerControls());
  EXPECT_TRUE(WebContentsMatchesContainer(profile_.get(), contents.get(),
                                          containers[0]->id));
}

TEST_F(AndroidContainersBridgeTest,
       StartupInventoryNeverAuthorizesEmptyCleanup) {
  bool replied = false;
  GetPersistentContainerReferences(
      profile_.get(),
      base::BindOnce(
          [](bool* replied, const base::flat_set<std::string>& ids) {
            *replied = true;
            EXPECT_EQ(ids, base::flat_set<std::string>({kRestoredId}));
          },
          &replied));
  task_environment_.RunUntilIdle();
  EXPECT_FALSE(replied);
  SetPersistentContainerReferences(profile_.get(), {"../jar"});
  task_environment_.RunUntilIdle();
  EXPECT_FALSE(replied);
  SetPersistentContainerReferences(profile_.get(),
                                   {kRestoredId, "", kRestoredId});
  task_environment_.RunUntilIdle();
  EXPECT_TRUE(replied);
}

TEST_F(AndroidContainersBridgeTest,
       InvalidationBeforePostedReplyRequiresNewCompleteInventory) {
  SetPersistentContainerReferences(profile_.get(), {kRestoredId});
  bool replied = false;
  GetPersistentContainerReferences(
      profile_.get(),
      base::BindOnce(
          [](bool* replied, const base::flat_set<std::string>& ids) {
            *replied = true;
            EXPECT_EQ(ids, base::flat_set<std::string>({kRestoredTemporaryId}));
          },
          &replied));
  InvalidatePersistentContainerReferences(profile_.get());
  task_environment_.RunUntilIdle();
  EXPECT_FALSE(replied);
  SetPersistentContainerReferences(profile_.get(), {kRestoredTemporaryId});
  task_environment_.RunUntilIdle();
  EXPECT_TRUE(replied);
}

TEST_F(AndroidContainersBridgeTest,
       StaleInventoryGenerationCannotAuthorizeCleanup) {
  const int64_t stale_generation =
      GetPersistentContainerReferencesGeneration(profile_.get());
  ASSERT_GT(stale_generation, 0);
  InvalidatePersistentContainerReferences(profile_.get());
  const int64_t current_generation =
      GetPersistentContainerReferencesGeneration(profile_.get());
  ASSERT_GT(current_generation, stale_generation);
  EXPECT_FALSE(SetPersistentContainerReferencesIfGenerationMatches(
      profile_.get(), {}, stale_generation));
  bool replied = false;
  GetPersistentContainerReferences(
      profile_.get(),
      base::BindOnce(
          [](bool* replied, const base::flat_set<std::string>& ids) {
            *replied = true;
            EXPECT_EQ(ids, base::flat_set<std::string>({kRestoredId}));
          },
          &replied));
  task_environment_.RunUntilIdle();
  EXPECT_FALSE(replied);
  EXPECT_TRUE(SetPersistentContainerReferencesIfGenerationMatches(
      profile_.get(), {kRestoredId}, current_generation));
  task_environment_.RunUntilIdle();
  EXPECT_TRUE(replied);
}

TEST_F(AndroidContainersBridgeTest,
       CorruptUsedIdentityCannotResolveAndTrustedRestoreRepairsExactJar) {
  profile_->GetPrefs()->SetList(prefs::kContainersList, base::ListValue());
  base::DictValue used;
  used.Set(kRestoredId,
           base::DictValue()
               .Set("id", kRestoredTemporaryId)
               .Set("name", "Wrong temporary identity")
               .Set("icon", 0)
               .Set("background_color", static_cast<int>(SK_ColorBLUE)));
  profile_->GetPrefs()->SetDict(prefs::kLocallyUsedContainers, std::move(used));
  EXPECT_FALSE(ResolveContainerStoragePartition(profile_.get(), kRestoredId));
  EXPECT_FALSE(
      ResolveContainerStoragePartition(profile_.get(), kRestoredTemporaryId));
  EXPECT_TRUE(ResolveContainerStoragePartition(profile_.get(), ""));
  ASSERT_TRUE(RestoreContainerIdentity(profile_.get(), kRestoredId));
  auto repaired =
      GetLocallyUsedContainerFromPrefs(*profile_->GetPrefs(), kRestoredId);
  ASSERT_TRUE(repaired);
  EXPECT_EQ(repaired->id, kRestoredId);
  EXPECT_NE(repaired->name, "Wrong temporary identity");
  auto contents = NewContents(profile_.get(), kRestoredId);
  ASSERT_TRUE(contents);
  EXPECT_TRUE(
      WebContentsMatchesContainer(profile_.get(), contents.get(), kRestoredId));
  EXPECT_FALSE(WebContentsMatchesContainer(profile_.get(), contents.get(), ""));
  EXPECT_FALSE(WebContentsMatchesContainer(profile_.get(), contents.get(),
                                           kRestoredTemporaryId));
  EXPECT_FALSE(
      ResolveContainerStoragePartition(profile_.get(), kRestoredTemporaryId));
}

}  // namespace
}  // namespace containers::android
