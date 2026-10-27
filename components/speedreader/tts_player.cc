// Copyright (c) 2023 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "brave/components/speedreader/tts_player.h"

#include <algorithm>
#include <optional>
#if BUILDFLAG(IS_ANDROID)
#include <limits>
#endif
#include <utility>

#include "base/check.h"
#include "base/strings/utf_string_conversions.h"
#include "content/public/browser/navigation_handle.h"
#if BUILDFLAG(IS_ANDROID)
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/weak_document_ptr.h"
#endif
#include "content/public/browser/tts_controller.h"
#include "content/public/browser/tts_utterance.h"
#include "content/public/browser/web_contents.h"

namespace {
constexpr const char kParagraphsKey[] = "paragraphs";
#if BUILDFLAG(IS_ANDROID)
// Reader positions are UTF-16 code-unit offsets, like the paragraph returned
// by GetParagraphToRead. Ignore unknown positions and avoid signed overflow.
int BoundedReadingPosition(size_t paragraph_length, int start, int advance) {
  const size_t maximum =
      std::min(paragraph_length,
               static_cast<size_t>(std::numeric_limits<int>::max()));
  const size_t position = static_cast<size_t>(std::max(start, 0)) +
                          static_cast<size_t>(std::max(advance, 0));
  return static_cast<int>(std::min(position, maximum));
}
#endif
}

namespace speedreader {

class TtsPlayer::Controller::Delegate : public content::UtteranceEventDelegate {
 public:
  explicit Delegate(base::WeakPtr<TtsPlayer::Controller> handler)
      : handler_(handler) {}
  ~Delegate() override = default;

  using PassKey = base::PassKey<Delegate>;

  // content::UtteranceEventDelegate:
  void OnTtsEvent(content::TtsUtterance* utterance,
                  content::TtsEventType event_type,
                  int char_index,
                  int length,
                  const std::string& error_message) override {
    if (!handler_) {
      return;
    }

    handler_->OnTtsEvent(PassKey(), utterance, event_type, char_index, length,
                         error_message);
  }

 private:
  base::WeakPtr<TtsPlayer::Controller> handler_;
};

TtsPlayer::TtsPlayer() = default;

TtsPlayer::~TtsPlayer() = default;

// static
TtsPlayer* TtsPlayer::GetInstance() {
  return base::Singleton<TtsPlayer>::get();
}

TtsPlayer::Controller& TtsPlayer::GetControllerFor(
    content::WebContents* web_contents) {
  controller_.SetRequestWebContents(web_contents);
  return controller_;
}

void TtsPlayer::AddObserver(Observer* observer) {
  observers_.AddObserver(observer);
}

void TtsPlayer::RemoveObserver(Observer* observer) {
  observers_.RemoveObserver(observer);
}

double TtsPlayer::GetSpeed() const {
  return controller_.current_speed_;
}

const std::string& TtsPlayer::GetVoice() const {
  return controller_.current_voice_;
}

void TtsPlayer::SetSpeed(double speed) {
  if (std::abs(speed - controller_.current_speed_) > 0.05) {
    controller_.current_speed_ = speed;
#if BUILDFLAG(IS_ANDROID)
    controller_.DiscardPausedUtterance();
#endif
    if (controller_.IsPlaying()) {
      controller_.Resume(true);
    }
  }
}

void TtsPlayer::SetVoice(const std::string& voice) {
  if (controller_.current_voice_ != voice) {
    controller_.current_voice_ = voice;
#if BUILDFLAG(IS_ANDROID)
    controller_.DiscardPausedUtterance();
#endif
    if (controller_.IsPlaying()) {
      controller_.Resume(true);
    }
  }
}

TtsPlayer::Controller::Controller(TtsPlayer* owner) : owner_(owner) {}
TtsPlayer::Controller::~Controller() = default;

void TtsPlayer::Controller::SetRequestWebContents(
    content::WebContents* web_contents) {
#if BUILDFLAG(IS_ANDROID)
  if (request_web_contents_ != web_contents) {
    ++request_generation_;
    if (!playing_web_contents_) {
      Observe(nullptr);
    }
  }
#endif
  request_web_contents_ = web_contents;
}

bool TtsPlayer::Controller::IsPlaying() const {
#if BUILDFLAG(IS_ANDROID)
  if (android_utterance_id_ < 0 || android_paused_ || android_platform_paused_) {
    return false;
  }
#endif
  auto* tts = content::TtsController::GetInstance();
  return tts->IsSpeaking();
}

bool TtsPlayer::Controller::IsPlayingRequestedWebContents(
    std::optional<int> paragraph_index) const {
  if (paragraph_index.has_value() && paragraph_index != paragraph_index_) {
    return false;
  }
  return playing_web_contents_ == request_web_contents_;
}

void TtsPlayer::Controller::Play(std::optional<int> paragraph_index) {
#if BUILDFLAG(IS_ANDROID)
  if (!request_web_contents_) {
    return;
  }
#endif
  DCHECK(request_web_contents_);
  if (IsPlayingRequestedWebContents()) {
    Observe(playing_web_contents_);
#if BUILDFLAG(IS_ANDROID)
    if ((android_paused_ || android_platform_paused_) &&
        android_utterance_id_ >= 0 &&
        (!paragraph_index.has_value() || paragraph_index == paragraph_index_)) {
      Resume(false);
      return;
    }
#endif
    if (paragraph_index.has_value() && paragraph_index != paragraph_index_) {
      paragraph_index_ = paragraph_index.value();
      reading_start_position_ = 0;
      reading_position_ = 0;
    }
    Resume(true);
  } else {
#if BUILDFLAG(IS_ANDROID)
    auto* requested_contents = request_web_contents_.get();
    auto* rfh = requested_contents->GetPrimaryMainFrame();
    if (!rfh) {
      return;
    }
    const auto source_document = rfh->GetWeakDocumentPtr();
    Stop();
    rfh = source_document.AsRenderFrameHostIfValid();
    if (!rfh || request_web_contents_ != requested_contents ||
        content::WebContents::FromRenderFrameHost(rfh) != requested_contents ||
        requested_contents->GetPrimaryMainFrame() != rfh || !owner_->delegate_) {
      return;
    }
    const uint64_t generation = request_generation_;
    Observe(requested_contents);
    owner_->delegate_->RequestReadingContent(
        requested_contents,
        base::BindOnce(
            [](base::WeakPtr<Controller> controller, uint64_t generation,
               content::WeakDocumentPtr source_document,
               content::WebContents* requested_contents,
               std::optional<int> paragraph_index, base::Value content) {
              auto* rfh = source_document.AsRenderFrameHostIfValid();
              if (!controller || generation != controller->request_generation_ ||
                  !rfh || controller->request_web_contents_ != requested_contents ||
                  controller->web_contents() != requested_contents ||
                  content::WebContents::FromRenderFrameHost(rfh) !=
                      requested_contents ||
                  requested_contents->GetPrimaryMainFrame() != rfh) {
                return;
              }
              controller->OnContentReady(requested_contents, paragraph_index,
                                         std::move(content));
            },
            weak_factory_.GetWeakPtr(), generation, source_document,
            requested_contents, paragraph_index));
#else
    Stop();
    TtsPlayer::GetInstance()->delegate_->RequestReadingContent(
        request_web_contents_,
        base::BindOnce(&Controller::OnContentReady, base::Unretained(this),
                       request_web_contents_, std::move(paragraph_index)));
#endif
  }
}

void TtsPlayer::Controller::Pause() {
  if (IsPlayingRequestedWebContents()) {
    auto* tts = content::TtsController::GetInstance();
#if BUILDFLAG(IS_ANDROID)
    if (android_utterance_id_ < 0 || android_paused_) {
      return;
    }
    continue_next_paragraph_ = false;
    android_paused_ = true;
    const int utterance_id = android_utterance_id_;
    const uint64_t generation = request_generation_;
    tts->Pause();
    // A pending engine may not emit PAUSE before its first START. Publish this
    // user action once; synchronous native PAUSE is suppressed below.
    if (android_paused_ && android_utterance_id_ == utterance_id &&
        request_generation_ == generation) {
      for (auto& o : owner_->observers_) {
        o.OnReadingStop(playing_web_contents_);
      }
    }
#else
    reading_start_position_ =
        std::min(static_cast<int>(GetParagraphToRead().size()),
                 reading_start_position_ + reading_position_);
    reading_position_ = 0;
    tts->Stop();
#endif
  } else {
    Stop();
  }
}

void TtsPlayer::Controller::Resume() {
  const bool start_new = !IsPlayingRequestedWebContents();
  Resume(start_new);
}

void TtsPlayer::Controller::Stop() {
#if BUILDFLAG(IS_ANDROID)
  const uint64_t generation = ++request_generation_;
  continue_next_paragraph_ = false;
  android_paused_ = false;
  android_platform_paused_ = false;
#endif
  auto* tts = content::TtsController::GetInstance();
  tts->Stop();
#if BUILDFLAG(IS_ANDROID)
  if (generation != request_generation_) {
    return;
  }
  android_utterance_id_ = -1;
#endif

  paragraph_index_ = -1;
  reading_position_ = 0;
  reading_start_position_ = 0;
  for (auto& o : owner_->observers_) {
    o.OnReadingProgress(playing_web_contents_, paragraph_index_, 0, 0);
  }
  playing_web_contents_ = nullptr;

  Observe(nullptr);
}

void TtsPlayer::Controller::Forward() {
  if (!HasNextParagraph()) {
    return;
  }
#if BUILDFLAG(IS_ANDROID)
  DiscardPausedUtterance();
#endif
  ++paragraph_index_;
  reading_start_position_ = 0;
  reading_position_ = 0;
  if (IsPlaying()) {
    Resume(true);
  } else {
    for (auto& o : owner_->observers_) {
      o.OnReadingProgress(request_web_contents_, paragraph_index_, 0, 0);
    }
  }
}

void TtsPlayer::Controller::Rewind() {
#if BUILDFLAG(IS_ANDROID)
  DiscardPausedUtterance();
#endif
  if (paragraph_index_ > 0) {
    --paragraph_index_;
  }
  reading_start_position_ = 0;
  reading_position_ = 0;
  if (IsPlaying()) {
    Resume(true);
  } else {
    for (auto& o : owner_->observers_) {
      o.OnReadingProgress(request_web_contents_, paragraph_index_, 0, 0);
    }
  }
}

#if BUILDFLAG(IS_ANDROID)
void TtsPlayer::Controller::DiscardPausedUtterance() {
  if ((!android_paused_ && !android_platform_paused_) ||
      android_utterance_id_ < 0) {
    return;
  }
  // Seek and parameter changes rebuild later. Do not resume unrelated speech.
  android_paused_ = false;
  android_platform_paused_ = false;
  android_utterance_id_ = -1;
  content::TtsController::GetInstance()->Stop();
}

#endif
void TtsPlayer::Controller::Resume(bool recreate_utterance) {
  auto* tts = content::TtsController::GetInstance();
  if (!recreate_utterance) {
#if BUILDFLAG(IS_ANDROID)
    if ((!android_paused_ && !android_platform_paused_) ||
        android_utterance_id_ < 0) {
      return;
    }
    if (!android_paused_) {
      auto* playing_contents = playing_web_contents_.get();
      auto* rfh = playing_contents ? playing_contents->GetPrimaryMainFrame()
                                   : nullptr;
      if (!rfh) {
        return;
      }
      const auto source_document = rfh->GetWeakDocumentPtr();
      auto* requested_contents = request_web_contents_.get();
      const int utterance_id = android_utterance_id_;
      const uint64_t generation = request_generation_;
      // Focus pauses bypass the controller's manual pause flag. Establish the
      // ordinary pause/resume handoff so a user Play requests fresh audio focus.
      android_paused_ = true;
      tts->Pause();
      rfh = source_document.AsRenderFrameHostIfValid();
      if (!android_paused_ || android_utterance_id_ != utterance_id ||
          generation != request_generation_ || !rfh ||
          playing_web_contents_ != playing_contents ||
          request_web_contents_ != requested_contents ||
          content::WebContents::FromRenderFrameHost(rfh) != playing_contents ||
          playing_contents->GetPrimaryMainFrame() != rfh) {
        return;
      }
    }
    android_paused_ = false;
#endif
    tts->Resume();
  } else {
#if BUILDFLAG(IS_ANDROID)
    auto* playing_contents = playing_web_contents_.get();
    auto* rfh = playing_contents ? playing_contents->GetPrimaryMainFrame()
                                 : nullptr;
    if (!rfh) {
      return;
    }
    const auto source_document = rfh->GetWeakDocumentPtr();
    auto* requested_contents = request_web_contents_.get();
    const uint64_t generation = ++request_generation_;
    // Another TTS consumer can replace Reader while the global queue remains
    // paused. An explicit Reader restart must retire that queue before Speak.
    tts->Stop();
    rfh = source_document.AsRenderFrameHostIfValid();
    if (generation != request_generation_ || !rfh ||
        playing_web_contents_ != playing_contents ||
        request_web_contents_ != requested_contents ||
        content::WebContents::FromRenderFrameHost(rfh) != playing_contents ||
        playing_contents->GetPrimaryMainFrame() != rfh) {
      return;
    }
    android_paused_ = false;
    android_platform_paused_ = false;
    android_utterance_id_ = -1;
#endif
    auto utterance = content::TtsUtterance::Create();

#if BUILDFLAG(IS_ANDROID)
    const std::u16string paragraph = GetParagraphToRead();
    reading_start_position_ =
        BoundedReadingPosition(paragraph.size(), reading_start_position_,
                               reading_position_);
    reading_position_ = 0;
    utterance->SetText(base::UTF16ToUTF8(
        paragraph.substr(static_cast<size_t>(reading_start_position_))));
#else
    reading_start_position_ += reading_position_;
    reading_position_ = 0;

    utterance->SetText(base::UTF16ToUTF8(
        GetParagraphToRead().substr(reading_start_position_)));
#endif
    utterance->SetShouldClearQueue(true);
    utterance->SetEventDelegate(
        std::make_unique<TtsPlayer::Controller::Delegate>(
            weak_factory_.GetWeakPtr()));
    utterance->SetVoiceName(current_voice_);
    const auto& params = utterance->GetContinuousParameters();
    utterance->SetContinuousParameters(current_speed_, params.pitch,
                                       params.volume);
#if BUILDFLAG(IS_ANDROID)
    android_utterance_id_ = utterance->GetId();
#endif
    tts->SpeakOrEnqueue(std::move(utterance));
  }
}

bool TtsPlayer::Controller::HasNextParagraph() {
  if (!reading_content_.is_dict()) {
    return false;
  }
  const auto* content = reading_content_.GetDict().FindList(kParagraphsKey);
  if (!content) {
    return false;
  }
  return paragraph_index_ + 1 < static_cast<int>(content->size());
}

std::u16string TtsPlayer::Controller::GetParagraphToRead() {
  if (!reading_content_.is_dict()) {
    return {};
  }
  const auto* content = reading_content_.GetDict().FindList(kParagraphsKey);
  if (!content) {
    return {};
  }
  if (0 <= paragraph_index_ &&
      paragraph_index_ < static_cast<int>(content->size())) {
    return base::UTF8ToUTF16((*content)[paragraph_index_].GetString());
  }
  return {};
}

void TtsPlayer::Controller::DidStartNavigation(
    content::NavigationHandle* handle) {
  if (!handle->IsInPrimaryMainFrame() || handle->IsSameDocument()) {
    return;
  }
  Stop();
}

void TtsPlayer::Controller::WebContentsDestroyed() {
#if BUILDFLAG(IS_ANDROID)
  if (request_web_contents_ == web_contents()) {
    request_web_contents_ = nullptr;
  }
#endif
  Stop();
}

void TtsPlayer::Controller::OnTtsEvent(base::PassKey<Delegate>,
                                       content::TtsUtterance* utterance,
                                       content::TtsEventType event_type,
                                       int char_index,
                                       int length,
                                       const std::string& error_message) {
#if BUILDFLAG(IS_ANDROID)
  // Terminal events from replaced speech must not retire a newer Reader.
  if (utterance->GetId() != android_utterance_id_) {
    return;
  }
#endif
  switch (event_type) {
    case content::TtsEventType::TTS_EVENT_WORD:
#if BUILDFLAG(IS_ANDROID)
      {
        // -1 denotes an unknown engine offset. Keep the last valid position.
        if (char_index < 0 || reading_start_position_ < 0) {
          break;
        }
        const auto paragraph = GetParagraphToRead();
        const size_t start = static_cast<size_t>(reading_start_position_);
        if (start > paragraph.size() ||
            static_cast<size_t>(char_index) > paragraph.size() - start ||
            char_index > std::numeric_limits<int>::max() -
                             reading_start_position_) {
          break;
        }
      }
#endif
      reading_position_ = char_index;
      for (auto& o : owner_->observers_) {
        o.OnReadingProgress(playing_web_contents_, paragraph_index_,
                            reading_start_position_ + char_index, length);
      }
      break;
#if BUILDFLAG(IS_ANDROID)
    case content::TtsEventType::TTS_EVENT_PAUSE:
      android_platform_paused_ = true;
      if (!android_paused_) {
        for (auto& o : owner_->observers_) {
          o.OnReadingStop(playing_web_contents_);
        }
      }
      break;
#endif
    case content::TtsEventType::TTS_EVENT_ERROR:
#if BUILDFLAG(IS_ANDROID)
      // A failed next utterance can end before its START event arrives.
      continue_next_paragraph_ = false;
      [[fallthrough]];
#endif
    case content::TtsEventType::TTS_EVENT_INTERRUPTED:
    case content::TtsEventType::TTS_EVENT_CANCELLED:
#if BUILDFLAG(IS_ANDROID)
      android_utterance_id_ = -1;
      android_paused_ = false;
      android_platform_paused_ = false;
#else
    case content::TtsEventType::TTS_EVENT_PAUSE:
#endif
      if (!continue_next_paragraph_) {
        for (auto& o : owner_->observers_) {
          o.OnReadingStop(playing_web_contents_);
        }
      }
      break;
    case content::TtsEventType::TTS_EVENT_END:
#if BUILDFLAG(IS_ANDROID)
      android_utterance_id_ = -1;
      android_paused_ = false;
      android_platform_paused_ = false;
#endif
      reading_position_ = 0;
      reading_start_position_ = 0;

      if (HasNextParagraph()) {
        ++paragraph_index_;
        continue_next_paragraph_ = true;
        Resume(true);
#if BUILDFLAG(IS_ANDROID)
        // Suppress only the synchronous interruption of the previous utterance.
        // Later events belong to the next paragraph, including early failures.
        continue_next_paragraph_ = false;
#endif
      } else {
        paragraph_index_ = -1;
        continue_next_paragraph_ = false;
        for (auto& o : owner_->observers_) {
          o.OnReadingProgress(playing_web_contents_, paragraph_index_,
                              char_index, length);
          o.OnReadingStop(playing_web_contents_);
        }
      }
      break;
    case content::TtsEventType::TTS_EVENT_RESUME:
    case content::TtsEventType::TTS_EVENT_START:
#if BUILDFLAG(IS_ANDROID)
      if (android_paused_) {
        break;
      }
      android_platform_paused_ = false;
#endif
      if (!continue_next_paragraph_) {
        for (auto& o : owner_->observers_) {
          o.OnReadingStart(playing_web_contents_);
        }
      }
      continue_next_paragraph_ = false;
#if BUILDFLAG(IS_ANDROID)
      // Native Android TTS has START events but no WORD range events. Expose
      // the active paragraph without inventing a word range.
      for (auto& o : owner_->observers_) {
        o.OnReadingProgress(playing_web_contents_, paragraph_index_,
                            reading_start_position_, 0);
      }
#endif
      break;
    case content::TTS_EVENT_SENTENCE:
    case content::TTS_EVENT_MARKER:
      break;
  }
}

void TtsPlayer::Controller::OnContentReady(content::WebContents* web_contents,
                                           std::optional<int> paragraph_index,
                                           base::Value content) {
  if (!content.is_dict() || web_contents != request_web_contents_) {
    return;
  }
  playing_web_contents_ = web_contents;

  Observe(playing_web_contents_);

  paragraph_index_ = paragraph_index.value_or(0);
  reading_content_ = std::move(content);
  reading_position_ = 0;
  reading_start_position_ = 0;
  Resume(true);
}

}  // namespace speedreader
