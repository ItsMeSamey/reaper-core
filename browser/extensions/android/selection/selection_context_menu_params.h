// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#ifndef BRAVE_BROWSER_EXTENSIONS_ANDROID_SELECTION_SELECTION_CONTEXT_MENU_PARAMS_H_
#define BRAVE_BROWSER_EXTENSIONS_ANDROID_SELECTION_SELECTION_CONTEXT_MENU_PARAMS_H_

#include <string>

namespace content {
struct ContextMenuParams;
class RenderFrameHost;
}  // namespace content

namespace extensions {

// Matches the browser-populated URL fields of RenderFrameHostImpl's context
// menu path. Callers retain responsibility for document and selection lifetime.
content::ContextMenuParams BuildSelectionContextMenuParams(
    content::RenderFrameHost& frame,
    const std::u16string& selected_text,
    bool editable);

}  // namespace extensions

#endif  // BRAVE_BROWSER_EXTENSIONS_ANDROID_SELECTION_SELECTION_CONTEXT_MENU_PARAMS_H_
