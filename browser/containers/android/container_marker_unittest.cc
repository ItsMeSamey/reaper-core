// Copyright 2026 The Reaper Authors.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <cmath>
#include <limits>

#include "base/android/jni_android.h"
#include "base/android/scoped_java_ref.h"
#include "base/memory/raw_ptr.h"
#include "cc/resources/scoped_ui_resource.h"
#include "cc/resources/ui_resource_bitmap.h"
#include "cc/resources/ui_resource_manager.h"
#include "cc/slim/layer.h"
#include "chrome/browser/android/compositor/decoration_tab_title.h"
#include "chrome/browser/android/compositor/layer/tab_handle_layer.h"
#include "chrome/browser/android/compositor/layer_title_cache.h"
#include "testing/gmock/include/gmock/gmock.h"
#include "testing/gtest/include/gtest/gtest.h"
#include "third_party/skia/include/core/SkBitmap.h"
#include "third_party/skia/include/core/SkColor.h"
#include "ui/android/resources/nine_patch_resource.h"
#include "ui/android/resources/resource_manager.h"
#include "ui/gfx/geometry/rect.h"
#include "ui/gfx/geometry/size.h"

namespace android {
namespace {

// Observe the actual public compositor tree; no production-only testing API.
class ContainerMarkerTest : public testing::Test {
 protected:
  void SetUp() override {
    handle_ = TabHandleLayer::Create(nullptr);
    ASSERT_FALSE(handle_->layer()->children().empty());
    tab_ = handle_->layer()->children().front();
    ASSERT_FALSE(tab_->children().empty());
    decoration_ = tab_->children().front();
    ASSERT_EQ(1u, decoration_->children().size());
    marker_ = decoration_->children().front();
  }

  void ShowNormalMarker() {
    decoration_->SetBounds(gfx::Size(100, 20));
    handle_->SetContainerMarker(SK_ColorBLUE, 2.f);
    ASSERT_TRUE(marker_->draws_content());
  }

  void ExpectMarker(int width, int height, float x, float y) {
    EXPECT_TRUE(marker_->draws_content());
    EXPECT_EQ(width, marker_->bounds().width());
    EXPECT_EQ(height, marker_->bounds().height());
    EXPECT_FLOAT_EQ(x, marker_->position().x());
    EXPECT_FLOAT_EQ(y, marker_->position().y());
    EXPECT_EQ(SK_ColorBLUE, marker_->background_color().toSkColor());
  }

  scoped_refptr<TabHandleLayer> handle_;
  scoped_refptr<cc::slim::Layer> tab_;
  scoped_refptr<cc::slim::Layer> decoration_;
  scoped_refptr<cc::slim::Layer> marker_;
};

TEST_F(ContainerMarkerTest, UsesCurrentBoundsAndOpaqueColor) {
  ShowNormalMarker();
  ExpectMarker(92, 2, 4.f, 2.f);
  EXPECT_FLOAT_EQ(1.f, marker_->corner_radii().upper_left());
  decoration_->SetBounds(gfx::Size(140, 20));
  handle_->SetContainerMarker(SK_ColorBLUE, 2.f);
  ExpectMarker(132, 2, 4.f, 2.f);
}

TEST_F(ContainerMarkerTest, FractionalThicknessRoundsUpWithinBounds) {
  decoration_->SetBounds(gfx::Size(100, 20));
  handle_->SetContainerMarker(SK_ColorBLUE, 2.1f);
  ExpectMarker(88, 3, 6.f, 3.f);
  EXPECT_FLOAT_EQ(1.5f, marker_->corner_radii().upper_left());
  handle_->SetContainerMarker(
      SK_ColorBLUE, std::nextafter(2.f, std::numeric_limits<float>::infinity()));
  ExpectMarker(88, 3, 6.f, 3.f);
}

TEST_F(ContainerMarkerTest, NonPositiveThicknessHidesPreviouslyVisibleMarker) {
  for (float thickness : {0.f, -1.f, -std::numeric_limits<float>::max()}) {
    SCOPED_TRACE(thickness);
    ShowNormalMarker();
    handle_->SetContainerMarker(SK_ColorBLUE, thickness);
    EXPECT_FALSE(marker_->draws_content());
  }
}

TEST_F(ContainerMarkerTest, NonFiniteThicknessHidesPreviouslyVisibleMarker) {
  for (float thickness : {std::numeric_limits<float>::quiet_NaN(),
                          std::numeric_limits<float>::infinity(),
                          -std::numeric_limits<float>::infinity()}) {
    ShowNormalMarker();
    handle_->SetContainerMarker(SK_ColorBLUE, thickness);
    EXPECT_FALSE(marker_->draws_content());
  }
}

TEST_F(ContainerMarkerTest, NonOpaqueColorHidesPreviouslyVisibleMarker) {
  for (SkColor color : {SkColorSetARGB(0, 0, 0, 255),
                        SkColorSetARGB(128, 0, 0, 255)}) {
    ShowNormalMarker();
    handle_->SetContainerMarker(color, 2.f);
    EXPECT_FALSE(marker_->draws_content());
  }
}

TEST_F(ContainerMarkerTest, InvalidBoundsHidePreviouslyVisibleMarker) {
  // gfx::Size's actual constructor normalizes negative dimensions to zero.
  for (gfx::Size bounds : {gfx::Size(0, 20), gfx::Size(100, 0),
                           gfx::Size(-1, 20), gfx::Size(100, -1),
                           gfx::Size(100, 1), gfx::Size(3, 20),
                           gfx::Size(4, 2)}) {
    ShowNormalMarker();
    decoration_->SetBounds(bounds);
    handle_->SetContainerMarker(SK_ColorBLUE, 2.f);
    EXPECT_FALSE(marker_->draws_content());
  }
}

TEST_F(ContainerMarkerTest, NarrowValidBoundsClampHugeFiniteThickness) {
  decoration_->SetBounds(gfx::Size(5, 2));
  handle_->SetContainerMarker(SK_ColorBLUE, std::numeric_limits<float>::max());
  ExpectMarker(1, 1, 2.f, 1.f);
}

TEST_F(ContainerMarkerTest, MaximumIntegerBoundsKeepPositiveWidth) {
  decoration_->SetBounds(gfx::Size(std::numeric_limits<int>::max(),
                                   std::numeric_limits<int>::max()));
  handle_->SetContainerMarker(SK_ColorBLUE, std::numeric_limits<float>::max());
  // Assert real layer dimensions after the previous signed-overflow boundary.
  ExpectMarker(3, 536870911, static_cast<float>(1073741822),
               static_cast<float>(536870911));
}

TEST_F(ContainerMarkerTest, IntegerCapIsNotRoundedUpThroughFloat) {
  decoration_->SetBounds(gfx::Size(134217727, 67108862));
  handle_->SetContainerMarker(SK_ColorBLUE, std::numeric_limits<float>::max());
  // 33,554,431 cannot be represented exactly as float. The resulting layer
  // must keep its actual integer cap rather than round up to 33,554,432.
  ExpectMarker(3, 33554431, static_cast<float>(67108862),
               static_cast<float>(33554431));
}

class MockMarkerResourceManager : public ui::ResourceManager {
 public:
  MOCK_METHOD(base::android::ScopedJavaLocalRef<jobject>, GetJavaObject, (),
              (override));
  MOCK_METHOD(ui::Resource*, GetResource, (ui::AndroidResourceType, int),
              (override));
  MOCK_METHOD(ui::Resource*, GetStaticResourceWithTint, (int, SkColor),
              (override));
  MOCK_METHOD(ui::Resource*, GetStaticResourceWithTint, (int, SkColor, bool),
              (override));
  MOCK_METHOD(ui::Resource*, GetAndRetainStaticResourceWithTint, (int, SkColor),
              (override));
  MOCK_METHOD(void, ReleaseStaticResource, (int), (override));
  MOCK_METHOD(void, PreloadResource, (ui::AndroidResourceType, int), (override));
  MOCK_METHOD(void, OnFrameUpdatesFinished, (), (override));
  MOCK_METHOD(base::WeakPtr<ui::ResourceManager>, GetWeakPtr, (), (override));
};

// Real cached DecorationTabTitle objects and real UI bitmap resources exercise
// SetProperties. Only the ordinary resource retrieval boundary is mocked.
class ContainerTabTitleRecyclingTest : public testing::Test {
 protected:
  void SetUp() override {
    env_ = base::android::AttachCurrentThread();
    SkBitmap bitmap;
    bitmap.allocN32Pixels(8, 8);
    bitmap.eraseColor(SK_ColorWHITE);
    bitmap.setImmutable();
    resource_.SetUIResource(
        cc::ScopedUIResource::Create(&ui_resources_, cc::UIResourceBitmap(bitmap)),
        gfx::Size(8, 8));
    nine_patch_.SetUIResource(
        cc::ScopedUIResource::Create(&ui_resources_, cc::UIResourceBitmap(bitmap)),
        gfx::Size(8, 8));
    ON_CALL(resource_manager_, GetResource(testing::_, testing::_))
        .WillByDefault(testing::Return(&resource_));
    cache_ = new LayerTitleCache(
        env_, base::android::ScopedJavaLocalRef<jobject>(),
        /* fade_width= */ 0, /* icon_start_padding= */ 0,
        /* icon_end_padding= */ 0, /* spinner_resource_id= */ 0,
        /* spinner_incognito_resource_id= */ 0,
        /* bubble_inner_dimension= */ 0, /* bubble_outer_dimension= */ 0,
        /* bubble_offset= */ 0, /* bubble_inner_tint= */ 0,
        /* bubble_outer_tint= */ 0, &resource_manager_);
    cache_->UpdateLayer(env_, 1, 1, 1, false, false, false);
    cache_->UpdateLayer(env_, 2, 2, 2, false, false, false);
    handle_ = TabHandleLayer::Create(cache_);
    tab_ = handle_->layer()->children().front();
  }

  void TearDown() override {
    handle_.reset();
    tab_.reset();
    if (cache_) {
      cache_->Destroy(env_);
      cache_ = nullptr;
    }
  }

  void ApplyTab(int id, bool foreground = true, float opacity = 1.f) {
    handle_->SetProperties(
        /* id= */ id,
        /* close_button_resource= */ &resource_,
        /* close_button_background_resource= */ &resource_,
        /* is_close_keyboard_focused= */ false,
        /* close_button_keyboard_focus_ring_resource= */ &resource_,
        /* divider_resource= */ &resource_,
        /* tab_handle_resource= */ &nine_patch_,
        /* tab_handle_outline_resource= */ &nine_patch_,
        /* foreground= */ foreground,
        /* is_pinned= */ false,
        /* shouldShowTabOutline= */ false,
        /* close_pressed= */ false,
        /* should_hide_favicon= */ false,
        /* should_show_alert_indicator= */ false,
        /* alert_indicator_resource= */ nullptr,
        /* alert_indicator_width= */ 0.f,
        /* alert_indicator_spacing= */ 0.f,
        /* alert_indicator_internal_padding= */ 0.f,
        /* title_to_alert_indicator_spacing= */ 0.f,
        /* alert_indicator_opacity= */ 0.f,
        /* toolbar_width= */ 100.f,
        /* x= */ 0.f,
        /* y= */ 0.f,
        /* width= */ 100.f,
        /* height= */ 20.f,
        /* content_offset_y= */ 0.f,
        /* divider_offset_x= */ 0.f,
        /* bottom_margin= */ 0.f,
        /* top_margin= */ 0.f,
        /* content_padding_x= */ 0.f,
        /* close_button_padding= */ 0.f,
        /* close_button_extra_offset= */ 0.f,
        /* close_button_alpha= */ 0.f,
        /* is_start_divider_visible= */ false,
        /* is_end_divider_visible= */ false,
        /* is_loading= */ false,
        /* spinner_rotation= */ 0.f,
        /* opacity= */ opacity,
        /* is_keyboard_focused= */ false,
        /* keyboard_focus_ring_drawable= */ &nine_patch_,
        /* keyboard_focus_ring_offset= */ 0,
        /* stroke_width= */ 0,
        /* folio_foot_length= */ 0.f,
        /* desktop_min_tab_width= */ 0.f);
  }

  cc::UIResourceManager ui_resources_;
  ui::Resource resource_;
  ui::NinePatchResource nine_patch_{gfx::Rect(0, 0, 8, 8),
                                    gfx::Rect(0, 0, 8, 8)};
  testing::NiceMock<MockMarkerResourceManager> resource_manager_;
  raw_ptr<LayerTitleCache> cache_ = nullptr;
  scoped_refptr<TabHandleLayer> handle_;
  scoped_refptr<cc::slim::Layer> tab_;
  raw_ptr<JNIEnv> env_ = nullptr;
};

TEST_F(ContainerTabTitleRecyclingTest, InitialInactiveTabUsesRequestedOpacity) {
  EXPECT_FLOAT_EQ(1.f, tab_->children().front()->opacity());
  ApplyTab(1, false, 0.35f);
  EXPECT_FALSE(handle_->foreground());
  ASSERT_EQ(5u, tab_->children().size());
  EXPECT_FLOAT_EQ(0.35f, tab_->children().front()->opacity());
  EXPECT_EQ(cache_->GetTitleLayer(1)->layer().get(),
            tab_->children().back().get());
  ApplyTab(1, false, 0.75f);
  EXPECT_FALSE(handle_->foreground());
  ASSERT_EQ(5u, tab_->children().size());
  EXPECT_FLOAT_EQ(0.75f, tab_->children().front()->opacity());
}

TEST_F(ContainerTabTitleRecyclingTest, DistinctCachedTitleReplacesOldChild) {
  scoped_refptr<cc::slim::Layer> first = cache_->GetTitleLayer(1)->layer();
  scoped_refptr<cc::slim::Layer> second = cache_->GetTitleLayer(2)->layer();
  ApplyTab(1);
  ASSERT_EQ(5u, tab_->children().size());
  EXPECT_EQ(first.get(), tab_->children().back().get());
  ApplyTab(2);
  ASSERT_EQ(5u, tab_->children().size());
  EXPECT_EQ(second.get(), tab_->children().back().get());
  EXPECT_EQ(nullptr, first->parent());
  EXPECT_EQ(tab_.get(), second->parent());
}

TEST_F(ContainerTabTitleRecyclingTest, RepeatedTitleAndNoTitleKeepCorrectOwnership) {
  scoped_refptr<cc::slim::Layer> first = cache_->GetTitleLayer(1)->layer();
  ApplyTab(1);
  ApplyTab(1);
  ASSERT_EQ(5u, tab_->children().size());
  EXPECT_EQ(first.get(), tab_->children().back().get());
  ApplyTab(-1);
  ASSERT_EQ(4u, tab_->children().size());
  EXPECT_EQ(nullptr, first->parent());
}

TEST_F(ContainerTabTitleRecyclingTest, FreshTitleCanAttachAfterNoTitle) {
  scoped_refptr<cc::slim::Layer> first = cache_->GetTitleLayer(1)->layer();
  scoped_refptr<cc::slim::Layer> second = cache_->GetTitleLayer(2)->layer();
  ApplyTab(1);
  ApplyTab(-1);
  ApplyTab(2);
  ASSERT_EQ(5u, tab_->children().size());
  EXPECT_EQ(second.get(), tab_->children().back().get());
  EXPECT_EQ(nullptr, first->parent());
  EXPECT_EQ(tab_.get(), second->parent());
}

}  // namespace
}  // namespace android
