// Copyright 2026 The Reaper Authors
// SPDX-License-Identifier: MPL-2.0
#include "brave/browser/containers/android/containers_tab_lifecycle.h"

#include "brave/browser/containers/android/containers_bridge.h"
#include "brave/components/containers/content/browser/storage_partition_utils.h"
#include "brave/components/containers/content/browser/tab_restore_utils.h"
#include "chrome/browser/profiles/profile.h"
#include "components/sessions/core/serialized_navigation_entry.h"
#include "content/public/browser/site_instance.h"
#include "content/public/browser/storage_partition_config.h"

namespace containers::android {

std::optional<std::string> ConfigureContainerRestore(
    Profile* profile,
    base::span<const sessions::SerializedNavigationEntry> navigations,
    int selected_navigation,
    content::WebContents::CreateParams* params,
    std::optional<std::string_view> explicit_container_id) {
  if (!profile || !params || selected_navigation < 0 ||
      static_cast<size_t>(selected_navigation) >= navigations.size()) {
    return std::nullopt;
  }
  std::string container_id;
  const auto& key = navigations[selected_navigation].storage_partition_key();
  if (key && key->first == kContainersStoragePartitionDomain) {
    container_id = key->second;
    if (container_id.empty() ||
        (explicit_container_id && *explicit_container_id != container_id)) {
      return std::nullopt;
    }
  }
  if (key && key->first != kContainersStoragePartitionDomain) {
    return std::nullopt;
  }
  if (explicit_container_id) {
    container_id = *explicit_container_id;
  }
  for (const auto& navigation : navigations) {
    const auto& saved_key = navigation.storage_partition_key();
    if (saved_key && (saved_key->first != kContainersStoragePartitionDomain ||
                      saved_key->second != container_id)) {
      return std::nullopt;
    }
  }
  if (!RestoreContainerIdentity(profile, container_id)) {
    return std::nullopt;
  }
  auto partition = ResolveContainerStoragePartition(profile, container_id);
  if (!partition) {
    return std::nullopt;
  }
  if (!container_id.empty()) {
    // The helper also validates that serialized keys use the existing domain.
    auto saved_partition = GetStoragePartitionConfigToRestore(
        profile, navigations, selected_navigation);
    if (key && key->first == kContainersStoragePartitionDomain &&
        !saved_partition) {
      return std::nullopt;
    }
    // Android NativePage/NTP and WebUI transitions must retain actual storage,
    // not just a label that is later mistaken for storage isolation.
    params->site_instance =
        content::SiteInstance::CreateForFixedStoragePartition(
            profile, GURL(), *partition);
  }
  return container_id;
}

}  // namespace containers::android
