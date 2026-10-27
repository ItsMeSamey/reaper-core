// Copyright 2026 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/ui/android/tab_model/tab_model_observer_jni_bridge.h"

#include <memory>

#include "base/functional/bind.h"
#include "base/test/bind.h"
#include "chrome/browser/tab_list/tab_list_interface_observer.h"
#include "chrome/browser/ui/android/tab_model/tab_model_test_helper.h"
#include "chrome/test/base/chrome_render_view_host_test_harness.h"
#include "chrome/test/base/testing_profile.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace {
class ModelObserver : public TabModelObserver {
 public:
  void OnWillActiveStateChange(TabModel& model, bool active) override {
    if (on_will) {
      on_will.Run();
    }
  }
  void OnDidActiveStateChange(TabModel& model, bool active) override {
    if (on_did) {
      on_did.Run();
    }
  }
  void OnTabModelDestroyed(TabModel& model) override {
    if (on_shutdown) {
      on_shutdown.Run();
    }
  }
  base::RepeatingClosure on_will;
  base::RepeatingClosure on_did;
  base::RepeatingClosure on_shutdown;
};

class InterfaceObserver : public TabListInterfaceObserver {
 public:
  void OnTabListActiveChanged(TabListInterface& list, bool active) override {
    if (on_active) {
      on_active.Run();
    }
  }
  void OnTabListDestroyed(TabListInterface& list) override {
    if (on_shutdown) {
      on_shutdown.Run();
    }
  }
  base::RepeatingClosure on_active;
  base::RepeatingClosure on_shutdown;
};
}  // namespace

// Uses the production dispatcher and observer lists, with only Java attachment
// omitted. The owner releases its ref through the same on-empty callback as
// TabModelJniBridge; no test dispatcher or deferred-task substitute is
// involved.
class TabModelObserverJniBridgeTest : public ChromeRenderViewHostTestHarness {
 protected:
  void SetUp() override {
    ChromeRenderViewHostTestHarness::SetUp();
    model_ = std::make_unique<OwningTestTabModel>(profile());
    bridge_ = base::WrapRefCounted(new TabModelObserverJniBridge(
        *model_,
        base::BindRepeating(&TabModelObserverJniBridgeTest::ReleaseOwner,
                            base::Unretained(this))));
  }
  void TearDown() override {
    ShutdownModel();
    ChromeRenderViewHostTestHarness::TearDown();
  }
  void ReleaseOwner() {
    EXPECT_FALSE(bridge_->has_observers());
    EXPECT_EQ(bridge_->dispatch_depth_, 0u);
    ++owner_releases_;
    bridge_.reset();
  }
  void ShutdownModel() {
    if (bridge_) {
      bridge_->NotifyShutdown();
      bridge_.reset();
    }
    model_.reset();
  }
  base::WeakPtr<TabModelObserverJniBridge> WeakBridge() {
    return bridge_->weak_factory_.GetWeakPtr();
  }

  std::unique_ptr<OwningTestTabModel> model_;
  scoped_refptr<TabModelObserverJniBridge> bridge_;
  int owner_releases_ = 0;
};

TEST_F(TabModelObserverJniBridgeTest, LastModelObserverCanRemoveItself) {
  ModelObserver observer;
  auto weak = WeakBridge();
  observer.on_will = base::BindLambdaForTesting([&] {
    bridge_->RemoveObserver(&observer);
    EXPECT_TRUE(weak);
    EXPECT_EQ(0, owner_releases_);
  });
  bridge_->AddObserver(&observer);
  bridge_->OnWillActiveStateChange(nullptr, model_.get(), false);
  EXPECT_FALSE(weak);
  EXPECT_FALSE(bridge_);
  EXPECT_EQ(1, owner_releases_);
}

TEST_F(TabModelObserverJniBridgeTest, LastInterfaceObserverCanRemoveItself) {
  InterfaceObserver observer;
  auto weak = WeakBridge();
  observer.on_active = base::BindLambdaForTesting([&] {
    bridge_->RemoveTabListInterfaceObserver(&observer);
    EXPECT_TRUE(weak);
    EXPECT_EQ(0, owner_releases_);
  });
  bridge_->AddTabListInterfaceObserver(&observer);
  bridge_->OnDidActiveStateChange(nullptr, model_.get(), false);
  EXPECT_FALSE(weak);
  EXPECT_EQ(1, owner_releases_);
}

TEST_F(TabModelObserverJniBridgeTest, BothObserverListsFinishBeforeRelease) {
  ModelObserver model_observer;
  InterfaceObserver interface_observer;
  auto weak = WeakBridge();
  int phase = 0;
  model_observer.on_did = base::BindLambdaForTesting([&] {
    EXPECT_EQ(0, phase++);
    bridge_->RemoveObserver(&model_observer);
  });
  interface_observer.on_active = base::BindLambdaForTesting([&] {
    EXPECT_EQ(1, phase++);
    bridge_->RemoveTabListInterfaceObserver(&interface_observer);
    EXPECT_TRUE(weak);
  });
  bridge_->AddObserver(&model_observer);
  bridge_->AddTabListInterfaceObserver(&interface_observer);
  bridge_->OnDidActiveStateChange(nullptr, model_.get(), true);
  EXPECT_EQ(2, phase);
  EXPECT_FALSE(weak);
  EXPECT_EQ(1, owner_releases_);
}

TEST_F(TabModelObserverJniBridgeTest, NestedRemovalWaitsForOutermostDispatch) {
  ModelObserver observer;
  auto weak = WeakBridge();
  observer.on_did = base::BindLambdaForTesting([&] {
    bridge_->RemoveObserver(&observer);
    EXPECT_TRUE(weak);
  });
  observer.on_will = base::BindLambdaForTesting([&] {
    bridge_->OnDidActiveStateChange(nullptr, model_.get(), false);
    EXPECT_TRUE(weak);
    EXPECT_TRUE(bridge_);
    EXPECT_EQ(0, owner_releases_);
  });
  bridge_->AddObserver(&observer);
  bridge_->OnWillActiveStateChange(nullptr, model_.get(), false);
  EXPECT_FALSE(weak);
  EXPECT_EQ(1, owner_releases_);
}

TEST_F(TabModelObserverJniBridgeTest, ReaddedObserverKeepsExistingBridge) {
  ModelObserver first;
  ModelObserver replacement;
  auto weak = WeakBridge();
  int replacement_calls = 0;
  first.on_will = base::BindLambdaForTesting([&] {
    bridge_->RemoveObserver(&first);
    bridge_->AddObserver(&replacement);
  });
  replacement.on_will =
      base::BindLambdaForTesting([&] { ++replacement_calls; });
  bridge_->AddObserver(&first);
  bridge_->OnWillActiveStateChange(nullptr, model_.get(), false);
  EXPECT_TRUE(weak);
  EXPECT_EQ(1, replacement_calls);
  EXPECT_EQ(0, owner_releases_);
  bridge_->RemoveObserver(&replacement);
  EXPECT_FALSE(weak);
  EXPECT_EQ(1, owner_releases_);
}

TEST_F(TabModelObserverJniBridgeTest, ShutdownAllowsBothListsToUnsubscribe) {
  ModelObserver model_observer;
  InterfaceObserver interface_observer;
  auto weak = WeakBridge();
  int model_shutdowns = 0;
  int interface_shutdowns = 0;
  model_observer.on_shutdown = base::BindLambdaForTesting([&] {
    ++model_shutdowns;
    bridge_->RemoveObserver(&model_observer);
    EXPECT_TRUE(weak);
  });
  interface_observer.on_shutdown = base::BindLambdaForTesting([&] {
    ++interface_shutdowns;
    bridge_->RemoveTabListInterfaceObserver(&interface_observer);
    EXPECT_TRUE(weak);
  });
  bridge_->AddObserver(&model_observer);
  bridge_->AddTabListInterfaceObserver(&interface_observer);
  bridge_->NotifyShutdown();
  bridge_->NotifyShutdown();
  EXPECT_EQ(1, model_shutdowns);
  EXPECT_EQ(1, interface_shutdowns);
  EXPECT_EQ(0, owner_releases_);
  ShutdownModel();
  EXPECT_FALSE(weak);
}

TEST_F(TabModelObserverJniBridgeTest,
       OwnerDestroyedDuringDispatchStopsDelivery) {
  ModelObserver model_observer;
  InterfaceObserver interface_observer;
  auto weak = WeakBridge();
  int interface_events = 0;
  int shutdowns = 0;
  model_observer.on_did = base::BindLambdaForTesting([&] {
    ShutdownModel();
    EXPECT_TRUE(weak);  // The outer dispatcher still owns a reference.
  });
  model_observer.on_shutdown = base::BindLambdaForTesting([&] {
    ++shutdowns;
    bridge_->RemoveObserver(&model_observer);
  });
  interface_observer.on_shutdown = base::BindLambdaForTesting([&] {
    ++shutdowns;
    bridge_->RemoveTabListInterfaceObserver(&interface_observer);
  });
  interface_observer.on_active =
      base::BindLambdaForTesting([&] { ++interface_events; });
  bridge_->AddObserver(&model_observer);
  bridge_->AddTabListInterfaceObserver(&interface_observer);
  bridge_->OnDidActiveStateChange(nullptr, model_.get(), true);
  EXPECT_EQ(2, shutdowns);
  EXPECT_EQ(0, interface_events);
  EXPECT_FALSE(weak);
  EXPECT_FALSE(model_);
  EXPECT_EQ(0, owner_releases_);
}

TEST_F(TabModelObserverJniBridgeTest,
       RemovalOutsideDispatchReleasesImmediately) {
  ModelObserver observer;
  auto weak = WeakBridge();
  bridge_->AddObserver(&observer);
  bridge_->RemoveObserver(&observer);
  EXPECT_FALSE(weak);
  EXPECT_FALSE(bridge_);
  EXPECT_EQ(1, owner_releases_);
}
