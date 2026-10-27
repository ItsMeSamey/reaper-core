// Copyright 2026 The Reaper Authors. SPDX-License-Identifier: MPL-2.0

#include "chrome/browser/ui/extensions/download_open_dialog.h"

#include <memory>
#include <vector>

#include "base/files/file_path.h"
#include "base/run_loop.h"
#include "base/test/bind.h"
#include "chrome/test/base/chrome_render_view_host_test_harness.h"
#include "content/public/browser/web_contents.h"
#include "testing/gtest/include/gtest/gtest.h"
#include "ui/android/fake_modal_dialog_manager_bridge.h"
#include "ui/android/modal_dialog_wrapper.h"
#include "ui/android/window_android.h"

namespace extensions {
namespace {

class DownloadOpenDialogAndroidTest : public ChromeRenderViewHostTestHarness {
 protected:
  void AttachWindow(bool with_manager) {
    window_ = ui::WindowAndroid::CreateForTesting();
    window_->get()->AddChild(web_contents()->GetNativeView());
    if (with_manager) {
      manager_ = ui::FakeModalDialogManagerBridge::CreateForTab(
          window_->get(), /*use_empty_java_presenter=*/false);
    }
  }

  void Show() {
    ShowDownloadOpenConfirmationDialog(
        web_contents(), "An extension",
        base::FilePath(FILE_PATH_LITERAL("file.txt")),
        base::BindLambdaForTesting(
            [this](bool accepted) { results_.push_back(accepted); }));
  }

  void Dismiss() {
    manager_->DismissAllDialogs(static_cast<int>(
        ui::ModalDialogWrapper::DismissalCause::TAB_DESTROYED));
    base::RunLoop().RunUntilIdle();
  }

  void TearDown() override {
    if (manager_) {
      Dismiss();
      manager_.reset();
    }
    ChromeRenderViewHostTestHarness::TearDown();
    window_.reset();
  }

  std::vector<bool> results_;
  std::unique_ptr<ui::WindowAndroid::ScopedWindowAndroidForTesting> window_;
  std::unique_ptr<ui::FakeModalDialogManagerBridge> manager_;
};

TEST_F(DownloadOpenDialogAndroidTest, MissingWindowDeniesSynchronouslyOnce) {
  ASSERT_EQ(nullptr, web_contents()->GetTopLevelNativeWindow());
  Show();
  EXPECT_EQ(std::vector<bool>({false}), results_);
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(std::vector<bool>({false}), results_);
}

TEST_F(DownloadOpenDialogAndroidTest, MissingDialogManagerDeniesOnce) {
  AttachWindow(/*with_manager=*/false);
  ASSERT_NE(nullptr, web_contents()->GetTopLevelNativeWindow());
  ASSERT_EQ(nullptr, window_->get()->GetModalDialogManagerBridge());
  Show();
  EXPECT_EQ(std::vector<bool>({false}), results_);
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(std::vector<bool>({false}), results_);
}

TEST_F(DownloadOpenDialogAndroidTest, OnlyPositiveButtonGrantsConsent) {
  AttachWindow(/*with_manager=*/true);
  Show();
  EXPECT_TRUE(results_.empty());
  manager_->ClickPositiveButton();
  EXPECT_EQ(std::vector<bool>({true}), results_);
  Dismiss();
  EXPECT_EQ(std::vector<bool>({true}), results_);
}

TEST_F(DownloadOpenDialogAndroidTest, NegativeButtonDeniesOnce) {
  AttachWindow(/*with_manager=*/true);
  Show();
  EXPECT_TRUE(results_.empty());
  manager_->ClickNegativeButton();
  EXPECT_EQ(std::vector<bool>({false}), results_);
  Dismiss();
  EXPECT_EQ(std::vector<bool>({false}), results_);
}

TEST_F(DownloadOpenDialogAndroidTest, DismissalWithoutActionDeniesOnce) {
  AttachWindow(/*with_manager=*/true);
  Show();
  EXPECT_TRUE(results_.empty());
  Dismiss();
  EXPECT_EQ(std::vector<bool>({false}), results_);
  Dismiss();
  EXPECT_EQ(std::vector<bool>({false}), results_);
}

}  // namespace
}  // namespace extensions
