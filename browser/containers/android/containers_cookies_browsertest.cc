// Copyright (c) 2026 The Reaper Authors.
// Use of this source code is governed by the MPL 2.0 license.

#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/containers/flat_set.h"
#include "base/functional/bind.h"
#include "base/functional/callback_helpers.h"
#include "base/memory/raw_ptr.h"
#include "base/memory/weak_ptr.h"
#include "base/strings/string_number_conversions.h"
#include "base/test/run_until.h"
#include "base/test/scoped_command_line.h"
#include "base/test/scoped_feature_list.h"
#include "base/uuid.h"
#include "brave/browser/containers/android/containers_bridge.h"
#include "brave/browser/containers/containers_service_factory.h"
#include "brave/components/containers/core/browser/containers_service.h"
#include "brave/components/containers/core/browser/containers_settings_handler.h"
#include "brave/components/containers/core/browser/containers_test_utils.h"
#include "brave/components/containers/core/browser/prefs.h"
#include "brave/components/containers/core/common/features.h"
#include "chrome/browser/android/tab_android.h"
#include "chrome/browser/content_settings/host_content_settings_map_factory.h"
#include "chrome/browser/extensions/api/cookies/cookies_api.h"
#include "chrome/browser/extensions/api/cookies/cookies_helpers.h"
#include "chrome/browser/extensions/api/tabs/tabs_api.h"
#include "chrome/browser/extensions/browser_extension_window_controller.h"
#include "chrome/browser/extensions/chrome_test_extension_loader.h"
#include "chrome/browser/extensions/extension_tab_util.h"
#include "chrome/browser/extensions/window_controller.h"
#include "chrome/browser/extensions/window_controller_list.h"
#include "chrome/browser/profiles/profile.h"
#include "chrome/browser/ui/android/tab_model/tab_model.h"
#include "chrome/browser/ui/android/tab_model/tab_model_list.h"
#include "chrome/browser/ui/android/tab_model/tab_model_test_helper.h"
#include "chrome/browser/ui/browser_window/public/browser_window_interface.h"
#include "chrome/browser/ui/browser_window/public/create_browser_window.h"
#include "chrome/common/webui_url_constants.h"
#include "chrome/test/base/android/android_browser_test.h"
#include "chrome/test/base/chrome_test_utils.h"
#include "components/blocked_content/popup_blocker_tab_helper.h"
#include "components/content_settings/core/browser/host_content_settings_map.h"
#include "content/public/browser/page_navigator.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/web_contents.h"
#include "content/public/test/browser_test.h"
#include "content/public/test/browser_test_utils.h"
#include "content/public/test/test_navigation_observer.h"
#include "extensions/browser/api_test_utils.h"
#include "extensions/common/extension_builder.h"
#include "extensions/common/mojom/context_type.mojom.h"
#include "extensions/test/test_extension_dir.h"
#include "net/test/embedded_test_server/embedded_test_server.h"
#include "testing/gmock/include/gmock/gmock.h"
#include "testing/gtest/include/gtest/gtest.h"
#include "ui/android/window_android.h"
#include "ui/base/base_window.h"
#include "ui/base/page_transition_types.h"
#include "ui/base/window_open_disposition.h"

namespace extensions {
namespace {
constexpr char kWork[] = "19ac4640-1744-4e25-b809-2ec33cc647de";
// Intentionally borrow an already registered window identity to test the API
// boundary. Production headless/archive models do not own a UI window; using
// real native window/tab IDs here ensures registry membership alone cannot
// accidentally grant them metadata access. No substitute enumerator is used.
class IneligibleMetadataModel final : public TestTabModel {
 public:
  IneligibleMetadataModel(TabAndroid* tab, TabModelType type)
      : TestTabModel(tab->GetProfile(),
                     chrome::android::ActivityType::kTabbed,
                     type),
        tab_(tab) {
    SetSessionId(tab->GetWindowId());
    TabModelList::AddTabModel(this);
  }
  ~IneligibleMetadataModel() override { TabModelList::RemoveTabModel(this); }
  int GetTabCount() const override { return 1; }
  TabAndroid* GetTabAt(int index) const override {
    return index == 0 ? tab_.get() : nullptr;
  }
  bool IsThisTabListEditable() override { return false; }

 private:
  raw_ptr<TabAndroid> tab_;
};
}  // namespace

class AndroidContainerCookieStoresBrowserTest : public AndroidBrowserTest {
 protected:
  AndroidContainerCookieStoresBrowserTest() {
    features_.InitFromCommandLine(
        std::string(containers::features::kContainers.name) +
            ",EnableBrowserWindowInterfaceForCustomTabActivity",
        "LoadAllTabsAtStartup");
  }

  void SetUpOnMainThread() override {
    AndroidBrowserTest::SetUpOnMainThread();
    ASSERT_TRUE(embedded_test_server()->Start());
    std::vector<containers::mojom::ContainerPtr> configured;
    configured.push_back(containers::MakeContainer(kWork, "Work"));
    containers::SetContainersToPrefs(configured, *GetProfile()->GetPrefs());
  }

  base::android::ScopedJavaLocalRef<jobject> WindowCreatorTracker(JNIEnv* env) {
    auto factory =
        base::android::GetClass(env,
                                "org/chromium/chrome/browser/ui/browser_window/"
                                "ChromeAndroidTaskTrackerFactory");
    auto get =
        env->GetStaticMethodID(factory.obj(), "getInstance",
                               "()Lorg/chromium/chrome/browser/ui/"
                               "browser_window/ChromeAndroidTaskTracker;");
    base::android::CheckException(env);
    return base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallStaticObjectMethod(factory.obj(), get));
  }

  std::vector<int> HeldWindowRequestIds(
      JNIEnv* env,
      const base::android::JavaRef<jobject>& tracker) {
    auto cls = base::android::ScopedJavaLocalRef<jclass>::Adopt(
        env, env->GetObjectClass(tracker.obj()));
    auto get =
        env->GetMethodID(cls.obj(), "getHeldPendingTaskIdsForTesting", "()[I");
    auto array = base::android::ScopedJavaLocalRef<jintArray>::Adopt(
        env, static_cast<jintArray>(env->CallObjectMethod(tracker.obj(), get)));
    base::android::CheckException(env);
    const jsize length = env->GetArrayLength(array.obj());
    std::vector<int> ids(length);
    if (length != 0)
      env->GetIntArrayRegion(array.obj(), 0, length, ids.data());
    base::android::CheckException(env);
    return ids;
  }

  void CallWindowRequestMethod(JNIEnv* env,
                               const base::android::JavaRef<jobject>& tracker,
                               const char* name,
                               int id) {
    auto cls = base::android::ScopedJavaLocalRef<jclass>::Adopt(
        env, env->GetObjectClass(tracker.obj()));
    auto method = env->GetMethodID(cls.obj(), name, "(I)V");
    base::android::CheckException(env);
    env->CallVoidMethod(tracker.obj(), method, id);
    base::android::CheckException(env);
  }

  void DestroyOnlyPendingWindowRequest(
      JNIEnv* env,
      const base::android::JavaRef<jobject>& tracker,
      int id) {
    auto cls = base::android::ScopedJavaLocalRef<jclass>::Adopt(
        env, env->GetObjectClass(tracker.obj()));
    auto get = env->GetMethodID(cls.obj(), "getPendingTaskForTesting",
                                "(I)Lorg/chromium/chrome/browser/ui/"
                                "browser_window/ChromeAndroidTaskImpl;");
    auto task = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallObjectMethod(tracker.obj(), get, id));
    base::android::CheckException(env);
    if (!task.is_null()) {
      auto task_class = base::android::ScopedJavaLocalRef<jclass>::Adopt(
          env, env->GetObjectClass(task.obj()));
      auto destroy = env->GetMethodID(task_class.obj(), "destroy", "()V");
      env->CallVoidMethod(task.obj(), destroy);
      base::android::CheckException(env);
    }
    // Cancel first, so resuming merely releases the testing pause. Other
    // Activity/window owners are never selected as a fallback.
    CallWindowRequestMethod(env, tracker,
                            "resumePendingTaskActivityCreationForTesting", id);
  }

  void VerifyActualTrustedPopupCookieStore(bool incognito) {
    auto* source = TabAndroid::FromWebContents(
        chrome_test_utils::GetActiveWebContents(this));
    ASSERT_TRUE(source);
    const int source_id = ExtensionTabUtil::GetTabId(*source);
    const int source_window_id = source->GetWindowId().id();
    Profile* profile =
        incognito ? GetProfile()->GetPrimaryOTRProfile(true) : GetProfile();
    ASSERT_TRUE(profile);
    JNIEnv* env = base::android::AttachCurrentThread();
    auto tracker = WindowCreatorTracker(env);
    ASSERT_TRUE(HeldWindowRequestIds(env, tracker).empty());
    auto tracker_class = base::android::ScopedJavaLocalRef<jclass>::Adopt(
        env, env->GetObjectClass(tracker.obj()));
    auto pause =
        env->GetMethodID(tracker_class.obj(),
                         "pausePendingTaskActivityCreationForTesting", "()V");
    env->CallVoidMethod(tracker.obj(), pause);
    base::android::CheckException(env);

    struct Completion {
      bool completed = false;
      bool registered_at_completion = false;
      bool model_at_completion = false;
      int window_id = -1;
    };
    auto completion = std::make_shared<Completion>();
    auto expected_profile = profile->GetWeakPtr();
    auto contents = content::WebContents::Create(
        content::WebContents::CreateParams(profile));
    auto weak_contents = contents->GetWeakPtr();
    auto owned_request = std::make_shared<int>(-1);
    auto global_tracker = base::android::ScopedJavaGlobalRef<jobject>(tracker);
    base::ScopedClosureRunner cleanup(base::BindOnce(
        [](AndroidContainerCookieStoresBrowserTest* test,
           std::shared_ptr<int> owned_request,
           base::WeakPtr<content::WebContents> weak_contents,
           base::android::ScopedJavaGlobalRef<jobject> tracker) {
          JNIEnv* env = base::android::AttachCurrentThread();
          test->DestroyOnlyPendingWindowRequest(env, tracker, *owned_request);
          if (!weak_contents)
            return;
          for (TabModel* model : TabModelList::models()) {
            for (int i = 0; i < model->GetTabCount(); ++i) {
              auto* tab = model->GetTabAt(i);
              if (!tab || tab->web_contents() != weak_contents.get())
                continue;
              auto activity = test->ActivityFor(tab, false);
              if (!activity.is_null())
                test->FinishActivityOnScopeExit(activity).RunAndReset();
              return;
            }
          }
        },
        base::Unretained(this), owned_request, weak_contents,
        std::move(global_tracker)));

    BrowserWindowCreateParams params(BrowserWindowInterface::TYPE_POPUP,
                                     profile, true);
    params.web_contents = std::move(contents);
    CreateBrowserWindow(
        std::move(params),
        base::BindOnce([](std::shared_ptr<Completion> completion,
                          base::WeakPtr<Profile> expected_profile,
                          BrowserWindowInterface* window) {
          completion->completed = true;
          if (!window || !expected_profile ||
              window->GetType() != BrowserWindowInterface::TYPE_POPUP ||
              window->GetProfile() != expected_profile.get())
            return;
          completion->window_id = window->GetSessionID().id();
          auto* controller = BrowserExtensionWindowController::From(window);
          if (controller &&
              controller->GetWindowId() == completion->window_id) {
            for (WindowController* registered : *WindowControllerList::GetInstance()) {
              if (registered == controller)
                completion->registered_at_completion = true;
            }
          }
          for (TabModel* model : TabModelList::models()) {
            if (model->GetProfile() == expected_profile.get() &&
                model->GetSessionId().id() == completion->window_id &&
                model->GetTabModelType() == TabModel::TabModelType::kStandard &&
                model->activity_type() ==
                    chrome::android::ActivityType::kCustomTab) {
              completion->model_at_completion = true;
            }
          }
        }, completion, expected_profile));
    const auto held = HeldWindowRequestIds(env, tracker);
    if (held.size() == 1)
      *owned_request = held[0];
    ASSERT_EQ(1u, held.size());
    CallWindowRequestMethod(env, tracker,
                            "resumePendingTaskActivityCreationForTesting",
                            *owned_request);
    ASSERT_TRUE(base::test::RunUntil([&] { return completion->completed; }));
    ASSERT_TRUE(completion->registered_at_completion);
    ASSERT_TRUE(completion->model_at_completion);
    ASSERT_NE(source_window_id, completion->window_id);
    ASSERT_TRUE(weak_contents);
    TabAndroid* popup_tab = TabAndroid::FromWebContents(weak_contents.get());
    ASSERT_TRUE(popup_tab);
    auto activity = ActivityFor(popup_tab, false);
    ASSERT_FALSE(activity.is_null());
    auto finish_popup = FinishActivityOnScopeExit(activity);
    ASSERT_TRUE(ControllerFor(popup_tab));
    EXPECT_EQ(completion->window_id, ControllerFor(popup_tab)->GetWindowId());
    EXPECT_EQ(profile, popup_tab->GetProfile());
    EXPECT_TRUE(popup_tab->GetContainerId().empty());
    EXPECT_TRUE(containers::android::WebContentsMatchesContainer(
        profile, weak_contents.get(), ""));
    ASSERT_TRUE(content::NavigateToURL(
        weak_contents.get(), embedded_test_server()->GetURL("/title1.html")));
    const int popup_id = ExtensionTabUtil::GetTabId(*popup_tab);
    if (incognito) {
      EXPECT_THAT(StoreTabIds("1", false),
                  testing::Not(testing::Contains(popup_id)));
      EXPECT_THAT(StoreTabIds("1", true), testing::Contains(popup_id));
      EXPECT_THAT(StoreTabIds("0", true),
                  testing::Not(testing::Contains(popup_id)));
    } else {
      EXPECT_THAT(StoreTabIds("0"), testing::Contains(source_id));
      EXPECT_THAT(StoreTabIds("0"), testing::Contains(popup_id));
    }
    finish_popup.RunAndReset();
    ASSERT_TRUE(base::test::RunUntil(
        [&] { return !HasControllerId(completion->window_id); }));
    EXPECT_THAT(StoreTabIds(incognito ? "1" : "0", incognito),
                testing::Not(testing::Contains(popup_id)));
    EXPECT_TRUE(HasControllerId(source_window_id));
    EXPECT_THAT(StoreTabIds("0"), testing::Contains(source_id));
  }

  base::android::ScopedJavaLocalRef<jobject> Creator(JNIEnv* env,
                                                     TabModel* model) {
    auto java_model = model->GetJavaObject();
    auto cls = base::android::ScopedJavaLocalRef<jclass>::Adopt(
        env, env->GetObjectClass(java_model.obj()));
    jmethodID method =
        env->GetMethodID(cls.obj(), "getTabCreator",
                         "()Lorg/chromium/chrome/browser/tabmodel/TabCreator;");
    base::android::CheckException(env);
    CHECK(method);
    return base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallObjectMethod(java_model.obj(), method));
  }

  TabAndroid* CreateContainerTab(TabModel* model, const std::string& id) {
    return CreateContainerTab(model, id,
                              embedded_test_server()->GetURL("/title1.html"));
  }

  TabAndroid* CreateContainerTab(TabModel* model,
                                 const std::string& id,
                                 const GURL& target_url) {
    JNIEnv* env = base::android::AttachCurrentThread();
    auto creator = Creator(env, model);
    auto creator_class = base::android::ScopedJavaLocalRef<jclass>::Adopt(
        env, env->GetObjectClass(creator.obj()));
    auto params_class = base::android::GetClass(
        env, "org/chromium/content_public/browser/LoadUrlParams");
    jmethodID constructor =
        env->GetMethodID(params_class.obj(), "<init>", "(Ljava/lang/String;)V");
    auto url =
        base::android::ConvertUTF8ToJavaString(env, target_url.spec());
    auto params = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->NewObject(params_class.obj(), constructor, url.obj()));
    auto java_id = base::android::ConvertUTF8ToJavaString(env, id);
    jmethodID create = env->GetMethodID(
        creator_class.obj(), "createNewTabInContainer",
        "(Lorg/chromium/content_public/browser/LoadUrlParams;I"
        "Ljava/lang/String;)Lorg/chromium/chrome/browser/tab/Tab;");
    base::android::CheckException(env);
    CHECK(create);
    auto java_tab = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallObjectMethod(
                 creator.obj(), create, params.obj(),
                 static_cast<jint>(TabModel::TabLaunchType::FROM_CHROME_UI),
                 java_id.obj()));
    base::android::CheckException(env);
    return java_tab.is_null() ? nullptr
                              : TabAndroid::GetNativeTab(env, java_tab);
  }

  TabAndroid* RestoreFrozenCopy(TabModel* model, TabAndroid* source) {
    JNIEnv* env = base::android::AttachCurrentThread();
    auto extractor = base::android::GetClass(
        env, "org/chromium/chrome/browser/tab/TabStateExtractor");
    jmethodID from =
        env->GetStaticMethodID(extractor.obj(), "from",
                               "(Lorg/chromium/chrome/browser/tab/Tab;)"
                               "Lorg/chromium/chrome/browser/tab/TabState;");
    base::android::CheckException(env);
    CHECK(from);
    auto java_source = source->GetJavaObject();
    auto state = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env,
        env->CallStaticObjectMethod(extractor.obj(), from, java_source.obj()));
    base::android::CheckException(env);
    CHECK(!state.is_null());
    auto id_manager_class = base::android::GetClass(
        env, "org/chromium/chrome/browser/tab/TabIdManager");
    jmethodID get_manager = env->GetStaticMethodID(
        id_manager_class.obj(), "getInstance",
        "()Lorg/chromium/chrome/browser/tab/TabIdManager;");
    auto manager = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallStaticObjectMethod(id_manager_class.obj(), get_manager));
    jmethodID allocate =
        env->GetMethodID(id_manager_class.obj(), "generateValidId", "(I)I");
    const jint id = env->CallIntMethod(manager.obj(), allocate, -1);
    base::android::CheckException(env);
    auto creator = Creator(env, model);
    auto creator_class = base::android::ScopedJavaLocalRef<jclass>::Adopt(
        env, env->GetObjectClass(creator.obj()));
    jmethodID create =
        env->GetMethodID(creator_class.obj(), "createFrozenTab",
                         "(Lorg/chromium/chrome/browser/tab/TabState;II)"
                         "Lorg/chromium/chrome/browser/tab/Tab;");
    base::android::CheckException(env);
    CHECK(create);
    auto java_frozen = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallObjectMethod(creator.obj(), create, state.obj(), id,
                                   model->GetTabCount()));
    base::android::CheckException(env);
    return java_frozen.is_null() ? nullptr
                                 : TabAndroid::GetNativeTab(env, java_frozen);
  }

  std::vector<int> StoreTabIds(const std::string& store_id,
                               bool incognito = false) {
    auto extension = ExtensionBuilder("Real container cookie-store tabs")
                         .SetManifestVersion(3)
                         .AddAPIPermission("cookies")
                         .Build();
    auto function = base::MakeRefCounted<CookiesGetAllCookieStoresFunction>();
    function->set_extension(extension.get());
    auto result = api_test_utils::RunFunctionAndReturnSingleResult(
        function, "[]", GetProfile(),
        incognito ? api_test_utils::FunctionMode::kIncognito
                  : api_test_utils::FunctionMode::kNone);
    EXPECT_TRUE(result && result->is_list());
    if (!result || !result->is_list()) {
      return {};
    }
    for (const auto& value : result->GetList()) {
      const auto& store = value.GetDict();
      const std::string* id = store.FindString("id");
      if (!id || *id != store_id) {
        continue;
      }
      std::vector<int> ids;
      const auto* tab_ids = store.FindList("tabIds");
      if (!tab_ids) {
        ADD_FAILURE() << "Cookie store omitted tabIds";
        continue;
      }
      for (const auto& tab_id : *tab_ids) {
        ids.push_back(tab_id.GetInt());
      }
      return ids;
    }
    return {};
  }

  base::android::ScopedJavaLocalRef<jobject> ActivityFor(
      TabAndroid* tab,
      bool require_attached = true) {
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!tab->web_contents()) {
      CHECK(!require_attached);
      return {};
    }
    auto* window = tab->web_contents()->GetTopLevelNativeWindow();
    if (!window) {
      CHECK(!require_attached);
      return {};
    }
    auto java_window = window->GetJavaObject();
    if (java_window.is_null()) {
      CHECK(!require_attached);
      return {};
    }
    auto cls = base::android::ScopedJavaLocalRef<jclass>::Adopt(
        env, env->GetObjectClass(java_window.obj()));
    auto get_activity = env->GetMethodID(cls.obj(), "getActivity",
                                         "()Ljava/lang/ref/WeakReference;");
    base::android::CheckException(env);
    CHECK(get_activity);
    auto weak_activity = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallObjectMethod(java_window.obj(), get_activity));
    base::android::CheckException(env);
    if (weak_activity.is_null()) {
      CHECK(!require_attached);
      return {};
    }
    auto weak_class = base::android::ScopedJavaLocalRef<jclass>::Adopt(
        env, env->GetObjectClass(weak_activity.obj()));
    auto get =
        env->GetMethodID(weak_class.obj(), "get", "()Ljava/lang/Object;");
    auto activity = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallObjectMethod(weak_activity.obj(), get));
    base::android::CheckException(env);
    if (require_attached) {
      CHECK(!activity.is_null());
    }
    return activity;
  }

  TabAndroid* CreateDefaultUiTab(TabAndroid* source, bool incognito) {
    JNIEnv* env = base::android::AttachCurrentThread();
    auto activity = ActivityFor(source);
    auto cls = base::android::ScopedJavaLocalRef<jclass>::Adopt(
        env, env->GetObjectClass(activity.obj()));
    auto get_creator = env->GetMethodID(
        cls.obj(), "getTabCreator",
        "(Z)Lorg/chromium/chrome/browser/tabmodel/TabCreator;");
    base::android::CheckException(env);
    CHECK(get_creator);
    auto creator = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallObjectMethod(activity.obj(), get_creator,
                                   static_cast<jboolean>(incognito)));
    auto creator_class = base::android::ScopedJavaLocalRef<jclass>::Adopt(
        env, env->GetObjectClass(creator.obj()));
    auto params_class = base::android::GetClass(
        env, "org/chromium/content_public/browser/LoadUrlParams");
    auto constructor =
        env->GetMethodID(params_class.obj(), "<init>", "(Ljava/lang/String;)V");
    auto url = base::android::ConvertUTF8ToJavaString(env, "about:blank");
    auto params = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->NewObject(params_class.obj(), constructor, url.obj()));
    auto create =
        env->GetMethodID(creator_class.obj(), "createNewTab",
                         "(Lorg/chromium/content_public/browser/LoadUrlParams;I"
                         "Lorg/chromium/chrome/browser/tab/Tab;)Lorg/chromium/"
                         "chrome/browser/tab/Tab;");
    base::android::CheckException(env);
    CHECK(create);
    auto tab = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallObjectMethod(
                 creator.obj(), create, params.obj(),
                 static_cast<jint>(TabModel::TabLaunchType::FROM_CHROME_UI),
                 nullptr));
    base::android::CheckException(env);
    return tab.is_null() ? nullptr : TabAndroid::GetNativeTab(env, tab);
  }

  void RecreateActualActivity(TabAndroid* source) {
    JNIEnv* env = base::android::AttachCurrentThread();
    auto activity = ActivityFor(source);
    auto cls = base::android::ScopedJavaLocalRef<jclass>::Adopt(
        env, env->GetObjectClass(activity.obj()));
    auto recreate = env->GetMethodID(cls.obj(), "recreate", "()V");
    base::android::CheckException(env);
    CHECK(recreate);
    env->CallVoidMethod(activity.obj(), recreate);
    base::android::CheckException(env);
  }

  bool LaunchActualTabbedWindow(TabAndroid* source) {
    JNIEnv* env = base::android::AttachCurrentThread();
    auto activity = ActivityFor(source);
    auto utils = base::android::GetClass(
        env, "org/chromium/chrome/browser/multiwindow/MultiWindowUtils");
    auto create = env->GetStaticMethodID(
        utils.obj(), "createNewWindowIntent",
        "(Landroid/app/Activity;ZI)Landroid/content/Intent;");
    base::android::CheckException(env);
    CHECK(create);
    // The source integer is launch bookkeeping; all window ownership is
    // established by the actual Activity/task/model initialization below.
    auto intent = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallStaticObjectMethod(utils.obj(), create, activity.obj(),
                                         false, 0));
    base::android::CheckException(env);
    if (intent.is_null()) {
      return false;
    }
    auto cls = base::android::ScopedJavaLocalRef<jclass>::Adopt(
        env, env->GetObjectClass(activity.obj()));
    auto start = env->GetMethodID(cls.obj(), "startActivity",
                                  "(Landroid/content/Intent;)V");
    base::android::CheckException(env);
    CHECK(start);
    env->CallVoidMethod(activity.obj(), start, intent.obj());
    base::android::CheckException(env);
    return true;
  }

  TabAndroid* FindActualTab(int android_id) {
    for (TabModel* model : TabModelList::models()) {
      if (model->GetTabModelType() != TabModel::TabModelType::kStandard ||
          model->activity_type() != chrome::android::ActivityType::kTabbed) {
        continue;
      }
      for (int i = 0; i < model->GetTabCount(); ++i) {
        TabAndroid* tab = model->GetTabAt(i);
        if (tab && tab->GetAndroidId() == android_id) {
          return tab;
        }
      }
    }
    return nullptr;
  }

  void LaunchActualCustomTab(TabAndroid* source) {
    JNIEnv* env = base::android::AttachCurrentThread();
    auto activity = ActivityFor(source);
    auto activity_class = base::android::ScopedJavaLocalRef<jclass>::Adopt(
        env, env->GetObjectClass(activity.obj()));
    auto package_method = env->GetMethodID(
        activity_class.obj(), "getPackageName", "()Ljava/lang/String;");
    auto package = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallObjectMethod(activity.obj(), package_method));
    auto intent_class = base::android::GetClass(env, "android/content/Intent");
    auto constructor =
        env->GetMethodID(intent_class.obj(), "<init>", "(Ljava/lang/String;)V");
    auto action = base::android::ConvertUTF8ToJavaString(
        env, "android.intent.action.VIEW");
    auto intent = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->NewObject(intent_class.obj(), constructor, action.obj()));
    auto set_class = env->GetMethodID(
        intent_class.obj(), "setClassName",
        "(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;");
    auto class_name = base::android::ConvertUTF8ToJavaString(
        env, "org.chromium.chrome.browser.customtabs.CustomTabActivity");
    auto classified = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallObjectMethod(intent.obj(), set_class, package.obj(),
                                   class_name.obj()));
    auto uri_class = base::android::GetClass(env, "android/net/Uri");
    auto parse = env->GetStaticMethodID(
        uri_class.obj(), "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
    auto url = base::android::ConvertUTF8ToJavaString(
        env, embedded_test_server()->GetURL("/title1.html").spec());
    auto uri = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallStaticObjectMethod(uri_class.obj(), parse, url.obj()));
    auto set_data =
        env->GetMethodID(intent_class.obj(), "setData",
                         "(Landroid/net/Uri;)Landroid/content/Intent;");
    auto with_data = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallObjectMethod(intent.obj(), set_data, uri.obj()));
    auto start = env->GetMethodID(activity_class.obj(), "startActivity",
                                  "(Landroid/content/Intent;)V");
    base::android::CheckException(env);
    CHECK(start);
    env->CallVoidMethod(activity.obj(), start, intent.obj());
    base::android::CheckException(env);
  }

  WindowController* ControllerFor(TabAndroid* tab) {
    for (WindowController* controller : *WindowControllerList::GetInstance()) {
      if (controller->profile() == tab->GetProfile() &&
          controller->GetWindowId() == tab->GetWindowId().id()) {
        return controller;
      }
    }
    return nullptr;
  }

  bool HasControllerId(int id) {
    for (WindowController* controller : *WindowControllerList::GetInstance()) {
      if (controller->GetWindowId() == id)
        return true;
    }
    return false;
  }

  void CheckExtensionOpenerWindowPartitions(bool named_opener) {
    // The normal-window supplied-contents route requires this manifest/device
    // capability. Keep real creation failures visible when it is available.
    JNIEnv* env = base::android::AttachCurrentThread();
    auto multi_window = base::android::GetClass(
        env, "org/chromium/chrome/browser/multiwindow/MultiWindowUtils");
    auto enabled = env->GetStaticMethodID(
        multi_window.obj(), "isMultiInstanceApi31Enabled", "()Z");
    base::android::CheckException(env);
    ASSERT_TRUE(enabled);
    const bool available = env->CallStaticBooleanMethod(multi_window.obj(), enabled);
    base::android::CheckException(env);
    if (!available) {
      GTEST_SKIP() << "Requires normal-window WebContents transfer capability";
    }

    auto* regular = TabAndroid::FromWebContents(
        chrome_test_utils::GetActiveWebContents(this));
    ASSERT_TRUE(regular);
    auto* model =
        TabModelList::FindTabModelWithWindowSessionId(regular->GetWindowId());
    ASSERT_TRUE(model);
    TabAndroid* named = CreateContainerTab(model, kWork);
    ASSERT_TRUE(named);
    content::WebContents* regular_contents = regular->web_contents();
    content::WebContents* named_contents = named->web_contents();
    ASSERT_TRUE(regular_contents);
    ASSERT_TRUE(named_contents);
    const GURL first_url = embedded_test_server()->GetURL("/title1.html");
    const GURL second_url = embedded_test_server()->GetURL("/title2.html");
    ASSERT_TRUE(content::NavigateToURL(regular_contents, first_url));
    ASSERT_TRUE(content::NavigateToURL(named_contents, first_url));
    ASSERT_TRUE(content::ExecJs(
        regular_contents, "document.cookie='reaper_window_partition=default'"));
    ASSERT_TRUE(content::ExecJs(
        named_contents, "document.cookie='reaper_window_partition=named'"));
    auto* regular_partition =
        regular_contents->GetPrimaryMainFrame()->GetStoragePartition();
    auto* named_partition =
        named_contents->GetPrimaryMainFrame()->GetStoragePartition();
    ASSERT_NE(regular_partition, named_partition);

    TestExtensionDir extension_dir;
    extension_dir.WriteManifest(R"({
      "name": "Container opener windows",
      "version": "1.0",
      "manifest_version": 3,
      "permissions": ["tabs"]
    })");
    extension_dir.WriteFile("opener.html", "<!doctype html><title>Opener</title>");
    ChromeTestExtensionLoader loader(GetProfile());
    scoped_refptr<const Extension> extension =
        loader.LoadExtension(extension_dir.UnpackedPath());
    ASSERT_TRUE(extension);
    auto* opener_contents = named_opener ? named_contents : regular_contents;
    auto* expected_partition = named_opener ? named_partition : regular_partition;
    const std::string expected_id = named_opener ? kWork : "";
    const GURL opener_url = extension->GetResourceURL("opener.html");
    ASSERT_TRUE(content::NavigateToURL(opener_contents, opener_url));
    ASSERT_EQ(expected_partition,
              opener_contents->GetPrimaryMainFrame()->GetStoragePartition());

    auto function = base::MakeRefCounted<WindowsCreateFunction>();
    function->set_extension(extension.get());
    function->set_has_callback(true);
    function->set_user_gesture(true);
    function->set_source_url(opener_url);
    function->set_source_context_type(mojom::ContextType::kPrivilegedExtension);
    function->SetRenderFrameHost(opener_contents->GetPrimaryMainFrame());
    base::ListValue urls;
    urls.Append(first_url.spec());
    urls.Append(second_url.spec());
    base::DictValue options;
    options.Set("url", std::move(urls));
    options.Set("type", "normal");
    options.Set("setSelfAsOpener", true);
    base::ListValue args;
    args.Append(std::move(options));
    auto result = api_test_utils::RunFunctionAndReturnSingleResult(
        function, std::move(args), GetProfile());
    ASSERT_TRUE(result && result->is_dict());
    const int window_id = result->GetDict().FindInt("id").value_or(-1);
    ASSERT_GE(window_id, 0);
    ASSERT_NE(regular->GetWindowId().id(), window_id);
    base::ScopedClosureRunner close_window(base::BindOnce([](int owned_id) {
      for (WindowController* controller : *WindowControllerList::GetInstance()) {
        if (controller->GetWindowId() == owned_id) {
          if (auto* window = controller->window())
            window->Close();
          return;
        }
      }
    }, window_id));
    TabModel* created_model = nullptr;
    for (TabModel* candidate : TabModelList::models()) {
      if (candidate->GetProfile() == GetProfile() &&
          candidate->GetSessionId().id() == window_id) {
        created_model = candidate;
        break;
      }
    }
    ASSERT_TRUE(created_model);
    ASSERT_EQ(2, created_model->GetTabCount());
    EXPECT_EQ(opener_url, opener_contents->GetLastCommittedURL());
    EXPECT_EQ(expected_partition,
              opener_contents->GetPrimaryMainFrame()->GetStoragePartition());
    for (int i = 0; i < created_model->GetTabCount(); ++i) {
      auto* child = created_model->GetTabAt(i);
      ASSERT_TRUE(child);
      ASSERT_TRUE(child->web_contents());
      ASSERT_TRUE(content::WaitForLoadStop(child->web_contents()));
      EXPECT_EQ(i == 0 ? first_url : second_url,
                child->web_contents()->GetLastCommittedURL());
      EXPECT_EQ(expected_id, child->GetContainerId());
      EXPECT_EQ(expected_partition,
                child->web_contents()->GetPrimaryMainFrame()->GetStoragePartition());
      EXPECT_TRUE(containers::android::WebContentsMatchesContainer(
          GetProfile(), child->web_contents(), expected_id));
      EXPECT_EQ(named_opener ? "reaper_window_partition=named"
                             : "reaper_window_partition=default",
                content::EvalJs(child->web_contents(), "document.cookie"));
    }
    ASSERT_TRUE(content::NavigateToURL(regular_contents, first_url));
    ASSERT_TRUE(content::NavigateToURL(named_contents, first_url));
    EXPECT_EQ("reaper_window_partition=default",
              content::EvalJs(regular_contents, "document.cookie"));
    EXPECT_EQ("reaper_window_partition=named",
              content::EvalJs(named_contents, "document.cookie"));
    close_window.RunAndReset();
    EXPECT_TRUE(base::test::RunUntil([&] { return !HasControllerId(window_id); }));
  }

  base::ScopedClosureRunner FinishActivityOnScopeExit(
      const base::android::ScopedJavaLocalRef<jobject>& activity) {
    auto global = base::android::ScopedJavaGlobalRef<jobject>(activity);
    return base::ScopedClosureRunner(base::BindOnce(
        [](base::android::ScopedJavaGlobalRef<jobject> activity) {
          JNIEnv* env = base::android::AttachCurrentThread();
          auto cls = base::android::ScopedJavaLocalRef<jclass>::Adopt(
              env, env->GetObjectClass(activity.obj()));
          auto finish = env->GetMethodID(cls.obj(), "finish", "()V");
          base::android::CheckException(env);
          CHECK(finish);
          env->CallVoidMethod(activity.obj(), finish);
          base::android::CheckException(env);
        },
        std::move(global)));
  }

  void SelectActualProfileModel(TabAndroid* source, bool incognito) {
    JNIEnv* env = base::android::AttachCurrentThread();
    auto activity = ActivityFor(source);
    auto cls = base::android::ScopedJavaLocalRef<jclass>::Adopt(
        env, env->GetObjectClass(activity.obj()));
    auto get_selector = env->GetMethodID(
        cls.obj(), "getTabModelSelector",
        "()Lorg/chromium/chrome/browser/tabmodel/TabModelSelector;");
    base::android::CheckException(env);
    CHECK(get_selector);
    auto selector = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallObjectMethod(activity.obj(), get_selector));
    auto selector_class = base::android::ScopedJavaLocalRef<jclass>::Adopt(
        env, env->GetObjectClass(selector.obj()));
    auto select = env->GetMethodID(selector_class.obj(), "selectModel", "(Z)V");
    base::android::CheckException(env);
    CHECK(select);
    env->CallVoidMethod(selector.obj(), select,
                        static_cast<jboolean>(incognito));
    base::android::CheckException(env);
    content::RunAllTasksUntilIdle();
  }

  void CloseIncognitoUiTabs() {
    JNIEnv* env = base::android::AttachCurrentThread();
    auto cls = base::android::GetClass(
        env, "org/chromium/chrome/browser/tabmodel/IncognitoTabHostUtils");
    auto close =
        env->GetStaticMethodID(cls.obj(), "closeAllIncognitoTabs", "()V");
    base::android::CheckException(env);
    CHECK(close);
    env->CallStaticVoidMethod(cls.obj(), close);
    base::android::CheckException(env);
    content::RunAllTasksUntilIdle();
  }

  void SetActualDragging(TabAndroid* tab, bool dragging) {
    JNIEnv* env = base::android::AttachCurrentThread();
    auto cls = base::android::GetClass(
        env, "org/chromium/chrome/browser/tab/TabDragStateData");
    auto get =
        env->GetStaticMethodID(cls.obj(), "getOrCreateForTab",
                               "(Lorg/chromium/chrome/browser/tab/Tab;)Lorg/"
                               "chromium/chrome/browser/tab/TabDragStateData;");
    auto java_tab = tab->GetJavaObject();
    auto data = base::android::ScopedJavaLocalRef<jobject>::Adopt(
        env, env->CallStaticObjectMethod(cls.obj(), get, java_tab.obj()));
    auto set = env->GetMethodID(cls.obj(), "setIsDragging", "(Z)V");
    base::android::CheckException(env);
    CHECK(set);
    env->CallVoidMethod(data.obj(), set, static_cast<jboolean>(dragging));
    base::android::CheckException(env);
  }

  void CheckFrozenStore(const std::string& id) {
    TabAndroid* initial = TabAndroid::FromWebContents(
        chrome_test_utils::GetActiveWebContents(this));
    ASSERT_TRUE(initial);
    TabModel* model =
        TabModelList::FindTabModelWithWindowSessionId(initial->GetWindowId());
    ASSERT_TRUE(model);
    const int initial_api_id = ExtensionTabUtil::GetTabId(*initial);
    TabAndroid* live = CreateContainerTab(model, id);
    ASSERT_TRUE(live);
    ASSERT_TRUE(live->web_contents());
    ASSERT_TRUE(content::WaitForLoadStop(live->web_contents()));
    ASSERT_TRUE(containers::android::WebContentsMatchesContainer(
        GetProfile(), live->web_contents(), id));
    const int live_api_id = ExtensionTabUtil::GetTabId(*live);
    TabAndroid* frozen = RestoreFrozenCopy(model, live);
    ASSERT_TRUE(frozen);
    ASSERT_EQ(id, frozen->GetContainerId());
    ASSERT_EQ(nullptr, frozen->web_contents());
    const int frozen_api_id = ExtensionTabUtil::GetTabId(*frozen);
    ASSERT_NE(api::tabs::TAB_ID_NONE, frozen_api_id);
    EXPECT_THAT(StoreTabIds("0"), testing::Contains(initial_api_id));
    const std::string store_id = cookies_helpers::GetContainerStoreId(id);
    EXPECT_THAT(StoreTabIds(store_id),
                testing::UnorderedElementsAre(live_api_id, frozen_api_id));
    // Enumeration must preserve laziness; it cannot create default contents
    // merely to recover either the native tab ID or the container UUID.
    EXPECT_EQ(nullptr, frozen->web_contents());
    frozen->LoadIfNeeded();
    ASSERT_TRUE(frozen->web_contents());
    ASSERT_TRUE(content::WaitForLoadStop(frozen->web_contents()));
    EXPECT_TRUE(containers::android::WebContentsMatchesContainer(
        GetProfile(), frozen->web_contents(), id));
    EXPECT_EQ(frozen_api_id, ExtensionTabUtil::GetTabId(*frozen));
    EXPECT_THAT(StoreTabIds(store_id),
                testing::UnorderedElementsAre(live_api_id, frozen_api_id));
  }

 private:
  base::test::ScopedFeatureList features_;
};

IN_PROC_BROWSER_TEST_F(AndroidContainerCookieStoresBrowserTest,
                       FreshNamedNativeNtpRetainsPartitionOnWebNavigation) {
  auto* initial = TabAndroid::FromWebContents(
      chrome_test_utils::GetActiveWebContents(this));
  ASSERT_TRUE(initial);
  auto* model =
      TabModelList::FindTabModelWithWindowSessionId(initial->GetWindowId());
  ASSERT_TRUE(model);
  auto* default_ntp =
      CreateContainerTab(model, "", GURL(chrome::kChromeUINewTabURL));
  ASSERT_TRUE(default_ntp);
  ASSERT_TRUE(default_ntp->web_contents());
  ASSERT_TRUE(content::WaitForLoadStop(default_ntp->web_contents()));
  ASSERT_TRUE(base::test::RunUntil([&] { return default_ntp->IsNativePage(); }));
  ASSERT_TRUE(default_ntp->GetContainerId().empty());
  ASSERT_TRUE(containers::android::WebContentsMatchesContainer(
      GetProfile(), default_ntp->web_contents(), ""));

  // The chooser passes the current Tab's URL to this real Java creator. Native
  // NTP creation must preserve actual storage, not only the container label.
  auto* named = CreateContainerTab(model, kWork, default_ntp->GetURL());
  ASSERT_TRUE(named);
  ASSERT_NE(default_ntp, named);
  ASSERT_TRUE(named->web_contents());
  ASSERT_NE(default_ntp->web_contents(), named->web_contents());
  ASSERT_TRUE(content::WaitForLoadStop(named->web_contents()));
  ASSERT_TRUE(base::test::RunUntil([&] { return named->IsNativePage(); }));
  EXPECT_TRUE(default_ntp->IsNativePage());
  EXPECT_TRUE(default_ntp->GetContainerId().empty());
  EXPECT_EQ(kWork, named->GetContainerId());
  ASSERT_TRUE(containers::android::WebContentsMatchesContainer(
      GetProfile(), named->web_contents(), kWork));
  EXPECT_FALSE(containers::android::WebContentsMatchesContainer(
      GetProfile(), named->web_contents(), ""));
  auto* default_partition =
      default_ntp->web_contents()->GetPrimaryMainFrame()->GetStoragePartition();
  auto* named_partition =
      named->web_contents()->GetPrimaryMainFrame()->GetStoragePartition();
  ASSERT_NE(default_partition, named_partition);

  const GURL url = embedded_test_server()->GetURL("/title1.html");
  ASSERT_TRUE(content::NavigateToURL(default_ntp->web_contents(), url));
  ASSERT_TRUE(content::NavigateToURL(named->web_contents(), url));
  EXPECT_TRUE(default_ntp->GetContainerId().empty());
  EXPECT_EQ(kWork, named->GetContainerId());
  EXPECT_EQ(default_partition, default_ntp->web_contents()
                                   ->GetPrimaryMainFrame()
                                   ->GetStoragePartition());
  EXPECT_EQ(named_partition,
            named->web_contents()->GetPrimaryMainFrame()->GetStoragePartition());
  EXPECT_TRUE(containers::android::WebContentsMatchesContainer(
      GetProfile(), default_ntp->web_contents(), ""));
  EXPECT_TRUE(containers::android::WebContentsMatchesContainer(
      GetProfile(), named->web_contents(), kWork));
  EXPECT_FALSE(containers::android::WebContentsMatchesContainer(
      GetProfile(), named->web_contents(), ""));
  ASSERT_TRUE(content::ExecJs(default_ntp->web_contents(),
                             "document.cookie='reaper_ntp_partition=default';"
                             "localStorage.reaperNtpPartition='default'"));
  ASSERT_TRUE(content::ExecJs(named->web_contents(),
                             "document.cookie='reaper_ntp_partition=named';"
                             "localStorage.reaperNtpPartition='named'"));
  EXPECT_EQ("reaper_ntp_partition=default",
            content::EvalJs(default_ntp->web_contents(), "document.cookie"));
  EXPECT_EQ("reaper_ntp_partition=named",
            content::EvalJs(named->web_contents(), "document.cookie"));
  EXPECT_EQ("default", content::EvalJs(default_ntp->web_contents(),
                                     "localStorage.reaperNtpPartition"));
  EXPECT_EQ("named", content::EvalJs(named->web_contents(),
                                   "localStorage.reaperNtpPartition"));
}

IN_PROC_BROWSER_TEST_F(AndroidContainerCookieStoresBrowserTest,
                       DelayedPopupRetainsNamedContainerAndCookiePartition) {
  base::test::ScopedCommandLine command_line;
  command_line.GetProcessCommandLine()->RemoveSwitch("disable-popup-blocking");
  auto* initial = TabAndroid::FromWebContents(
        chrome_test_utils::GetActiveWebContents(this));
  ASSERT_TRUE(initial);
  auto* model =
      TabModelList::FindTabModelWithWindowSessionId(initial->GetWindowId());
  ASSERT_TRUE(model);
  ASSERT_TRUE(content::NavigateToURL(
      initial->web_contents(), embedded_test_server()->GetURL("/title1.html")));
  ASSERT_TRUE(content::ExecJs(initial->web_contents(),
                             "document.cookie='reaper_delayed_popup=default'"));
  auto* named = CreateContainerTab(model, kWork);
  ASSERT_TRUE(named);
  auto* source = named->web_contents();
  ASSERT_TRUE(source);
  ASSERT_TRUE(content::WaitForLoadStop(source));
  ASSERT_TRUE(containers::android::WebContentsMatchesContainer(
      GetProfile(), source, kWork));
  ASSERT_TRUE(content::ExecJs(source,
                             "document.cookie='reaper_delayed_popup=named'"));
  auto* settings = HostContentSettingsMapFactory::GetForProfile(GetProfile());
  settings->SetContentSettingDefaultScope(
      source->GetLastCommittedURL(), source->GetLastCommittedURL(),
      ContentSettingsType::POPUPS, CONTENT_SETTING_BLOCK);
  auto* blocker = blocked_content::PopupBlockerTabHelper::FromWebContents(source);
  ASSERT_TRUE(blocker);
  ASSERT_EQ(0u, blocker->GetBlockedPopupsCount());
  const int before_count = model->GetTabCount();
  content::OpenURLParams params = content::OpenURLParams::CreateBrowserInitiated(
      embedded_test_server()->GetURL("/title2.html"),
      WindowOpenDisposition::NEW_FOREGROUND_TAB, ui::PAGE_TRANSITION_LINK);
  params.user_gesture = false;
  source->OpenURL(params, base::DoNothing());
  ASSERT_EQ(before_count, model->GetTabCount());
  ASSERT_EQ(1u, blocker->GetBlockedPopupsCount());
  const auto requests = blocker->GetBlockedPopupRequests();
  ASSERT_EQ(1u, requests.size());
  // Release the actual retained delegate, not a reconstructed NavigateParams.
  blocker->ShowBlockedPopup(requests.begin()->first,
                            WindowOpenDisposition::NEW_FOREGROUND_TAB);
  ASSERT_TRUE(base::test::RunUntil(
      [&] { return model->GetTabCount() == before_count + 1; }));
  auto* child = model->GetTabAt(model->GetActiveIndex());
  ASSERT_TRUE(child);
  ASSERT_NE(named, child);
  ASSERT_TRUE(child->web_contents());
  ASSERT_TRUE(content::WaitForLoadStop(child->web_contents()));
  EXPECT_EQ(params.url, child->web_contents()->GetLastCommittedURL());
  EXPECT_EQ(kWork, child->GetContainerId());
  EXPECT_TRUE(containers::android::WebContentsMatchesContainer(
      GetProfile(), child->web_contents(), kWork));
  EXPECT_EQ(true, content::EvalJs(child->web_contents(),
      "document.cookie.includes('reaper_delayed_popup=named')"));
  EXPECT_EQ(false, content::EvalJs(child->web_contents(),
      "document.cookie.includes('reaper_delayed_popup=default')"));
  EXPECT_EQ(true, content::EvalJs(initial->web_contents(),
      "document.cookie.includes('reaper_delayed_popup=default')"));
  EXPECT_EQ(0u, blocker->GetBlockedPopupsCount());
}

IN_PROC_BROWSER_TEST_F(AndroidContainerCookieStoresBrowserTest,
                       ExtensionUpdateKeepsActualNamedPartition) {
  auto* regular = TabAndroid::FromWebContents(
        chrome_test_utils::GetActiveWebContents(this));
  ASSERT_TRUE(regular);
  auto* model =
      TabModelList::FindTabModelWithWindowSessionId(regular->GetWindowId());
  ASSERT_TRUE(model);
  TabAndroid* named = CreateContainerTab(model, kWork);
  ASSERT_TRUE(named);
  content::WebContents* regular_contents = regular->web_contents();
  content::WebContents* named_contents = named->web_contents();
  ASSERT_TRUE(regular_contents);
  ASSERT_TRUE(named_contents);
  const GURL url = embedded_test_server()->GetURL("/title1.html");
  ASSERT_TRUE(content::NavigateToURL(regular_contents, url));
  ASSERT_TRUE(content::NavigateToURL(named_contents, url));
  ASSERT_TRUE(content::ExecJs(
      regular_contents, "document.cookie='reaper_navigation_partition=default'"));
  ASSERT_TRUE(content::ExecJs(
      named_contents, "document.cookie='reaper_navigation_partition=named'"));
  auto* regular_partition =
      regular_contents->GetPrimaryMainFrame()->GetStoragePartition();
  auto* named_partition =
      named_contents->GetPrimaryMainFrame()->GetStoragePartition();
  ASSERT_NE(regular_partition, named_partition);

  TestExtensionDir extension_dir;
  extension_dir.WriteManifest(R"({
    "name": "Container navigation boundary",
    "version": "1.0",
    "manifest_version": 3,
    "permissions": ["tabs"]
  })");
  ChromeTestExtensionLoader loader(GetProfile());
  scoped_refptr<const Extension> extension =
      loader.LoadExtension(extension_dir.UnpackedPath());
  ASSERT_TRUE(extension);
  auto function = base::MakeRefCounted<TabsUpdateFunction>();
  function->set_extension(extension.get());
  const int tab_id = ExtensionTabUtil::GetTabId(*named);
  content::TestNavigationObserver navigation(named_contents);
  auto result = api_test_utils::RunFunctionAndReturnSingleResult(
      function,
      "[" + base::NumberToString(tab_id) + R"(,{"url":"about:blank"}])",
      GetProfile());
  ASSERT_TRUE(result && result->is_dict());
  EXPECT_EQ(tab_id, result->GetDict().FindInt("id").value_or(-1));
  navigation.Wait();
  ASSERT_TRUE(navigation.last_navigation_succeeded());
  EXPECT_EQ(GURL("about:blank"), named_contents->GetLastCommittedURL());
  EXPECT_EQ(kWork, named->GetContainerId());
  EXPECT_EQ(named_partition,
            named_contents->GetPrimaryMainFrame()->GetStoragePartition());
  EXPECT_NE(regular_partition,
            named_contents->GetPrimaryMainFrame()->GetStoragePartition());
  EXPECT_TRUE(containers::android::WebContentsMatchesContainer(
      GetProfile(), named_contents, kWork));

  ASSERT_TRUE(content::NavigateToURL(named_contents, url));
  EXPECT_EQ("reaper_navigation_partition=named",
            content::EvalJs(named_contents, "document.cookie"));
  EXPECT_EQ("reaper_navigation_partition=default",
            content::EvalJs(regular_contents, "document.cookie"));
}

IN_PROC_BROWSER_TEST_F(AndroidContainerCookieStoresBrowserTest,
                       ExtensionOpenerWindowsRetainNamedPartitions) {
  CheckExtensionOpenerWindowPartitions(true);
}

IN_PROC_BROWSER_TEST_F(AndroidContainerCookieStoresBrowserTest,
                       ExtensionOpenerWindowsKeepDefaultPartitions) {
  CheckExtensionOpenerWindowPartitions(false);
}

IN_PROC_BROWSER_TEST_F(AndroidContainerCookieStoresBrowserTest,
                       NamedStoreIncludesRealFrozenRestoredTab) {
  CheckFrozenStore(kWork);
}

IN_PROC_BROWSER_TEST_F(AndroidContainerCookieStoresBrowserTest,
                       TemporaryStoreIncludesRealFrozenRestoredTab) {
  auto* service = ContainersServiceFactory::GetForProfile(GetProfile());
  ASSERT_TRUE(service);
  auto temporary = service->CreateAndPersistTemporaryContainer();
  ASSERT_TRUE(temporary);
  ASSERT_TRUE(temporary->id.starts_with("t-"));
  CheckFrozenStore(temporary->id);
}
IN_PROC_BROWSER_TEST_F(AndroidContainerCookieStoresBrowserTest,
                       StoreQueryDoesNotCreateIncognitoProfileOrWindow) {
  TabAndroid* regular = TabAndroid::FromWebContents(
      chrome_test_utils::GetActiveWebContents(this));
  ASSERT_TRUE(regular);
  ASSERT_TRUE(ControllerFor(regular));
  ASSERT_FALSE(GetProfile()->HasPrimaryOTRProfile());
  EXPECT_THAT(StoreTabIds("0", true),
              testing::Contains(ExtensionTabUtil::GetTabId(*regular)));
  EXPECT_TRUE(StoreTabIds("1", true).empty());
  EXPECT_FALSE(GetProfile()->HasPrimaryOTRProfile());
}

IN_PROC_BROWSER_TEST_F(
    AndroidContainerCookieStoresBrowserTest,
    RegularStartDiscoversActualIncognitoStoreAndUniqueWindowIds) {
  TabAndroid* regular = TabAndroid::FromWebContents(
      chrome_test_utils::GetActiveWebContents(this));
  ASSERT_TRUE(regular);
  ASSERT_TRUE(ControllerFor(regular));
  const int regular_window = ControllerFor(regular)->GetWindowId();
  TabAndroid* incognito = CreateDefaultUiTab(regular, true);
  ASSERT_TRUE(incognito);
  ASSERT_TRUE(incognito->GetProfile()->IsPrimaryOTRProfile());
  ASSERT_TRUE(ControllerFor(incognito));
  const int incognito_window = ControllerFor(incognito)->GetWindowId();
  EXPECT_NE(regular_window, incognito_window);
  auto* model =
      TabModelList::FindTabModelWithWindowSessionId(incognito->GetWindowId());
  ASSERT_TRUE(model);
  EXPECT_EQ(model->GetSessionId().id(), incognito_window);
  EXPECT_EQ(model->GetProfile(), incognito->GetProfile());
  EXPECT_THAT(StoreTabIds("0", true),
              testing::Contains(ExtensionTabUtil::GetTabId(*regular)));
  EXPECT_THAT(StoreTabIds("1", true),
              testing::Contains(ExtensionTabUtil::GetTabId(*incognito)));
  EXPECT_TRUE(StoreTabIds("1", false).empty());
  EXPECT_EQ(regular_window, ControllerFor(regular)->GetWindowId());
}

IN_PROC_BROWSER_TEST_F(
    AndroidContainerCookieStoresBrowserTest,
    ActualIncognitoModelDestructionRemovesStoreAndRecreationUsesFreshWindow) {
  TabAndroid* regular = TabAndroid::FromWebContents(
      chrome_test_utils::GetActiveWebContents(this));
  ASSERT_TRUE(regular);
  ASSERT_TRUE(ControllerFor(regular));
  const int regular_window = ControllerFor(regular)->GetWindowId();
  TabAndroid* incognito = CreateDefaultUiTab(regular, true);
  ASSERT_TRUE(incognito);
  ASSERT_TRUE(ControllerFor(incognito));
  const int retired_window = ControllerFor(incognito)->GetWindowId();
  EXPECT_FALSE(StoreTabIds("1", true).empty());
  CloseIncognitoUiTabs();
  EXPECT_FALSE(HasControllerId(retired_window));
  EXPECT_TRUE(HasControllerId(regular_window));
  EXPECT_TRUE(StoreTabIds("1", true).empty());
  const bool had_profile = GetProfile()->HasPrimaryOTRProfile();
  StoreTabIds("1", true);
  EXPECT_EQ(had_profile, GetProfile()->HasPrimaryOTRProfile());
  TabAndroid* replacement = CreateDefaultUiTab(regular, true);
  ASSERT_TRUE(replacement);
  ASSERT_TRUE(ControllerFor(replacement));
  EXPECT_NE(retired_window, ControllerFor(replacement)->GetWindowId());
  EXPECT_THAT(StoreTabIds("1", true),
              testing::Contains(ExtensionTabUtil::GetTabId(*replacement)));
}

IN_PROC_BROWSER_TEST_F(AndroidContainerCookieStoresBrowserTest,
                       TemporaryDragLockPreservesNormalCookieStoreMetadata) {
  TabAndroid* regular = TabAndroid::FromWebContents(
      chrome_test_utils::GetActiveWebContents(this));
  ASSERT_TRUE(regular);
  auto* model =
      TabModelList::FindTabModelWithWindowSessionId(regular->GetWindowId());
  ASSERT_TRUE(model);
  ASSERT_TRUE(model->IsThisTabListEditable());
  SetActualDragging(regular, true);
  EXPECT_TRUE(regular->IsDragging());
  EXPECT_FALSE(model->IsThisTabListEditable());
  EXPECT_THAT(StoreTabIds("0"),
              testing::Contains(ExtensionTabUtil::GetTabId(*regular)));
  // Restore actual Java drag data before any tab/window teardown.
  SetActualDragging(regular, false);
  EXPECT_TRUE(model->IsThisTabListEditable());
}

IN_PROC_BROWSER_TEST_F(
    AndroidContainerCookieStoresBrowserTest,
    ActualRegisteredCustomTabIsExcludedFromCookieStoreMetadata) {
  TabAndroid* regular = TabAndroid::FromWebContents(
      chrome_test_utils::GetActiveWebContents(this));
  ASSERT_TRUE(regular);
  const int regular_id = ExtensionTabUtil::GetTabId(*regular);
  LaunchActualCustomTab(regular);
  TabAndroid* custom_tab = nullptr;
  base::ScopedClosureRunner finish_activity;
  bool activity_captured = false;
  ASSERT_TRUE(base::test::RunUntil([&] {
    for (TabModel* model : TabModelList::models()) {
      if (model->activity_type() == chrome::android::ActivityType::kCustomTab &&
          model->GetTabCount() > 0) {
        custom_tab = model->GetTabAt(0);
        if (custom_tab && custom_tab->web_contents()) {
          if (!activity_captured) {
            auto activity = ActivityFor(custom_tab);
            finish_activity = FinishActivityOnScopeExit(activity);
            activity_captured = true;
          }
          if (ControllerFor(custom_tab)) {
            return true;
          }
        }
      }
    }
    return false;
  }));
  ASSERT_TRUE(custom_tab);
  ASSERT_TRUE(ControllerFor(custom_tab));
  const int custom_window_id = ControllerFor(custom_tab)->GetWindowId();
  ASSERT_TRUE(content::WaitForLoadStop(custom_tab->web_contents()));
  const int custom_id = ExtensionTabUtil::GetTabId(*custom_tab);
  ASSERT_NE(api::tabs::TAB_ID_NONE, custom_id);
  EXPECT_THAT(StoreTabIds("0"), testing::Contains(regular_id));
  EXPECT_THAT(StoreTabIds("0"), testing::Not(testing::Contains(custom_id)));
  finish_activity.RunAndReset();
  ASSERT_TRUE(
      base::test::RunUntil([&] { return !HasControllerId(custom_window_id); }));
}

IN_PROC_BROWSER_TEST_F(
    AndroidContainerCookieStoresBrowserTest,
    OnlySelectedProfileWindowReportsFocusedAcrossRealUiSwitches) {
  TabAndroid* regular = TabAndroid::FromWebContents(
      chrome_test_utils::GetActiveWebContents(this));
  ASSERT_TRUE(regular);
  ASSERT_TRUE(ControllerFor(regular));
  TabAndroid* incognito = CreateDefaultUiTab(regular, true);
  ASSERT_TRUE(incognito);
  ASSERT_TRUE(ControllerFor(incognito));
  auto* regular_controller = ControllerFor(regular);
  auto* incognito_controller = ControllerFor(incognito);
  // Windows API serialization reads this actual native BaseWindow::IsActive,
  // which crosses JNI into the same profile/window ownership check.
  ASSERT_TRUE(base::test::RunUntil([&] {
    return incognito_controller->window()->IsActive() &&
           !regular_controller->window()->IsActive();
  }));
  SelectActualProfileModel(regular, false);
  EXPECT_TRUE(regular_controller->window()->IsActive());
  EXPECT_FALSE(incognito_controller->window()->IsActive());
  SelectActualProfileModel(regular, true);
  EXPECT_FALSE(regular_controller->window()->IsActive());
  EXPECT_TRUE(incognito_controller->window()->IsActive());
}

IN_PROC_BROWSER_TEST_F(
    AndroidContainerCookieStoresBrowserTest,
    IneligibleModelsCannotReuseRegisteredWindowForCookieMetadata) {
  TabAndroid* regular = TabAndroid::FromWebContents(
      chrome_test_utils::GetActiveWebContents(this));
  ASSERT_TRUE(regular);
  ASSERT_TRUE(ControllerFor(regular));
  const auto initial = StoreTabIds("0");
  ASSERT_THAT(initial, testing::Contains(ExtensionTabUtil::GetTabId(*regular)));
  for (const auto type :
       {TabModel::TabModelType::kHeadless, TabModel::TabModelType::kArchived,
        TabModel::TabModelType::kEmpty}) {
    IneligibleMetadataModel ineligible(regular, type);
    EXPECT_THAT(StoreTabIds("0"), testing::UnorderedElementsAreArray(initial));
  }
}

IN_PROC_BROWSER_TEST_F(
    AndroidContainerCookieStoresBrowserTest,
    IncognitoActivityRecreationDiscoversBothStoresAndActualFrozenTab) {
  TabAndroid* regular = TabAndroid::FromWebContents(
      chrome_test_utils::GetActiveWebContents(this));
  ASSERT_TRUE(regular);
  auto* regular_model =
      TabModelList::FindTabModelWithWindowSessionId(regular->GetWindowId());
  ASSERT_TRUE(regular_model);
  TabAndroid* named = CreateContainerTab(regular_model, kWork);
  ASSERT_TRUE(named);
  ASSERT_TRUE(content::WaitForLoadStop(named->web_contents()));
  TabAndroid* frozen = RestoreFrozenCopy(regular_model, named);
  ASSERT_TRUE(frozen);
  ASSERT_FALSE(frozen->web_contents());
  TabAndroid* incognito = CreateDefaultUiTab(named, true);
  ASSERT_TRUE(incognito);
  const int regular_id = regular->GetAndroidId();
  const int frozen_id = frozen->GetAndroidId();
  const int incognito_id = incognito->GetAndroidId();
  JNIEnv* env = base::android::AttachCurrentThread();
  auto old_activity =
      base::android::ScopedJavaGlobalRef<jobject>(ActivityFor(incognito));
  SelectActualProfileModel(incognito, true);
  RecreateActualActivity(incognito);
  ASSERT_TRUE(base::test::RunUntil([&] {
    auto* current = FindActualTab(incognito_id);
    auto* current_regular = FindActualTab(regular_id);
    auto* current_frozen = FindActualTab(frozen_id);
    if (!current || !current->web_contents() || !current_regular ||
        !current_frozen || !ControllerFor(current) ||
        !ControllerFor(current_regular) || !ControllerFor(current_frozen)) {
      return false;
    }
    auto activity = ActivityFor(current);
    return !env->IsSameObject(activity.obj(), old_activity.obj());
  }));
  regular = FindActualTab(regular_id);
  frozen = FindActualTab(frozen_id);
  incognito = FindActualTab(incognito_id);
  ASSERT_TRUE(regular);
  ASSERT_TRUE(frozen);
  ASSERT_TRUE(incognito);
  ASSERT_FALSE(frozen->web_contents());
  EXPECT_EQ(kWork, frozen->GetContainerId());
  ASSERT_TRUE(ControllerFor(regular));
  ASSERT_TRUE(ControllerFor(incognito));
  EXPECT_NE(regular->GetWindowId(), incognito->GetWindowId());
  for (TabAndroid* tab : {regular, frozen, incognito}) {
    auto* model =
        TabModelList::FindTabModelWithWindowSessionId(tab->GetWindowId());
    ASSERT_TRUE(model);
    EXPECT_EQ(model->GetProfile(), tab->GetProfile());
    EXPECT_EQ(model->GetSessionId().id(), ControllerFor(tab)->GetWindowId());
  }
  // Both stores are discoverable before selecting the restored regular UI.
  EXPECT_THAT(StoreTabIds("0", true),
              testing::Contains(ExtensionTabUtil::GetTabId(*regular)));
  EXPECT_THAT(StoreTabIds("1", true),
              testing::Contains(ExtensionTabUtil::GetTabId(*incognito)));
  EXPECT_THAT(StoreTabIds("container:" + std::string(kWork), true),
              testing::Contains(ExtensionTabUtil::GetTabId(*frozen)));
  EXPECT_FALSE(frozen->web_contents());
  SelectActualProfileModel(incognito, false);
  EXPECT_THAT(StoreTabIds("container:" + std::string(kWork)),
              testing::Contains(ExtensionTabUtil::GetTabId(*frozen)));
  EXPECT_FALSE(frozen->web_contents());
}

IN_PROC_BROWSER_TEST_F(
    AndroidContainerCookieStoresBrowserTest,
    ActualWindowReparentPreservesLiveAndFrozenIdsAfterFormerOwnerTeardown) {
  TabAndroid* regular = TabAndroid::FromWebContents(
      chrome_test_utils::GetActiveWebContents(this));
  ASSERT_TRUE(regular);
  auto* source_model =
      TabModelList::FindTabModelWithWindowSessionId(regular->GetWindowId());
  ASSERT_TRUE(source_model);
  const SessionID source_window_id = source_model->GetSessionId();
  TabAndroid* named = CreateContainerTab(source_model, kWork);
  ASSERT_TRUE(named);
  ASSERT_TRUE(content::WaitForLoadStop(named->web_contents()));
  TabAndroid* frozen = RestoreFrozenCopy(source_model, named);
  ASSERT_TRUE(frozen);
  ASSERT_FALSE(frozen->web_contents());
  const int named_api_id = ExtensionTabUtil::GetTabId(*named);
  const int frozen_api_id = ExtensionTabUtil::GetTabId(*frozen);
  const int named_android_id = named->GetAndroidId();
  const int frozen_android_id = frozen->GetAndroidId();
  base::flat_set<int> preexisting_window_ids;
  for (TabModel* model : TabModelList::models()) {
    preexisting_window_ids.insert(model->GetSessionId().id());
  }
  // This real multiwindow case requires a device supporting the production
  // create-new-window flow; unavailable launch support is a test failure.
  ASSERT_TRUE(LaunchActualTabbedWindow(named));
  TabModel* destination_model = nullptr;
  TabAndroid* destination_tab = nullptr;
  JNIEnv* env = base::android::AttachCurrentThread();
  base::android::ScopedJavaGlobalRef<jobject> destination_activity;
  base::ScopedClosureRunner finish_destination;
  ASSERT_TRUE(base::test::RunUntil([&] {
    for (TabModel* model : TabModelList::models()) {
      if (model->GetProfile() == source_model->GetProfile() &&
          model->GetTabModelType() == TabModel::TabModelType::kStandard &&
          model->activity_type() == chrome::android::ActivityType::kTabbed &&
          !preexisting_window_ids.contains(model->GetSessionId().id()) &&
          model->GetTabCount() > 0) {
        auto* tab = model->GetTabAt(0);
        if (!tab || !tab->web_contents()) {
          continue;
        }
        auto activity = ActivityFor(tab, /* require_attached= */ false);
        if (activity.is_null()) {
          continue;
        }
        // Capture this launch's real attached activity before asserting that
        // extension registration succeeded. A registration regression must
        // not leave a second activity behind when the wait fails.
        if (destination_activity.is_null() ||
            !env->IsSameObject(activity.obj(), destination_activity.obj())) {
          finish_destination = FinishActivityOnScopeExit(activity);
          destination_activity.Reset(activity);
        }
        if (ControllerFor(tab)) {
          destination_model = model;
          destination_tab = tab;
          return true;
        }
      }
    }
    return false;
  }));
  ASSERT_TRUE(destination_model);
  const SessionID destination_window_id = destination_model->GetSessionId();
  ASSERT_FALSE(destination_activity.is_null());
  ASSERT_TRUE(finish_destination);
  source_model->MoveTabToWindow(named->GetHandle(), destination_window_id,
                                destination_model->GetTabCount());
  source_model->MoveTabToWindow(frozen->GetHandle(), destination_window_id,
                                destination_model->GetTabCount());
  ASSERT_TRUE(base::test::RunUntil([&] {
    return named->GetWindowId() == destination_window_id &&
           frozen->GetWindowId() == destination_window_id;
  }));
  for (auto* tab : {named, frozen}) {
    ASSERT_TRUE(ControllerFor(tab));
    EXPECT_EQ(destination_window_id.id(), ControllerFor(tab)->GetWindowId());
    EXPECT_EQ(kWork, tab->GetContainerId());
  }
  EXPECT_EQ(named_api_id, ExtensionTabUtil::GetTabId(*named));
  EXPECT_EQ(frozen_api_id, ExtensionTabUtil::GetTabId(*frozen));
  EXPECT_EQ(named_android_id, named->GetAndroidId());
  EXPECT_EQ(frozen_android_id, frozen->GetAndroidId());
  // Reparenting may select the tab and load it as a real UI action. Its
  // immutable ID and partition must survive; metadata queries cannot load it.
  if (frozen->web_contents()) {
    EXPECT_TRUE(containers::android::WebContentsMatchesContainer(
        GetProfile(), frozen->web_contents(), kWork));
  }
  const bool lazy_after_reparent = !frozen->web_contents();
  EXPECT_THAT(StoreTabIds("container:" + std::string(kWork)),
              testing::Contains(frozen_api_id));
  EXPECT_EQ(lazy_after_reparent, !frozen->web_contents());
  destination_model->MoveTabToWindow(named->GetHandle(), source_window_id,
                                     source_model->GetTabCount());
  destination_model->MoveTabToWindow(frozen->GetHandle(), source_window_id,
                                     source_model->GetTabCount());
  ASSERT_TRUE(base::test::RunUntil([&] {
    return named->GetWindowId() == source_window_id &&
           frozen->GetWindowId() == source_window_id;
  }));
  finish_destination.RunAndReset();
  ASSERT_TRUE(base::test::RunUntil(
      [&] { return !HasControllerId(destination_window_id.id()); }));
  EXPECT_EQ(source_window_id, named->GetWindowId());
  EXPECT_EQ(source_window_id, frozen->GetWindowId());
  EXPECT_EQ(named_api_id, ExtensionTabUtil::GetTabId(*named));
  EXPECT_EQ(frozen_api_id, ExtensionTabUtil::GetTabId(*frozen));
  if (frozen->web_contents()) {
    EXPECT_TRUE(containers::android::WebContentsMatchesContainer(
        GetProfile(), frozen->web_contents(), kWork));
  }
  const bool lazy_after_teardown = !frozen->web_contents();
  EXPECT_THAT(StoreTabIds("container:" + std::string(kWork)),
              testing::Contains(frozen_api_id));
  EXPECT_EQ(lazy_after_teardown, !frozen->web_contents());
}

IN_PROC_BROWSER_TEST_F(
    AndroidContainerCookieStoresBrowserTest,
    TrustedNativePopupHasDefaultStoreAndRegisteredControllerAtCompletion) {
  VerifyActualTrustedPopupCookieStore(false);
}

IN_PROC_BROWSER_TEST_F(
    AndroidContainerCookieStoresBrowserTest,
    TrustedNativeOtrPopupPreservesPermissionAndExactStoreOwnership) {
  VerifyActualTrustedPopupCookieStore(true);
}

IN_PROC_BROWSER_TEST_F(AndroidContainerCookieStoresBrowserTest,
                       FreshNamedCreationRetainsMetadataAfterSelectorRemoval) {
  auto* regular = TabAndroid::FromWebContents(
        chrome_test_utils::GetActiveWebContents(this));
  ASSERT_TRUE(regular);
  auto* model =
      TabModelList::FindTabModelWithWindowSessionId(regular->GetWindowId());
  ASSERT_TRUE(model);
  auto* service = ContainersServiceFactory::GetForProfile(GetProfile());
  ASSERT_TRUE(service);
  auto& prefs = *GetProfile()->GetPrefs();
  const std::string first_id =
      base::Uuid::GenerateRandomV4().AsLowercaseString();
  const std::string second_id =
      base::Uuid::GenerateRandomV4().AsLowercaseString();
  const std::string rejected_id =
      base::Uuid::GenerateRandomV4().AsLowercaseString();
  const std::string first_name = "Fresh container A";
  const std::string second_name = "Fresh container B";
  auto configured = containers::GetContainersFromPrefs(prefs);
  configured.push_back(containers::MakeContainer(first_id, first_name));
  configured.push_back(containers::MakeContainer(second_id, second_name));
  containers::SetContainersToPrefs(configured, prefs);
  // Do not restore or mark either ID: the real fresh creation path must save
  // it.
  ASSERT_FALSE(containers::GetLocallyUsedContainerFromPrefs(prefs, first_id));
  ASSERT_FALSE(containers::GetLocallyUsedContainerFromPrefs(prefs, second_id));
  ASSERT_FALSE(
      containers::GetLocallyUsedContainerFromPrefs(prefs, rejected_id));
  EXPECT_EQ(nullptr, CreateContainerTab(model, rejected_id));
  EXPECT_FALSE(
      containers::GetLocallyUsedContainerFromPrefs(prefs, rejected_id));

  TabAndroid* first = CreateContainerTab(model, first_id);
  ASSERT_TRUE(first);
  ASSERT_TRUE(first->web_contents());
  auto first_snapshot =
      containers::GetLocallyUsedContainerFromPrefs(prefs, first_id);
  ASSERT_TRUE(first_snapshot);
  EXPECT_EQ(first_id, first_snapshot->id);
  EXPECT_EQ(first_name, first_snapshot->name);
  ASSERT_TRUE(content::WaitForLoadStop(first->web_contents()));
  TabAndroid* second = CreateContainerTab(model, second_id);
  ASSERT_TRUE(second);
  ASSERT_TRUE(second->web_contents());
  auto second_snapshot =
      containers::GetLocallyUsedContainerFromPrefs(prefs, second_id);
  ASSERT_TRUE(second_snapshot);
  EXPECT_EQ(second_id, second_snapshot->id);
  EXPECT_EQ(second_name, second_snapshot->name);
  ASSERT_TRUE(content::WaitForLoadStop(second->web_contents()));
  ASSERT_NE(
      first->web_contents()->GetPrimaryMainFrame()->GetStoragePartition(),
      second->web_contents()->GetPrimaryMainFrame()->GetStoragePartition());
  ASSERT_TRUE(content::ExecJs(first->web_contents(),
                              "document.cookie='reaper_fresh_use=first'"));
  ASSERT_TRUE(content::ExecJs(second->web_contents(),
                              "document.cookie='reaper_fresh_use=second'"));

  containers::ContainersSettingsHandler handler(&prefs);
  bool removed = false;
  handler.RemoveContainer(
      first_id,
      base::BindOnce(
          [](bool* completed,
             std::optional<containers::mojom::ContainerOperationError> error) {
            EXPECT_FALSE(error.has_value());
            *completed = true;
          },
          &removed));
  ASSERT_TRUE(removed);
  EXPECT_FALSE(containers::GetContainerFromPrefs(prefs, first_id));
  auto retained_snapshot =
      containers::GetLocallyUsedContainerFromPrefs(prefs, first_id);
  ASSERT_TRUE(retained_snapshot);
  EXPECT_EQ(first_id, retained_snapshot->id);
  EXPECT_EQ(first_name, retained_snapshot->name);
  auto retained = service->GetRuntimeContainerById(first_id);
  ASSERT_TRUE(retained);
  EXPECT_EQ(first_id, retained->id);
  EXPECT_EQ(first_name, retained->name);
  EXPECT_TRUE(containers::android::ResolveContainerStoragePartition(
                  GetProfile(), first_id)
                  .has_value());
  EXPECT_TRUE(containers::android::WebContentsMatchesContainer(
      GetProfile(), first->web_contents(), first_id));

  TabAndroid* reopened = CreateContainerTab(model, first_id);
  ASSERT_TRUE(reopened);
  ASSERT_TRUE(reopened->web_contents());
  ASSERT_TRUE(content::WaitForLoadStop(reopened->web_contents()));
  EXPECT_EQ(first_id, reopened->GetContainerId());
  EXPECT_TRUE(containers::android::WebContentsMatchesContainer(
      GetProfile(), reopened->web_contents(), first_id));
  EXPECT_EQ(true, content::EvalJs(
                      reopened->web_contents(),
                      "document.cookie.includes('reaper_fresh_use=first')"));
  EXPECT_EQ(false, content::EvalJs(
                       reopened->web_contents(),
                       "document.cookie.includes('reaper_fresh_use=second')"));
  EXPECT_EQ(true, content::EvalJs(
                      second->web_contents(),
                      "document.cookie.includes('reaper_fresh_use=second')"));
  auto second_runtime = service->GetRuntimeContainerById(second_id);
  ASSERT_TRUE(second_runtime);
  EXPECT_EQ(second_name, second_runtime->name);
  EXPECT_TRUE(containers::GetContainerFromPrefs(prefs, second_id));
  EXPECT_TRUE(containers::android::WebContentsMatchesContainer(
      GetProfile(), second->web_contents(), second_id));
}

}  // namespace extensions
