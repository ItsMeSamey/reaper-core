// Copyright 2026 The Reaper Authors
// SPDX-License-Identifier: MPL-2.0
#ifndef BRAVE_BROWSER_CONTAINERS_ANDROID_CONTAINERS_TAB_LIFECYCLE_H_
#define BRAVE_BROWSER_CONTAINERS_ANDROID_CONTAINERS_TAB_LIFECYCLE_H_

#include <optional>
#include <string>
#include <string_view>

#include "base/containers/span.h"
#include "content/public/browser/web_contents.h"

class Profile;
namespace sessions {
class SerializedNavigationEntry;
}

namespace containers::android {

// Empty returned identity is Default; nullopt rejects invalid/conflicting state.
// Configure fresh contents with a fixed partition before any navigation.
std::optional<std::string> ConfigureContainerRestore(
    Profile* profile,
    base::span<const sessions::SerializedNavigationEntry> navigations,
    int selected_navigation,
    content::WebContents::CreateParams* params,
    std::optional<std::string_view> explicit_container_id = std::nullopt);

}  // namespace containers::android
#endif  // BRAVE_BROWSER_CONTAINERS_ANDROID_CONTAINERS_TAB_LIFECYCLE_H_
