// Copyright (c) 2025 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "brave/components/containers/core/browser/prefs.h"

#include <utility>

#include "base/test/scoped_feature_list.h"
#include "base/values.h"
#include "brave/components/containers/core/browser/default_containers_list.h"
#include "brave/components/containers/core/browser/pref_names.h"
#include "brave/components/containers/core/browser/prefs_registration.h"
#include "brave/components/containers/core/common/features.h"
#include "brave/components/containers/core/mojom/containers.mojom-data-view.h"
#include "brave/components/containers/core/mojom/containers.mojom.h"
#include "build/build_config.h"
#include "components/sync_preferences/testing_pref_service_syncable.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace containers {

namespace {

#if BUILDFLAG(IS_ANDROID)
constexpr char kCanonicalFixtureId1[] = "77aa0001-0000-4000-8000-000000000001";
constexpr char kCanonicalFixtureId2[] = "77aa0002-0000-4000-8000-000000000002";
constexpr char kCanonicalFixtureId3[] = "77aa0003-0000-4000-8000-000000000003";
constexpr char kCanonicalFixtureId4[] = "77aa0004-0000-4000-8000-000000000004";
#else
constexpr char kCanonicalFixtureId1[] = "test-id-1";
constexpr char kCanonicalFixtureId2[] = "test-id-2";
constexpr char kCanonicalFixtureId3[] = "used-id";
constexpr char kCanonicalFixtureId4[] = "test-id";
#endif

}  // namespace

class ContainersPrefsTest : public testing::Test {
 protected:
  void SetUp() override {
    feature_list_.InitAndEnableFeature(features::kContainers);
    RegisterProfilePrefs(prefs_.registry());
  }

  static void ExpectDefaultContainer(const mojom::ContainerPtr& container,
                                     const std::string& id,
                                     const mojom::Icon icon) {
    ASSERT_TRUE(container);
    SCOPED_TRACE(container->id);
    EXPECT_EQ(container->id, id);
    EXPECT_EQ(container->icon, icon);
    EXPECT_FALSE(container->name.empty());
    EXPECT_NE(container->background_color, SK_ColorTRANSPARENT);
  }

  base::test::ScopedFeatureList feature_list_;
  sync_preferences::TestingPrefServiceSyncable prefs_;
};

TEST_F(ContainersPrefsTest, RegisterStoresDefaultContainers) {
  auto containers = GetContainersFromPrefs(prefs_);
  ASSERT_EQ(containers.size(), 4u);

  ExpectDefaultContainer(containers[0], kDefaultContainerIds[0],
                         mojom::Icon::kPersonal);
  ExpectDefaultContainer(containers[1], kDefaultContainerIds[1],
                         mojom::Icon::kWork);
  ExpectDefaultContainer(containers[2], kDefaultContainerIds[2],
                         mojom::Icon::kSocial);
  ExpectDefaultContainer(containers[3], kDefaultContainerIds[3],
                         mojom::Icon::kSchool);
}

TEST_F(ContainersPrefsTest, ModifyDefaultContainers) {
  auto containers = GetContainersFromPrefs(prefs_);
  ASSERT_EQ(containers.size(), 4u);

  containers.erase(containers.begin());
  SetContainersToPrefs(containers, prefs_);

  containers = GetContainersFromPrefs(prefs_);
  ASSERT_EQ(containers.size(), 3u);
  ExpectDefaultContainer(containers[0], kDefaultContainerIds[1],
                         mojom::Icon::kWork);
  ExpectDefaultContainer(containers[1], kDefaultContainerIds[2],
                         mojom::Icon::kSocial);
  ExpectDefaultContainer(containers[2], kDefaultContainerIds[3],
                         mojom::Icon::kSchool);
}

TEST_F(ContainersPrefsTest, SetAndGetContainerList) {
  std::vector<mojom::ContainerPtr> test_containers;
  test_containers.push_back(
      mojom::Container::New(kCanonicalFixtureId1, "Test Container 1",
                            mojom::Icon::kPersonal, SK_ColorWHITE));
  test_containers.push_back(
      mojom::Container::New(kCanonicalFixtureId2, "Test Container 2",
                            mojom::Icon::kWork, SK_ColorBLACK));

  SetContainersToPrefs(test_containers, prefs_);

  auto retrieved_containers = GetContainersFromPrefs(prefs_);
  ASSERT_EQ(retrieved_containers.size(), 2u);

  EXPECT_EQ(retrieved_containers[0]->id, kCanonicalFixtureId1);
  EXPECT_EQ(retrieved_containers[0]->name, "Test Container 1");
  EXPECT_EQ(retrieved_containers[0]->icon, mojom::Icon::kPersonal);
  EXPECT_EQ(retrieved_containers[0]->background_color, SK_ColorWHITE);

  EXPECT_EQ(retrieved_containers[1]->id, kCanonicalFixtureId2);
  EXPECT_EQ(retrieved_containers[1]->name, "Test Container 2");
  EXPECT_EQ(retrieved_containers[1]->icon, mojom::Icon::kWork);
  EXPECT_EQ(retrieved_containers[1]->background_color, SK_ColorBLACK);
}

TEST_F(ContainersPrefsTest, GetContainerListInvalidData) {
  // Test with invalid list items
  base::ListValue invalid_list;
  invalid_list.Append(base::Value(42));  // Not a dictionary
  invalid_list.Append(
      base::DictValue()
          // Missing name field
          .Set("id", kCanonicalFixtureId4)
          .Set("icon", std::to_underlying(mojom::Icon::kPersonal))
          .Set("background_color", static_cast<int>(SK_ColorWHITE)));

  invalid_list.Append(
      base::DictValue()
          // Missing id field
          .Set("name", "Test Container")
          .Set("icon", std::to_underlying(mojom::Icon::kPersonal))
          .Set("background_color", static_cast<int>(SK_ColorWHITE)));

  invalid_list.Append(
      base::DictValue()
          // Missing icon field
          .Set("id", kCanonicalFixtureId4)
          .Set("name", "Test Container")
          .Set("background_color", static_cast<int>(SK_ColorWHITE)));

  invalid_list.Append(
      base::DictValue()
          // Missing background_color field
          .Set("id", kCanonicalFixtureId4)
          .Set("name", "Test Container")
          .Set("icon", std::to_underlying(mojom::Icon::kPersonal)));

  prefs_.SetList(prefs::kContainersList, std::move(invalid_list));

  auto containers = GetContainersFromPrefs(prefs_);
  EXPECT_TRUE(containers.empty());
}

TEST_F(ContainersPrefsTest, SetContainerListEmpty) {
  std::vector<mojom::ContainerPtr> empty_containers;
  SetContainersToPrefs(empty_containers, prefs_);

  const base::ListValue& list = prefs_.GetList(prefs::kContainersList);
  EXPECT_TRUE(list.empty());
}

TEST_F(ContainersPrefsTest, GetContainerById) {
  std::vector<mojom::ContainerPtr> test_containers;
  test_containers.push_back(
      mojom::Container::New(kCanonicalFixtureId1, "Test Container 1",
                            mojom::Icon::kPersonal, SK_ColorWHITE));
  test_containers.push_back(
      mojom::Container::New(kCanonicalFixtureId2, "Test Container 2",
                            mojom::Icon::kWork, SK_ColorBLACK));
  SetContainersToPrefs(test_containers, prefs_);

  auto container = GetContainerFromPrefs(prefs_, kCanonicalFixtureId2);
  ASSERT_TRUE(container);
  EXPECT_EQ(container->name, "Test Container 2");
  EXPECT_EQ(container->icon, mojom::Icon::kWork);

  EXPECT_FALSE(GetContainerFromPrefs(prefs_, "missing-id"));
}

TEST_F(ContainersPrefsTest, SetAndGetLocallyUsedContainer) {
  auto container = mojom::Container::New(kCanonicalFixtureId3, "Used Container",
                                         mojom::Icon::kShopping, SK_ColorBLUE);
  EXPECT_FALSE(HasLocallyUsedContainerInPrefs(prefs_, kCanonicalFixtureId3));
  SetLocallyUsedContainerToPrefs(container, prefs_);
  EXPECT_TRUE(HasLocallyUsedContainerInPrefs(prefs_, kCanonicalFixtureId3));
  EXPECT_FALSE(HasLocallyUsedContainerInPrefs(prefs_, "other-id"));

  auto retrieved =
      GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId3);
  ASSERT_TRUE(retrieved);
  EXPECT_EQ(retrieved->name, "Used Container");
  EXPECT_EQ(retrieved->icon, mojom::Icon::kShopping);
  EXPECT_EQ(retrieved->background_color, SK_ColorBLUE);

  auto all_used = GetLocallyUsedContainersFromPrefs(prefs_);
  ASSERT_EQ(all_used.size(), 1u);
  EXPECT_EQ(all_used[0]->id, kCanonicalFixtureId3);
}

TEST_F(ContainersPrefsTest, UpdateAndRemoveLocallyUsedContainer) {
  SetLocallyUsedContainerToPrefs(
      mojom::Container::New(kCanonicalFixtureId3, "Used Container",
                            mojom::Icon::kShopping, SK_ColorBLUE),
      prefs_);
  SetLocallyUsedContainerToPrefs(
      mojom::Container::New(kCanonicalFixtureId3, "Updated Container",
                            mojom::Icon::kWork, SK_ColorRED),
      prefs_);

  auto retrieved =
      GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId3);
  ASSERT_TRUE(retrieved);
  EXPECT_EQ(retrieved->name, "Updated Container");
  EXPECT_EQ(retrieved->icon, mojom::Icon::kWork);
  EXPECT_EQ(retrieved->background_color, SK_ColorRED);

  RemoveLocallyUsedContainerFromPrefs(kCanonicalFixtureId3, prefs_);
  EXPECT_FALSE(GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId3));
  EXPECT_TRUE(GetLocallyUsedContainersFromPrefs(prefs_).empty());
}

TEST_F(ContainersPrefsTest, ContainersEnabledDefaultsToTrue) {
  EXPECT_TRUE(prefs_.GetBoolean(prefs::kContainersEnabled));
  prefs_.SetBoolean(prefs::kContainersEnabled, false);
  EXPECT_FALSE(prefs_.GetBoolean(prefs::kContainersEnabled));
}

TEST(ContainersPrefsRegistrationTest,
     ContainersEnabledDefaultsToFalseOnStable) {
  base::test::ScopedFeatureList feature_list;
  feature_list.InitAndEnableFeature(features::kContainers);
  sync_preferences::TestingPrefServiceSyncable prefs;
  RegisterProfilePrefs(prefs.registry(), version_info::Channel::STABLE);
  EXPECT_FALSE(prefs.GetBoolean(prefs::kContainersEnabled));
}

TEST(ContainersPrefsRegistrationTest,
     ContainersEnabledPrefDefaultFinchOverride) {
  base::test::ScopedFeatureList feature_list;
  feature_list.InitAndEnableFeatureWithParameters(
      features::kContainers, {{"enabled_by_default", "true"}});
  sync_preferences::TestingPrefServiceSyncable prefs;
  RegisterProfilePrefs(prefs.registry(), version_info::Channel::STABLE);
  EXPECT_TRUE(prefs.GetBoolean(prefs::kContainersEnabled));
}

TEST(ContainersPrefsRegistrationTest,
     ContainersEnabledPrefDefaultFinchOverrideOnUnknown) {
  base::test::ScopedFeatureList feature_list;
  feature_list.InitAndEnableFeatureWithParameters(
      features::kContainers, {{"enabled_by_default", "false"}});
  sync_preferences::TestingPrefServiceSyncable prefs;
  RegisterProfilePrefs(prefs.registry());
  EXPECT_FALSE(prefs.GetBoolean(prefs::kContainersEnabled));
}

#if BUILDFLAG(IS_ANDROID)
TEST_F(ContainersPrefsTest, AndroidRejectsMalformedConfiguredIdentities) {
  base::ListValue records;
  for (const char* id :
       {"", "not-a-container", "77AA0001-0000-4000-8000-000000000001", "t-",
        "t-t-77aa0001-0000-4000-8000-000000000001"}) {
    records.Append(
        base::DictValue()
            .Set("id", id)
            .Set("name", "Hidden invalid container")
            .Set("icon", 0)
            .Set("background_color", static_cast<int>(SK_ColorBLUE)));
  }
  records.Append(base::DictValue()
                     .Set("id", kCanonicalFixtureId1)
                     .Set("name", "Valid container")
                     .Set("icon", 0)
                     .Set("background_color", static_cast<int>(SK_ColorBLUE)));
  prefs_.SetList(prefs::kContainersList, std::move(records));
  auto containers = GetContainersFromPrefs(prefs_);
  ASSERT_EQ(containers.size(), 1u);
  EXPECT_EQ(containers[0]->id, kCanonicalFixtureId1);
  EXPECT_FALSE(GetContainerFromPrefs(prefs_, ""));
  EXPECT_FALSE(GetContainerFromPrefs(prefs_, "not-a-container"));
}

TEST_F(ContainersPrefsTest, AndroidUsedSnapshotRequiresCanonicalMatchingKey) {
  auto snapshot = [](const char* id) {
    return base::DictValue()
        .Set("id", id)
        .Set("name", "Used snapshot")
        .Set("icon", 0)
        .Set("background_color", static_cast<int>(SK_ColorGREEN));
  };
  base::DictValue used;
  used.Set("", snapshot(""));
  used.Set("garbage", snapshot("garbage"));
  used.Set(kCanonicalFixtureId1, snapshot(kCanonicalFixtureId2));
  used.Set(kCanonicalFixtureId3, snapshot(kCanonicalFixtureId3));
  prefs_.SetDict(prefs::kLocallyUsedContainers, std::move(used));
  auto containers = GetLocallyUsedContainersFromPrefs(prefs_);
  ASSERT_EQ(containers.size(), 1u);
  EXPECT_EQ(containers[0]->id, kCanonicalFixtureId3);
  EXPECT_FALSE(GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId1));
  EXPECT_FALSE(GetLocallyUsedContainerFromPrefs(prefs_, kCanonicalFixtureId2));
  EXPECT_FALSE(HasLocallyUsedContainerInPrefs(prefs_, kCanonicalFixtureId1));
  EXPECT_FALSE(HasLocallyUsedContainerInPrefs(prefs_, ""));
  EXPECT_FALSE(HasLocallyUsedContainerInPrefs(prefs_, "garbage"));
  EXPECT_TRUE(HasLocallyUsedContainerInPrefs(prefs_, kCanonicalFixtureId3));
}

TEST_F(ContainersPrefsTest, AndroidNamedAndTemporaryMetadataRoundTrip) {
  constexpr char kTemporaryId[] = "t-77aa0002-0000-4000-8000-000000000002";
  std::vector<mojom::ContainerPtr> expected;
  expected.push_back(mojom::Container::New(kCanonicalFixtureId1, "Named",
                                           mojom::Icon::kWork, SK_ColorRED));
  expected.push_back(mojom::Container::New(
      kTemporaryId, "Temporary", mojom::Icon::kShopping, SK_ColorBLUE));
  SetContainersToPrefs(expected, prefs_);
  for (const auto& container : expected) {
    SetLocallyUsedContainerToPrefs(container, prefs_);
  }
  auto configured = GetContainersFromPrefs(prefs_);
  ASSERT_EQ(configured.size(), expected.size());
  ASSERT_EQ(GetLocallyUsedContainersFromPrefs(prefs_).size(), expected.size());
  for (size_t i = 0; i < expected.size(); ++i) {
    EXPECT_TRUE(configured[i].Equals(expected[i]));
    auto used = GetLocallyUsedContainerFromPrefs(prefs_, expected[i]->id);
    ASSERT_TRUE(used);
    EXPECT_TRUE(used.Equals(expected[i]));
    EXPECT_TRUE(HasLocallyUsedContainerInPrefs(prefs_, expected[i]->id));
  }
}
#endif  // BUILDFLAG(IS_ANDROID)

}  // namespace containers
