// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "brave/browser/extensions/android/selection/selection_context_menu_params.h"

#include "content/public/browser/context_menu_params.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/render_process_host.h"

namespace extensions {

content::ContextMenuParams BuildSelectionContextMenuParams(
    content::RenderFrameHost& frame,
    const std::u16string& selected_text,
    bool editable) {
  content::ContextMenuParams params;
  params.page_url = frame.GetOutermostMainFrame()->GetLastCommittedURL();
  params.frame_url = frame.GetLastCommittedURL();
  params.frame_origin = frame.GetLastCommittedOrigin();
  params.is_subframe = !!frame.GetParentOrOuterDocument();
  // Browser-derived URLs still use the requesting renderer's policy, matching
  // RenderFrameHostImpl::ShowContextMenu (including crbug.com/1285312).
  frame.GetProcess()->FilterURL(false, &params.page_url);
  frame.GetProcess()->FilterURL(true, &params.frame_url);
  params.selection_text = selected_text;
  params.is_editable = editable;
  return params;
}

}  // namespace extensions
