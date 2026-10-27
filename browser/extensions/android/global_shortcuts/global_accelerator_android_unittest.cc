// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "ui/base/accelerators/global_accelerator_listener/global_accelerator_listener_android.h"

#include <memory>
#include <string>

#include "base/functional/bind.h"
#include "base/run_loop.h"
#include "base/test/bind.h"
#include "content/public/test/browser_task_environment.h"
#include "testing/gmock/include/gmock/gmock.h"
#include "testing/gtest/include/gtest/gtest.h"
#include "ui/base/accelerators/accelerator.h"

namespace ui {
namespace {

class MockShortcutObserver : public GlobalAcceleratorListener::Observer {
 public:
  MOCK_METHOD(void, OnKeyPressed, (const Accelerator&), (override));
  MOCK_METHOD(void,
              ExecuteCommand,
              (const std::string&, const std::string&),
              (override));
};

class AndroidGlobalAcceleratorListenerTest : public testing::Test {
 protected:
  AndroidGlobalAcceleratorListenerTest()
      : listener_(GlobalAcceleratorListenerAndroid::Create()) {}

  void TearDown() override {
    listener_->SetShortcutHandlingSuspended(false);
    listener_->UnregisterAccelerators(&observer_);
    android_listener()->DetachBackend(&owner_);
    base::RunLoop().RunUntilIdle();
  }

  GlobalAcceleratorListenerAndroid* android_listener() {
    return static_cast<GlobalAcceleratorListenerAndroid*>(listener_.get());
  }

  GlobalAcceleratorListenerAndroid::RegistrationCallback Registration() {
    return base::BindRepeating(
        &AndroidGlobalAcceleratorListenerTest::OnRegistration,
        base::Unretained(this));
  }

  bool Connect() {
    return android_listener()->AttachBackend(&owner_, Registration());
  }

  bool OnRegistration(const Accelerator&, bool add) {
    in_platform_callback_ = true;
    if (add) {
      ++add_count_;
      if (detach_on_add_) {
        detach_on_add_ = false;
        android_listener()->DetachBackend(&owner_);
      }
    } else {
      ++remove_count_;
      if (detach_on_remove_) {
        detach_on_remove_ = false;
        android_listener()->DetachBackend(&owner_);
      }
    }
    in_platform_callback_ = false;
    return !add || accept_;
  }

  content::BrowserTaskEnvironment task_environment_;
  testing::StrictMock<MockShortcutObserver> observer_;
  std::unique_ptr<GlobalAcceleratorListener> listener_;
  const Accelerator first_{VKEY_5, EF_CONTROL_DOWN | EF_SHIFT_DOWN};
  const Accelerator second_{VKEY_6, EF_CONTROL_DOWN | EF_SHIFT_DOWN};
  int owner_ = 0;
  int other_owner_ = 0;
  int add_count_ = 0;
  int remove_count_ = 0;
  bool accept_ = true;
  bool detach_on_add_ = false;
  bool detach_on_remove_ = false;
  bool in_platform_callback_ = false;
};

TEST_F(AndroidGlobalAcceleratorListenerTest, RequiresAcceptedRegistration) {
  EXPECT_FALSE(listener_->RegisterAccelerator(first_, &observer_));
  ASSERT_TRUE(Connect());
  accept_ = false;
  EXPECT_FALSE(listener_->RegisterAccelerator(first_, &observer_));
  EXPECT_FALSE(android_listener()->DispatchAccelerator(&owner_, first_));
  EXPECT_EQ(add_count_, 1);
  EXPECT_EQ(remove_count_, 0);
}

TEST_F(AndroidGlobalAcceleratorListenerTest,
       ExactOwnerAndUnregisterRevokeDispatch) {
  EXPECT_FALSE(android_listener()->AttachBackend(nullptr, Registration()));
  EXPECT_FALSE(android_listener()->AttachBackend(&owner_, {}));
  ASSERT_TRUE(Connect());
  EXPECT_FALSE(Connect());
  EXPECT_FALSE(
      android_listener()->AttachBackend(&other_owner_, Registration()));
  ASSERT_TRUE(listener_->RegisterAccelerator(first_, &observer_));

  android_listener()->DetachBackend(&other_owner_);
  EXPECT_TRUE(android_listener()->HasBackend());
  EXPECT_FALSE(android_listener()->DispatchAccelerator(&other_owner_, first_));
  EXPECT_CALL(observer_, OnKeyPressed(first_)).Times(1);
  EXPECT_TRUE(android_listener()->DispatchAccelerator(&owner_, first_));

  listener_->UnregisterAccelerator(first_, &observer_);
  EXPECT_FALSE(android_listener()->DispatchAccelerator(&owner_, first_));
  EXPECT_EQ(remove_count_, 1);
}

TEST_F(AndroidGlobalAcceleratorListenerTest,
       SuspensionReacquiresBeforeDispatch) {
  ASSERT_TRUE(Connect());
  ASSERT_TRUE(listener_->RegisterAccelerator(first_, &observer_));
  listener_->SetShortcutHandlingSuspended(true);
  EXPECT_FALSE(android_listener()->DispatchAccelerator(&owner_, first_));
  EXPECT_FALSE(listener_->RegisterAccelerator(second_, &observer_));
  EXPECT_EQ(remove_count_, 1);

  listener_->SetShortcutHandlingSuspended(false);
  EXPECT_EQ(add_count_, 2);
  EXPECT_CALL(observer_, OnKeyPressed(first_)).Times(1);
  EXPECT_TRUE(android_listener()->DispatchAccelerator(&owner_, first_));
}

TEST_F(AndroidGlobalAcceleratorListenerTest,
       AvailabilityIsCoalescedOutsidePlatformCallbacks) {
  int notifications = 0;
  auto subscription =
      android_listener()->AddBackendObserver(base::BindLambdaForTesting([&] {
        EXPECT_FALSE(in_platform_callback_);
        ++notifications;
      }));
  ASSERT_TRUE(Connect());
  android_listener()->RefreshBackendRegistrations();
  android_listener()->RefreshBackendRegistrations();
  EXPECT_EQ(notifications, 0);
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(notifications, 1);
}

TEST_F(AndroidGlobalAcceleratorListenerTest,
       ReentrantDetachDuringRegistrationDoesNotAcceptShortcut) {
  ASSERT_TRUE(Connect());
  detach_on_add_ = true;
  EXPECT_FALSE(listener_->RegisterAccelerator(first_, &observer_));
  EXPECT_FALSE(android_listener()->HasBackend());
  EXPECT_FALSE(android_listener()->DispatchAccelerator(&owner_, first_));
  base::RunLoop().RunUntilIdle();
  // A failed registration must not leave an entry in the base accelerator map.
  ASSERT_TRUE(Connect());
  EXPECT_TRUE(listener_->RegisterAccelerator(first_, &observer_));
}

TEST_F(AndroidGlobalAcceleratorListenerTest,
       ReentrantDetachDuringUnregisterDoesNotReenterBaseMap) {
  ASSERT_TRUE(Connect());
  ASSERT_TRUE(listener_->RegisterAccelerator(first_, &observer_));
  base::RunLoop().RunUntilIdle();
  int notifications = 0;
  auto subscription =
      android_listener()->AddBackendObserver(base::BindLambdaForTesting([&] {
        EXPECT_FALSE(in_platform_callback_);
        ++notifications;
        // Model the registry's cleanup after availability was revoked. Running
        // this inside StopListeningForAccelerator invalidates the outer erase.
        listener_->UnregisterAccelerators(&observer_);
      }));
  detach_on_remove_ = true;
  listener_->UnregisterAccelerator(first_, &observer_);
  EXPECT_EQ(notifications, 0);
  EXPECT_FALSE(android_listener()->HasBackend());
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(notifications, 1);
  EXPECT_FALSE(android_listener()->DispatchAccelerator(&owner_, first_));
}

TEST_F(AndroidGlobalAcceleratorListenerTest,
       ReentrantDetachDuringSuspensionDoesNotInvalidateIteration) {
  ASSERT_TRUE(Connect());
  ASSERT_TRUE(listener_->RegisterAccelerator(first_, &observer_));
  ASSERT_TRUE(listener_->RegisterAccelerator(second_, &observer_));
  base::RunLoop().RunUntilIdle();
  int notifications = 0;
  auto subscription =
      android_listener()->AddBackendObserver(base::BindLambdaForTesting([&] {
        EXPECT_FALSE(in_platform_callback_);
        ++notifications;
        listener_->UnregisterAccelerators(&observer_);
      }));
  detach_on_remove_ = true;
  listener_->SetShortcutHandlingSuspended(true);
  EXPECT_EQ(notifications, 0);
  EXPECT_FALSE(android_listener()->DispatchAccelerator(&owner_, first_));
  EXPECT_FALSE(android_listener()->DispatchAccelerator(&owner_, second_));
  // Finish the base-map iteration and resume before the availability observer
  // performs cleanup, as required by the listener's suspension contract.
  listener_->SetShortcutHandlingSuspended(false);
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(notifications, 1);
}

TEST_F(AndroidGlobalAcceleratorListenerTest,
       DestructionCancelsPendingAvailabilityNotification) {
  auto owned = GlobalAcceleratorListenerAndroid::Create();
  auto* android = static_cast<GlobalAcceleratorListenerAndroid*>(owned.get());
  int notifications = 0;
  auto subscription = android->AddBackendObserver(
      base::BindLambdaForTesting([&] { ++notifications; }));
  ASSERT_TRUE(android->AttachBackend(&other_owner_, Registration()));
  EXPECT_EQ(notifications, 0);
  owned.reset();
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(notifications, 0);
}

}  // namespace
}  // namespace ui
