// Copyright 2026 The Reaper Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include <algorithm>

#include "base/run_loop.h"
#include "build/build_config.h"
#include "chrome/browser/extensions/extension_apitest.h"
#include "chrome/browser/profiles/profile.h"
#include "chrome/browser/speech/extension_api/tts_engine_extension_api.h"
#include "content/public/browser/tts_controller.h"
#include "content/public/test/browser_test.h"
#include "extensions/browser/process_manager.h"
#include "extensions/common/extension.h"
#include "extensions/test/extension_test_message_listener.h"
#include "extensions/test/test_extension_dir.h"

static_assert(BUILDFLAG(IS_ANDROID));

namespace extensions {

class AndroidTtsEngineTest : public ExtensionApiTest {
 protected:
  const Extension* LoadEngine() {
    directory_.WriteManifest(R"({
      "manifest_version": 3, "name": "Android speech engine", "version": "1",
      "permissions": ["ttsEngine"],
      "background": {"service_worker": "worker.js"},
      "tts_engine": {"voices": [{"voice_name": "Reaper test voice",
        "lang": "en-US", "event_types": ["start", "end", "error"]}]}
    })");
    directory_.WriteFile(FILE_PATH_LITERAL("worker.js"), R"(
      chrome.ttsEngine.onSpeak.addListener((text, options, sendEvent) => {
        sendEvent({type: 'start', charIndex: 0});
        chrome.test.sendMessage('speaking');
      });
      chrome.ttsEngine.onStop.addListener(() => chrome.test.sendMessage('stopped'));
      // Deliberately omit onResume to exercise the background-page warning path.
      chrome.ttsEngine.onPause.addListener(() => chrome.test.sendMessage('paused'));
      chrome.test.sendMessage('ready');
    )");
    ExtensionTestMessageListener ready("ready");
    const Extension* extension = LoadExtension(directory_.UnpackedPath());
    if (!extension || !ready.WaitUntilSatisfied()) {
      return nullptr;
    }
    return extension;
  }

 private:
  TestExtensionDir directory_;
};

IN_PROC_BROWSER_TEST_F(AndroidTtsEngineTest, WorkerVoicePauseResumeStop) {
  const Extension* engine = LoadEngine();
  ASSERT_TRUE(engine);
  EXPECT_FALSE(ProcessManager::Get(profile())->GetBackgroundHostForExtension(
      engine->id()));
  auto* controller = content::TtsController::GetInstance();
  std::vector<content::VoiceData> voices;
  // This uses the actual browser-client hookup; do not install a test delegate.
  controller->GetVoices(profile(), GURL(), &voices);
  ASSERT_TRUE(std::ranges::any_of(voices, [&](const auto& voice) {
    return !voice.native && voice.engine_id == engine->id();
  }));
  auto utterance = content::TtsUtterance::Create(profile(), true);
  utterance->SetText("worker speech");
  utterance->SetEngineId(engine->id());
  utterance->SetVoiceName("Reaper test voice");
  ExtensionTestMessageListener speaking("speaking");
  controller->SpeakOrEnqueue(std::move(utterance));
  ASSERT_TRUE(speaking.WaitUntilSatisfied());
  EXPECT_TRUE(controller->IsSpeaking());
  ExtensionTestMessageListener paused("paused");
  controller->Pause();
  ASSERT_TRUE(paused.WaitUntilSatisfied());
  controller->Resume();
  ExtensionTestMessageListener stopped("stopped");
  controller->Stop();
  ASSERT_TRUE(stopped.WaitUntilSatisfied());
  EXPECT_FALSE(controller->IsSpeaking());
}

IN_PROC_BROWSER_TEST_F(AndroidTtsEngineTest, PrivateEngineRequiresOptIn) {
  const Extension* engine = LoadEngine();
  ASSERT_TRUE(engine);
  std::vector<content::VoiceData> voices;
  auto* private_profile = profile()->GetPrimaryOTRProfile(true);
  content::TtsController::GetInstance()->GetVoices(private_profile, GURL(),
                                                   &voices);
  EXPECT_FALSE(std::ranges::any_of(voices, [&](const auto& voice) {
    return voice.engine_id == engine->id();
  }));
}

IN_PROC_BROWSER_TEST_F(AndroidTtsEngineTest, ClearedContextControlIsSafe) {
  auto* engine = TtsExtensionEngine::GetInstance();
  engine->Stop(nullptr, "unloaded-engine");
  engine->Pause(nullptr, "unloaded-engine");
  engine->Resume(nullptr, "unloaded-engine");
  base::RunLoop().RunUntilIdle();
}

}  // namespace extensions
