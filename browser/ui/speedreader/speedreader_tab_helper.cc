/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/browser/ui/speedreader/speedreader_tab_helper.h"

#include <initializer_list>
#if BUILDFLAG(IS_ANDROID)
#include <memory>
#endif
#include <string>
#include <string_view>
#include <utility>

#include "base/check.h"
#include "base/check_is_test.h"
#include "base/feature_list.h"
#include "base/functional/bind.h"
#include "base/functional/callback_helpers.h"
#include "base/json/json_writer.h"
#include "base/no_destructor.h"
#include "base/strings/strcat.h"
#include "base/strings/string_number_conversions.h"
#include "base/strings/string_util.h"
#include "base/strings/utf_string_conversions.h"
#if BUILDFLAG(IS_ANDROID)
#include "base/task/single_thread_task_runner.h"
#endif
#include "brave/browser/brave_browser_process.h"
#include "brave/browser/speedreader/page_distiller.h"
#include "brave/browser/speedreader/speedreader_service_factory.h"
#include "brave/browser/ui/speedreader/speedreader_bubble_view.h"
#include "brave/components/speedreader/common/features.h"
#if BUILDFLAG(IS_ANDROID)
#include "brave/components/speedreader/speedreader_pref_names.h"
#include "components/prefs/pref_service.h"
#include "components/user_prefs/user_prefs.h"
#endif
#include "brave/components/speedreader/speedreader_extended_info_handler.h"
#include "brave/components/speedreader/speedreader_rewriter_service.h"
#include "brave/components/speedreader/speedreader_service.h"
#include "brave/components/speedreader/speedreader_util.h"
#include "brave/components/speedreader/tts_player.h"
#include "chrome/common/chrome_isolated_world_ids.h"
#include "components/dom_distiller/content/browser/distillable_page_utils.h"
#include "components/grit/brave_components_resources.h"
#include "components/grit/brave_components_strings.h"
#include "content/public/browser/browser_context.h"
#include "content/public/browser/navigation_controller.h"
#include "content/public/browser/navigation_entry.h"
#include "content/public/browser/navigation_handle.h"
#include "content/public/browser/reload_type.h"
#include "content/public/browser/web_contents.h"
#include "third_party/blink/public/common/web_preferences/web_preferences.h"
#include "ui/base/l10n/l10n_util.h"
#include "ui/base/resource/resource_bundle.h"

#if !BUILDFLAG(IS_ANDROID)
#include "brave/browser/ui/brave_browser_window.h"
#include "chrome/browser/ui/browser.h"
#endif

namespace speedreader {
#if BUILDFLAG(IS_ANDROID)
namespace {
class AndroidTtsPlayerDelegate : public TtsPlayer::Delegate {
 public:
  void RequestReadingContent(
      content::WebContents* web_contents,
      base::OnceCallback<void(base::Value)> callback) override {
    auto* page_distiller = SpeedreaderTabHelper::GetPageDistiller(web_contents);
    if (page_distiller) {
      page_distiller->GetTextToSpeak(std::move(callback));
    } else {
      std::move(callback).Run(base::Value());
    }
  }
};
}  // namespace
#endif

std::u16string GetSpeedreaderData(
    std::initializer_list<std::pair<std::string_view, int>> resources) {
  base::DictValue sr_data;
  sr_data.Set("ttsEnabled", features::kSpeedreaderTTS.Get());
#if BUILDFLAG(IS_ANDROID)
  sr_data.Set("androidControls", true);
#endif
  for (const auto& r : resources) {
    sr_data.Set(r.first, l10n_util::GetStringUTF16(r.second));
  }

  return base::StrCat(
      {u"speedreaderData = ",
       base::UTF8ToUTF16(base::WriteJson(sr_data).value_or("{}"))});
}

SpeedreaderTabHelper::SpeedreaderTabHelper(
    content::WebContents* web_contents,
    SpeedreaderService& speedreader_service,
    SpeedreaderRewriterService* rewriter_service)
    : content::WebContentsObserver(web_contents),
      content::WebContentsUserData<SpeedreaderTabHelper>(*web_contents),
      PageDistiller(web_contents),
      speedreader_service_(speedreader_service),
      rewriter_service_(rewriter_service) {
  dom_distiller::AddObserver(web_contents, this);
  speedreader_service_observation_.Observe(&speedreader_service_.get());
  tts_player_observation_.Observe(speedreader::TtsPlayer::GetInstance());
#if BUILDFLAG(IS_ANDROID)
  TtsPlayer::GetInstance()->set_delegate(
      std::make_unique<AndroidTtsPlayerDelegate>());
#endif
  is_visible_ = web_contents->GetVisibility() != content::Visibility::HIDDEN;
}

SpeedreaderTabHelper::~SpeedreaderTabHelper() {
  DCHECK(!speedreader_bubble_);
  DCHECK(!speedreader_service_observation_.IsObserving());
}

// static
void SpeedreaderTabHelper::MaybeCreateForWebContents(
    content::WebContents* contents) {
  if (!base::FeatureList::IsEnabled(features::kSpeedreaderFeature)) {
    return;
  }

  // Check if speedreader is available (feature enabled and not disabled by
  // policy)
  auto* speedreader_service = SpeedreaderServiceFactory::GetForBrowserContext(
      contents->GetBrowserContext());
  if (!speedreader_service) {
    return;
  }

  auto* rewriter_service =
      g_brave_browser_process->speedreader_rewriter_service();
  if (!rewriter_service) {
    CHECK_IS_TEST();
    return;
  }

  SpeedreaderTabHelper::CreateForWebContents(contents, *speedreader_service,
                                             rewriter_service);
}

// static
void SpeedreaderTabHelper::BindSpeedreaderHost(
    mojo::PendingAssociatedReceiver<mojom::SpeedreaderHost> receiver,
    content::RenderFrameHost* rfh) {
  auto* sender = content::WebContents::FromRenderFrameHost(rfh);
  if (!sender) {
    return;
  }
  auto* tab_helper = SpeedreaderTabHelper::FromWebContents(sender);
  if (!tab_helper) {
    return;
  }
  tab_helper->BindReceiver(std::move(receiver));
}

// static
PageDistiller* SpeedreaderTabHelper::GetPageDistiller(
    content::WebContents* contents) {
  auto* tab_helper = SpeedreaderTabHelper::FromWebContents(contents);
  if (!tab_helper) {
    return nullptr;
  }
  return tab_helper;
}

void SpeedreaderTabHelper::BindReceiver(
    mojo::PendingAssociatedReceiver<mojom::SpeedreaderHost> receiver) {
  receiver_.reset();
  receiver_.Bind(std::move(receiver));
}

base::WeakPtr<SpeedreaderTabHelper> SpeedreaderTabHelper::GetWeakPtr() {
  return weak_factory_.GetWeakPtr();
}

#if BUILDFLAG(IS_ANDROID)
SpeedreaderTabHelper::AndroidReaderState
SpeedreaderTabHelper::GetAndroidReaderState() const {
  if (!web_contents() || !web_contents()->GetPrimaryMainFrame() ||
      !web_contents()->GetLastCommittedURL().SchemeIsHTTPOrHTTPS() ||
      web_contents()->GetPrimaryMainFrame()->IsErrorDocument()) {
    return AndroidReaderState::kUnavailable;
  }
  // Returning to the original page remains possible after a policy change.
  if (IsDistilled(distill_state_)) {
    return web_contents()->IsLoading() ? AndroidReaderState::kBusy
                                       : AndroidReaderState::kReader;
  }
  auto* prefs = user_prefs::UserPrefs::Get(web_contents()->GetBrowserContext());
  if (!prefs || !prefs->GetBoolean(kSpeedreaderEnabled) ||
      !base::FeatureList::IsEnabled(features::kSpeedreaderFeature)) {
    return AndroidReaderState::kUnavailable;
  }
  if (android_manual_distill_pending_ || IsDistilling(distill_state_) ||
      IsDistillReverting(distill_state_) || web_contents()->IsLoading()) {
    return AndroidReaderState::kBusy;
  }
  if (!IsViewOriginal(distill_state_)) {
    return AndroidReaderState::kUnavailable;
  }
  // Manual reading also supports pages outside the automatic URL heuristic.
  return AndroidReaderState::kOriginal;
}

bool SpeedreaderTabHelper::ToggleReaderModeForAndroid() {
  auto state = GetAndroidReaderState();
  if (state != AndroidReaderState::kOriginal &&
      state != AndroidReaderState::kReader) {
    return false;
  }
  const auto weak_this = weak_factory_.GetWeakPtr();
  ProcessIconClick();
  if (!weak_this || !weak_this->web_contents()) {
    return false;
  }
  if (state == AndroidReaderState::kReader) {
    return !IsDistilled(weak_this->distill_state_);
  }
  return weak_this->android_manual_distill_pending_ ||
         IsDistilling(weak_this->distill_state_) ||
         IsDistilled(weak_this->distill_state_);
}

void SpeedreaderTabHelper::OnAndroidDocumentSource(uint64_t generation,
                                                   bool success,
                                                   std::string html) {
  if (!web_contents() || !android_manual_distill_pending_ ||
      generation != android_request_generation_) {
    return;
  }
  android_manual_distill_pending_ = false;
  auto* prefs = user_prefs::UserPrefs::Get(web_contents()->GetBrowserContext());
  if (!prefs || !prefs->GetBoolean(kSpeedreaderEnabled) ||
      !base::FeatureList::IsEnabled(features::kSpeedreaderFeature) ||
      !IsViewOriginal(distill_state_) || web_contents()->IsLoading()) {
    UpdateUI();
    return;
  }
  OnGetDocumentSource(success, std::move(html));
}
#endif

void SpeedreaderTabHelper::ProcessIconClick() {
#if BUILDFLAG(IS_ANDROID)
  auto state = GetAndroidReaderState();
  if (state != AndroidReaderState::kOriginal &&
      state != AndroidReaderState::kReader) {
    return;
  }
#endif
  if (IsViewOriginal(distill_state_)) {
    const auto& vo = std::get<ViewOriginal>(distill_state_);
    if (!vo.was_auto_distilled ||
        !speedreader_service_->IsAllowedForSite(web_contents())) {
#if BUILDFLAG(IS_ANDROID)
      android_manual_distill_pending_ = true;
      const uint64_t generation = ++android_request_generation_;
      const auto weak_this = weak_factory_.GetWeakPtr();
      const auto source_document =
          web_contents()->GetPrimaryMainFrame()->GetWeakDocumentPtr();
      UpdateUI();
      if (!weak_this || !weak_this->web_contents() ||
          !source_document.AsRenderFrameHostIfValid() ||
          source_document.AsRenderFrameHostIfValid() !=
              weak_this->web_contents()->GetPrimaryMainFrame() ||
          !weak_this->android_manual_distill_pending_ ||
          generation != weak_this->android_request_generation_) {
        return;
      }
      auto* prefs = user_prefs::UserPrefs::Get(
          weak_this->web_contents()->GetBrowserContext());
      if (!prefs || !prefs->GetBoolean(kSpeedreaderEnabled) ||
          !base::FeatureList::IsEnabled(features::kSpeedreaderFeature) ||
          !IsViewOriginal(weak_this->distill_state_) ||
          weak_this->web_contents()->IsLoading()) {
        weak_this->android_manual_distill_pending_ = false;
        ++weak_this->android_request_generation_;
        weak_this->UpdateUI();
        return;
      }
      weak_this->GetDistilledHTML(
          base::BindOnce(&SpeedreaderTabHelper::OnAndroidDocumentSource,
                         weak_this, generation));
#else
      GetDistilledHTML(
          base::BindOnce(&SpeedreaderTabHelper::OnGetDocumentSource,
                         weak_factory_.GetWeakPtr()));
#endif
    } else {
      TransitStateTo(Distilling(Distilling::Reason::kAutomatic));
    }
  } else if (IsDistilled(distill_state_)) {
    OnShowOriginalPage();
  }
}

SpeedreaderBubbleView* SpeedreaderTabHelper::speedreader_bubble_view() const {
  return speedreader_bubble_;
}

bool SpeedreaderTabHelper::MaybeUpdateCachedState(
    content::NavigationHandle* handle) {
  auto* entry = handle->GetNavigationEntry();
  if (!entry || (handle->GetRestoreType() != content::RestoreType::kRestored &&
                 !handle->IsServedFromBackForwardCache())) {
    return false;
  }

  const DistillState state = SpeedreaderExtendedInfoHandler::GetCachedMode(
      entry, &speedreader_service_.get());
  if (IsDistilled(state)) {
    if (handle->IsServedFromBackForwardCache() ||
        IsDistilledAutomatically(state)) {
      distill_state_ = state;
      return true;
    }
  }
  SpeedreaderExtendedInfoHandler::ClearPersistedData(entry);

  return false;
}

void SpeedreaderTabHelper::OnBubbleClosed() {
  speedreader_bubble_ = nullptr;
#if BUILDFLAG(IS_ANDROID)
  const auto weak_this = weak_factory_.GetWeakPtr();
#endif
  UpdateUI();
#if BUILDFLAG(IS_ANDROID)
  if (!weak_this || !weak_this->web_contents()) {
    return;
  }
#endif

  observers_.Notify(&Observer::OnTuneBubbleClosed);
}

void SpeedreaderTabHelper::AddObserver(Observer* observer) {
  observers_.AddObserver(observer);
}

void SpeedreaderTabHelper::RemoveObserver(Observer* observer) {
  observers_.RemoveObserver(observer);
}

void SpeedreaderTabHelper::ShowSpeedreaderBubble(
    SpeedreaderBubbleLocation location) {
#if !BUILDFLAG(IS_ANDROID)
  if (auto* browser_window = BraveBrowserWindow::From(
          BrowserWindow::FindBrowserWindowWithWebContents(web_contents()))) {
    speedreader_bubble_ = browser_window->ShowSpeedreaderBubble(this, location);
  }
#endif
}

void SpeedreaderTabHelper::HideSpeedreaderBubble() {
  if (speedreader_bubble_) {
    speedreader_bubble_->Hide();
    speedreader_bubble_ = nullptr;
  }
}

void SpeedreaderTabHelper::OnShowOriginalPage() {
  if (!IsDistilled(distill_state_)) {
    return;
  }
  TransitStateTo(ViewOriginal());
}

void SpeedreaderTabHelper::OnTtsPlayPause(int paragraph_index) {
#if BUILDFLAG(IS_ANDROID)
  if (!IsDistilled(distill_state_) || !features::kSpeedreaderTTS.Get()) {
    return;
  }
#endif
  auto& tts_controller =
      speedreader::TtsPlayer::GetInstance()->GetControllerFor(web_contents());
  if (tts_controller.IsPlaying() &&
      tts_controller.IsPlayingRequestedWebContents(paragraph_index)) {
    tts_controller.Pause();
  } else {
#if BUILDFLAG(IS_ANDROID)
    if (!tts_controller.IsPlayingRequestedWebContents()) {
      const auto settings = speedreader_service_->GetTtsSettings();
      const auto weak_this = weak_factory_.GetWeakPtr();
      const auto source_document =
          web_contents()->GetPrimaryMainFrame()->GetWeakDocumentPtr();
      tts_controller.Stop();
      auto* rfh = source_document.AsRenderFrameHostIfValid();
      if (!weak_this || !rfh || !weak_this->web_contents() ||
          weak_this->web_contents()->GetPrimaryMainFrame() != rfh ||
          !IsDistilled(weak_this->distill_state_)) {
        return;
      }
      TtsPlayer::GetInstance()->SetSpeed(
          static_cast<double>(settings.speed) / 100.0);
      TtsPlayer::GetInstance()->SetVoice(settings.voice);
    }
#endif
    tts_controller.Play(paragraph_index);
  }
}

#if !BUILDFLAG(IS_ANDROID)
void SpeedreaderTabHelper::OnToolbarStateChanged(mojom::MainButtonType button) {
  switch (button) {
    case mojom::MainButtonType::None:
      SetDocumentAttribute("data-toolbar-button", "");
      break;
    case mojom::MainButtonType::Tune:
      SetDocumentAttribute("data-toolbar-button", "tune");
      break;
    case mojom::MainButtonType::Appearance:
      SetDocumentAttribute("data-toolbar-button", "appearance");
      break;
    case mojom::MainButtonType::TextToSpeech:
      SetDocumentAttribute("data-toolbar-button", "tts");
      break;
    case mojom::MainButtonType::AI:
      SetDocumentAttribute("data-toolbar-button", "ai");
      break;
  }
}

#endif

void SpeedreaderTabHelper::ClearPersistedData() {
  if (auto* entry = web_contents()->GetController().GetLastCommittedEntry()) {
    SpeedreaderExtendedInfoHandler::ClearPersistedData(entry);
  }
}

void SpeedreaderTabHelper::ReloadContents() {
  web_contents()->GetController().Reload(content::ReloadType::NORMAL, false);
}

void SpeedreaderTabHelper::ProcessNavigation(
    content::NavigationHandle* navigation_handle,
    bool finish_navigation) {
  if (!navigation_handle->IsInPrimaryMainFrame() ||
      navigation_handle->IsSameDocument() ||
      MaybeUpdateCachedState(navigation_handle)) {
    UpdateUI();
    return;
  }

  if (finish_navigation) {
    if (navigation_handle->IsErrorPage() ||
        web_contents()->GetPrimaryMainFrame()->IsErrorDocument()) {
      TransitStateTo(ViewOriginal(ViewOriginal::Reason::kNotDistillable, false),
                     true);
    }
    return;
  }

  if (IsDistilling(distill_state_)) {
    // State will be determined in OnDistillComplete.
    return;
  }
  if (IsDistillReverting(distill_state_)) {
    TransitStateTo(ViewOriginal(), true);
    return;
  }

  auto* nav_entry = navigation_handle->GetNavigationEntry();
  const bool url_looks_readable =
      nav_entry && nav_entry->GetVirtualURL().SchemeIsHTTPOrHTTPS() &&
      rewriter_service_->URLLooksReadable(navigation_handle->GetURL());

  const bool enabled_for_site =
      speedreader_service_->IsAllowedForSite(navigation_handle->GetURL());

  const auto reason = url_looks_readable
                          ? ViewOriginal::Reason::kNone
                          : ViewOriginal::Reason::kNotDistillable;
#if BUILDFLAG(IS_ANDROID)
  const auto weak_this = weak_factory_.GetWeakPtr();
  const uint64_t generation = android_request_generation_;
  uint64_t transition_generation = android_transition_generation_;
  const GURL navigation_url = navigation_handle->GetURL();
  const bool homepage =
      !navigation_url.has_path() || navigation_url.path() == "/";
  const bool explicit_enabled_for_site =
      !homepage && features::kSpeedreaderExplicitPref.Get() &&
      speedreader_service_->IsEnabledForSite(navigation_url);
#endif
  TransitStateTo(DistillReverting(reason, false), true);
#if BUILDFLAG(IS_ANDROID)
  if (!weak_this || !weak_this->web_contents() ||
      generation != weak_this->android_request_generation_ ||
      transition_generation + 1 != weak_this->android_transition_generation_) {
    return;
  }
  transition_generation = weak_this->android_transition_generation_;
#endif
  TransitStateTo(ViewOriginal(), true);
#if BUILDFLAG(IS_ANDROID)
  if (!weak_this || !weak_this->web_contents() ||
      generation != weak_this->android_request_generation_ ||
      transition_generation + 1 != weak_this->android_transition_generation_) {
    return;
  }
  auto* prefs = user_prefs::UserPrefs::Get(
      weak_this->web_contents()->GetBrowserContext());
  if (enabled_for_site && prefs && prefs->GetBoolean(kSpeedreaderEnabled) &&
      base::FeatureList::IsEnabled(features::kSpeedreaderFeature) &&
      weak_this->speedreader_service_->IsAllowedForSite(navigation_url) &&
      (url_looks_readable || explicit_enabled_for_site)) {
    TransitStateTo(Distilling(Distilling::Reason::kAutomatic), true);
  }
#else
  if (enabled_for_site) {
    // Check if url is pointed to the homepage, basically these pages aren't
    // readable. We've got the same check in speedreader::IsURLLooksReadable
    const bool homepage = !navigation_handle->GetURL().has_path() ||
                          navigation_handle->GetURL().path() == "/";

    // Enable speedreader if the user explicitly enabled speedreader on the
    // site.
    const bool explicit_enabled_for_size =
        !homepage && features::kSpeedreaderExplicitPref.Get() &&
        speedreader_service_->IsEnabledForSite(navigation_handle->GetURL());
    if (url_looks_readable || explicit_enabled_for_size) {
      // Speedreader enabled for this page.
      TransitStateTo(Distilling(Distilling::Reason::kAutomatic), true);
    }
  }
#endif
}

#if BUILDFLAG(IS_ANDROID)
void SpeedreaderTabHelper::FinishAndroidUINotification() {
  android_notifying_observers_ = false;
  if (!android_ui_update_pending_) {
    return;
  }
  android_ui_update_pending_ = false;
  // Post only after observer iteration has finished. An observer may pump a
  // nested RunLoop, so posting from the nested UpdateUI would requeue forever.
  base::SingleThreadTaskRunner::GetCurrentDefault()->PostTask(
      FROM_HERE, base::BindOnce(&SpeedreaderTabHelper::UpdateUI,
                                weak_factory_.GetWeakPtr()));
}
#endif

void SpeedreaderTabHelper::UpdateUI() {
#if BUILDFLAG(IS_ANDROID)
  // The observer lists disallow synchronous reentrant iteration. A state or
  // navigation change from an observer must survive, but its UI notification
  // waits until the current notification has unwound.
  if (android_notifying_observers_) {
    android_ui_update_pending_ = true;
    return;
  }
  android_ui_update_pending_ = false;
  android_notifying_observers_ = true;
  const auto weak_this = weak_factory_.GetWeakPtr();
  const uint64_t generation = android_request_generation_;
  const uint64_t transition_generation = android_transition_generation_;
#endif
  if (IsDistilled(distill_state_)) {
    UpdateState(State::kDistilled);
  } else if (IsDistillable(distill_state_)) {
    UpdateState(State::kDistillable);
  } else {
    UpdateState(State::kNotDistillable);
  }

#if BUILDFLAG(IS_ANDROID)
  if (!weak_this || !weak_this->web_contents()) {
    return;
  }
  if (generation != weak_this->android_request_generation_ ||
      transition_generation != weak_this->android_transition_generation_) {
    weak_this->FinishAndroidUINotification();
    return;
  }
#endif
  observers_.Notify(&Observer::OnDistillStateUpdated);
#if BUILDFLAG(IS_ANDROID)
  if (!weak_this || !weak_this->web_contents()) {
    return;
  }
  weak_this->FinishAndroidUINotification();
  if (generation != weak_this->android_request_generation_ ||
      transition_generation != weak_this->android_transition_generation_) {
    return;
  }
#endif

  if (!is_visible_) {
    return;
  }
#if !BUILDFLAG(IS_ANDROID)
  if (auto* browser_window = BraveBrowserWindow::From(
          BrowserWindow::FindBrowserWindowWithWebContents(web_contents()))) {
    browser_window->UpdateReaderModeToolbar();
  }
#endif
}

void SpeedreaderTabHelper::ReadyToCommitNavigation(
    content::NavigationHandle* navigation_handle) {
  if (!navigation_handle->IsInPrimaryMainFrame()) {
    return;
  }

  const bool is_distilled = IsDistilled(PageDistillState());

  blink::web_pref::WebPreferences prefs =
      web_contents()->GetOrCreateWebPreferences();
  if (prefs.page_in_reader_mode != is_distilled) {
    prefs.page_in_reader_mode = IsDistilled(PageDistillState());
    web_contents()->SetWebPreferences(prefs);
  }
}

void SpeedreaderTabHelper::DidStartNavigation(
    content::NavigationHandle* navigation_handle) {
#if BUILDFLAG(IS_ANDROID)
  if (navigation_handle->IsInPrimaryMainFrame() &&
      !navigation_handle->IsSameDocument()) {
    ++android_request_generation_;
    android_manual_distill_pending_ = false;
    if (navigation_handle->GetURL().GetWithoutRef() !=
        web_contents()->GetLastCommittedURL().GetWithoutRef()) {
      single_show_content_.clear();
    }
  }
#endif
  ProcessNavigation(navigation_handle);
}

void SpeedreaderTabHelper::DidRedirectNavigation(
    content::NavigationHandle* navigation_handle) {
  ProcessNavigation(navigation_handle);
}

void SpeedreaderTabHelper::DidFinishNavigation(
    content::NavigationHandle* navigation_handle) {
  ProcessNavigation(navigation_handle, true);
}

void SpeedreaderTabHelper::DidStopLoading() {
  auto* entry = web_contents()->GetController().GetLastCommittedEntry();
  if (entry) {
    SpeedreaderExtendedInfoHandler::PersistMode(entry, distill_state_);
  }
}

void SpeedreaderTabHelper::DOMContentLoaded(
    content::RenderFrameHost* render_frame_host) {
  if (!render_frame_host->IsInPrimaryMainFrame() ||
      !IsDistilled(distill_state_)) {
    return;
  }
#if BUILDFLAG(IS_ANDROID)
  const auto weak_this = weak_factory_.GetWeakPtr();
  const uint64_t generation = android_request_generation_;
  const uint64_t transition_generation = android_transition_generation_;
  const auto source_document = render_frame_host->GetWeakDocumentPtr();
#endif
  UpdateUI();
#if BUILDFLAG(IS_ANDROID)
  if (!weak_this || !weak_this->web_contents() ||
      generation != weak_this->android_request_generation_ ||
      transition_generation != weak_this->android_transition_generation_ ||
      !source_document.AsRenderFrameHostIfValid() ||
      source_document.AsRenderFrameHostIfValid() !=
          weak_this->web_contents()->GetPrimaryMainFrame() ||
      !IsDistilled(weak_this->distill_state_)) {
    return;
  }
  render_frame_host = source_document.AsRenderFrameHostIfValid();
#endif

  static base::NoDestructor<std::u16string> kSpeedreaderData(GetSpeedreaderData(
      {{"showOriginalLinkText", IDS_READER_MODE_SHOW_ORIGINAL_PAGE_LINK},
       {"minutesText", IDS_READER_MODE_MINUTES_TEXT},
#if defined(IDS_READER_MODE_TEXT_TO_SPEECH_PLAY_PAUSE)
       {"playButtonTitle", IDS_READER_MODE_TEXT_TO_SPEECH_PLAY_PAUSE}
#endif
      }));

  static base::NoDestructor<std::u16string> kJsScript(base::UTF8ToUTF16(
      ui::ResourceBundle::GetSharedInstance().LoadDataResourceString(
          IDR_SPEEDREADER_JS_DESKTOP)));

  static base::NoDestructor<std::u16string> kLoadScript(*kSpeedreaderData +
                                                        *kJsScript);

  render_frame_host->ExecuteJavaScriptInIsolatedWorld(
      *kLoadScript, base::DoNothing(), ISOLATED_WORLD_ID_BRAVE_INTERNAL);

  observers_.Notify(&Observer::OnContentsReady);
}

void SpeedreaderTabHelper::OnVisibilityChanged(content::Visibility visibility) {
  is_visible_ = visibility != content::Visibility::HIDDEN;
  UpdateUI();
}

void SpeedreaderTabHelper::WebContentsDestroyed() {
  speedreader_service_observation_.Reset();
  tts_player_observation_.Reset();
  dom_distiller::RemoveObserver(web_contents(), this);
  SetWebContents(nullptr);
  HideSpeedreaderBubble();
}

bool SpeedreaderTabHelper::IsPageDistillationAllowed() {
  return IsDistilling(distill_state_) || IsDistilled(distill_state_);
}

bool SpeedreaderTabHelper::IsPageContentPresent() {
  return !single_show_content_.empty();
}

std::string SpeedreaderTabHelper::TakePageContent() {
  return std::move(single_show_content_);
}

void SpeedreaderTabHelper::OnDistillComplete(DistillationResult result) {
#if BUILDFLAG(IS_ANDROID)
  ++android_transition_generation_;
#endif
  // Perform a state transition
  Transit(distill_state_, Distilled(result));
#if !BUILDFLAG(IS_ANDROID)
  if (result == DistillationResult::kSuccess) {
    speedreader_service_->metrics().RecordPageView();
  }
#endif
}

void SpeedreaderTabHelper::OnDistilledDocumentSent() {
#if BUILDFLAG(IS_ANDROID)
  const auto weak_this = weak_factory_.GetWeakPtr();
  const uint64_t generation = android_request_generation_;
  const uint64_t transition_generation = android_transition_generation_;
  if (!web_contents() || !web_contents()->GetPrimaryMainFrame()) {
    return;
  }
  const auto source_document =
      web_contents()->GetPrimaryMainFrame()->GetWeakDocumentPtr();
#endif
  UpdateUI();

#if BUILDFLAG(IS_ANDROID)
  if (!weak_this || !weak_this->web_contents() ||
      generation != weak_this->android_request_generation_ ||
      transition_generation != weak_this->android_transition_generation_ ||
      !source_document.AsRenderFrameHostIfValid() ||
      source_document.AsRenderFrameHostIfValid() !=
          weak_this->web_contents()->GetPrimaryMainFrame()) {
    return;
  }
  if (IsDistilled(weak_this->distill_state_)) {
    weak_this->web_contents()->SetPageScale(1.f);
  }
#endif
}

void SpeedreaderTabHelper::OnReadingStart(content::WebContents* web_contents) {
  if (!speedreader::IsDistilled(distill_state_)) {
    return;
  }

  static constexpr char16_t kReading[] =
      uR"js( speedreaderUtils.setTtsReadingState($1) )js";

  const auto script = base::ReplaceStringPlaceholders(
      kReading, (web_contents == this->web_contents()) ? u"true" : u"false",
      nullptr);

  this->web_contents()->GetPrimaryMainFrame()->ExecuteJavaScriptInIsolatedWorld(
      script, base::DoNothing(), ISOLATED_WORLD_ID_BRAVE_INTERNAL);
}

void SpeedreaderTabHelper::OnReadingStop(content::WebContents* web_contents) {
  OnReadingStart(nullptr);
}

void SpeedreaderTabHelper::OnReadingProgress(content::WebContents* web_contents,
                                             int paragraph_index,
                                             int char_index,
                                             int length) {
  if (!speedreader::IsDistilled(distill_state_) ||
      web_contents != this->web_contents()) {
    return;
  }
  static constexpr char16_t kHighlight[] =
      uR"js( speedreaderUtils.highlightText($1, $2, $3) )js";

  const auto script = base::ReplaceStringPlaceholders(
      kHighlight,
      {base::NumberToString16(paragraph_index),
       base::NumberToString16(char_index), base::NumberToString16(length)},
      nullptr);

  this->web_contents()->GetPrimaryMainFrame()->ExecuteJavaScriptInIsolatedWorld(
      script, base::DoNothing(), ISOLATED_WORLD_ID_BRAVE_INTERNAL);
}

void SpeedreaderTabHelper::OnSiteEnableSettingChanged(
    content::WebContents* site,
    bool enabled_on_site) {
  if (site != web_contents()) {
    return;
  }

#if BUILDFLAG(IS_ANDROID)
  if (enabled_on_site) {
    const GURL& url = web_contents()->GetLastCommittedURL();
    auto* prefs =
        user_prefs::UserPrefs::Get(web_contents()->GetBrowserContext());
    const bool readable =
        rewriter_service_->URLLooksReadable(url) ||
        (url.path() != "/" && speedreader_service_->IsEnabledForSite(url));
    if (!url.SchemeIsHTTPOrHTTPS() || !readable || !prefs ||
        !prefs->GetBoolean(kSpeedreaderEnabled)) {
      return;
    }
  }
#endif
#if BUILDFLAG(IS_ANDROID)
  const auto weak_this = weak_factory_.GetWeakPtr();
#endif
  if (enabled_on_site) {
    TransitStateTo(Distilling(Distilled::Reason::kAutomatic));
  } else {
    TransitStateTo(ViewOriginal());
  }
#if BUILDFLAG(IS_ANDROID)
  if (!weak_this || !weak_this->web_contents()) {
    return;
  }
#endif
  HideSpeedreaderBubble();
}

void SpeedreaderTabHelper::OnAllSitesEnableSettingChanged(
    bool enabled_on_all_sites) {
  if (!is_visible_) {
    return;
  }
  OnSiteEnableSettingChanged(
      web_contents(), speedreader_service_->IsAllowedForSite(web_contents()));
}

void SpeedreaderTabHelper::OnAppearanceSettingsChanged(
    const mojom::AppearanceSettings& view_settings) {
  if (!speedreader::IsDistilled(distill_state_)) {
    return;
  }

  SetDocumentAttribute("data-theme", speedreader_service_->GetThemeName());
  SetDocumentAttribute("data-font-family",
                       speedreader_service_->GetFontFamilyName());
  SetDocumentAttribute("data-font-size",
                       speedreader_service_->GetFontSizeName());
  SetDocumentAttribute("data-column-width",
                       speedreader_service_->GetColumnWidth());
}

void SpeedreaderTabHelper::OnResult(
    const dom_distiller::DistillabilityResult& result) {
  if (IsNotDistillable(distill_state_) && result.is_distillable) {
#if BUILDFLAG(IS_ANDROID)
    const auto weak_this = weak_factory_.GetWeakPtr();
    const uint64_t generation = android_request_generation_;
    const uint64_t transition_generation = android_transition_generation_;
#endif
    TransitStateTo(DistillReverting(DistillReverting::Reason::kNone, false));
#if BUILDFLAG(IS_ANDROID)
    if (!weak_this || !weak_this->web_contents() ||
        generation != weak_this->android_request_generation_ ||
        transition_generation + 1 !=
            weak_this->android_transition_generation_) {
      return;
    }
#endif
    TransitStateTo(ViewOriginal());
  }
}

void SpeedreaderTabHelper::SetDocumentAttribute(const std::string& attribute,
                                                const std::string& value) {
  static constexpr char16_t kSetAttribute[] =
      uR"js(
    (function() {
      const attribute = '$1'
      const value = '$2'
      if (value == '') {
        document?.documentElement?.removeAttribute(attribute)
      } else {
        document?.documentElement?.setAttribute(attribute, value)
      }
    })();
  )js";

  const auto script = base::ReplaceStringPlaceholders(
      kSetAttribute, {base::UTF8ToUTF16(attribute), base::UTF8ToUTF16(value)},
      nullptr);

  web_contents()->GetPrimaryMainFrame()->ExecuteJavaScriptInIsolatedWorld(
      script, base::DoNothing(), ISOLATED_WORLD_ID_BRAVE_INTERNAL);
}

void SpeedreaderTabHelper::OnGetDocumentSource(bool success, std::string html) {
  if (!success || html.empty()) {
#if BUILDFLAG(IS_ANDROID)
    const auto weak_this = weak_factory_.GetWeakPtr();
    const uint64_t generation = android_request_generation_;
    const uint64_t transition_generation = android_transition_generation_;
#endif
    TransitStateTo(DistillReverting(DistillReverting::Reason::kError, false));
#if BUILDFLAG(IS_ANDROID)
    if (!weak_this || !weak_this->web_contents() ||
        generation != weak_this->android_request_generation_ ||
        transition_generation + 1 !=
            weak_this->android_transition_generation_) {
      return;
    }
#endif
    TransitStateTo(ViewOriginal());
    return;
  }

  single_show_content_ = std::move(html);
  TransitStateTo(Distilling(Distilling::Reason::kManual));
}

void SpeedreaderTabHelper::TransitStateTo(const DistillState& desired_state,
                                          bool no_reload) {
#if BUILDFLAG(IS_ANDROID)
  const auto weak_this = weak_factory_.GetWeakPtr();
  ++android_transition_generation_;
  if (android_manual_distill_pending_) {
    android_manual_distill_pending_ = false;
    ++android_request_generation_;
  }
#endif
  if (Transit(distill_state_, desired_state) && !no_reload) {
    ClearPersistedData();
    ReloadContents();
  }
#if BUILDFLAG(IS_ANDROID)
  if (!weak_this || !weak_this->web_contents()) {
    return;
  }
#endif
  UpdateUI();
}

WEB_CONTENTS_USER_DATA_KEY_IMPL(SpeedreaderTabHelper);

}  // namespace speedreader
