// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "brave/browser/extensions/android/selection/selection_context_menu_params.h"

#include <string>

#include "base/test/scoped_command_line.h"
#include "chrome/test/base/chrome_render_view_host_test_harness.h"
#include "content/public/browser/child_process_security_policy.h"
#include "content/public/browser/context_menu_params.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/render_process_host.h"
#include "content/public/common/url_constants.h"
#include "content/public/test/navigation_simulator.h"
#include "content/public/test/test_renderer_host.h"
#include "content/public/test/test_utils.h"
#include "testing/gtest/include/gtest/gtest.h"
#include "url/gurl.h"
#include "url/origin.h"

namespace extensions {
namespace {

// NavigationSimulator drives the real browser-side frame/navigation state.
// The harness's MockRenderProcessHost registers with the real child-process
// security policy and delegates FilterURL to RenderProcessHostImpl. These tests
// do not load a local file, run a renderer, or exercise Android selection UI.
class SelectionContextMenuParamsTest : public ChromeRenderViewHostTestHarness {
 protected:
  void SetUp() override {
    content::IsolateAllSitesForTesting(command_line_.GetProcessCommandLine());
    ChromeRenderViewHostTestHarness::SetUp();
  }

 private:
  // Restore the global command line after the base harness tears down its
  // WebContents and processes. No permission grants or policy overrides.
  base::test::ScopedCommandLine command_line_;
};

TEST_F(SelectionContextMenuParamsTest, MainFrameRetainsUrlsAndSelection) {
  const GURL page_url("https://main.example.test/page");
  const std::u16string selected_text = u"selected main-frame text";
  auto* frame = content::NavigationSimulator::NavigateAndCommitFromBrowser(
      web_contents(), page_url);
  ASSERT_TRUE(frame);
  ASSERT_EQ(page_url, frame->GetLastCommittedURL());
  ASSERT_TRUE(frame->IsActive());
  ASSERT_TRUE(frame->IsRenderFrameLive());
  ASSERT_EQ(nullptr, frame->GetParentOrOuterDocument());

  const auto params =
      BuildSelectionContextMenuParams(*frame, selected_text, false);

  EXPECT_EQ(page_url, params.page_url);
  // Chromium supplies frame_url even when the selected frame is the main one.
  EXPECT_EQ(page_url, params.frame_url);
  EXPECT_EQ(url::Origin::Create(page_url), params.frame_origin);
  EXPECT_FALSE(params.is_subframe);
  EXPECT_EQ(selected_text, params.selection_text);
  EXPECT_FALSE(params.is_editable);
}

TEST_F(SelectionContextMenuParamsTest, RemoteSubframeRetainsPageAndFrameUrls) {
  const GURL page_url("https://parent.example.test/page");
  const GURL frame_url("https://remote.example.org/frame");
  const std::u16string selected_text = u"selected editable child text";
  auto* parent = content::NavigationSimulator::NavigateAndCommitFromBrowser(
      web_contents(), page_url);
  ASSERT_TRUE(parent);
  auto* child =
      content::RenderFrameHostTester::For(parent)->AppendChild("child");
  ASSERT_TRUE(child);
  // Cross-process navigation replaces the frame host; keep the returned host.
  child = content::NavigationSimulator::NavigateAndCommitFromDocument(frame_url,
                                                                      child);
  ASSERT_TRUE(child);
  ASSERT_EQ(frame_url, child->GetLastCommittedURL());
  ASSERT_TRUE(child->IsActive());
  ASSERT_TRUE(child->IsRenderFrameLive());
  ASSERT_NE(parent->GetProcess(), child->GetProcess());
  ASSERT_EQ(parent, child->GetParentOrOuterDocument());
  ASSERT_EQ(parent, child->GetOutermostMainFrame());
  auto* policy = content::ChildProcessSecurityPolicy::GetInstance();
  ASSERT_TRUE(
      policy->CanRequestURL(child->GetProcess()->GetDeprecatedID(), page_url));
  ASSERT_TRUE(
      policy->CanRequestURL(child->GetProcess()->GetDeprecatedID(), frame_url));

  const auto params =
      BuildSelectionContextMenuParams(*child, selected_text, true);

  EXPECT_EQ(page_url, params.page_url);
  EXPECT_EQ(frame_url, params.frame_url);
  EXPECT_EQ(url::Origin::Create(frame_url), params.frame_origin);
  EXPECT_TRUE(params.is_subframe);
  EXPECT_EQ(selected_text, params.selection_text);
  EXPECT_TRUE(params.is_editable);
}

TEST_F(SelectionContextMenuParamsTest,
       RemoteChildFiltersUnrequestableFileParent) {
  // This is a simulated browser-initiated file navigation, not an artificial
  // LastCommittedURL setter. Production navigation grants the parent process
  // file access; the isolated remote child must not inherit that access.
  const GURL file_url("file:///reaper-selection-parent.html");
  const GURL frame_url("https://remote.example.org/frame");
  const std::u16string selected_text = u"selected remote text";
  auto* parent = content::NavigationSimulator::NavigateAndCommitFromBrowser(
      web_contents(), file_url);
  ASSERT_TRUE(parent);
  ASSERT_EQ(file_url, parent->GetLastCommittedURL());
  ASSERT_TRUE(parent->IsActive());
  ASSERT_TRUE(parent->IsRenderFrameLive());
  auto* child =
      content::RenderFrameHostTester::For(parent)->AppendChild("child");
  ASSERT_TRUE(child);
  child = content::NavigationSimulator::NavigateAndCommitFromDocument(frame_url,
                                                                      child);
  ASSERT_TRUE(child);
  ASSERT_EQ(frame_url, child->GetLastCommittedURL());
  ASSERT_TRUE(child->IsActive());
  ASSERT_TRUE(child->IsRenderFrameLive());
  ASSERT_NE(parent->GetProcess(), child->GetProcess());
  ASSERT_EQ(parent, child->GetParentOrOuterDocument());
  ASSERT_EQ(parent, child->GetOutermostMainFrame());

  auto* policy = content::ChildProcessSecurityPolicy::GetInstance();
  const int parent_process_id = parent->GetProcess()->GetDeprecatedID();
  const int child_process_id = child->GetProcess()->GetDeprecatedID();
  ASSERT_NE(parent_process_id, child_process_id);
  ASSERT_TRUE(policy->CanRequestURL(parent_process_id, file_url));
  ASSERT_FALSE(policy->CanRequestURL(child_process_id, file_url));
  ASSERT_TRUE(policy->CanRequestURL(child_process_id, frame_url));

  const auto params =
      BuildSelectionContextMenuParams(*child, selected_text, false);

  EXPECT_EQ(GURL(content::kBlockedURL), params.page_url);
  EXPECT_EQ(frame_url, params.frame_url);
  EXPECT_EQ(url::Origin::Create(frame_url), params.frame_origin);
  EXPECT_TRUE(params.is_subframe);
  EXPECT_EQ(selected_text, params.selection_text);
  EXPECT_FALSE(params.is_editable);
  // Building a menu must neither mutate the document URL nor grant access.
  EXPECT_EQ(file_url, parent->GetLastCommittedURL());
  EXPECT_FALSE(policy->CanRequestURL(child_process_id, file_url));
}

}  // namespace
}  // namespace extensions
