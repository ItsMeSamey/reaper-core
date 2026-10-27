// Copyright 2026 The Reaper Authors.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "brave/browser/extensions/android/replacement_webapp/replacement_web_app.h"

#include <limits>
#include <map>
#include <memory>
#include <utility>

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/functional/bind.h"
#include "base/memory/weak_ptr.h"
#include "base/no_destructor.h"
#include "base/scoped_observation.h"
#include "base/task/bind_post_task.h"
#include "base/timer/timer.h"
#include "chrome/browser/profiles/profile.h"
#include "chrome/browser/profiles/profile_observer.h"
#include "chrome/browser/ui/browser_window/public/browser_window_interface.h"
#include "chrome/browser/ui/browser_window/public/global_browser_collection.h"
#include "chrome/browser/ui/navigator/browser_navigator.h"
#include "chrome/browser/ui/navigator/browser_navigator_params.h"
#include "components/url_formatter/elide_url.h"
#include "components/webapps/browser/android/shortcut_info.h"
#include "components/webapps/browser/installable/installable_data.h"
#include "components/webapps/browser/installable/installable_manager.h"
#include "components/webapps/browser/installable/installable_params.h"
#include "content/public/browser/navigation_handle.h"
#include "content/public/browser/web_contents.h"
#include "content/public/browser/web_contents_observer.h"
#include "third_party/blink/public/mojom/manifest/manifest.mojom.h"
#include "ui/android/color_utils_android.h"
#include "ui/base/base_window.h"
#include "ui/base/page_transition_types.h"
#include "ui/base/window_open_disposition.h"
#include "ui/gfx/android/java_bitmap.h"
#include "url/origin.h"
#include "url/url_constants.h"

// After the JNI conversion headers above.
#include "brave/browser/extensions/android/replacement_webapp/jni_headers/ReaperReplacementWebAppBridge_jni.h"

namespace extensions {
namespace {

using Result = ManagementAPIDelegate::InstallOrLaunchWebAppResult;
using Callback = ManagementAPIDelegate::InstallOrLaunchWebAppCallback;
class ReplacementWebApp;
using Operations = std::map<int64_t, std::unique_ptr<ReplacementWebApp>>;
Operations& GetOperations();

bool IsHttpsUrl(const GURL& url) {
  return url.is_valid() && url.SchemeIs(url::kHttpsScheme) &&
         !url.has_username() && !url.has_password() &&
         url.spec().size() <= 8192;
}

// Match WebAppUrlLoader::UrlComparison::kIgnoreQueryParamsAndRef, used by
// desktop replacement-app loading. Initial redirects may change query or ref,
// but must preserve the replacement page's scheme, host, port and path.
bool IsSameReplacementPage(const GURL& actual, const GURL& expected) {
  if (!actual.is_valid() || !expected.is_valid()) {
    return false;
  }
  GURL::Replacements replacements;
  replacements.ClearQuery();
  replacements.ClearRef();
  return actual.ReplaceComponents(replacements) ==
         expected.ReplaceComponents(replacements);
}

// One bounded operation owns its native callback and Java peer. The map uses
// opaque request IDs: a late JNI result can never dereference a freed pointer.
class ReplacementWebApp : public content::WebContentsObserver,
                          public ProfileObserver {
 public:
  ReplacementWebApp(int64_t id, Profile* profile, GURL url, Callback callback)
      : id_(id),
        profile_(profile->GetWeakPtr()),
        url_(std::move(url)),
        callback_(std::move(callback)) {
    profile_observation_.Observe(profile);
  }

  ~ReplacementWebApp() override = default;

  void Start(BrowserWindowInterface* browser) {
    timer_.Start(
        FROM_HERE, base::Seconds(30),
        base::BindOnce(&ReplacementWebApp::Finish, weak_factory_.GetWeakPtr(),
                       Result::kInvalidWebApp));
    auto params = std::make_unique<NavigateParams>(
        browser, url_, ui::PAGE_TRANSITION_FROM_API);
    params->disposition = WindowOpenDisposition::NEW_FOREGROUND_TAB;
    params->window_action = NavigateParams::WindowAction::kShowWindow;
    NavigateParams* raw_params = params.get();
    Navigate(raw_params,
             base::BindOnce(
                 [](std::unique_ptr<NavigateParams> params,
                    base::WeakPtr<ReplacementWebApp> owner,
                    base::WeakPtr<content::NavigationHandle> handle) {
                   if (owner) {
                     owner->OnNavigated(handle);
                   }
                 },
                 std::move(params), weak_factory_.GetWeakPtr()));
  }

  bool IsCurrent() const {
    return profile_ && web_contents() &&
           web_contents()->GetBrowserContext() == profile_.get() &&
           IsSameReplacementPage(web_contents()->GetLastCommittedURL(), url_);
  }

  Profile* profile() const { return profile_.get(); }

  void Finish(Result result) {
    // Keep ourselves alive through Java cleanup and the extension response, but
    // make repeated callbacks and reentrant API calls unable to find this ID.
    auto self = std::move(GetOperations().at(id_));
    GetOperations().erase(id_);
    weak_factory_.InvalidateWeakPtrs();
    timer_.Stop();
    Observe(nullptr);
    profile_observation_.Reset();
    if (!java_bridge_.is_null()) {
      Java_ReaperReplacementWebAppBridge_destroy(
          base::android::AttachCurrentThread(), java_bridge_);
      java_bridge_.Reset();
    }
    std::move(callback_).Run(result);
  }

 private:
  void OnNavigated(base::WeakPtr<content::NavigationHandle> handle) {
    if (!profile_ || !handle ||
        handle->GetWebContents()->GetBrowserContext() != profile_.get()) {
      Finish(Result::kUnknownError);
      return;
    }
    Observe(handle->GetWebContents());
    if (!web_contents()->IsLoading()) {
      DidStopLoading();
    }
  }

  void DidStartNavigation(content::NavigationHandle* handle) override {
    if (checking_ && handle->IsInPrimaryMainFrame() &&
        !handle->IsSameDocument()) {
      Finish(Result::kUnknownError);
    }
  }

  void DidFinishNavigation(content::NavigationHandle* handle) override {
    if (handle->IsInPrimaryMainFrame() && handle->HasCommitted() &&
        (handle->IsErrorPage() || !IsCurrent())) {
      Finish(Result::kInvalidWebApp);
    }
  }

  void DidStopLoading() override {
    if (checking_) {
      return;
    }
    if (!IsCurrent()) {
      Finish(Result::kInvalidWebApp);
      return;
    }
    auto* manager =
        webapps::InstallableManager::FromWebContents(web_contents());
    if (!manager) {
      Finish(Result::kUnknownError);
      return;
    }
    checking_ = true;
    webapps::InstallableParams params;
    params.check_eligibility = true;
    params.valid_primary_icon = true;
    params.prefer_maskable_icon = true;
    params.installable_criteria =
        webapps::InstallableCriteria::kValidManifestWithIcons;
    manager->GetData(params, base::BindOnce(&ReplacementWebApp::OnInstallable,
                                            weak_factory_.GetWeakPtr()));
  }

  void OnInstallable(const webapps::InstallableData& data) {
    const auto& manifest = *data.manifest;
    if (!IsCurrent() || !data.errors.empty() ||
        !data.installable_check_passed || !IsHttpsUrl(*data.manifest_url) ||
        !IsHttpsUrl(manifest.start_url) || !IsHttpsUrl(manifest.id) ||
        !IsHttpsUrl(manifest.scope) ||
        !url::Origin::Create(url_).IsSameOriginWith(manifest.start_url) ||
        !url::Origin::Create(manifest.start_url)
             .IsSameOriginWith(manifest.id) ||
        !url::Origin::Create(manifest.start_url)
             .IsSameOriginWith(manifest.scope) ||
        !manifest.start_url.spec().starts_with(manifest.scope.spec()) ||
        !data.primary_icon || data.primary_icon->drawsNothing() ||
        data.primary_icon->width() > 2048 ||
        data.primary_icon->height() > 2048) {
      Finish(Result::kInvalidWebApp);
      return;
    }
    webapps::ShortcutInfo info(manifest.start_url);
    info.UpdateFromManifest(manifest);
    if (info.name.empty() || info.name.size() > 1024 ||
        info.short_name.size() > 1024 ||
        (info.display != blink::mojom::DisplayMode::kStandalone &&
         info.display != blink::mojom::DisplayMode::kMinimalUi &&
         info.display != blink::mojom::DisplayMode::kFullscreen)) {
      Finish(Result::kInvalidWebApp);
      return;
    }
    // Includes time for both Chromium consent and the launcher's pin consent.
    // Android has no negative pin callback, so timeout is an honest failure.
    timer_.Start(
        FROM_HERE, base::Minutes(3),
        base::BindOnce(&ReplacementWebApp::Finish, weak_factory_.GetWeakPtr(),
                       Result::kUnknownError));
    JNIEnv* env = base::android::AttachCurrentThread();
    java_bridge_ = Java_ReaperReplacementWebAppBridge_create(
        env, id_, web_contents()->GetJavaWebContents(),
        profile_->GetJavaObject(),
        base::android::ConvertUTF8ToJavaString(env, manifest.id.spec()),
        base::android::ConvertUTF8ToJavaString(env, info.url.spec()),
        base::android::ConvertUTF8ToJavaString(env, info.scope.spec()),
        base::android::ConvertUTF16ToJavaString(env, info.name),
        base::android::ConvertUTF16ToJavaString(env, info.short_name),
        gfx::ConvertToJavaBitmap(*data.primary_icon),
        data.has_maskable_primary_icon, static_cast<int>(info.display),
        static_cast<int>(info.orientation),
        ui::OptionalSkColorToJavaColor(info.theme_color),
        ui::OptionalSkColorToJavaColor(info.background_color),
        base::android::ConvertUTF16ToJavaString(
            env,
            url_formatter::FormatUrlForSecurityDisplay(
                info.url, url_formatter::SchemeDisplay::OMIT_CRYPTOGRAPHIC)));
    if (java_bridge_.is_null()) {
      Finish(Result::kUnknownError);
    }
  }

  void WebContentsDestroyed() override { Finish(Result::kUnknownError); }
  void OnProfileWillBeDestroyed(Profile*) override {
    Finish(Result::kUnknownError);
  }

  const int64_t id_;
  base::WeakPtr<Profile> profile_;
  const GURL url_;
  Callback callback_;
  bool checking_ = false;
  base::OneShotTimer timer_;
  base::android::ScopedJavaGlobalRef<jobject> java_bridge_;
  base::ScopedObservation<Profile, ProfileObserver> profile_observation_{this};
  base::WeakPtrFactory<ReplacementWebApp> weak_factory_{this};
};

Operations& GetOperations() {
  static base::NoDestructor<Operations> operations;
  return *operations;
}

}  // namespace

bool CanInstallAndroidReplacementWebApp(content::BrowserContext* context) {
  Profile* profile = Profile::FromBrowserContext(context);
  return !profile->IsOffTheRecord() && !profile->IsGuestSession() &&
         Java_ReaperReplacementWebAppBridge_canInstall(
             base::android::AttachCurrentThread(), profile->GetJavaObject());
}

void InstallAndroidReplacementWebApp(content::BrowserContext* context,
                                     const GURL& url,
                                     Callback callback) {
  // ManagementInstallReplacementWebAppFunction always returns RespondLater().
  // Even rejection and a synchronously completed Navigate must reply later.
  callback = base::BindPostTaskToCurrentDefault(std::move(callback));
  Profile* profile = Profile::FromBrowserContext(context);
  auto* browser =
      GlobalBrowserCollection::GetInstance()->GetLastActiveBrowser();
  if (!IsHttpsUrl(url) || !CanInstallAndroidReplacementWebApp(context) ||
      !browser || browser->GetProfile() != profile || !browser->GetWindow() ||
      !browser->GetWindow()->IsActive()) {
    std::move(callback).Run(Result::kUnknownError);
    return;
  }
  for (const auto& [id, operation] : GetOperations()) {
    if (operation->profile() == profile) {
      std::move(callback).Run(Result::kUnknownError);
      return;
    }
  }
  static int64_t next_id = 0;
  if (next_id == std::numeric_limits<int64_t>::max()) {
    std::move(callback).Run(Result::kUnknownError);
    return;
  }
  const int64_t id = ++next_id;
  auto operation = std::make_unique<ReplacementWebApp>(id, profile, url,
                                                       std::move(callback));
  auto* pointer = operation.get();
  GetOperations().emplace(id, std::move(operation));
  pointer->Start(browser);
}

static bool JNI_ReaperReplacementWebAppBridge_IsCurrent(JNIEnv*, int64_t id) {
  const auto entry = GetOperations().find(id);
  return entry != GetOperations().end() && entry->second->IsCurrent();
}

static void JNI_ReaperReplacementWebAppBridge_OnFinished(JNIEnv*,
                                                         int64_t id,
                                                         bool success) {
  const auto entry = GetOperations().find(id);
  if (entry != GetOperations().end()) {
    entry->second->Finish(success ? Result::kSuccess : Result::kUnknownError);
  }
}

}  // namespace extensions

DEFINE_JNI(ReaperReplacementWebAppBridge)
