// Copyright 2026 The Reaper Authors
// SPDX-License-Identifier: MPL-2.0
#include "brave/browser/containers/android/containers_tab_lifecycle.h"

#include <memory>
#include <string>
#include <vector>

#include "base/test/scoped_feature_list.h"
#include "brave/browser/containers/android/containers_bridge.h"
#include "brave/browser/containers/containers_service_factory.h"
#include "brave/components/containers/core/common/features.h"
#include "chrome/test/base/testing_profile.h"
#include "components/sessions/core/serialized_navigation_entry.h"
#include "content/public/test/browser_task_environment.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace containers::android {
namespace {
constexpr char kId[] = "4a4d13b6-c971-4c61-ae11-e7a864f58165";
constexpr char kOther[] = "22a6d27a-e4ef-46ec-8f8f-11a93d558b33";
constexpr char kTemporary[] = "t-4a4d13b6-c971-4c61-ae11-e7a864f58165";

class AndroidContainerTabLifecycleTest : public testing::Test {
 protected:
  void SetUp() override {
    features_.InitAndEnableFeature(features::kContainers);
    ContainersServiceFactory::GetInstance();
    profile_ = TestingProfile::Builder().Build();
  }
  sessions::SerializedNavigationEntry Navigation(std::string_view id,
                                                 const GURL& url) {
    sessions::SerializedNavigationEntry entry;
    entry.set_virtual_url(url);
    if (!id.empty()) entry.set_storage_partition_key({"containers", std::string(id)});
    return entry;
  }
  content::BrowserTaskEnvironment task_environment_;
  base::test::ScopedFeatureList features_;
  std::unique_ptr<TestingProfile> profile_;
};

TEST_F(AndroidContainerTabLifecycleTest, EmptyLegacyStateRestoresDefaultOnly) {
  std::vector<sessions::SerializedNavigationEntry> navs{
      Navigation("", GURL("https://same.example/"))};
  content::WebContents::CreateParams params(profile_.get());
  auto id = ConfigureContainerRestore(profile_.get(), navs, 0, &params, "");
  ASSERT_TRUE(id); EXPECT_TRUE(id->empty());
  auto contents = content::WebContents::Create(params);
  EXPECT_TRUE(WebContentsMatchesContainer(profile_.get(), contents.get(), ""));
  EXPECT_FALSE(WebContentsMatchesContainer(profile_.get(), contents.get(), kId));
}

TEST_F(AndroidContainerTabLifecycleTest, NamedAndTemporaryNtpRestoreHaveActualPersistentConfig) {
  for (const auto* id : {kId, kTemporary}) {
    std::vector<sessions::SerializedNavigationEntry> navs{
        Navigation(id, GURL("chrome://newtab/"))};
    content::WebContents::CreateParams params(profile_.get());
    auto restored_id =
        ConfigureContainerRestore(profile_.get(), navs, 0, &params, id);
    ASSERT_TRUE(restored_id);
    EXPECT_EQ(*restored_id, id);
    ASSERT_TRUE(params.site_instance);
    auto contents = content::WebContents::Create(params);
    EXPECT_TRUE(WebContentsMatchesContainer(profile_.get(), contents.get(), id));
    EXPECT_FALSE(WebContentsMatchesContainer(profile_.get(), contents.get(), ""));
  }
}

TEST_F(AndroidContainerTabLifecycleTest, ConflictingIndexAndNavigationIdentityFailClosed) {
  std::vector<sessions::SerializedNavigationEntry> navs{
      Navigation(kId, GURL("https://same.example/"))};
  content::WebContents::CreateParams params(profile_.get());
  EXPECT_FALSE(ConfigureContainerRestore(profile_.get(), navs, 0, &params, ""));
  EXPECT_FALSE(ConfigureContainerRestore(profile_.get(), navs, 0, &params, kOther));
  EXPECT_FALSE(params.site_instance);
}

TEST_F(AndroidContainerTabLifecycleTest, MixedHistoryAndMalformedKeysFailClosed) {
  std::vector<sessions::SerializedNavigationEntry> navs{
      Navigation(kId, GURL("https://same.example/")),
      Navigation(kOther, GURL("https://same.example/"))};
  content::WebContents::CreateParams params(profile_.get());
  EXPECT_FALSE(ConfigureContainerRestore(profile_.get(), navs, 0, &params));
  navs.resize(1);
  navs[0].set_storage_partition_key({"containers", "../invalid"});
  EXPECT_FALSE(ConfigureContainerRestore(profile_.get(), navs, 0, &params));
  EXPECT_FALSE(params.site_instance);
}

TEST_F(AndroidContainerTabLifecycleTest, IncognitoCannotAdoptPersistedRegularContainer) {
  auto* otr = profile_->GetPrimaryOTRProfile(true);
  std::vector<sessions::SerializedNavigationEntry> navs{
      Navigation(kId, GURL("https://same.example/"))};
  content::WebContents::CreateParams params(otr);
  EXPECT_FALSE(ConfigureContainerRestore(otr, navs, 0, &params, kId));
  EXPECT_FALSE(params.site_instance);
}
}  // namespace
}  // namespace containers::android
