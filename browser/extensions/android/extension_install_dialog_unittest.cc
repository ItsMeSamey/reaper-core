// Copyright (c) 2026 The Reaper Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include <memory>
#include <utility>

#include "base/test/bind.h"
#include "chrome/browser/extensions/extension_install_prompt.h"
#include "chrome/browser/ui/android/extensions/extension_install_dialog_view_android.h"
#include "chrome/test/base/chrome_render_view_host_test_harness.h"
#include "extensions/browser/extension_install_prompt_client.h"
#include "extensions/browser/install_prompt_data.h"
#include "testing/gtest/include/gtest/gtest.h"
#include "ui/android/window_android.h"

namespace extensions {
namespace {

class PromptObserver final : public ExtensionInstallPromptClient::Observer {
 public:
  void OnDialogOpened() override { ++opened; }
  void OnDialogAccepted() override { ++accepted; }
  void OnDialogCanceled() override { ++canceled; }
  int opened = 0;
  int accepted = 0;
  int canceled = 0;
};

using ExtensionInstallDialogAndroidTest = ChromeRenderViewHostTestHarness;

TEST_F(ExtensionInstallDialogAndroidTest, MissingActivityAbortsOnce) {
  // The real WindowAndroid testing factory has an application Context, but
  // neither an Activity nor a ModalDialogManager. Exercise real JNI creation,
  // not an injected null result, and the self-owned native view cleanup.
  auto window = ui::WindowAndroid::CreateForTesting();
  PromptObserver observer;
  auto prompt =
      std::make_unique<InstallPromptData>(InstallPromptData::INSTALL_PROMPT);
  prompt->AddObserver(&observer);
  int completions = 0;
  auto* view = new ExtensionInstallDialogViewAndroid(
      nullptr, std::move(prompt),
      base::BindLambdaForTesting(
          [&](ExtensionInstallPrompt::DoneCallbackPayload payload) {
            ++completions;
            EXPECT_EQ(ExtensionInstallPrompt::Result::ABORTED, payload.result);
            EXPECT_EQ(1, observer.canceled);
          }));
  view->ShowDialog(window->get());
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, completions);
  EXPECT_EQ(1, observer.canceled);
  EXPECT_EQ(0, observer.accepted);
  EXPECT_EQ(0, observer.opened);
}

TEST_F(ExtensionInstallDialogAndroidTest, DestructionBeforeShowCancelsOnce) {
  int completions = 0;
  {
    ExtensionInstallDialogViewAndroid view(
        nullptr,
        std::make_unique<InstallPromptData>(InstallPromptData::INSTALL_PROMPT),
        base::BindLambdaForTesting(
            [&](ExtensionInstallPrompt::DoneCallbackPayload payload) {
              ++completions;
              EXPECT_EQ(ExtensionInstallPrompt::Result::USER_CANCELED,
                        payload.result);
            }));
  }
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, completions);
}

TEST_F(ExtensionInstallDialogAndroidTest, MissingActivityWithoutCallback) {
  auto window = ui::WindowAndroid::CreateForTesting();
  auto* view = new ExtensionInstallDialogViewAndroid(
      nullptr,
      std::make_unique<InstallPromptData>(InstallPromptData::INSTALL_PROMPT), {});
  view->ShowDialog(window->get());
  task_environment()->RunUntilIdle();
}

}  // namespace
}  // namespace extensions
