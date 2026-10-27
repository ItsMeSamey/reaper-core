// Copyright (c) 2026 The Reaper Authors.
// Use of this source code is governed by the MPL 2.0 license.

#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "base/files/file_path.h"
#include "base/functional/bind.h"
#include "base/functional/callback_helpers.h"
#include "base/memory/raw_ptr.h"
#include "base/run_loop.h"
#include "base/strings/stringprintf.h"
#include "base/test/bind.h"
#include "base/test/scoped_feature_list.h"
#include "base/time/time.h"
#include "brave/browser/containers/containers_service_factory.h"
#include "brave/components/containers/core/browser/containers_service.h"
#include "brave/components/containers/core/browser/containers_test_utils.h"
#include "brave/components/containers/core/browser/prefs.h"
#include "brave/components/containers/core/common/features.h"
#include "chrome/browser/extensions/api/cookies/cookies_api.h"
#include "chrome/browser/extensions/api/cookies/cookies_helpers.h"
#include "chrome/browser/extensions/extension_api_unittest.h"
#include "chrome/browser/net/profile_network_context_service.h"
#include "chrome/browser/net/profile_network_context_service_factory.h"
#include "chrome/browser/net/system_network_context_manager.h"
#include "chrome/test/base/testing_browser_process.h"
#include "chrome/test/base/testing_profile.h"
#include "components/variations/scoped_variations_ids_provider.h"
#include "content/public/browser/storage_partition.h"
#include "content/public/browser/storage_partition_config.h"
#include "extensions/browser/api/web_request/web_request_api.h"
#include "extensions/browser/event_router.h"
#include "extensions/browser/event_router_factory.h"
#include "extensions/browser/extension_prefs.h"
#include "extensions/browser/extensions_browser_client.h"
#include "extensions/common/extension_builder.h"
#include "mojo/public/cpp/bindings/receiver.h"
#include "net/cookies/canonical_cookie.h"
#include "net/cookies/cookie_access_result.h"
#include "net/cookies/cookie_options.h"
#include "services/cert_verifier/public/mojom/cert_verifier_service_factory.mojom.h"
#include "services/network/public/mojom/cookie_manager.mojom.h"
#include "services/network/public/mojom/network_context.mojom.h"
#include "services/network/test/test_network_context.h"
#include "testing/gmock/include/gmock/gmock.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace extensions {
namespace {
constexpr char kWork[] = "19ac4640-1744-4e25-b809-2ec33cc647de";
constexpr char kPersonal[] = "9ea44b48-f0d7-4659-b454-27ad6e5f8f24";
constexpr char kRestored[] = "e929c897-2703-4cf8-ae4c-3d387c2da1c5";

class CookieBroadcastObserver : public EventRouter::TestObserver {
 public:
  explicit CookieBroadcastObserver(EventRouter* router) : router_(router) {
    router_->AddObserverForTesting(this);
  }
  ~CookieBroadcastObserver() override {
    router_->RemoveObserverForTesting(this);
  }
  void OnWillDispatchEvent(const Event&) override {}
  void OnDidDispatchEventToProcess(const Event&, int) override {}
  void OnWillBroadcastEvent(const Event& event) override {
    if (event.event_name == api::cookies::OnChanged::kEventName) {
      changes.push_back(event.args().Clone());
    }
  }
  std::vector<base::ListValue> changes;

 private:
  raw_ptr<EventRouter> router_;
};
class RecordingNetworkContext : public network::TestNetworkContext {
 public:
  void ResetURLLoaderFactories() override { ++reset_count; }
  int reset_count = 0;
  mojo::Receiver<network::mojom::NetworkContext> receiver{this};
};

class SessionCookieTestingProfile : public TestingProfile {
 public:
  bool ShouldRestoreOldSessionCookies() override { return true; }
  bool ShouldPersistSessionCookies() const override { return true; }
};
}  // namespace

class AndroidContainerCookiesTest : public ExtensionApiUnittest {
 protected:
  void SetUp() override {
    features_.InitAndEnableFeature(containers::features::kContainers);
    ContainersServiceFactory::GetInstance();
    ExtensionApiUnittest::SetUp();
    // The profile factory is null in tests unless a real router is installed.
    // Keep BroadcastEvent and its TestObserver delivery on the production path.
    EventRouterFactory::GetInstance()->SetTestingFactoryAndUse(
        profile(),
        base::BindRepeating([](content::BrowserContext* context)
                                -> std::unique_ptr<KeyedService> {
          return std::make_unique<EventRouter>(context,
                                               ExtensionPrefs::Get(context));
        }));
    ASSERT_NE(nullptr, EventRouter::Get(profile()));
    ContainersServiceFactory::GetInstance()->SetTestingFactoryAndUse(
        profile(),
        base::BindRepeating(
            [](AndroidContainerCookiesTest* fixture,
               content::BrowserContext* context)
                -> std::unique_ptr<KeyedService> {
              auto delegate = std::make_unique<testing::NiceMock<
                  containers::MockContainersServiceDelegate>>();
              // Exercise cookie routing while asynchronous orphan discovery is
              // outstanding, rather than making the fixture hide that race.
              delegate->set_defer_referenced_container_ids_callback(true);
              fixture->delegate_ = delegate.get();
              return std::make_unique<containers::ContainersService>(
                  Profile::FromBrowserContext(context)->GetPrefs(), false,
                  std::move(delegate));
            },
            base::Unretained(this)));
    std::vector<containers::mojom::ContainerPtr> configured;
    configured.push_back(containers::MakeContainer(kWork, "Work"));
    configured.push_back(containers::MakeContainer(kPersonal, "Personal"));
    containers::SetContainersToPrefs(configured, *profile()->GetPrefs());
    set_extension(ExtensionBuilder("Container cookie permissions")
                      .SetManifestVersion(3)
                      .AddAPIPermission("cookies")
                      .AddHostPermission("http://example.test/*")
                      .Build());
  }

  void TearDown() override {
    delegate_ = nullptr;
    ExtensionApiUnittest::TearDown();
  }

  std::string Store(const std::string& id) {
    return cookies_helpers::GetContainerStoreId(id);
  }
  std::string Details(const std::string& store) {
    return base::StringPrintf(
        R"([{"url":"http://example.test/","name":"jar","storeId":"%s"}])",
        store.c_str());
  }
  void SetJar(const std::string& store,
              const std::string& value,
              const std::string& name = "jar") {
    auto result = RunFunctionAndReturnDictionary(
        base::MakeRefCounted<CookiesSetFunction>(),
        base::StringPrintf(
            R"([{"url":"http://example.test/","name":"%s","value":"%s","storeId":"%s"}])",
            name.c_str(), value.c_str(), store.c_str()));
    ASSERT_TRUE(result);
    ASSERT_TRUE(result->FindString("storeId"));
    ASSERT_TRUE(result->FindString("value"));
    EXPECT_EQ(store, *result->FindString("storeId"));
    EXPECT_EQ(value, *result->FindString("value"));
  }
  std::string Jar(const std::string& store) {
    auto result = RunFunctionAndReturnDictionary(
        base::MakeRefCounted<CookiesGetFunction>(), Details(store));
    if (!result || !result->FindString("value")) {
      ADD_FAILURE() << "Expected a cookie from " << store;
      return std::string();
    }
    EXPECT_EQ(store, *result->FindString("storeId"));
    return *result->FindString("value");
  }
  void FlushCookieEvents() {
    profile()->ForEachLoadedStoragePartition(
        [](content::StoragePartition* partition) {
          partition->FlushNetworkInterfaceForTesting();
        });
    base::RunLoop().RunUntilIdle();
  }
  raw_ptr<containers::MockContainersServiceDelegate> delegate_ = nullptr;
  base::test::ScopedFeatureList features_;
};

TEST_F(AndroidContainerCookiesTest, SetGetAndRemoveUseThreeRealJars) {
  SetJar("0", "default");
  SetJar(Store(kWork), "work");
  SetJar(Store(kPersonal), "personal");
  EXPECT_EQ("default", Jar("0"));
  EXPECT_EQ("work", Jar(Store(kWork)));
  EXPECT_EQ("personal", Jar(Store(kPersonal)));
  auto removed = RunFunctionAndReturnDictionary(
      base::MakeRefCounted<CookiesRemoveFunction>(), Details(Store(kWork)));
  ASSERT_TRUE(removed);
  EXPECT_EQ(Store(kWork), *removed->FindString("storeId"));
  auto missing = RunFunctionAndReturnValue(
      base::MakeRefCounted<CookiesGetFunction>(), Details(Store(kWork)));
  ASSERT_TRUE(missing);
  EXPECT_TRUE(missing->is_none());
  EXPECT_EQ("default", Jar("0"));
  EXPECT_EQ("personal", Jar(Store(kPersonal)));
}

TEST_F(AndroidContainerCookiesTest,
       DefaultStoreRepliesUseCanonicalIdForAbsentAndEmptyInputs) {
  for (const std::string& store_argument :
       std::vector<std::string>{"", R"(,"storeId":"")"}) {
    SCOPED_TRACE(store_argument);
    auto set = RunFunctionAndReturnDictionary(
        base::MakeRefCounted<CookiesSetFunction>(),
        base::StringPrintf(
            R"([{"url":"http://example.test/","name":"jar","value":"default"%s}])",
            store_argument.c_str()));
    ASSERT_TRUE(set);
    ASSERT_TRUE(set->FindString("storeId"));
    EXPECT_EQ("0", *set->FindString("storeId"));

    const std::string details = base::StringPrintf(
        R"([{"url":"http://example.test/","name":"jar"%s}])",
        store_argument.c_str());
    auto get = RunFunctionAndReturnDictionary(
        base::MakeRefCounted<CookiesGetFunction>(), details);
    ASSERT_TRUE(get);
    ASSERT_TRUE(get->FindString("storeId"));
    ASSERT_TRUE(get->FindString("value"));
    EXPECT_EQ("0", *get->FindString("storeId"));
    EXPECT_EQ("default", *get->FindString("value"));

    auto all = RunFunctionAndReturnList(
        base::MakeRefCounted<CookiesGetAllFunction>(), details);
    ASSERT_TRUE(all);
    ASSERT_EQ(1u, all->size());
    ASSERT_TRUE((*all)[0].is_dict());
    ASSERT_TRUE((*all)[0].GetDict().FindString("storeId"));
    EXPECT_EQ("0", *(*all)[0].GetDict().FindString("storeId"));

    auto removed = RunFunctionAndReturnDictionary(
        base::MakeRefCounted<CookiesRemoveFunction>(), details);
    ASSERT_TRUE(removed);
    ASSERT_TRUE(removed->FindString("storeId"));
    EXPECT_EQ("0", *removed->FindString("storeId"));
  }
}

TEST_F(AndroidContainerCookiesTest, GetAllOnlyReadsRequestedContainer) {
  SetJar("0", "default");
  SetJar(Store(kWork), "work");
  SetJar(Store(kPersonal), "personal");
  auto values = RunFunctionAndReturnList(
      base::MakeRefCounted<CookiesGetAllFunction>(),
      base::StringPrintf(R"([{"storeId":"%s"}])", Store(kWork).c_str()));
  ASSERT_TRUE(values);
  ASSERT_EQ(1u, values->size());
  EXPECT_EQ("work", *(*values)[0].GetDict().FindString("value"));
  EXPECT_EQ(Store(kWork), *(*values)[0].GetDict().FindString("storeId"));
}

TEST_F(AndroidContainerCookiesTest, InvalidStoreNeverFallsBackToDefaultJar) {
  SetJar("0", "private-default");
  const std::vector<std::string> invalid_stores = {
      "container:", "container:not-a-uuid",
      "container:19AC4640-1744-4E25-B809-2EC33CC647DE",
      "container:" + std::string(kRestored), "2"};
  for (const std::string& store : invalid_stores) {
    EXPECT_EQ("Invalid cookie store id: \"" + store + "\".",
              RunFunctionAndReturnError(
                  base::MakeRefCounted<CookiesGetFunction>(), Details(store)));
  }
  EXPECT_EQ("private-default", Jar("0"));
}

TEST_F(AndroidContainerCookiesTest, OffTheRecordCannotReachRegularContainer) {
  auto* otr = profile()->GetPrimaryOTRProfile(true);
  ASSERT_TRUE(otr);
  for (bool include_incognito : {false, true}) {
    EXPECT_EQ(nullptr, cookies_helpers::ChooseStoragePartitionFromStoreId(
                           Store(kWork), otr, include_incognito));
    EXPECT_EQ(nullptr, cookies_helpers::ChooseStoragePartitionFromStoreId(
                           "0", otr, include_incognito));
  }
  EXPECT_EQ(nullptr, cookies_helpers::ChooseStoragePartitionFromStoreId(
                         "1", profile(), false));
  EXPECT_EQ(
      otr->GetDefaultStoragePartition(),
      cookies_helpers::ChooseStoragePartitionFromStoreId("1", profile(), true));
}

TEST_F(AndroidContainerCookiesTest, RemovedButUsedStoreSurvivesDiscovery) {
  auto* service = ContainersServiceFactory::GetForProfile(profile());
  ASSERT_TRUE(service);
  service->MarkContainerUsed(kWork);
  containers::SetContainersToPrefs({}, *profile()->GetPrefs());
  service->ScheduleOrphanedContainersCleanupForTesting();
  EXPECT_TRUE(service->GetUsedContainerIds().empty());
  EXPECT_THAT(service->GetRuntimeContainerIds(), testing::Contains(kWork));
  SetJar(Store(kWork), "still-used");
  EXPECT_EQ("still-used", Jar(Store(kWork)));
}

TEST_F(AndroidContainerCookiesTest, CookieEventsIdentifyActualStore) {
  CookieBroadcastObserver observer(EventRouter::Get(profile()));
  CookiesEventRouter router(profile());
  SetJar("0", "default");
  SetJar(Store(kWork), "work");
  SetJar(Store(kPersonal), "personal");
  // This used snapshot did not exist when the router was created. Its pref
  // change must attach a listener before the first container cookie write.
  ContainersServiceFactory::GetForProfile(profile())->MarkContainerUsed(
      kRestored);
  SetJar(Store(kRestored), "restored");
  FlushCookieEvents();
  std::vector<std::string> stores;
  for (const auto& args : observer.changes) {
    ASSERT_EQ(1u, args.size());
    const auto* cookie = args[0].GetDict().FindDict("cookie");
    ASSERT_TRUE(cookie);
    ASSERT_TRUE(cookie->FindString("storeId"));
    stores.push_back(*cookie->FindString("storeId"));
  }
  EXPECT_THAT(stores,
              testing::UnorderedElementsAre("0", Store(kWork), Store(kPersonal),
                                            Store(kRestored)));
}

TEST_F(AndroidContainerCookiesTest,
       MalformedConfiguredIdsNeverDuplicateDefaultCookieEvents) {
  // Write the genuine persisted records, including the formerly accepted empty
  // ID. Both existing records and subsequent pref notifications reach the real
  // service/router; a default-manager mutation must produce exactly one event.
  std::vector<containers::mojom::ContainerPtr> configured;
  configured.push_back(containers::MakeContainer("", "Invalid default alias"));
  configured.push_back(containers::MakeContainer("not-a-uuid", "Invalid jar"));
  configured.push_back(containers::MakeContainer(kWork, "Work"));
  containers::SetContainersToPrefs(configured, *profile()->GetPrefs());
  CookieBroadcastObserver observer(EventRouter::Get(profile()));
  CookiesEventRouter router(profile());
  SetJar("0", "before-pref-refresh");
  FlushCookieEvents();
  ASSERT_EQ(1u, observer.changes.size());
  ASSERT_EQ(1u, observer.changes[0].size());
  const auto* initial_cookie =
      observer.changes[0][0].GetDict().FindDict("cookie");
  ASSERT_TRUE(initial_cookie);
  ASSERT_TRUE(initial_cookie->FindString("storeId"));
  EXPECT_EQ("0", *initial_cookie->FindString("storeId"));
  configured.push_back(containers::MakeContainer("../default", "Invalid path"));
  containers::SetContainersToPrefs(configured, *profile()->GetPrefs());
  SetJar("0", "after-pref-refresh", "after-refresh");
  FlushCookieEvents();
  for (const auto& args : observer.changes) {
    ASSERT_EQ(1u, args.size());
    const auto* cookie = args[0].GetDict().FindDict("cookie");
    ASSERT_TRUE(cookie);
    ASSERT_TRUE(cookie->FindString("storeId"));
    EXPECT_EQ("0", *cookie->FindString("storeId"));
  }
  // Each fresh default-manager cookie insertion must be broadcast exactly once.
  // A duplicate listener would double events and invent a container: store.
  EXPECT_EQ(2u, observer.changes.size());
}

TEST_F(AndroidContainerCookiesTest,
       PendingRemovalSuppressesActualCookieEventsAndApiAccess) {
  CookieBroadcastObserver observer(EventRouter::Get(profile()));
  CookiesEventRouter router(profile());
  SetJar(Store(kWork), "before-removal");
  FlushCookieEvents();
  ASSERT_EQ(1u, observer.changes.size());
  auto* partition = cookies_helpers::ChooseStoragePartitionFromStoreId(
      Store(kWork), profile(), false);
  ASSERT_TRUE(partition);
  auto* manager = partition->GetCookieManagerForBrowserProcess();
  auto* service = ContainersServiceFactory::GetForProfile(profile());
  ASSERT_TRUE(service);
  ASSERT_TRUE(delegate_);
  delegate_->set_defer_delete_container_storage_callback(true);
  service->MarkContainerUsed(kWork);
  std::vector<containers::mojom::ContainerPtr> configured;
  configured.push_back(containers::MakeContainer(kPersonal, "Personal"));
  containers::SetContainersToPrefs(configured, *profile()->GetPrefs());
  service->ScheduleOrphanedContainersCleanupForTesting();
  delegate_->SetReferencedContainersIds({});
  delegate_->RunDeferredReferencedContainerIdsCallback();
  EXPECT_THAT(delegate_->delete_requests(), testing::Contains(kWork));
  EXPECT_THAT(service->GetRuntimeContainerIds(),
              testing::Not(testing::Contains(kWork)));
  EXPECT_EQ(nullptr, cookies_helpers::ChooseStoragePartitionFromStoreId(
                         Store(kWork), profile(), false));
  EXPECT_EQ(
      "Invalid cookie store id: \"" + Store(kWork) + "\".",
      RunFunctionAndReturnError(base::MakeRefCounted<CookiesGetFunction>(),
                                Details(Store(kWork))));
  // The real manager of the already loaded jar remains alive while deletion
  // is outstanding. Its actual cookie mutation must not escape through a stale
  // listener or restore API access merely because the partition is still
  // loaded.
  const GURL url("http://example.test/");
  auto cookie = net::CanonicalCookie::CreateForTesting(
      url, "jar=during-removal; Path=/", base::Time::Now(),
      net::CookieSourceType::kOther);
  ASSERT_TRUE(cookie);
  base::RunLoop write;
  manager->SetCanonicalCookie(
      *cookie, url, net::CookieOptions::MakeAllInclusive(),
      base::BindLambdaForTesting([&](net::CookieAccessResult result) {
        EXPECT_TRUE(result.status.IsInclude());
        write.Quit();
      }));
  write.Run();
  FlushCookieEvents();
  EXPECT_EQ(1u, observer.changes.size());
  SetJar("0", "default-still-routes");
  FlushCookieEvents();
  ASSERT_EQ(2u, observer.changes.size());
  const auto* default_cookie =
      observer.changes.back()[0].GetDict().FindDict("cookie");
  ASSERT_TRUE(default_cookie);
  ASSERT_TRUE(default_cookie->FindString("storeId"));
  EXPECT_EQ("0", *default_cookie->FindString("storeId"));
  EXPECT_EQ("default-still-routes", Jar("0"));
  delegate_->RunDeferredDeleteContainerStorageCallback(kWork);
}

TEST_F(AndroidContainerCookiesTest, TemporaryContainerUsesPersistentLocalJar) {
  auto* service = ContainersServiceFactory::GetForProfile(profile());
  ASSERT_TRUE(service);
  auto container = service->CreateAndPersistTemporaryContainer();
  ASSERT_TRUE(container);
  ASSERT_TRUE(container->id.starts_with("t-"));
  EXPECT_THAT(service->GetRuntimeContainerIds(),
              testing::Contains(container->id));
  SetJar(Store(container->id), "temporary");
  EXPECT_EQ("temporary", Jar(Store(container->id)));
  auto* partition = cookies_helpers::ChooseStoragePartitionFromStoreId(
      Store(container->id), profile(), false);
  ASSERT_TRUE(partition);
  EXPECT_FALSE(partition->GetConfig().in_memory());
  EXPECT_EQ("containers", partition->GetConfig().partition_domain());
  EXPECT_EQ(container->id, partition->GetConfig().partition_name());
}

TEST_F(AndroidContainerCookiesTest,
       HostPermissionsStillProtectContainerCookies) {
  SetJar(Store(kWork), "work");
  set_extension(ExtensionBuilder("No cookie host grant")
                    .SetManifestVersion(3)
                    .AddAPIPermission("cookies")
                    .Build());
  EXPECT_EQ(
      "No host permissions for cookies at url: \"http://example.test/\".",
      RunFunctionAndReturnError(base::MakeRefCounted<CookiesGetFunction>(),
                                Details(Store(kWork))));
  auto list = RunFunctionAndReturnList(
      base::MakeRefCounted<CookiesGetAllFunction>(),
      base::StringPrintf(R"([{"storeId":"%s"}])", Store(kWork).c_str()));
  ASSERT_TRUE(list);
  EXPECT_TRUE(list->empty());
}
TEST_F(AndroidContainerCookiesTest, WebRequestResetReachesEveryLoadedJar) {
  std::vector<content::StoragePartition*> partitions;
  for (const std::string& store :
       std::vector<std::string>{"0", Store(kWork), Store(kPersonal)}) {
    auto* partition = cookies_helpers::ChooseStoragePartitionFromStoreId(
        store, profile(), false);
    ASSERT_TRUE(partition);
    partitions.push_back(partition);
  }
  auto* primary_otr = profile()->GetPrimaryOTRProfile(true);
  auto* secondary_otr = profile()->GetOffTheRecordProfile(
      Profile::OTRProfileID::CreateUniqueForTesting(), true);
  ASSERT_TRUE(primary_otr);
  ASSERT_TRUE(secondary_otr);
  ASSERT_NE(primary_otr, secondary_otr);
  partitions.push_back(primary_otr->GetDefaultStoragePartition());
  partitions.push_back(secondary_otr->GetDefaultStoragePartition());
  for (size_t first = 0; first < partitions.size(); ++first) {
    for (size_t second = first + 1; second < partitions.size(); ++second) {
      ASSERT_NE(partitions[first], partitions[second]);
    }
  }
  EXPECT_THAT(
      ExtensionsBrowserClient::Get()->GetRelatedContextsForFactoryReset(
          profile()),
      testing::UnorderedElementsAre(profile(), primary_otr, secondary_otr));
  std::vector<std::unique_ptr<RecordingNetworkContext>> contexts;
  for (auto* partition : partitions) {
    auto context = std::make_unique<RecordingNetworkContext>();
    partition->SetNetworkContextForTesting(
        context->receiver.BindNewPipeAndPassRemote());
    contexts.push_back(std::move(context));
  }
  // Use the actual redirected factory, not a standalone API object. Both
  // OTR profiles share the original service, which must invalidate every
  // existing related network context without changing incognito permissions.
  WebRequestAPI::GetFactoryInstance()->SetTestingFactoryAndUse(
      profile(), base::BindRepeating([](content::BrowserContext* context)
                                         -> std::unique_ptr<KeyedService> {
        return std::make_unique<WebRequestAPI>(context);
      }));
  auto* api = BrowserContextKeyedAPIFactory<WebRequestAPI>::Get(profile());
  ASSERT_TRUE(api);
  EXPECT_EQ(api,
            BrowserContextKeyedAPIFactory<WebRequestAPI>::Get(primary_otr));
  EXPECT_EQ(api,
            BrowserContextKeyedAPIFactory<WebRequestAPI>::Get(secondary_otr));
  ASSERT_FALSE(api->MayHaveProxies());
  api->ForceProxyForTesting();
  for (const auto& context : contexts) {
    context->receiver.FlushForTesting();
    EXPECT_EQ(1, context->reset_count);
  }
}

TEST_F(AndroidContainerCookiesTest,
       FactoryResetDoesNotCreateOffTheRecordProfile) {
  ASSERT_TRUE(profile()->GetAllOffTheRecordProfiles().empty());
  EXPECT_THAT(ExtensionsBrowserClient::Get()->GetRelatedContextsForFactoryReset(
                  profile()),
              testing::ElementsAre(profile()));
  RecordingNetworkContext context;
  profile()->GetDefaultStoragePartition()->SetNetworkContextForTesting(
      context.receiver.BindNewPipeAndPassRemote());
  WebRequestAPI::GetFactoryInstance()->SetTestingFactoryAndUse(
      profile(),
      base::BindRepeating([](content::BrowserContext* browser_context)
                              -> std::unique_ptr<KeyedService> {
        return std::make_unique<WebRequestAPI>(browser_context);
      }));
  auto* api = BrowserContextKeyedAPIFactory<WebRequestAPI>::Get(profile());
  ASSERT_TRUE(api);
  ASSERT_FALSE(api->MayHaveProxies());
  api->ForceProxyForTesting();
  context.receiver.FlushForTesting();
  EXPECT_EQ(1, context.reset_count);
  EXPECT_TRUE(profile()->GetAllOffTheRecordProfiles().empty());
}

TEST_F(AndroidContainerCookiesTest,
       SessionCookiePolicyOnlyFollowsContainerDomain) {
  variations::test::ScopedVariationsIdsProvider scoped_variations_ids_provider(
      variations::VariationsIdsProvider::Mode::kUseSignedInState);
  auto* browser_process = TestingBrowserProcess::GetGlobal();
  ASSERT_FALSE(SystemNetworkContextManager::HasInstance());
  ASSERT_EQ(nullptr, browser_process->system_network_context_manager());
  browser_process->SetSystemNetworkContextManagerForTesting(
      SystemNetworkContextManager::CreateInstance(
          browser_process->local_state()));
  // Declared before the profile so its network services are destroyed first.
  base::ScopedClosureRunner reset_network_manager(base::BindOnce(
      [](TestingBrowserProcess* browser_process) {
        browser_process->SetSystemNetworkContextManagerForTesting(nullptr);
        SystemNetworkContextManager::DeleteInstance();
      },
      base::Unretained(browser_process)));
  SessionCookieTestingProfile session_profile;
  ContainersServiceFactory::GetInstance()->SetTestingFactoryAndUse(
      &session_profile,
      base::BindRepeating([](content::BrowserContext* context)
                              -> std::unique_ptr<KeyedService> {
        auto delegate = std::make_unique<
            testing::NiceMock<containers::MockContainersServiceDelegate>>();
        delegate->set_defer_referenced_container_ids_callback(true);
        return std::make_unique<containers::ContainersService>(
            Profile::FromBrowserContext(context)->GetPrefs(), false,
            std::move(delegate));
      }));
  std::vector<containers::mojom::ContainerPtr> named;
  named.push_back(containers::MakeContainer(kWork, "Work"));
  containers::SetContainersToPrefs(named, *session_profile.GetPrefs());
  auto* containers_service =
      ContainersServiceFactory::GetForProfile(&session_profile);
  ASSERT_TRUE(containers_service);
  auto temporary = containers_service->CreateAndPersistTemporaryContainer();
  ASSERT_TRUE(temporary);
  // This service is null by default for TestingProfile; use the real service.
  ProfileNetworkContextServiceFactory::GetInstance()->SetTestingFactoryAndUse(
      &session_profile,
      base::BindRepeating([](content::BrowserContext* context)
                              -> std::unique_ptr<KeyedService> {
        return std::make_unique<ProfileNetworkContextService>(
            Profile::FromBrowserContext(context));
      }));
  auto* service =
      ProfileNetworkContextServiceFactory::GetForContext(&session_profile);
  ASSERT_TRUE(service);
  for (const std::string& id : std::vector<std::string>{kWork, temporary->id}) {
    SCOPED_TRACE(id);
    auto* partition = cookies_helpers::ChooseStoragePartitionFromStoreId(
        Store(id), &session_profile, false);
    ASSERT_TRUE(partition);
    EXPECT_EQ("containers", partition->GetConfig().partition_domain());
    EXPECT_EQ(id, partition->GetConfig().partition_name());
    EXPECT_FALSE(partition->GetConfig().in_memory());
    base::FilePath relative;
    ASSERT_TRUE(session_profile.GetPath().AppendRelativePath(
        partition->GetPath(), &relative));
    network::mojom::NetworkContextParams params;
    cert_verifier::mojom::CertVerifierCreationParams verifier;
    service->ConfigureNetworkContextParams(false, relative, &params, &verifier);
    EXPECT_TRUE(params.restore_old_session_cookies);
    EXPECT_TRUE(params.persist_session_cookies);
  }
  // Obtain another nondefault path from Content too; the same hash shape must
  // not be enough to make an extension/webview domain inherit session policy.
  auto* unrelated = session_profile.GetStoragePartition(
      content::StoragePartitionConfig::Create(
          &session_profile, "another-extension", kWork, false));
  ASSERT_TRUE(unrelated);
  base::FilePath unrelated_relative;
  ASSERT_TRUE(session_profile.GetPath().AppendRelativePath(
      unrelated->GetPath(), &unrelated_relative));
  network::mojom::NetworkContextParams unrelated_params;
  cert_verifier::mojom::CertVerifierCreationParams unrelated_verifier;
  service->ConfigureNetworkContextParams(
      false, unrelated_relative, &unrelated_params, &unrelated_verifier);
  EXPECT_FALSE(unrelated_params.restore_old_session_cookies);
  EXPECT_FALSE(unrelated_params.persist_session_cookies);
  const std::vector<std::pair<std::string, bool>> cases = {
      {"", true},
      {"Storage/ext/containers/19AC46401744", true},
      {"Storage/ext/another-extension/19AC46401744", false},
      {"Storage/ext/containers/def", false},
      {"Storage/ext/containers/19AC4640174z", false},
      {"Storage/ext/containers/19AC46401744/extra", false},
      {"other/Storage/ext/containers/19AC46401744", false}};
  for (const auto& [path, expected] : cases) {
    SCOPED_TRACE(path);
    network::mojom::NetworkContextParams params;
    cert_verifier::mojom::CertVerifierCreationParams verifier;
    service->ConfigureNetworkContextParams(
        false, base::FilePath::FromUTF8Unsafe(path), &params, &verifier);
    EXPECT_EQ(expected, params.restore_old_session_cookies);
    EXPECT_EQ(expected, params.persist_session_cookies);
  }
  network::mojom::NetworkContextParams in_memory_params;
  cert_verifier::mojom::CertVerifierCreationParams verifier;
  service->ConfigureNetworkContextParams(
      true,
      base::FilePath::FromUTF8Unsafe("Storage/ext/containers/19AC46401744"),
      &in_memory_params, &verifier);
  EXPECT_FALSE(in_memory_params.restore_old_session_cookies);
  EXPECT_FALSE(in_memory_params.persist_session_cookies);
}
}  // namespace extensions
