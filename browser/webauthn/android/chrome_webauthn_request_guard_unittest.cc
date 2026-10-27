// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
#include "brave/browser/webauthn/android/chrome_webauthn_request_guard.h"

#include <memory>
#include <utility>

#include "base/callback_list.h"
#include "base/functional/bind.h"
#include "base/memory/weak_ptr.h"
#include "base/run_loop.h"
#include "base/test/bind.h"
#include "chrome/browser/ui/android/tab_model/tab_model_list.h"
#include "chrome/browser/ui/android/tab_model/tab_model_observer.h"
#include "chrome/browser/ui/android/tab_model/tab_model_test_helper.h"
#include "chrome/test/base/chrome_render_view_host_test_harness.h"
#include "components/tabs/public/mock_tab_interface.h"
#include "content/public/browser/web_contents.h"
#include "content/public/test/navigation_simulator.h"
#include "content/public/test/test_renderer_host.h"
#include "net/base/net_errors.h"
#include "testing/gmock/include/gmock/gmock.h"
#include "testing/gtest/include/gtest/gtest.h"
#include "ui/android/view_android.h"
#include "ui/android/window_android.h"
#include "url/gurl.h"

class ChromeWebAuthnRequestGuardTest : public ChromeRenderViewHostTestHarness {
 public:
  void SetUp() override {
    ChromeRenderViewHostTestHarness::SetUp();
    NavigateAndCommit(GURL("https://webauthn.test"));
    window_ = ui::WindowAndroid::CreateForTesting();
    window_->get()->AddChild(web_contents()->GetNativeView());
    tabs::TabLookupFromWebContents::CreateForWebContents(web_contents(), &tab_);
    ON_CALL(tab_, GetWeakPtr()).WillByDefault([this] {
      return tab_weak_factory_.GetWeakPtr();
    });
    ON_CALL(tab_, GetContents()).WillByDefault(testing::Return(web_contents()));
    ON_CALL(tab_, GetProfile()).WillByDefault(testing::Return(profile()));
    ON_CALL(tab_, IsActivated()).WillByDefault([this] { return active_; });
    ON_CALL(tab_, IsVisible()).WillByDefault([this] { return visible_; });
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
    model_ = std::make_unique<TestTabModel>(profile());
    model_->SetWebContentsList({web_contents()});
    model_->SetIsActiveModel(true);
    TabModelList::AddTabModel(model_.get());
  }

  void TearDown() override {
    if (model_) {
      EXPECT_EQ(nullptr, model_->GetObserver());
      TabModelList::RemoveTabModel(model_.get());
      model_.reset();
    }
    DeleteContents();
    window_.reset();
    ChromeRenderViewHostTestHarness::TearDown();
  }

 protected:
  std::unique_ptr<webauthn::WebAuthnRequestGuard> Request(
      bool conditional = false,
      content::RenderFrameHost* frame = nullptr) {
    return CreateChromeWebAuthnRequestGuard(
        frame ? frame : main_rfh(), conditional,
        base::BindLambdaForTesting([this] { ++cancellations_; }));
  }

  void DeactivateModel() {
    // Production observers run before IsActiveModel changes.
    if (auto* observer = model_->GetObserver())
      observer->OnWillActiveStateChange(*model_, false);
    model_->SetIsActiveModel(false);
  }

  tabs::MockTabInterface tab_;
  bool active_ = true;
  bool visible_ = true;
  int cancellations_ = 0;
  std::unique_ptr<TestTabModel> model_;
  base::RepeatingCallbackList<void(tabs::TabInterface*)> deactivation_;
  base::RepeatingCallbackList<void(tabs::TabInterface*,
                                   tabs::TabInterface::DetachReason)>
      detachment_;
  base::RepeatingCallbackList<
      void(tabs::TabInterface*, content::WebContents*, content::WebContents*)>
      discard_;
  std::unique_ptr<ui::WindowAndroid::ScopedWindowAndroidForTesting> window_;
  base::WeakPtrFactory<tabs::MockTabInterface> tab_weak_factory_{&tab_};
};

TEST_F(ChromeWebAuthnRequestGuardTest, SelectedVisibleDocumentIsAdmitted) {
  auto request = Request();
  EXPECT_TRUE(request->IsValid());
  EXPECT_TRUE(request->CanCompleteRequest());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(0, cancellations_);
}

TEST_F(ChromeWebAuthnRequestGuardTest, UnselectedVisiblePaneIsDenied) {
  active_ = false;
  auto request = Request();
  active_ = true;
  EXPECT_FALSE(request->IsValid());
  EXPECT_FALSE(request->BeginInteraction());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ChromeWebAuthnRequestGuardTest, SelectedInactiveProfileModelIsDenied) {
  model_->SetIsActiveModel(false);
  auto request = Request();
  EXPECT_FALSE(request->IsValid());
  EXPECT_EQ(nullptr, model_->GetObserver());
}

TEST_F(ChromeWebAuthnRequestGuardTest, InitiallyHiddenModalRequestIsDenied) {
  visible_ = false;
  auto request = Request();
  visible_ = true;
  EXPECT_FALSE(request->CanCompleteRequest());
}

TEST_F(ChromeWebAuthnRequestGuardTest, MissingWindowCannotBeginInteraction) {
  web_contents()->GetNativeView()->RemoveFromParent();
  auto request = Request(true);
  EXPECT_TRUE(request->IsValid());
  EXPECT_FALSE(request->BeginInteraction());
  window_->get()->AddChild(web_contents()->GetNativeView());
  EXPECT_FALSE(request->IsValid());
}

TEST_F(ChromeWebAuthnRequestGuardTest, MismatchedTabProfileIsDenied) {
  ON_CALL(tab_, GetProfile()).WillByDefault(testing::Return(nullptr));
  auto request = Request(true);
  EXPECT_FALSE(request->IsValid());
}

TEST_F(ChromeWebAuthnRequestGuardTest, ConditionalWaitsUntilActiveInteraction) {
  active_ = false;
  visible_ = false;
  model_->SetIsActiveModel(false);
  auto request = Request(true);
  EXPECT_TRUE(request->IsValid());
  EXPECT_FALSE(request->CanCompleteRequest());
  deactivation_.Notify(&tab_);
  DeactivateModel();
  EXPECT_TRUE(request->IsValid());
  active_ = true;
  visible_ = true;
  model_->SetIsActiveModel(true);
  EXPECT_TRUE(request->BeginInteraction());
  EXPECT_TRUE(request->CanCompleteRequest());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(0, cancellations_);
  deactivation_.Notify(&tab_);
  EXPECT_FALSE(request->IsValid());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ChromeWebAuthnRequestGuardTest, ModalDeselectAndReselectCannotRevive) {
  auto request = Request();
  deactivation_.Notify(&tab_);
  active_ = false;
  active_ = true;
  EXPECT_FALSE(request->CanCompleteRequest());
  detachment_.Notify(&tab_, tabs::TabInterface::DetachReason::kDelete);
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ChromeWebAuthnRequestGuardTest, ModelSwitchCancelsWithoutTabDeselect) {
  auto request = Request();
  DeactivateModel();
  model_->SetIsActiveModel(true);
  EXPECT_TRUE(active_);
  EXPECT_TRUE(visible_);
  EXPECT_FALSE(request->CanCompleteRequest());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ChromeWebAuthnRequestGuardTest, AuthenticatorActivityHideAllowsResult) {
  EXPECT_CALL(tab_, RegisterWillBecomeHidden(testing::_)).Times(0);
  auto request = Request();
  visible_ = false;
  web_contents()->WasHidden();
  EXPECT_TRUE(request->IsValid());
  EXPECT_TRUE(request->CanCompleteRequest());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(0, cancellations_);
}

TEST_F(ChromeWebAuthnRequestGuardTest, ConditionalDocumentNavigationCancels) {
  auto request = Request(true);
  auto navigation = content::NavigationSimulator::CreateRendererInitiated(
      GURL("https://webauthn.test/elsewhere"), main_rfh());
  navigation->Start();
  navigation->Fail(net::ERR_ABORTED);
  EXPECT_FALSE(request->IsValid());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ChromeWebAuthnRequestGuardTest, SameDocumentNavigationRemainsValid) {
  auto request = Request();
  auto navigation = content::NavigationSimulator::CreateRendererInitiated(
      GURL("https://webauthn.test/#anchor"), main_rfh());
  navigation->CommitSameDocument();
  EXPECT_TRUE(request->CanCompleteRequest());
}

TEST_F(ChromeWebAuthnRequestGuardTest, SubframeRequestTracksExactDocument) {
  auto* child =
      content::RenderFrameHostTester::For(main_rfh())->AppendChild("requester");
  child = content::NavigationSimulator::NavigateAndCommitFromDocument(
      GURL("https://child.test"), child);
  auto request = Request(true, child);
  EXPECT_TRUE(request->IsValid());
  content::NavigationSimulator::NavigateAndCommitFromDocument(
      GURL("https://child.test/other"), child);
  EXPECT_FALSE(request->IsValid());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ChromeWebAuthnRequestGuardTest, DiscardCancelsConditionalWait) {
  auto request = Request(true);
  discard_.Notify(&tab_, web_contents(), nullptr);
  EXPECT_FALSE(request->IsValid());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ChromeWebAuthnRequestGuardTest, ContentsDestructionCancels) {
  auto request = Request(true);
  DeleteContents();
  EXPECT_FALSE(request->IsValid());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ChromeWebAuthnRequestGuardTest, ModelDestructionReleasesObservation) {
  auto request = Request(true);
  ASSERT_NE(nullptr, model_->GetObserver());
  auto* observer = model_->GetObserver();
  observer->OnTabModelDestroyed(*model_);
  // The dying source owns cleanup; no later guard callback or destructor
  // may try to unregister from this model.
  EXPECT_EQ(observer, model_->GetObserver());
  TabModelList::RemoveTabModel(model_.get());
  model_.reset();
  EXPECT_FALSE(request->IsValid());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ChromeWebAuthnRequestGuardTest, DestroyedGuardDropsPostedCancellation) {
  auto request = Request();
  deactivation_.Notify(&tab_);
  request.reset();
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(0, cancellations_);
}

TEST_F(ChromeWebAuthnRequestGuardTest, CancellationCanDestroyItsOwnGuard) {
  std::unique_ptr<webauthn::WebAuthnRequestGuard> request;
  request = CreateChromeWebAuthnRequestGuard(main_rfh(), false,
                                             base::BindLambdaForTesting([&] {
                                               ++cancellations_;
                                               request.reset();
                                             }));
  DeactivateModel();
  EXPECT_NE(nullptr, request);
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(nullptr, request);
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ChromeWebAuthnRequestGuardTest, VanishedOriginalTabCannotComplete) {
  auto request = Request(true);
  tab_weak_factory_.InvalidateWeakPtrs();
  EXPECT_FALSE(request->IsValid());
  EXPECT_FALSE(request->CanCompleteRequest());
  EXPECT_FALSE(request->BeginInteraction());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}

TEST_F(ChromeWebAuthnRequestGuardTest, UnregisteredNonTabDocumentIsDenied) {
  auto contents = CreateTestWebContents();
  content::NavigationSimulator::NavigateAndCommitFromBrowser(
      contents.get(), GURL("https://unowned.test"));
  window_->get()->AddChild(contents->GetNativeView());
  auto request = Request(true, contents->GetPrimaryMainFrame());
  EXPECT_FALSE(request->IsValid());
  EXPECT_FALSE(request->CanCompleteRequest());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(1, cancellations_);
}
