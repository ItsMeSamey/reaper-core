// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
#include "brave/browser/ui/android/device_chooser/active_device_chooser_request.h"

#include <memory>
#include <utility>

#include "base/callback_list.h"
#include "base/functional/bind.h"
#include "base/run_loop.h"
#include "base/task/sequenced_task_runner.h"
#include "base/test/bind.h"
#include "chrome/browser/ui/android/tab_model/tab_model_list.h"
#include "chrome/browser/ui/android/tab_model/tab_model_test_helper.h"
#include "chrome/browser/usb/usb_chooser_controller.h"
#include "chrome/test/base/chrome_render_view_host_test_harness.h"
#include "components/tabs/public/mock_tab_interface.h"
#include "content/public/browser/web_contents.h"
#include "content/public/test/navigation_simulator.h"
#include "content/public/test/test_renderer_host.h"
#include "content/public/test/web_contents_tester.h"
#include "net/base/net_errors.h"
#include "services/device/public/mojom/usb_enumeration_options.mojom.h"
#include "testing/gmock/include/gmock/gmock.h"
#include "ui/android/window_android.h"

class ActiveDeviceChooserRequestTest : public ChromeRenderViewHostTestHarness {
 public:
  void SetUp() override {
    ChromeRenderViewHostTestHarness::SetUp();
    NavigateAndCommit(GURL("https://chooser.test"));
    window_ = ui::WindowAndroid::CreateForTesting();
    window_->get()->AddChild(web_contents()->GetNativeView());
    tabs::TabLookupFromWebContents::CreateForWebContents(web_contents(), &tab_);
    ON_CALL(tab_, GetWeakPtr()).WillByDefault([this] {
      return tab_weak_factory_.GetWeakPtr();
    });
    ON_CALL(tab_, GetContents()).WillByDefault(testing::Return(web_contents()));
    ON_CALL(tab_, GetProfile()).WillByDefault(testing::Return(profile()));
    ON_CALL(tab_, IsVisible()).WillByDefault([this] { return visible_; });
    model_ = std::make_unique<TestTabModel>(profile());
    model_->SetWebContentsList({web_contents()});
    model_->SetIsActiveModel(true);
    TabModelList::AddTabModel(model_.get());
    ON_CALL(tab_, RegisterWillBecomeHidden(testing::_))
        .WillByDefault(
            [this](auto callback) { return hidden_.Add(std::move(callback)); });
    ON_CALL(tab_, IsActivated()).WillByDefault([this] { return active_; });
    ON_CALL(tab_, RegisterWillDeactivate(testing::_))
        .WillByDefault([this](auto callback) {
          return deactivation_.Add(std::move(callback));
        });
    ON_CALL(tab_, RegisterWillDetach(testing::_))
        .WillByDefault([this](auto callback) {
          return detachment_.Add(std::move(callback));
        });
    ON_CALL(tab_, RegisterWillDiscardContents(testing::_))
        .WillByDefault([this](auto callback) {
          return discard_.Add(std::move(callback));
        });
  }
  void TearDown() override {
    TabModelList::RemoveTabModel(model_.get());
    model_.reset();
    DeleteContents();
    window_.reset();
    ChromeRenderViewHostTestHarness::TearDown();
  }
  std::unique_ptr<ActiveDeviceChooserRequest> Request(
      content::RenderFrameHost* frame = nullptr) {
    return std::make_unique<ActiveDeviceChooserRequest>(
        frame ? frame : main_rfh(),
        base::BindLambdaForTesting([this] { ++cancellations_; }));
  }
  void Deselect() {
    // TabInterface fires before it changes IsActivated().
    deactivation_.Notify(&tab_);
    active_ = false;
  }

 protected:
  tabs::MockTabInterface tab_;
  base::WeakPtrFactory<tabs::MockTabInterface> tab_weak_factory_{&tab_};
  bool active_ = true;
  bool visible_ = true;
  std::unique_ptr<TestTabModel> model_;
  base::RepeatingCallbackList<void(tabs::TabInterface*)> hidden_;
  int cancellations_ = 0;
  base::RepeatingCallbackList<void(tabs::TabInterface*)> deactivation_;
  base::RepeatingCallbackList<void(tabs::TabInterface*,
                                   tabs::TabInterface::DetachReason)>
      detachment_;
  base::RepeatingCallbackList<
      void(tabs::TabInterface*, content::WebContents*, content::WebContents*)>
      discard_;
  std::unique_ptr<ui::WindowAndroid::ScopedWindowAndroidForTesting> window_;
};

TEST_F(ActiveDeviceChooserRequestTest, ActivatedDocumentIsEligible) {
  auto request = Request();
  EXPECT_TRUE(request->IsValid());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(0, cancellations_);
}

TEST_F(ActiveDeviceChooserRequestTest, MissingWindowIsDenied) {
  web_contents()->GetNativeView()->RemoveFromParent();
  auto request = Request();
  EXPECT_FALSE(request->IsValid());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ActiveDeviceChooserRequestTest, MissingDocumentIsDenied) {
  ActiveDeviceChooserRequest request(nullptr);
  EXPECT_FALSE(request.IsValid());
}

TEST_F(ActiveDeviceChooserRequestTest, InitiallyInactiveDoesNotRevive) {
  active_ = false;
  auto request = Request();
  active_ = true;
  EXPECT_FALSE(request->IsValid());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ActiveDeviceChooserRequestTest, DeselectAndReselectCancelsOnce) {
  auto request = Request();
  Deselect();
  active_ = true;
  EXPECT_FALSE(request->IsValid());
  detachment_.Notify(&tab_, tabs::TabInterface::DetachReason::kDelete);
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ActiveDeviceChooserRequestTest, PostedSelectionRetainsLatchedRejection) {
  auto request = Request();
  request->ClearCancellationCallback();
  bool delivered = false;
  bool permission_granted = true;
  // USB refresh and Bluetooth posted selection retain this same object until
  // immediately before their permission-grant callback, after UI destruction.
  base::SequencedTaskRunner::GetCurrentDefault()->PostTask(
      FROM_HERE, base::BindOnce(
                     [](std::unique_ptr<ActiveDeviceChooserRequest> pending,
                        bool* delivered, bool* granted) {
                       *delivered = true;
                       *granted = pending->IsValid();
                     },
                     std::move(request), &delivered, &permission_granted));
  Deselect();
  active_ = true;
  base::RunLoop().RunUntilIdle();
  EXPECT_TRUE(delivered);
  EXPECT_FALSE(permission_granted);
  EXPECT_EQ(0, cancellations_);
}

TEST_F(ActiveDeviceChooserRequestTest, CancelledNavigationDoesNotRevive) {
  auto request = Request();
  auto navigation = content::NavigationSimulator::CreateRendererInitiated(
      GURL("https://elsewhere.test"), main_rfh());
  navigation->Start();
  navigation->Fail(net::ERR_ABORTED);
  EXPECT_FALSE(request->IsValid());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ActiveDeviceChooserRequestTest, SameDocumentNavigationRemainsEligible) {
  auto request = Request();
  auto navigation = content::NavigationSimulator::CreateRendererInitiated(
      GURL("https://chooser.test/#anchor"), main_rfh());
  navigation->CommitSameDocument();
  EXPECT_TRUE(request->IsValid());
}

TEST_F(ActiveDeviceChooserRequestTest, ExactSubframeNavigationCancels) {
  auto* child =
      content::RenderFrameHostTester::For(main_rfh())->AppendChild("requester");
  child = content::NavigationSimulator::NavigateAndCommitFromDocument(
      GURL("https://child.test"), child);
  auto request = Request(child);
  ASSERT_TRUE(request->IsValid());
  content::NavigationSimulator::NavigateAndCommitFromDocument(
      GURL("https://child.test/other"), child);
  EXPECT_FALSE(request->IsValid());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ActiveDeviceChooserRequestTest, DetachAndDiscardInvalidate) {
  auto request = Request();
  discard_.Notify(&tab_, web_contents(), nullptr);
  EXPECT_FALSE(request->IsValid());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ActiveDeviceChooserRequestTest, ContentsDestructionCancels) {
  auto request = Request();
  DeleteContents();
  EXPECT_FALSE(request->IsValid());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ActiveDeviceChooserRequestTest, GuardDestructionCancelsPostedWork) {
  auto request = Request();
  Deselect();
  request.reset();
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(0, cancellations_);
}

TEST_F(ActiveDeviceChooserRequestTest,
       UsbSelectionAfterDeactivationReturnsNoDevice) {
  int calls = 0;
  auto controller = std::make_unique<UsbChooserController>(
      main_rfh(), blink::mojom::WebUsbRequestDeviceOptions::New(),
      base::BindLambdaForTesting([&](device::mojom::UsbDeviceInfoPtr device) {
        ++calls;
        EXPECT_FALSE(device);
      }));
  Deselect();
  active_ = true;
  // Rejection precedes looking up a selected device row or requesting OS
  // access.
  controller->Select({0});
  EXPECT_EQ(1, calls);
  controller.reset();
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, calls);
}

TEST_F(ActiveDeviceChooserRequestTest, HiddenCurrentTabIsDenied) {
  visible_ = false;
  auto request = Request();
  EXPECT_FALSE(request->IsValid());
}

TEST_F(ActiveDeviceChooserRequestTest, InactiveProfileModelIsDenied) {
  model_->SetIsActiveModel(false);
  auto request = Request();
  EXPECT_FALSE(request->IsValid());
}

TEST_F(ActiveDeviceChooserRequestTest, HideThenRevealCannotReviveSelection) {
  auto request = Request();
  request->ClearCancellationCallback();
  bool granted = true;
  base::SequencedTaskRunner::GetCurrentDefault()->PostTask(
      FROM_HERE,
      base::BindOnce([](std::unique_ptr<ActiveDeviceChooserRequest> pending,
                        bool* granted) { *granted = pending->IsValid(); },
                     std::move(request), &granted));
  hidden_.Notify(&tab_);
  visible_ = false;
  model_->SetIsActiveModel(false);
  visible_ = true;
  model_->SetIsActiveModel(true);
  base::RunLoop().RunUntilIdle();
  EXPECT_FALSE(granted);
}

TEST_F(ActiveDeviceChooserRequestTest,
       UnregisteredNonTabCannotBorrowActiveOwner) {
  auto unrelated =
      content::WebContentsTester::CreateTestWebContents(profile(), nullptr);
  content::NavigationSimulator::NavigateAndCommitFromBrowser(
      unrelated.get(), GURL("https://unrelated.test"));
  window_->get()->AddChild(unrelated->GetNativeView());
  unrelated->WasShown();
  ActiveDeviceChooserRequest request(unrelated->GetPrimaryMainFrame());
  EXPECT_FALSE(request.IsValid());
  EXPECT_TRUE(active_);
  EXPECT_TRUE(visible_);
}
