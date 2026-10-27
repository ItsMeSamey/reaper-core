// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "brave/components/containers/core/browser/containers_service.h"

#include <memory>
#include <string>
#include <utility>

#include "base/test/scoped_feature_list.h"
#include "base/values.h"
#include "brave/components/containers/core/browser/container_specifier.h"
#include "brave/components/containers/core/browser/containers_service_observer.h"
#include "brave/components/containers/core/browser/containers_test_utils.h"
#include "brave/components/containers/core/browser/pref_names.h"
#include "brave/components/containers/core/browser/prefs.h"
#include "brave/components/containers/core/browser/prefs_registration.h"
#include "brave/components/containers/core/browser/temporary_container.h"
#include "brave/components/containers/core/browser/unknown_container.h"
#include "brave/components/containers/core/common/features.h"
#include "brave/components/containers/core/mojom/containers.mojom.h"
#include "build/build_config.h"
#include "components/sync_preferences/testing_pref_service_syncable.h"
#include "testing/gmock/include/gmock/gmock.h"
#include "testing/gtest/include/gtest/gtest.h"
#include "ui/color/color_provider_manager.h"

#if BUILDFLAG(IS_ANDROID)
#include "base/test/mock_callback.h"
#endif

namespace containers {

namespace {

#if BUILDFLAG(IS_ANDROID)
constexpr char kCanonicalFixtureId1[] = "88bb0001-0000-4000-8000-000000000001";
constexpr char kCanonicalFixtureId2[] = "88bb0002-0000-4000-8000-000000000002";
constexpr char kCanonicalFixtureId3[] = "88bb0003-0000-4000-8000-000000000003";
constexpr char kCanonicalFixtureId4[] = "88bb0004-0000-4000-8000-000000000004";
constexpr char kCanonicalFixtureId5[] = "88bb0005-0000-4000-8000-000000000005";
constexpr char kCanonicalFixtureId6[] = "88bb0006-0000-4000-8000-000000000006";
constexpr char kCanonicalFixtureId7[] = "88bb0007-0000-4000-8000-000000000007";
constexpr char kCanonicalFixtureId8[] = "88bb0008-0000-4000-8000-000000000008";
constexpr char kCanonicalFixtureId9[] = "88bb0009-0000-4000-8000-000000000009";
#else
constexpr char kCanonicalFixtureId1[] = "container-id";
constexpr char kCanonicalFixtureId2[] = "cached-id";
constexpr char kCanonicalFixtureId3[] = "c1";
constexpr char kCanonicalFixtureId4[] = "gone-id";
constexpr char kCanonicalFixtureId5[] = "orphan-id";
constexpr char kCanonicalFixtureId6[] = "other-id";
constexpr char kCanonicalFixtureId7[] = "retained-id";
constexpr char kCanonicalFixtureId8[] = "unknown-id";
constexpr char kCanonicalFixtureId9[] = "used-id";
#endif

class MockContainersServiceObserver : public ContainersServiceObserver {
 public:
  MOCK_METHOD(void, OnContainersListChanged, (), (override));
};

}  // namespace

class ContainersServiceTest : public testing::Test {
 protected:
  void SetUp() override {
    feature_list_.InitAndEnableFeature(features::kContainers);
    RegisterProfilePrefs(prefs_.registry());

    auto delegate =
        std::make_unique<testing::NiceMock<MockContainersServiceDelegate>>();
    delegate_ = delegate.get();
    service_ = std::make_unique<ContainersService>(
        &prefs_, /*is_off_the_record=*/false, std::move(delegate));
  }

  void TearDown() override {
    delegate_ = nullptr;
    service_->Shutdown();
    service_.reset();
    ui::ColorProviderManager::ResetForTesting();
  }

  base::test::ScopedFeatureList feature_list_;
  sync_preferences::TestingPrefServiceSyncable prefs_;
  std::unique_ptr<ContainersService> service_;
  raw_ptr<MockContainersServiceDelegate> delegate_ = nullptr;
};

TEST_F(ContainersServiceTest, GetRuntimeContainerById) {
  auto container = MakeContainer(kCanonicalFixtureId1, "Personal");
  std::vector<mojom::ContainerPtr> synced_containers;
  synced_containers.push_back(container.Clone());
  SetContainersToPrefs(std::move(synced_containers), prefs_);

  auto runtime_container =
      service_->GetRuntimeContainerById(kCanonicalFixtureId1);
  ExpectContainer(runtime_container, kCanonicalFixtureId1, "Personal");

  EXPECT_FALSE(service_->GetRuntimeContainerById("non-existent-container-id"));
}

TEST_F(ContainersServiceTest, GetRuntimeContainerByName) {
  auto container = MakeContainer(kCanonicalFixtureId1, "Work");
  std::vector<mojom::ContainerPtr> synced_containers;
  synced_containers.push_back(container.Clone());
  SetContainersToPrefs(std::move(synced_containers), prefs_);

  auto runtime_container = service_->GetRuntimeContainerByName("Work");
  ExpectContainer(runtime_container, kCanonicalFixtureId1, "Work");

  EXPECT_FALSE(service_->GetRuntimeContainerByName("non-existent-name"));
}

TEST_F(ContainersServiceTest,
       GetRuntimeContainerByName_FallsBackToUsedWhenNotInSyncedList) {
  SetContainersToPrefs({}, prefs_);
  SetLocallyUsedContainerToPrefs(
      MakeContainer(kCanonicalFixtureId2, "CachedName"), prefs_);

  auto runtime = service_->GetRuntimeContainerByName("CachedName");
  ExpectContainer(runtime, kCanonicalFixtureId2, "CachedName");
}

TEST_F(ContainersServiceTest, GetContainerIdFromContainerSpecifier_ById) {
  auto container = MakeContainer(kCanonicalFixtureId1, "Work");
  std::vector<mojom::ContainerPtr> synced_containers;
  synced_containers.push_back(container.Clone());
  SetContainersToPrefs(std::move(synced_containers), prefs_);

  EXPECT_EQ(service_->GetContainerIdFromContainerSpecifier(
                ContainerId(kCanonicalFixtureId1)),
            kCanonicalFixtureId1);
}

TEST_F(ContainersServiceTest, GetContainerIdFromContainerSpecifier_ByName) {
  auto container = MakeContainer(kCanonicalFixtureId1, "Shopping");
  std::vector<mojom::ContainerPtr> synced_containers;
  synced_containers.push_back(container.Clone());
  SetContainersToPrefs(std::move(synced_containers), prefs_);

  EXPECT_EQ(
      service_->GetContainerIdFromContainerSpecifier(ContainerName("Shopping")),
      kCanonicalFixtureId1);
}

TEST_F(ContainersServiceTest,
       GetContainerIdFromContainerSpecifier_ReturnsNulloptWhenNotFound) {
  SetContainersToPrefs({}, prefs_);

  EXPECT_FALSE(service_->GetContainerIdFromContainerSpecifier(
      ContainerId("missing-id")));
  EXPECT_FALSE(service_->GetContainerIdFromContainerSpecifier(
      ContainerName("missing-name")));
  EXPECT_FALSE(
      service_->GetContainerIdFromContainerSpecifier(ContainerSpecifier{}));
}

TEST_F(ContainersServiceTest, GetContainers) {
  auto container = MakeContainer(kCanonicalFixtureId1, "Work");
  std::vector<mojom::ContainerPtr> containers;
  containers.push_back(container.Clone());
  SetContainersToPrefs(std::move(containers), prefs_);

  auto containers_list = service_->GetContainers();
  ExpectContainer(containers_list[0], kCanonicalFixtureId1, "Work");
}

TEST_F(ContainersServiceTest, CreateAndPersistTemporaryContainer) {
  auto container = service_->CreateAndPersistTemporaryContainer();
  ASSERT_TRUE(container);
  EXPECT_TRUE(IsTemporaryContainerId(container->id));
  EXPECT_FALSE(container->name.empty());
  EXPECT_GE(container->icon, mojom::Icon::kMinValue);
  EXPECT_LE(container->icon, mojom::Icon::kMaxValue);
  EXPECT_NE(SK_ColorTRANSPARENT, container->background_color);

  auto persisted = GetLocallyUsedContainerFromPrefs(prefs_, container->id);
  ASSERT_TRUE(persisted);
  ExpectContainer(persisted, container->id, container->name, container->icon,
                  container->background_color);
}

TEST_F(ContainersServiceTest, GetOrCreateTemporaryContainerByName) {
  // A synced container with the same name must not be returned: the caller
  // asked for a temporary container.
  std::vector<mojom::ContainerPtr> synced_containers;
  synced_containers.push_back(
      MakeContainer(kCanonicalFixtureId1, "Shared Name"));
  SetContainersToPrefs(std::move(synced_containers), prefs_);

  auto container = service_->GetOrCreateTemporaryContainerByName("Shared Name");
  ASSERT_TRUE(container);
  EXPECT_TRUE(IsTemporaryContainerId(container->id));
  EXPECT_EQ("Shared Name", container->name);
  EXPECT_TRUE(GetLocallyUsedContainerFromPrefs(prefs_, container->id));

  // The same name resolves to the same temporary container.
  auto reused = service_->GetOrCreateTemporaryContainerByName("Shared Name");
  ASSERT_TRUE(reused);
  EXPECT_EQ(container->id, reused->id);

  // A different name creates another temporary container.
  auto other = service_->GetOrCreateTemporaryContainerByName("Other Name");
  ASSERT_TRUE(other);
  EXPECT_TRUE(IsTemporaryContainerId(other->id));
  EXPECT_NE(container->id, other->id);
}

TEST_F(ContainersServiceTest,
       Observer_OnContainersListChanged_WhenContainersListPrefChanges) {
  testing::NiceMock<MockContainersServiceObserver> observer;
  EXPECT_CALL(observer, OnContainersListChanged()).Times(1);

  service_->AddObserver(&observer);

  std::vector<mojom::ContainerPtr> containers;
  containers.push_back(MakeContainer(kCanonicalFixtureId1, "Work"));
  SetContainersToPrefs(containers, prefs_);
  testing::Mock::VerifyAndClearExpectations(&observer);

  EXPECT_CALL(observer, OnContainersListChanged()).Times(1);
  SetContainersToPrefs({}, prefs_);
  testing::Mock::VerifyAndClearExpectations(&observer);

  service_->RemoveObserver(&observer);
}

TEST_F(ContainersServiceTest, MarkContainerUsed_PersistsSnapshot) {
  auto container = MakeContainer(kCanonicalFixtureId9, "Local");
  std::vector<mojom::ContainerPtr> synced;
  synced.push_back(container.Clone());
  SetContainersToPrefs(synced, prefs_);

  service_->MarkContainerUsed(kCanonicalFixtureId9);

  auto used = GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId9);
  ExpectContainer(used, kCanonicalFixtureId9, "Local");
}

TEST_F(ContainersServiceTest, MarkContainerUsed_PersistsUnknownWhenNotSynced) {
  SetContainersToPrefs({}, prefs_);

  service_->MarkContainerUsed(kCanonicalFixtureId8);

  auto used = GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId8);
  ExpectContainer(used, kCanonicalFixtureId8, "unknown-", mojom::Icon::kDefault,
                  kUnknownContainerBackgroundColor);
}

TEST_F(ContainersServiceTest, GetUsedContainerIds) {
  EXPECT_TRUE(service_->GetUsedContainerIds().empty());

  service_->MarkContainerUsed(kCanonicalFixtureId9);

  EXPECT_THAT(service_->GetUsedContainerIds(),
              testing::ElementsAre(kCanonicalFixtureId9));
}

TEST_F(ContainersServiceTest, GetUsedContainerIds_EmptyWhileDiscovering) {
  SetContainersToPrefs({}, prefs_);
  service_->MarkContainerUsed(kCanonicalFixtureId5);

  delegate_->set_defer_referenced_container_ids_callback(true);
  service_->ScheduleOrphanedContainersCleanupForTesting();

  EXPECT_TRUE(GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId5));
  EXPECT_TRUE(service_->GetUsedContainerIds().empty());
}

TEST_F(ContainersServiceTest,
       GetUsedContainerIds_ExcludesOrphansWhileRemoving) {
  SetContainersToPrefs({}, prefs_);
  service_->MarkContainerUsed(kCanonicalFixtureId5);
  service_->MarkContainerUsed(kCanonicalFixtureId7);

  delegate_->SetReferencedContainersIds({kCanonicalFixtureId7});
  delegate_->set_defer_referenced_container_ids_callback(true);
  delegate_->set_defer_delete_container_storage_callback(true);
  service_->ScheduleOrphanedContainersCleanupForTesting();

  delegate_->RunDeferredReferencedContainerIdsCallback();

  EXPECT_TRUE(GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId5));
  EXPECT_TRUE(GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId7));
  EXPECT_THAT(service_->GetUsedContainerIds(),
              testing::ElementsAre(kCanonicalFixtureId7));
}

TEST_F(ContainersServiceTest, GetUsedContainerIds_IncludesFailedDelete) {
  SetContainersToPrefs({}, prefs_);
  service_->MarkContainerUsed(kCanonicalFixtureId5);

  delegate_->SetReferencedContainersIds({});
  delegate_->set_delete_result(false);
  service_->ScheduleOrphanedContainersCleanupForTesting();

  EXPECT_THAT(service_->GetUsedContainerIds(),
              testing::ElementsAre(kCanonicalFixtureId5));
}

TEST_F(ContainersServiceTest, GetUsedContainerIds_BackToIdleAfterRemoval) {
  SetContainersToPrefs({}, prefs_);
  service_->MarkContainerUsed(kCanonicalFixtureId5);

  delegate_->SetReferencedContainersIds({});
  service_->ScheduleOrphanedContainersCleanupForTesting();

  EXPECT_TRUE(service_->GetUsedContainerIds().empty());
  EXPECT_FALSE(GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId5));
}

TEST_F(ContainersServiceTest,
       MarkContainerUsed_DoesNotUpsertWhenAlreadyInUsedPrefs) {
  auto container = MakeContainer(kCanonicalFixtureId9, "Local");
  std::vector<mojom::ContainerPtr> synced;
  synced.push_back(container.Clone());
  SetContainersToPrefs(synced, prefs_);

  service_->MarkContainerUsed(kCanonicalFixtureId9);

  SetLocallyUsedContainerToPrefs(MakeContainer(kCanonicalFixtureId9, "Stale",
                                               mojom::Icon::kWork, SK_ColorRED),
                                 prefs_);

  service_->MarkContainerUsed(kCanonicalFixtureId9);

  auto used = GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId9);
  ExpectContainer(used, kCanonicalFixtureId9, "Stale", mojom::Icon::kWork,
                  SK_ColorRED);
}

TEST_F(ContainersServiceTest,
       GetRuntimeContainerById_FallsBackToUsedWhenNotInSyncedList) {
  SetContainersToPrefs({}, prefs_);
  SetLocallyUsedContainerToPrefs(
      MakeContainer(kCanonicalFixtureId2, "CachedName"), prefs_);

  auto runtime = service_->GetRuntimeContainerById(kCanonicalFixtureId2);
  ExpectContainer(runtime, kCanonicalFixtureId2, "CachedName");

  EXPECT_TRUE(service_->GetContainers().empty());
}

TEST_F(ContainersServiceTest,
       OnSyncedContainersChanged_RefreshesUsedSnapshotWhenIdStillSynced) {
  std::vector<mojom::ContainerPtr> v1;
  v1.push_back(MakeContainer(kCanonicalFixtureId3, "SyncedV1"));
  SetContainersToPrefs(v1, prefs_);

  SetLocallyUsedContainerToPrefs(MakeContainer(kCanonicalFixtureId3, "Stale",
                                               mojom::Icon::kWork, SK_ColorRED),
                                 prefs_);

  auto used = GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId3);
  ExpectContainer(used, kCanonicalFixtureId3, "Stale", mojom::Icon::kWork,
                  SK_ColorRED);

  std::vector<mojom::ContainerPtr> v2;
  v2.push_back(MakeContainer(kCanonicalFixtureId3, "SyncedV2"));
  SetContainersToPrefs(v2, prefs_);

  auto used_after_sync =
      GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId3);
  ExpectContainer(used_after_sync, kCanonicalFixtureId3, "SyncedV2");
}

TEST_F(ContainersServiceTest,
       OnSyncedContainersChanged_PreservesUsedWhenIdNotInSyncedList) {
  SetLocallyUsedContainerToPrefs(MakeContainer(kCanonicalFixtureId4, "Cached"),
                                 prefs_);

  std::vector<mojom::ContainerPtr> synced;
  synced.push_back(MakeContainer(kCanonicalFixtureId6, "Other"));
  SetContainersToPrefs(synced, prefs_);

  auto used = GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId4);
  ExpectContainer(used, kCanonicalFixtureId4, "Cached");
}

TEST_F(ContainersServiceTest, CleanupRunsWhenReferencesDisappear) {
  auto container = MakeContainer(kCanonicalFixtureId1, "Shopping",
                                 mojom::Icon::kShopping, SK_ColorRED);
  std::vector<mojom::ContainerPtr> synced_containers;
  synced_containers.push_back(container.Clone());
  SetContainersToPrefs(std::move(synced_containers), prefs_);
  service_->MarkContainerUsed(kCanonicalFixtureId1);

  delegate_->SetReferencedContainersIds({});
  SetContainersToPrefs({}, prefs_);

  service_->ScheduleOrphanedContainersCleanupForTesting();

  ASSERT_EQ(delegate_->delete_requests().size(), 1u);
  EXPECT_EQ(delegate_->delete_requests()[0], kCanonicalFixtureId1);
  EXPECT_FALSE(GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId1));
}

TEST_F(ContainersServiceTest, CleanupBlockedWhileContainerIsReferenced) {
  auto container = MakeContainer(kCanonicalFixtureId1, "Work",
                                 mojom::Icon::kWork, SK_ColorGREEN);
  std::vector<mojom::ContainerPtr> synced_containers;
  synced_containers.push_back(container.Clone());
  SetContainersToPrefs(std::move(synced_containers), prefs_);
  service_->MarkContainerUsed(kCanonicalFixtureId1);

  delegate_->SetReferencedContainersIds({kCanonicalFixtureId1});
  SetContainersToPrefs({}, prefs_);

  service_->ScheduleOrphanedContainersCleanupForTesting();

  EXPECT_TRUE(delegate_->delete_requests().empty());
  EXPECT_TRUE(GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId1));
}

TEST_F(ContainersServiceTest, CleanupFailureKeepsUsedSnapshot) {
  auto container = MakeContainer(kCanonicalFixtureId1, "Shopping",
                                 mojom::Icon::kShopping, SK_ColorRED);
  std::vector<mojom::ContainerPtr> synced_containers;
  synced_containers.push_back(container.Clone());
  SetContainersToPrefs(std::move(synced_containers), prefs_);
  service_->MarkContainerUsed(kCanonicalFixtureId1);

  delegate_->SetReferencedContainersIds({});
  SetContainersToPrefs({}, prefs_);

  delegate_->set_delete_result(false);
  service_->ScheduleOrphanedContainersCleanupForTesting();

  ASSERT_EQ(delegate_->delete_requests().size(), 1u);
  EXPECT_TRUE(GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId1));
}

#if BUILDFLAG(IS_ANDROID)
TEST_F(ContainersServiceTest,
       StorageRemovalCompletionNotifiesAfterCancelledRemoval) {
  service_->MarkContainerUsed(kCanonicalFixtureId1);
  delegate_->SetReferencedContainersIds({});
  delegate_->set_defer_delete_container_storage_callback(true);
  delegate_->set_delete_result(false);

  testing::StrictMock<base::MockRepeatingClosure> completion;
  auto subscription =
      service_->RegisterContainerStorageRemovalCompletedCallback(completion.Get());
  EXPECT_CALL(completion, Run()).Times(0);
  service_->ScheduleOrphanedContainersCleanupForTesting();
  ASSERT_TRUE(service_->IsContainerStorageRemovalPending(kCanonicalFixtureId1));
  EXPECT_TRUE(service_->GetRuntimeContainerIds().empty());

  // Sync restores the configured identity while the deletion delegate is
  // still rechecking references. It rejects removal without deleting storage.
  std::vector<mojom::ContainerPtr> configured;
  configured.push_back(MakeContainer(kCanonicalFixtureId1, "Restored"));
  configured.push_back(MakeContainer(kCanonicalFixtureId2, "Unrelated"));
  SetContainersToPrefs(std::move(configured), prefs_);
  EXPECT_TRUE(service_->IsContainerStorageRemovalPending(kCanonicalFixtureId1));
  EXPECT_THAT(service_->GetRuntimeContainerIds(),
              testing::ElementsAre(kCanonicalFixtureId2));
  testing::Mock::VerifyAndClearExpectations(&completion);

  EXPECT_CALL(completion, Run()).WillOnce([&] {
    EXPECT_FALSE(
        service_->IsContainerStorageRemovalPending(kCanonicalFixtureId1));
    EXPECT_THAT(service_->GetRuntimeContainerIds(),
                testing::UnorderedElementsAre(kCanonicalFixtureId1,
                                              kCanonicalFixtureId2));
    ExpectContainer(GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId1),
                    kCanonicalFixtureId1, "Restored");
  });
  delegate_->RunDeferredDeleteContainerStorageCallback(kCanonicalFixtureId1);
}

TEST_F(ContainersServiceTest,
       StorageRemovalCompletionSettlesPrefsAndRespectsUnsubscription) {
  service_->MarkContainerUsed(kCanonicalFixtureId5);
  delegate_->SetReferencedContainersIds({});
  delegate_->set_defer_delete_container_storage_callback(true);

  testing::StrictMock<base::MockRepeatingClosure> completion;
  auto subscription =
      service_->RegisterContainerStorageRemovalCompletedCallback(completion.Get());
  service_->ScheduleOrphanedContainersCleanupForTesting();
  ASSERT_TRUE(service_->IsContainerStorageRemovalPending(kCanonicalFixtureId5));
  EXPECT_CALL(completion, Run()).WillOnce([&] {
    EXPECT_FALSE(
        service_->IsContainerStorageRemovalPending(kCanonicalFixtureId5));
    EXPECT_FALSE(GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId5));
    EXPECT_TRUE(service_->GetRuntimeContainerIds().empty());
  });
  delegate_->RunDeferredDeleteContainerStorageCallback(kCanonicalFixtureId5);
  testing::Mock::VerifyAndClearExpectations(&completion);

  subscription = {};
  EXPECT_CALL(completion, Run()).Times(0);
  service_->MarkContainerUsed(kCanonicalFixtureId6);
  service_->ScheduleOrphanedContainersCleanupForTesting();
  ASSERT_TRUE(service_->IsContainerStorageRemovalPending(kCanonicalFixtureId6));
  delegate_->RunDeferredDeleteContainerStorageCallback(kCanonicalFixtureId6);
  EXPECT_FALSE(service_->IsContainerStorageRemovalPending(kCanonicalFixtureId6));
  EXPECT_FALSE(GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId6));
}
#endif  // BUILDFLAG(IS_ANDROID)

#if BUILDFLAG(IS_ANDROID)
TEST_F(ContainersServiceTest, AndroidRuntimeConsumersIgnoreMalformedRecords) {
  delegate_->set_defer_referenced_container_ids_callback(true);
  auto record = [](const char* id, const char* name) {
    return base::DictValue()
        .Set("id", id)
        .Set("name", name)
        .Set("icon", 0)
        .Set("background_color", static_cast<int>(SK_ColorBLUE));
  };
  constexpr char kTemporaryId[] = "t-88bb0002-0000-4000-8000-000000000002";
  base::ListValue configured;
  configured.Append(record("", "Invalid default"));
  configured.Append(record("not-an-id", "Invalid named"));
  configured.Append(record(kCanonicalFixtureId1, "Named"));
  configured.Append(record(kTemporaryId, "Temporary"));
  prefs_.SetList(prefs::kContainersList, std::move(configured));
  base::DictValue used;
  used.Set(kCanonicalFixtureId3,
           record(kCanonicalFixtureId4, "Wrong identity"));
  used.Set("", record("", "Invalid used"));
  used.Set(kTemporaryId, record(kTemporaryId, "Temporary"));
  prefs_.SetDict(prefs::kLocallyUsedContainers, std::move(used));
  EXPECT_THAT(
      service_->GetRuntimeContainerIds(),
      testing::UnorderedElementsAre(kCanonicalFixtureId1, kTemporaryId));
  EXPECT_FALSE(service_->GetRuntimeContainerById(""));
  EXPECT_FALSE(service_->GetRuntimeContainerById("not-an-id"));
  EXPECT_FALSE(service_->GetRuntimeContainerById(kCanonicalFixtureId3));
  EXPECT_FALSE(service_->GetRuntimeContainerById(kCanonicalFixtureId4));
  EXPECT_FALSE(service_->GetRuntimeContainerByName("Wrong identity"));
  EXPECT_FALSE(service_->GetRuntimeContainerByName("Invalid default"));
  EXPECT_FALSE(service_->GetRuntimeContainerByName("Invalid used"));
  ExpectContainer(service_->GetRuntimeContainerByName("Named"),
                  kCanonicalFixtureId1, "Named");
  ExpectContainer(service_->GetRuntimeContainerByName("Temporary"),
                  kTemporaryId, "Temporary");
}

TEST_F(ContainersServiceTest, AndroidTrustedMarkUsedRepairsMismatchedSnapshot) {
  delegate_->set_defer_referenced_container_ids_callback(true);
  std::vector<mojom::ContainerPtr> configured;
  configured.push_back(MakeContainer(kCanonicalFixtureId1, "Correct name",
                                     mojom::Icon::kWork, SK_ColorRED));
  SetContainersToPrefs(configured, prefs_);
  base::DictValue used;
  used.Set(kCanonicalFixtureId1,
           base::DictValue()
               .Set("id", kCanonicalFixtureId2)
               .Set("name", "Wrong identity")
               .Set("icon", 0)
               .Set("background_color", static_cast<int>(SK_ColorBLUE)));
  prefs_.SetDict(prefs::kLocallyUsedContainers, std::move(used));
  EXPECT_FALSE(HasLocallyUsedContainerInPrefs(prefs_, kCanonicalFixtureId1));
  service_->MarkContainerUsed(kCanonicalFixtureId1);
  auto repaired =
      GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId1);
  ASSERT_TRUE(repaired);
  EXPECT_TRUE(repaired.Equals(configured[0]));
  EXPECT_TRUE(HasLocallyUsedContainerInPrefs(prefs_, kCanonicalFixtureId1));
  EXPECT_FALSE(service_->GetRuntimeContainerById(kCanonicalFixtureId2));
}
#endif  // BUILDFLAG(IS_ANDROID)

}  // namespace containers
