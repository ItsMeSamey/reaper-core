// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#ifndef BRAVE_BROWSER_CONTAINERS_ANDROID_CONTAINERS_BRIDGE_H_
#define BRAVE_BROWSER_CONTAINERS_ANDROID_CONTAINERS_BRIDGE_H_

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "base/containers/flat_set.h"
#include "base/functional/callback.h"
#include "content/public/browser/storage_partition_config.h"

class Profile;
namespace content {
class WebContents;
}

namespace containers::android {

// Empty is Default. Other IDs are canonical lowercase UUIDs or t-UUIDs
// using the existing local-only temporary-container format.
bool IsContainerIdWellFormed(std::string_view id);

// UI thread only. Empty returns an engaged default config. Nullopt means
// invalid and callers must abort rather than use the default partition.
std::optional<content::StoragePartitionConfig> ResolveContainerStoragePartition(
    Profile* profile,
    std::string_view id);

// Actual isolation proof for adoption/navigation. Userdata is not sufficient.
bool WebContentsMatchesContainer(Profile* profile,
                                 content::WebContents* contents,
                                 std::string_view id);

// Only for trusted persisted or decoded Sync identities. Preserve an unknown
// valid restored identity through the existing used-container snapshot.
bool RestoreContainerIdentity(Profile* profile, std::string_view id);

// The persistence owner invalidates before rescanning and publishes only a
// complete validated inventory, including unopened/frozen/archived windows.
// Startup is not ready; incomplete inventories cannot authorize data removal.
void InvalidatePersistentContainerReferences(Profile* profile);
void RefreshPersistentContainerReferences(Profile* profile);
int64_t GetPersistentContainerReferencesGeneration(Profile* profile);
bool SetPersistentContainerReferencesIfGenerationMatches(
    Profile* profile,
    const std::vector<std::string>& ids,
    int64_t expected_generation);
// Low-level C++ publication for native unit fixtures; production Java callers
// must provide the generation captured after invalidating their scan.
void SetPersistentContainerReferences(Profile* profile,
                                      const std::vector<std::string>& ids);
void GetPersistentContainerReferences(
    Profile* profile,
    base::OnceCallback<void(const base::flat_set<std::string>&)> callback);

}  // namespace containers::android

#endif  // BRAVE_BROWSER_CONTAINERS_ANDROID_CONTAINERS_BRIDGE_H_
