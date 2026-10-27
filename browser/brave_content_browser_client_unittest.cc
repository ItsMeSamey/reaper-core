/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "build/build_config.h"

#include "brave/browser/brave_content_browser_client.h"

#include "brave/components/brave_search/common/brave_search_utils.h"
#if !BUILDFLAG(IS_ANDROID)
#include "brave/components/skus/common/skus_utils.h"
#endif  // !BUILDFLAG(IS_ANDROID)
#include "chrome/browser/chrome_content_browser_client.h"
#include "chrome/test/base/testing_browser_process.h"
#include "chrome/test/base/testing_profile.h"
#include "components/prefs/testing_pref_service.h"
#include "content/public/test/browser_task_environment.h"
#include "extensions/buildflags/buildflags.h"
#include "testing/gtest/include/gtest/gtest.h"

#if BUILDFLAG(IS_ANDROID) && BUILDFLAG(ENABLE_EXTENSIONS_CORE)
#include "base/test/scoped_command_line.h"
#include "chrome/browser/content_settings/host_content_settings_map_factory.h"
#include "chrome/common/chrome_switches.h"
#include "chrome/test/base/chrome_render_view_host_test_harness.h"
#include "components/content_settings/core/browser/host_content_settings_map.h"
#include "components/content_settings/core/common/content_settings_types.h"
#include "content/public/browser/navigation_controller.h"
#include "content/public/browser/navigation_entry.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/site_instance.h"
#include "content/public/browser/web_contents.h"
#include "content/public/test/navigation_simulator.h"
#include "content/public/test/web_contents_tester.h"
#include "net/base/net_errors.h"
#include "third_party/blink/public/common/user_agent/user_agent_metadata.h"
#include "third_party/blink/public/common/web_preferences/web_preferences.h"
#endif

#if BUILDFLAG(IS_WIN)
#include "base/test/scoped_os_info_override_win.h"
#include "brave/components/windows_recall/windows_recall.h"
#endif

#if BUILDFLAG(IS_WIN)
static base::test::ScopedOSInfoOverride* g_scoped_win_version = nullptr;
#endif

class BraveContentBrowserClientTest : public testing::Test {
 protected:
  content::BrowserTaskEnvironment task_environment_;
  TestingProfile profile_;
};

TEST_F(BraveContentBrowserClientTest, ResolvesSync) {
  GURL url("chrome://sync/");
  ASSERT_TRUE(
      BraveContentBrowserClient::HandleURLOverrideRewrite(&url, nullptr));
  ASSERT_STREQ(url.spec().c_str(), "chrome://settings/braveSync");

  GURL url2("chrome://sync/");
  ASSERT_TRUE(
      BraveContentBrowserClient::HandleURLOverrideRewrite(&url2, nullptr));
}

TEST_F(BraveContentBrowserClientTest, ResolvesWelcomePage) {
  GURL url("chrome://welcome/");
  ASSERT_TRUE(
      BraveContentBrowserClient::HandleURLOverrideRewrite(&url, nullptr));
}

TEST_F(BraveContentBrowserClientTest, IsolatedWebAppsAreDisabled) {
  BraveContentBrowserClient client;
  EXPECT_FALSE(client.AreIsolatedWebAppsEnabled(&profile_));
}

TEST_F(BraveContentBrowserClientTest, GetOriginsRequiringDedicatedProcess) {
  ChromeContentBrowserClient chrome_client;
  BraveContentBrowserClient client;
  auto chrome_origins = chrome_client.GetOriginsRequiringDedicatedProcess();
  auto brave_origins = client.GetOriginsRequiringDedicatedProcess();

  ASSERT_TRUE(std::all_of(
      brave_origins.begin(), brave_origins.end(),
      [chrome_origins](auto& origin) {
        return std::any_of(chrome_origins.begin(), chrome_origins.end(),
                           [origin](auto& other) {
                             return other.IsSameOriginWith(origin);
                           }) ||
#if !BUILDFLAG(IS_ANDROID)
               skus::IsSafeOrigin(origin.GetURL()) ||
#endif  // !BUILDFLAG(IS_ANDROID)
               brave_search::IsAllowedHost(origin.GetURL());
      }));
}

class BraveContentBrowserClientWindowsRecallTest
    : public BraveContentBrowserClientTest {
 public:
#if BUILDFLAG(IS_WIN)
  static void SetUpTestSuite() {
    // We need to setup win version override before TestingBrowserProcess is
    // created.
    g_scoped_win_version = new base::test::ScopedOSInfoOverride(
        base::test::ScopedOSInfoOverride::Type::kWin11Home);
  }

  static void TearDownTestSuite() {
    delete g_scoped_win_version;
    g_scoped_win_version = nullptr;
  }
#endif
};

TEST_F(BraveContentBrowserClientWindowsRecallTest, IsWindowsRecallDisabled) {
  BraveContentBrowserClient client;
#if BUILDFLAG(IS_WIN)
  // Pref is registered.
  EXPECT_TRUE(
      TestingBrowserProcess::GetGlobal()
          ->GetTestingLocalState()
          ->FindPreference(windows_recall::prefs::kWindowsRecallDisabled));
  // Disabled by default on Win11 or newer.
  EXPECT_TRUE(client.IsWindowsRecallDisabled());
#else
  EXPECT_FALSE(client.IsWindowsRecallDisabled());
#endif
}

#if BUILDFLAG(IS_ANDROID) && BUILDFLAG(ENABLE_EXTENSIONS_CORE)
class BraveStoreAuthorViewportTest : public ChromeRenderViewHostTestHarness {
 protected:
  void SetUp() override {
    ChromeRenderViewHostTestHarness::SetUp();
    Settings()->SetDefaultContentSetting(
        ContentSettingsType::REQUEST_DESKTOP_SITE, CONTENT_SETTING_BLOCK);
  }

  HostContentSettingsMap* Settings() {
    return HostContentSettingsMapFactory::GetForProfile(profile());
  }

  void Commit(const GURL& url) {
    // TestWebContents simulates a commit; no URL loader or Store request runs.
    content::WebContentsTester::For(web_contents())->NavigateAndCommit(url);
    blink::UserAgentOverride ua;
    ua.ua_string_override = "ViewportFixture/1";
    ua.ua_metadata_override.emplace();
    ua.ua_metadata_override->mobile = false;
    ua.ua_metadata_override->platform = "ViewportFixture";
    web_contents()->SetUserAgentOverride(ua, false);
    web_contents()->GetController().GetVisibleEntry()->SetIsOverridingUserAgent(
        true);
  }

  bool Apply(bool after_navigation = false) {
    const auto ua = web_contents()->GetUserAgentOverride();
    const auto* entry = web_contents()->GetController().GetVisibleEntry();
    const bool overriding = entry->GetIsOverridingUserAgent();
    const GURL url = web_contents()->GetVisibleURL();
    const auto desktop_setting = Settings()->GetContentSetting(
        url, url, ContentSettingsType::REQUEST_DESKTOP_SITE);
    bool changed = false;
    if (after_navigation) {
      changed = client_.OverrideWebPreferencesAfterNavigation(
          web_contents(), *web_contents()->GetSiteInstance(), &prefs_);
    } else {
      client_.OverrideWebPreferences(web_contents(),
                                    *web_contents()->GetSiteInstance(), &prefs_);
    }
    EXPECT_EQ(ua, web_contents()->GetUserAgentOverride());
    EXPECT_EQ(overriding, entry->GetIsOverridingUserAgent());
    EXPECT_EQ(desktop_setting, Settings()->GetContentSetting(
                                   url, url,
                                   ContentSettingsType::REQUEST_DESKTOP_SITE));
    return changed;
  }

  const GURL store_{"https://chromewebstore.google.com/detail/fixture"};
  BraveContentBrowserClient client_;
  blink::web_pref::WebPreferences prefs_;
};

TEST_F(BraveStoreAuthorViewportTest, NoEntryKeepsNormalMobileDefault) {
  ASSERT_FALSE(web_contents()->GetController().GetVisibleEntry());
  prefs_.viewport_meta_enabled = false;
  client_.OverrideWebPreferences(web_contents(),
                                *web_contents()->GetSiteInstance(), &prefs_);
  EXPECT_TRUE(prefs_.viewport_meta_enabled);
}

TEST_F(BraveStoreAuthorViewportTest, ForeignSiteInstanceUntouched) {
  Commit(store_);
  auto foreign_site = content::SiteInstance::Create(profile());
  ASSERT_NE(foreign_site.get(), web_contents()->GetSiteInstance());
  prefs_.viewport_meta_enabled = false;
  client_.OverrideWebPreferences(web_contents(), *foreign_site, &prefs_);
  EXPECT_FALSE(prefs_.viewport_meta_enabled);
}

TEST_F(BraveStoreAuthorViewportTest, PendingStoreNavigationDoesNotAdmitViewport) {
  const GURL control("https://viewport-control.invalid/");
  Commit(control);
  Apply();
  ASSERT_FALSE(prefs_.viewport_meta_enabled);
  auto navigation =
      content::NavigationSimulator::CreateBrowserInitiated(store_, web_contents());
  navigation->Start();
  ASSERT_EQ(store_, web_contents()->GetVisibleURL());
  ASSERT_EQ(control,
            web_contents()->GetPrimaryMainFrame()->GetLastCommittedURL());
  // Model the selected forced-Store desktop UA without persisting a setting.
  web_contents()->GetController().GetVisibleEntry()->SetIsOverridingUserAgent(
      true);
  Apply();
  EXPECT_FALSE(prefs_.viewport_meta_enabled);
  navigation->Fail(net::ERR_ABORTED);
  ASSERT_EQ(control,
            web_contents()->GetPrimaryMainFrame()->GetLastCommittedURL());
  Apply();
  EXPECT_FALSE(prefs_.viewport_meta_enabled);
}

TEST_F(BraveStoreAuthorViewportTest, AuthorViewportWithoutIdentityChanges) {
  Commit(store_);
  prefs_.viewport_meta_enabled = false;
  Apply();
  EXPECT_TRUE(prefs_.viewport_meta_enabled);
  // Initialize inherited preferences before testing changed returns.
  Apply(true);
  EXPECT_FALSE(Apply(true));
}

TEST_F(BraveStoreAuthorViewportTest, LeavingAndReturningRestoresPolicy) {
  Commit(store_);
  Apply();
  Apply(true);
  Commit(GURL("https://viewport-control.invalid/"));
  EXPECT_TRUE(Apply(true));
  EXPECT_FALSE(prefs_.viewport_meta_enabled);
  EXPECT_FALSE(Apply(true));
  Commit(store_);
  EXPECT_TRUE(Apply(true));
  EXPECT_TRUE(prefs_.viewport_meta_enabled);
}

TEST_F(BraveStoreAuthorViewportTest, ExplicitDesktopAndMobileChoices) {
  Commit(store_);
  Settings()->SetContentSettingDefaultScope(
      store_, store_, ContentSettingsType::REQUEST_DESKTOP_SITE,
      CONTENT_SETTING_ALLOW);
  Apply();
  EXPECT_FALSE(prefs_.viewport_meta_enabled);
  Settings()->SetDefaultContentSetting(
      ContentSettingsType::REQUEST_DESKTOP_SITE, CONTENT_SETTING_ALLOW);
  Settings()->SetContentSettingDefaultScope(
      store_, store_, ContentSettingsType::REQUEST_DESKTOP_SITE,
      CONTENT_SETTING_DEFAULT);
  Apply();
  EXPECT_FALSE(prefs_.viewport_meta_enabled);
  Settings()->SetContentSettingDefaultScope(
      store_, store_, ContentSettingsType::REQUEST_DESKTOP_SITE,
      CONTENT_SETTING_BLOCK);
  Apply();
  EXPECT_TRUE(prefs_.viewport_meta_enabled);
}

TEST_F(BraveStoreAuthorViewportTest, CommandLineDesktopChoice) {
  base::test::ScopedCommandLine command_line;
  command_line.GetProcessCommandLine()->AppendSwitch(
      switches::kRequestDesktopSites);
  Commit(store_);
  Apply();
  EXPECT_FALSE(prefs_.viewport_meta_enabled);
}

TEST_F(BraveStoreAuthorViewportTest, ExactOriginOnly) {
  for (const char* url : {"http://chromewebstore.google.com/",
                          "https://chromewebstore.google.com:444/",
                          "https://child.chromewebstore.google.com/",
                          "https://chromewebstore.google.com.example.invalid/",
                          "https://viewport-control.invalid/"}) {
    SCOPED_TRACE(url);
    Commit(GURL(url));
    prefs_.viewport_meta_enabled = true;
    Apply();
    EXPECT_FALSE(prefs_.viewport_meta_enabled);
  }
}

TEST_F(BraveStoreAuthorViewportTest, NormalMobileAndMissingMetadataDefaults) {
  Commit(GURL("https://viewport-control.invalid/"));
  auto ua = web_contents()->GetUserAgentOverride();
  ua.ua_metadata_override->mobile = true;
  web_contents()->SetUserAgentOverride(ua, false);
  prefs_.viewport_meta_enabled = false;
  Apply();
  EXPECT_TRUE(prefs_.viewport_meta_enabled);
  ua.ua_metadata_override.reset();
  web_contents()->SetUserAgentOverride(ua, false);
  prefs_.viewport_meta_enabled = false;
  Apply();
  EXPECT_TRUE(prefs_.viewport_meta_enabled);
  Commit(GURL("https://viewport-control.invalid/"));
  web_contents()->GetController().GetVisibleEntry()->SetIsOverridingUserAgent(
      false);
  prefs_.viewport_meta_enabled = false;
  Apply();
  EXPECT_TRUE(prefs_.viewport_meta_enabled);
}
#endif
