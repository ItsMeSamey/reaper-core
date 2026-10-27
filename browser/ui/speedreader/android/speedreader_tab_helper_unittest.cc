// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public License,
// v. 2.0.

#include "brave/browser/ui/speedreader/speedreader_tab_helper.h"

#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "base/functional/bind.h"
#include "base/functional/callback.h"
#include "base/run_loop.h"
#include "base/test/scoped_feature_list.h"
#include "brave/components/speedreader/common/features.h"
#include "brave/components/speedreader/speedreader_body_distiller.h"
#include "brave/components/speedreader/speedreader_pref_names.h"
#include "brave/components/speedreader/speedreader_rewriter_service.h"
#include "brave/components/speedreader/speedreader_service.h"
#include "brave/components/speedreader/tts_player.h"
#include "components/content_settings/core/browser/host_content_settings_map.h"
#include "components/sync_preferences/testing_pref_service_syncable.h"
#include "components/user_prefs/user_prefs.h"
#include "content/public/browser/tts_controller.h"
#include "content/public/browser/tts_platform.h"
#include "content/public/browser/tts_utterance.h"
#include "content/public/browser/web_contents.h"
#include "content/public/test/navigation_simulator.h"
#include "content/public/test/test_renderer_host.h"
#include "content/public/test/web_contents_tester.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace speedreader {

namespace {

class RecordingTtsPlatform final : public content::TtsPlatform {
 public:
  RecordingTtsPlatform()
      : controller_(content::TtsController::GetInstance()),
        original_engine_(controller_->GetTtsEngineDelegate()) {
    controller_->Stop();
    controller_->SetTtsEngineDelegate(nullptr);
    controller_->SetTtsPlatform(this);
  }

  ~RecordingTtsPlatform() {
    controller_->Stop();
    controller_->SetTtsPlatform(nullptr);
    controller_->SetTtsEngineDelegate(original_engine_);
  }

  bool PlatformImplSupported() override { return true; }
  bool PlatformImplInitialized() override { return true; }
  void Speak(int utterance_id,
             const std::string& utterance,
             const std::string&,
             const content::VoiceData&,
             const content::UtteranceContinuousParameters&,
             base::OnceCallback<void(bool)> callback) override {
    ++speak_count_;
    started_ = false;
    utterance_id_ = utterance_id;
    last_text_ = utterance;
    speaking_ = true;
    std::move(callback).Run(true);
  }
  bool IsSpeaking() override { return speaking_; }
  bool StopSpeaking() override {
    ++stop_count_;
    speaking_ = false;
    started_ = false;
    return true;
  }
  void Pause() override {
    ++pause_count_;
    speaking_ = false;
    if (started_) {
      controller_->OnTtsEvent(utterance_id_, content::TTS_EVENT_PAUSE, -1, -1, {});
    }
  }
  void Resume() override {
    ++resume_count_;
    speaking_ = true;
    // Synthesis accepted before first playback has no RESUME event.
    if (started_) {
      controller_->OnTtsEvent(utterance_id_, content::TTS_EVENT_RESUME, -1, -1, {});
    }
  }
  void GetVoices(std::vector<content::VoiceData>* voices) override {
    content::VoiceData voice;
    voice.name = "reader-fixture";
    voice.native = true;
    voices->push_back(voice);
  }
  void LoadBuiltInTtsEngine(content::BrowserContext*) override {}
  void WillSpeakUtteranceWithVoice(content::TtsUtterance*,
                                  const content::VoiceData&) override {}
  void SetError(const std::string& error) override { error_ = error; }
  std::string GetError() override { return error_; }
  void ClearError() override { error_.clear(); }
  void Shutdown() override {}
  void FinalizeVoiceOrdering(std::vector<content::VoiceData>&) override {}
  void RefreshVoices() override {}

  void SendStartEvent() {
    started_ = true;
    controller_->OnTtsEvent(utterance_id_, content::TTS_EVENT_START,
                           /* char_index= */ 0, /* length= */ 0, {});
  }
  void SendWordOffset(int index) {
    controller_->OnTtsEvent(utterance_id_, content::TTS_EVENT_WORD, index,
                           /* length= */ 1, {});
  }
  void SendEndEvent() {
    speaking_ = false;
    controller_->OnTtsEvent(utterance_id_, content::TTS_EVENT_END,
                           /* char_index= */ 0, /* length= */ 0, {});
  }
  void SendTerminalEvent(content::TtsEventType event) {
    speaking_ = false;
    controller_->OnTtsEvent(utterance_id_, event,
                           /* char_index= */ -1, /* length= */ -1, {});
  }
  const std::string& last_text() const { return last_text_; }
  int speak_count() const { return speak_count_; }
  int stop_count() const { return stop_count_; }
  int pause_count() const { return pause_count_; }
  int resume_count() const { return resume_count_; }

 private:
  raw_ptr<content::TtsController> controller_;
  raw_ptr<content::TtsEngineDelegate> original_engine_;
  int utterance_id_ = -1;
  bool speaking_ = false;
  bool started_ = false;
  int speak_count_ = 0;
  int stop_count_ = 0;
  int pause_count_ = 0;
  int resume_count_ = 0;
  std::string last_text_;
  std::string error_;
};

class ReadingPositionObserver final : public TtsPlayer::Observer {
 public:
  explicit ReadingPositionObserver(content::WebContents* contents)
      : contents_(contents) {
    TtsPlayer::GetInstance()->AddObserver(this);
  }
  ~ReadingPositionObserver() override {
    TtsPlayer::GetInstance()->RemoveObserver(this);
  }
  void OnReadingProgress(content::WebContents* contents,
                         int paragraph,
                         int index,
                         int) override {
    if (contents == contents_ && paragraph >= 0) {
      last_position_ = index;
    }
  }
  int last_position() const { return last_position_; }

 private:
  raw_ptr<content::WebContents> contents_;
  int last_position_ = -1;
};

class ReadingStateObserver final : public TtsPlayer::Observer {
 public:
  explicit ReadingStateObserver(content::WebContents* contents)
      : contents_(contents) {
    TtsPlayer::GetInstance()->AddObserver(this);
  }
  ~ReadingStateObserver() override {
    TtsPlayer::GetInstance()->RemoveObserver(this);
  }
  void OnReadingStart(content::WebContents* contents) override {
    if (contents == contents_) {
      reading_ = true;
      ++starts_;
    }
  }
  void OnReadingStop(content::WebContents* contents) override {
    if (contents == contents_) {
      reading_ = false;
      ++stops_;
    }
  }
  bool reading() const { return reading_; }
  int starts() const { return starts_; }
  int stops() const { return stops_; }

 private:
  raw_ptr<content::WebContents> contents_;
  bool reading_ = false;
  int starts_ = 0;
  int stops_ = 0;
};

class PendingTtsDelegate : public TtsPlayer::Delegate {
 public:
  void RequestReadingContent(
      content::WebContents*,
      base::OnceCallback<void(base::Value)> callback) override {
    callback_ = std::move(callback);
  }

  base::OnceCallback<void(base::Value)> TakeCallback() {
    return std::move(callback_);
  }

 private:
  base::OnceCallback<void(base::Value)> callback_;
};

class ScopedPendingTtsRequest {
 public:
  explicit ScopedPendingTtsRequest(content::WebContents* web_contents)
      : controller_(TtsPlayer::GetInstance()->GetControllerFor(web_contents)) {
    controller_.Stop();
    TtsPlayer::GetInstance()->SetSpeed(1.0);
    TtsPlayer::GetInstance()->SetVoice("");
    auto delegate = std::make_unique<PendingTtsDelegate>();
    delegate_ = delegate.get();
    TtsPlayer::GetInstance()->set_delegate(std::move(delegate));
  }

  ~ScopedPendingTtsRequest() {
    controller_.Stop();
    TtsPlayer::GetInstance()->SetSpeed(1.0);
    TtsPlayer::GetInstance()->SetVoice("");
    TtsPlayer::GetInstance()->GetControllerFor(nullptr);
    TtsPlayer::GetInstance()->set_delegate(nullptr);
  }

  TtsPlayer::Controller& controller() { return controller_; }
  base::OnceCallback<void(base::Value)> TakeCallback() {
    return delegate_->TakeCallback();
  }

 private:
  TtsPlayer::Controller& controller_;
  raw_ptr<PendingTtsDelegate> delegate_ = nullptr;
};

base::Value PendingTtsContent(
    std::string paragraph = "A readable article paragraph.") {
  base::ListValue paragraphs;
  paragraphs.Append(std::move(paragraph));
  base::DictValue content;
  content.Set("paragraphs", std::move(paragraphs));
  return base::Value(std::move(content));
}
base::Value TwoParagraphTtsContent() {
  base::ListValue paragraphs;
  paragraphs.Append("First readable paragraph.");
  paragraphs.Append("Second readable paragraph.");
  base::DictValue content;
  content.Set("paragraphs", std::move(paragraphs));
  return base::Value(std::move(content));
}

class DestroyingHelperObserver final : public SpeedreaderTabHelper::Observer {
 public:
  explicit DestroyingHelperObserver(base::OnceClosure destroy)
      : destroy_(std::move(destroy)) {}
  void OnDistillStateUpdated() override {
    if (destroy_) {
      std::move(destroy_).Run();
    }
  }

 private:
  base::OnceClosure destroy_;
};

class DestroyingPageObserver final : public PageDistiller::Observer {
 public:
  explicit DestroyingPageObserver(base::OnceClosure destroy)
      : destroy_(std::move(destroy)) {}
  void OnPageDistillStateChanged(PageDistiller::State state) override {
    if (destroy_) {
      std::move(destroy_).Run();
    }
  }

 private:
  base::OnceClosure destroy_;
};
}  // namespace

class AndroidSpeedreaderTabHelperTest
    : public content::RenderViewHostTestHarness {
 protected:
  void SetUp() override {
    feature_list_.InitAndEnableFeature(features::kSpeedreaderFeature);
    content::RenderViewHostTestHarness::SetUp();
    HostContentSettingsMap::RegisterProfilePrefs(prefs_.registry());
    SpeedreaderService::RegisterProfilePrefs(prefs_.registry());
    SpeedreaderService::RegisterLocalStatePrefs(prefs_.registry());
    user_prefs::UserPrefs::Set(browser_context(), &prefs_);
    settings_map_ = base::MakeRefCounted<HostContentSettingsMap>(
        &prefs_, /*is_off_the_record=*/false, /*store_last_modified=*/false,
        /*restore_session=*/false, /*should_record_metrics=*/false);
    service_ = std::make_unique<SpeedreaderService>(browser_context(), &prefs_,
                                                    settings_map_.get());
    rewriter_ = std::make_unique<SpeedreaderRewriterService>();
    SpeedreaderTabHelper::CreateForWebContents(web_contents(), *service_,
                                               rewriter_.get());
    NavigateAndCommit(GURL("https://example.com/article"));
  }

  void TearDown() override {
    DeleteContents();
    service_.reset();
    rewriter_.reset();
    settings_map_->ShutdownOnUIThread();
    settings_map_.reset();
    content::RenderViewHostTestHarness::TearDown();
  }

  SpeedreaderTabHelper* helper() {
    return SpeedreaderTabHelper::FromWebContents(web_contents());
  }

  uint64_t BeginPendingExtraction() {
    helper()->android_manual_distill_pending_ = true;
    return ++helper()->android_request_generation_;
  }

  void CompleteExtraction(uint64_t generation, bool success, std::string html) {
    helper()->OnAndroidDocumentSource(generation, success, std::move(html));
  }

  base::OnceClosure DestroyContentsCallback() {
    return base::BindOnce([this] { DeleteContents(); });
  }

  void EnableAutomaticForCurrentSite() {
    service_->SetEnabledForSite(web_contents(), true);
  }

  bool HasPendingExtraction() {
    return helper()->android_manual_distill_pending_;
  }
  bool HasPageContent() { return helper()->IsPageContentPresent(); }

  std::unique_ptr<SpeedreaderBodyDistiller> CreateBodyDistiller() {
    return SpeedreaderBodyDistiller::MaybeCreate(
        rewriter_.get(), service_.get(), helper()->GetWeakPtr());
  }

  using ReaderState = SpeedreaderTabHelper::AndroidReaderState;
  sync_preferences::TestingPrefServiceSyncable prefs_;

 private:
  base::test::ScopedFeatureList feature_list_;
  scoped_refptr<HostContentSettingsMap> settings_map_;
  std::unique_ptr<SpeedreaderService> service_;
  std::unique_ptr<SpeedreaderRewriterService> rewriter_;
};

TEST_F(AndroidSpeedreaderTabHelperTest, HttpDocumentOffersManualReading) {
  EXPECT_EQ(ReaderState::kOriginal, helper()->GetAndroidReaderState());
}

TEST_F(AndroidSpeedreaderTabHelperTest, InternalPageCannotBeDistilled) {
  NavigateAndCommit(GURL("about:blank"));
  EXPECT_EQ(ReaderState::kUnavailable, helper()->GetAndroidReaderState());
  EXPECT_FALSE(helper()->ToggleReaderModeForAndroid());
}

TEST_F(AndroidSpeedreaderTabHelperTest, LoadingDocumentCannotBeToggled) {
  content::WebContentsTester::For(web_contents())->TestSetIsLoading(true);
  EXPECT_EQ(ReaderState::kBusy, helper()->GetAndroidReaderState());
  EXPECT_FALSE(helper()->ToggleReaderModeForAndroid());
}

TEST_F(AndroidSpeedreaderTabHelperTest, DisabledPolicyRejectsOriginalDocument) {
  prefs_.SetManagedPref(kSpeedreaderEnabled, base::Value(false));
  EXPECT_EQ(ReaderState::kUnavailable, helper()->GetAndroidReaderState());
  EXPECT_FALSE(helper()->ToggleReaderModeForAndroid());
}

TEST_F(AndroidSpeedreaderTabHelperTest,
       ExistingReaderCanRevertAfterPolicyChange) {
  helper()->SetDistillStateForTesting(
      Distilled(Distilling::Reason::kManual, DistillationResult::kSuccess));
  prefs_.SetManagedPref(kSpeedreaderEnabled, base::Value(false));
  EXPECT_EQ(ReaderState::kReader, helper()->GetAndroidReaderState());
  EXPECT_TRUE(helper()->ToggleReaderModeForAndroid());
  // Reload can synchronously start navigation and finish the reverting-state
  // transition, so accept both native states along that real reload path.
  EXPECT_TRUE(IsDistillReverting(helper()->PageDistillState()) ||
              IsViewOriginal(helper()->PageDistillState()));
  EXPECT_NE(ReaderState::kReader, helper()->GetAndroidReaderState());
}

TEST_F(AndroidSpeedreaderTabHelperTest, PendingExtractionRejectsDoubleTap) {
  BeginPendingExtraction();
  EXPECT_EQ(ReaderState::kBusy, helper()->GetAndroidReaderState());
  EXPECT_FALSE(helper()->ToggleReaderModeForAndroid());
}

TEST_F(AndroidSpeedreaderTabHelperTest,
       NavigationDiscardsPreviousDocumentResult) {
  const uint64_t old_generation = BeginPendingExtraction();
  NavigateAndCommit(GURL("https://other.example/article"));
  CompleteExtraction(old_generation, true, "<article>stale document</article>");
  EXPECT_FALSE(HasPendingExtraction());
  EXPECT_FALSE(HasPageContent());
  EXPECT_TRUE(IsViewOriginal(helper()->PageDistillState()));
}

TEST_F(AndroidSpeedreaderTabHelperTest, OldCallbackCannotCancelNewExtraction) {
  const uint64_t old_generation = BeginPendingExtraction();
  NavigateAndCommit(GURL("https://other.example/article"));
  BeginPendingExtraction();
  CompleteExtraction(old_generation, true, "<article>stale document</article>");
  EXPECT_TRUE(HasPendingExtraction());
  EXPECT_FALSE(HasPageContent());
}

TEST_F(AndroidSpeedreaderTabHelperTest, PolicyChangeDiscardsPendingResult) {
  const uint64_t generation = BeginPendingExtraction();
  prefs_.SetManagedPref(kSpeedreaderEnabled, base::Value(false));
  CompleteExtraction(generation, true, "<article>previous document</article>");
  EXPECT_FALSE(HasPendingExtraction());
  EXPECT_FALSE(HasPageContent());
  EXPECT_TRUE(IsViewOriginal(helper()->PageDistillState()));
}

TEST_F(AndroidSpeedreaderTabHelperTest,
       DestructionInvalidatesExtractionCallback) {
  auto weak_helper = helper()->GetWeakPtr();
  BeginPendingExtraction();
  DeleteContents();
  EXPECT_FALSE(weak_helper);
}

TEST_F(AndroidSpeedreaderTabHelperTest,
       PendingStateObserverDestroysContentsBeforeExtraction) {
  auto weak_helper = helper()->GetWeakPtr();
  DestroyingHelperObserver observer(DestroyContentsCallback());
  helper()->AddObserver(&observer);
  EXPECT_FALSE(helper()->ToggleReaderModeForAndroid());
  EXPECT_FALSE(weak_helper);
  EXPECT_EQ(nullptr, web_contents());
}

TEST_F(AndroidSpeedreaderTabHelperTest,
       PageStateObserverDestroysContentsBeforeHelperNotification) {
  helper()->SetDistillStateForTesting(
      Distilled(Distilling::Reason::kManual, DistillationResult::kSuccess));
  auto weak_helper = helper()->GetWeakPtr();
  DestroyingPageObserver observer(DestroyContentsCallback());
  static_cast<PageDistiller*>(helper())->AddObserver(&observer);
  EXPECT_FALSE(helper()->ToggleReaderModeForAndroid());
  EXPECT_FALSE(weak_helper);
  EXPECT_EQ(nullptr, web_contents());
}

TEST_F(AndroidSpeedreaderTabHelperTest,
       PendingObserverPolicyChangeCancelsExtractionBeforeRendererRequest) {
  DestroyingHelperObserver observer(base::BindOnce([this] {
    prefs_.SetManagedPref(kSpeedreaderEnabled, base::Value(false));
  }));
  helper()->AddObserver(&observer);
  EXPECT_FALSE(helper()->ToggleReaderModeForAndroid());
  EXPECT_FALSE(HasPendingExtraction());
  EXPECT_FALSE(HasPageContent());
  EXPECT_EQ(ReaderState::kUnavailable, helper()->GetAndroidReaderState());
  // Deliver the weak coalesced notification after the real observer returns.
  base::RunLoop().RunUntilIdle();
  helper()->RemoveObserver(&observer);
}

TEST_F(AndroidSpeedreaderTabHelperTest,
       ReaderRevertObserverDestroysContentsDuringReload) {
  helper()->SetDistillStateForTesting(
      Distilled(Distilling::Reason::kManual, DistillationResult::kSuccess));
  auto weak_helper = helper()->GetWeakPtr();
  DestroyingHelperObserver observer(DestroyContentsCallback());
  helper()->AddObserver(&observer);
  EXPECT_FALSE(helper()->ToggleReaderModeForAndroid());
  EXPECT_FALSE(weak_helper);
  EXPECT_EQ(nullptr, web_contents());
}

TEST_F(
    AndroidSpeedreaderTabHelperTest,
    PendingObserverNavigationCancelsPreviousExtractionBeforeRendererRequest) {
  DestroyingHelperObserver observer(base::BindOnce(
      [this] { NavigateAndCommit(GURL("https://other.example/article")); }));
  helper()->AddObserver(&observer);
  EXPECT_FALSE(helper()->ToggleReaderModeForAndroid());
  EXPECT_FALSE(HasPendingExtraction());
  EXPECT_FALSE(HasPageContent());
  EXPECT_TRUE(IsViewOriginal(helper()->PageDistillState()));
  EXPECT_EQ(GURL("https://other.example/article"),
            web_contents()->GetLastCommittedURL());
  // Deliver the weak coalesced notification after the real observer returns.
  base::RunLoop().RunUntilIdle();
  helper()->RemoveObserver(&observer);
}

TEST_F(AndroidSpeedreaderTabHelperTest,
       ReentrantAutomaticStateSurvivesOlderManualToggleContinuation) {
  DestroyingHelperObserver observer(base::BindOnce([this] {
    EnableAutomaticForCurrentSite();
    // Pump while still inside Notify: no deferred UI task may spin here.
    base::RunLoop().RunUntilIdle();
  }));
  helper()->AddObserver(&observer);
  EXPECT_TRUE(helper()->ToggleReaderModeForAndroid());
  EXPECT_FALSE(HasPendingExtraction());
  EXPECT_FALSE(HasPageContent());
  ASSERT_TRUE(IsDistilling(helper()->PageDistillState()));
  EXPECT_EQ(Distilling::Reason::kAutomatic,
            std::get<Distilling>(helper()->PageDistillState()).reason);
  // Deliver the weak coalesced notification after the real observer returns.
  base::RunLoop().RunUntilIdle();
  helper()->RemoveObserver(&observer);
}

TEST_F(AndroidSpeedreaderTabHelperTest,
       PendingObserverDestructionCancelsDeferredNavigationUiUpdate) {
  auto weak_helper = helper()->GetWeakPtr();
  DestroyingHelperObserver observer(base::BindOnce([this] {
    NavigateAndCommit(GURL("https://other.example/article"));
    DeleteContents();
  }));
  helper()->AddObserver(&observer);
  EXPECT_FALSE(helper()->ToggleReaderModeForAndroid());
  EXPECT_FALSE(weak_helper);
  EXPECT_EQ(nullptr, web_contents());
  // The navigation requested a pending update before observer destruction.
  base::RunLoop().RunUntilIdle();
  EXPECT_FALSE(weak_helper);
}

TEST_F(AndroidSpeedreaderTabHelperTest,
       PageObserverDestructionCancelsDeferredNavigationUiUpdate) {
  helper()->SetDistillStateForTesting(
      Distilled(Distilling::Reason::kManual, DistillationResult::kSuccess));
  auto weak_helper = helper()->GetWeakPtr();
  DestroyingPageObserver observer(base::BindOnce([this] {
    NavigateAndCommit(GURL("https://other.example/article"));
    DeleteContents();
  }));
  static_cast<PageDistiller*>(helper())->AddObserver(&observer);
  EXPECT_FALSE(helper()->ToggleReaderModeForAndroid());
  EXPECT_FALSE(weak_helper);
  EXPECT_EQ(nullptr, web_contents());
  base::RunLoop().RunUntilIdle();
  EXPECT_FALSE(weak_helper);
}

TEST_F(AndroidSpeedreaderTabHelperTest,
       DestructionAfterObserverReturnCancelsPostedNavigationUiUpdate) {
  auto weak_helper = helper()->GetWeakPtr();
  DestroyingHelperObserver observer(base::BindOnce(
      [this] { NavigateAndCommit(GURL("https://other.example/article")); }));
  helper()->AddObserver(&observer);
  EXPECT_FALSE(helper()->ToggleReaderModeForAndroid());
  EXPECT_FALSE(HasPendingExtraction());
  helper()->RemoveObserver(&observer);
  // Notify returned and scheduled the latest-state update. Destroy the owner
  // before task delivery and drain the real weak callback.
  DeleteContents();
  EXPECT_FALSE(weak_helper);
  base::RunLoop().RunUntilIdle();
  EXPECT_FALSE(weak_helper);
}

TEST_F(AndroidSpeedreaderTabHelperTest, TtsUnknownWordOffsetRetainsLastPosition) {
  RecordingTtsPlatform platform;
  ScopedPendingTtsRequest request(web_contents());
  ReadingPositionObserver progress(web_contents());
  request.controller().Play();
  auto callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  std::move(callback).Run(PendingTtsContent());
  // START identifies the paragraph even when the platform has no WORD event.
  platform.SendStartEvent();
  ASSERT_EQ(0, progress.last_position());
  platform.SendWordOffset(2);
  ASSERT_EQ(2, progress.last_position());
  // The public engine contract uses -1 when a word offset is unavailable.
  platform.SendWordOffset(-1);
  ASSERT_EQ(2, progress.last_position());
  request.controller().Pause();
  TtsPlayer::GetInstance()->SetSpeed(1.25);
  request.controller().Play();
  EXPECT_EQ("readable article paragraph.", platform.last_text());
}

TEST_F(AndroidSpeedreaderTabHelperTest, TtsOutOfRangeWordOffsetRetainsLastPosition) {
  RecordingTtsPlatform platform;
  ScopedPendingTtsRequest request(web_contents());
  ReadingPositionObserver progress(web_contents());
  request.controller().Play();
  auto callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  std::move(callback).Run(PendingTtsContent());
  platform.SendWordOffset(2);
  ASSERT_EQ(2, progress.last_position());
  platform.SendWordOffset(1000);
  ASSERT_EQ(2, progress.last_position());
  request.controller().Pause();
  TtsPlayer::GetInstance()->SetSpeed(1.25);
  request.controller().Play();
  EXPECT_EQ("readable article paragraph.", platform.last_text());
}

TEST_F(AndroidSpeedreaderTabHelperTest, TtsResumeUsesUtf16CodeUnits) {
  RecordingTtsPlatform platform;
  ScopedPendingTtsRequest request(web_contents());
  ReadingPositionObserver progress(web_contents());
  request.controller().Play();
  auto callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  std::move(callback).Run(PendingTtsContent("A 😀 readable article paragraph."));
  // "A ", the surrogate pair, and a space occupy five UTF-16 code units.
  platform.SendWordOffset(5);
  ASSERT_EQ(5, progress.last_position());
  request.controller().Pause();
  TtsPlayer::GetInstance()->SetSpeed(1.25);
  request.controller().Play();
  EXPECT_EQ("readable article paragraph.", platform.last_text());
}

TEST_F(AndroidSpeedreaderTabHelperTest,
       TtsOrdinaryParagraphContinuationKeepsReadingState) {
  RecordingTtsPlatform platform;
  ScopedPendingTtsRequest request(web_contents());
  ReadingStateObserver state(web_contents());
  request.controller().Play();
  auto callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  std::move(callback).Run(TwoParagraphTtsContent());
  platform.SendStartEvent();
  ASSERT_TRUE(state.reading());
  platform.SendEndEvent();
  EXPECT_EQ("Second readable paragraph.", platform.last_text());
  EXPECT_TRUE(state.reading());
  EXPECT_EQ(0, state.stops());
  platform.SendStartEvent();
  EXPECT_TRUE(state.reading());
  EXPECT_EQ(2, state.starts());
  platform.SendEndEvent();
  EXPECT_FALSE(state.reading());
  EXPECT_EQ(1, state.stops());
}

TEST_F(AndroidSpeedreaderTabHelperTest,
       TtsPauseBeforeNextParagraphStartReportsStopped) {
  RecordingTtsPlatform platform;
  ScopedPendingTtsRequest request(web_contents());
  ReadingStateObserver state(web_contents());
  request.controller().Play();
  auto callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  std::move(callback).Run(TwoParagraphTtsContent());
  platform.SendStartEvent();
  platform.SendEndEvent();
  ASSERT_TRUE(state.reading());
  ASSERT_EQ("Second readable paragraph.", platform.last_text());
  // Speak has accepted the next paragraph, but its playback START is pending.
  request.controller().Pause();
  EXPECT_FALSE(state.reading());
  EXPECT_EQ(1, state.stops());
  request.controller().Play();
  EXPECT_EQ("Second readable paragraph.", platform.last_text());
  platform.SendStartEvent();
  EXPECT_TRUE(state.reading());
  EXPECT_EQ(2, state.starts());
}

TEST_F(AndroidSpeedreaderTabHelperTest,
       TtsStopBeforeNextParagraphStartDoesNotSuppressReplay) {
  RecordingTtsPlatform platform;
  ScopedPendingTtsRequest request(web_contents());
  ReadingStateObserver state(web_contents());
  request.controller().Play();
  auto callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  std::move(callback).Run(TwoParagraphTtsContent());
  platform.SendStartEvent();
  platform.SendEndEvent();
  ASSERT_TRUE(state.reading());
  request.controller().Stop();
  EXPECT_FALSE(state.reading());
  EXPECT_EQ(1, state.stops());
  request.controller().Play();
  callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  std::move(callback).Run(TwoParagraphTtsContent());
  platform.SendStartEvent();
  EXPECT_TRUE(state.reading());
  EXPECT_EQ(2, state.starts());
}

TEST_F(AndroidSpeedreaderTabHelperTest,
       TtsContinuationTerminalBeforeStartReportsStopped) {
  for (const auto event : {content::TTS_EVENT_ERROR,
                           content::TTS_EVENT_CANCELLED,
                           content::TTS_EVENT_INTERRUPTED}) {
    RecordingTtsPlatform platform;
    ScopedPendingTtsRequest request(web_contents());
    ReadingStateObserver state(web_contents());
    request.controller().Play();
    auto callback = request.TakeCallback();
    ASSERT_TRUE(callback);
    std::move(callback).Run(TwoParagraphTtsContent());
    platform.SendStartEvent();
    platform.SendEndEvent();
    ASSERT_TRUE(state.reading());
    // Ordinary asynchronous terminal events arrive before playback START.
    platform.SendTerminalEvent(event);
    EXPECT_FALSE(state.reading());
    EXPECT_EQ(1, state.stops());
  }
}

TEST_F(AndroidSpeedreaderTabHelperTest, TtsStopDiscardsPendingContent) {
  ScopedPendingTtsRequest request(web_contents());
  request.controller().Play();
  EXPECT_EQ(web_contents(), request.controller().web_contents());
  auto callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  request.controller().Stop();
  std::move(callback).Run(PendingTtsContent());
  EXPECT_EQ(nullptr, request.controller().web_contents());
  EXPECT_FALSE(request.controller().IsPlayingRequestedWebContents());
}

TEST_F(AndroidSpeedreaderTabHelperTest, TtsNavigationDiscardsPendingContent) {
  ScopedPendingTtsRequest request(web_contents());
  request.controller().Play();
  auto callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  NavigateAndCommit(GURL("https://example.com/next-article"));
  std::move(callback).Run(PendingTtsContent());
  EXPECT_EQ(nullptr, request.controller().web_contents());
  EXPECT_FALSE(request.controller().IsPlayingRequestedWebContents());
}

TEST_F(AndroidSpeedreaderTabHelperTest, TtsPreviousRequestCannotReplaceNewRequest) {
  ScopedPendingTtsRequest request(web_contents());
  request.controller().Play();
  auto old_callback = request.TakeCallback();
  ASSERT_TRUE(old_callback);
  request.controller().Stop();
  request.controller().Play();
  auto new_callback = request.TakeCallback();
  ASSERT_TRUE(new_callback);
  std::move(old_callback).Run(PendingTtsContent());
  EXPECT_EQ(web_contents(), request.controller().web_contents());
  EXPECT_FALSE(request.controller().IsPlayingRequestedWebContents());
  request.controller().Stop();
}


TEST_F(AndroidSpeedreaderTabHelperTest, TtsPauseResumesSameNativeUtterance) {
  RecordingTtsPlatform platform;
  ScopedPendingTtsRequest request(web_contents());
  ReadingStateObserver state(web_contents());
  request.controller().Play();
  auto callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  std::move(callback).Run(PendingTtsContent());
  platform.SendStartEvent();
  const int stops = platform.stop_count();
  request.controller().Pause();
  EXPECT_FALSE(request.controller().IsPlaying());
  EXPECT_FALSE(state.reading());
  EXPECT_EQ(1, state.stops());
  EXPECT_EQ(1, platform.pause_count());
  EXPECT_EQ(stops, platform.stop_count());
  request.controller().Play();
  EXPECT_TRUE(request.controller().IsPlaying());
  EXPECT_TRUE(state.reading());
  EXPECT_EQ(1, platform.speak_count());
  EXPECT_EQ(1, platform.resume_count());
  EXPECT_EQ(stops, platform.stop_count());
}

TEST_F(AndroidSpeedreaderTabHelperTest, TtsPausedParagraphSeekRebuildsAudio) {
  RecordingTtsPlatform platform;
  ScopedPendingTtsRequest request(web_contents());
  request.controller().Play();
  auto callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  std::move(callback).Run(TwoParagraphTtsContent());
  platform.SendStartEvent();
  request.controller().Pause();
  request.controller().Play(1);
  EXPECT_EQ("Second readable paragraph.", platform.last_text());
  EXPECT_EQ(2, platform.speak_count());
  EXPECT_EQ(0, platform.resume_count());
  EXPECT_TRUE(request.controller().IsPlaying());
}

TEST_F(AndroidSpeedreaderTabHelperTest, TtsPausedVoiceChangeRebuildsAudio) {
  RecordingTtsPlatform platform;
  ScopedPendingTtsRequest request(web_contents());
  request.controller().Play();
  auto callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  std::move(callback).Run(PendingTtsContent());
  platform.SendStartEvent();
  request.controller().Pause();
  TtsPlayer::GetInstance()->SetVoice("reader-fixture");
  EXPECT_FALSE(request.controller().IsPlaying());
  request.controller().Play();
  EXPECT_EQ(2, platform.speak_count());
  EXPECT_EQ(0, platform.resume_count());
}

TEST_F(AndroidSpeedreaderTabHelperTest, TtsPausedExternalTakeoverRebuildsReader) {
  RecordingTtsPlatform platform;
  ScopedPendingTtsRequest request(web_contents());
  request.controller().Play();
  auto callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  std::move(callback).Run(PendingTtsContent());
  platform.SendStartEvent();
  request.controller().Pause();
  auto other = content::TtsUtterance::Create();
  other->SetText("Another ordinary speech consumer.");
  other->SetShouldClearQueue(true);
  content::TtsController::GetInstance()->SpeakOrEnqueue(std::move(other));
  EXPECT_FALSE(request.controller().IsPlaying());
  request.controller().Play();
  EXPECT_EQ("A readable article paragraph.", platform.last_text());
  EXPECT_EQ(2, platform.speak_count());
  EXPECT_EQ(0, platform.resume_count());
  EXPECT_TRUE(request.controller().IsPlaying());
}

TEST_F(AndroidSpeedreaderTabHelperTest, TtsPausedNavigationRequiresFreshContent) {
  RecordingTtsPlatform platform;
  ScopedPendingTtsRequest request(web_contents());
  request.controller().Play();
  auto callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  std::move(callback).Run(PendingTtsContent());
  platform.SendStartEvent();
  request.controller().Pause();
  NavigateAndCommit(GURL("https://example.com/next-article"));
  EXPECT_FALSE(request.controller().IsPlayingRequestedWebContents());
  request.controller().Play();
  callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  std::move(callback).Run(PendingTtsContent("The new article."));
  EXPECT_EQ("The new article.", platform.last_text());
  EXPECT_EQ(2, platform.speak_count());
  EXPECT_EQ(0, platform.resume_count());
}

TEST_F(AndroidSpeedreaderTabHelperTest, TtsPausedErrorRetiresNativeResume) {
  RecordingTtsPlatform platform;
  ScopedPendingTtsRequest request(web_contents());
  request.controller().Play();
  auto callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  std::move(callback).Run(PendingTtsContent());
  platform.SendStartEvent();
  request.controller().Pause();
  platform.SendTerminalEvent(content::TTS_EVENT_ERROR);
  request.controller().Play();
  EXPECT_EQ(2, platform.speak_count());
  EXPECT_EQ(0, platform.resume_count());
  EXPECT_TRUE(request.controller().IsPlaying());
}


TEST_F(AndroidSpeedreaderTabHelperTest,
       TtsPlatformPauseTracksAutomaticAndUserResume) {
  RecordingTtsPlatform platform;
  ScopedPendingTtsRequest request(web_contents());
  ReadingStateObserver state(web_contents());
  request.controller().Play();
  auto callback = request.TakeCallback();
  ASSERT_TRUE(callback);
  std::move(callback).Run(PendingTtsContent());
  platform.SendStartEvent();
  const int stops = platform.stop_count();
  // Audio focus callbacks pause the platform without pausing TtsController.
  platform.Pause();
  EXPECT_FALSE(request.controller().IsPlaying());
  EXPECT_FALSE(state.reading());
  platform.Resume();
  EXPECT_TRUE(request.controller().IsPlaying());
  EXPECT_TRUE(state.reading());
  platform.Pause();
  EXPECT_FALSE(request.controller().IsPlaying());
  EXPECT_FALSE(state.reading());
  request.controller().Play();
  EXPECT_TRUE(request.controller().IsPlaying());
  EXPECT_TRUE(state.reading());
  EXPECT_EQ(1, platform.speak_count());
  EXPECT_EQ(2, platform.resume_count());
  EXPECT_EQ(stops, platform.stop_count());
}

TEST_F(AndroidSpeedreaderTabHelperTest, EmptyBodyCompletesAsOriginalPage) {
  helper()->SetDistillStateForTesting(Distilling(Distilling::Reason::kManual));
  auto distiller = CreateBodyDistiller();
  ASSERT_TRUE(distiller);

  int completion_count = 0;
  distiller->Transform(
      std::string(),
      base::BindOnce(
          [](int* completion_count, std::string body) {
            ++*completion_count;
            EXPECT_TRUE(body.empty());
          },
          &completion_count));
  ASSERT_EQ(1, completion_count);
  EXPECT_TRUE(IsDistilling(helper()->PageDistillState()));

  // The loader notifies the delegate after the empty transform completes.
  distiller->OnBeforeSending();
  ASSERT_TRUE(IsViewOriginal(helper()->PageDistillState()));
  EXPECT_EQ(ViewOriginal::Reason::kError,
            std::get<ViewOriginal>(helper()->PageDistillState()).reason);
  distiller->OnComplete();
  EXPECT_EQ(1, completion_count);
  ASSERT_TRUE(IsViewOriginal(helper()->PageDistillState()));
  EXPECT_EQ(ViewOriginal::Reason::kError,
            std::get<ViewOriginal>(helper()->PageDistillState()).reason);
}

}  // namespace speedreader
