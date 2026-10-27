// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "brave/browser/containers/used_container_storage_partitions.h"

#include <string>

#include "base/check.h"
#include "build/build_config.h"
#if BUILDFLAG(IS_ANDROID)
#include "brave/browser/containers/android/containers_bridge.h"
#endif
#include "base/containers/to_vector.h"
#include "brave/browser/containers/containers_service_factory.h"
#include "brave/components/containers/content/browser/storage_partition_utils.h"
#include "brave/components/containers/core/browser/containers_service.h"
#include "brave/components/containers/core/common/features.h"
#include "chrome/browser/profiles/profile.h"
#include "content/public/browser/storage_partition_config.h"

namespace containers {

std::vector<content::StoragePartitionConfig>
GetUsedContainerStoragePartitionConfigs(Profile* profile) {
  CHECK(base::FeatureList::IsEnabled(features::kContainers));

  if (!profile) {
    return {};
  }

  ContainersService* service = ContainersServiceFactory::GetForProfile(profile);
  if (!service) {
    return {};
  }

#if BUILDFLAG(IS_ANDROID)
  std::vector<content::StoragePartitionConfig> configs;
  for (const auto& id : service->GetRuntimeContainerIds()) {
    if (auto config = android::ResolveContainerStoragePartition(profile, id)) {
      configs.push_back(*config);
    }
  }
  return configs;
#else
  return base::ToVector(service->GetUsedContainerIds(),
                        [&](const std::string& id) {
                          return content::StoragePartitionConfig::Create(
                              profile, kContainersStoragePartitionDomain, id,
                              profile->IsOffTheRecord());
                        });
#endif
}

}  // namespace containers
