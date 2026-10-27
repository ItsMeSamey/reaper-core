// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "brave/components/containers/core/browser/default_containers_list.h"

#include "build/build_config.h"
#include "third_party/skia/include/core/SkColor.h"
#if !BUILDFLAG(IS_ANDROID)
#include "brave/ui/color/nala/nala_color_id.h"
#endif
#include "components/grit/brave_components_strings.h"
#include "ui/base/l10n/l10n_util.h"
#include "ui/color/color_provider.h"
#include "ui/color/color_provider_manager.h"

namespace containers {

std::vector<mojom::ContainerPtr> CreateDefaultContainersList() {
#if BUILDFLAG(IS_ANDROID)
  constexpr auto blue = SkColorSetRGB(0x1a, 0x73, 0xe8);
  constexpr auto red = SkColorSetRGB(0xd9, 0x30, 0x25);
  constexpr auto purple = SkColorSetRGB(0x93, 0x34, 0xe6);
  constexpr auto green = SkColorSetRGB(0x18, 0x80, 0x38);
#else
  const auto* color_provider =
      ui::ColorProviderManager::Get().GetColorProviderFor(
          ui::ColorProviderKey());
  CHECK(color_provider);
  const auto blue = color_provider->GetColor(nala::kColorPrimitiveBlue60);
  const auto red = color_provider->GetColor(nala::kColorPrimitiveRed60);
  const auto purple = color_provider->GetColor(nala::kColorPrimitivePurple60);
  const auto green = color_provider->GetColor(nala::kColorPrimitiveGreen60);
#endif

  std::vector<mojom::ContainerPtr> default_containers;

  default_containers.push_back(mojom::Container::New(
      kDefaultContainerIds[0],
      l10n_util::GetStringUTF8(IDS_CONTAINERS_DEFAULT_PERSONAL_NAME),
      mojom::Icon::kPersonal, blue));
  default_containers.push_back(mojom::Container::New(
      kDefaultContainerIds[1],
      l10n_util::GetStringUTF8(IDS_CONTAINERS_DEFAULT_WORK_NAME),
      mojom::Icon::kWork, red));
  default_containers.push_back(mojom::Container::New(
      kDefaultContainerIds[2],
      l10n_util::GetStringUTF8(IDS_CONTAINERS_DEFAULT_SOCIAL_NAME),
      mojom::Icon::kSocial, purple));
  default_containers.push_back(mojom::Container::New(
      kDefaultContainerIds[3],
      l10n_util::GetStringUTF8(IDS_CONTAINERS_DEFAULT_SCHOOL_NAME),
      mojom::Icon::kSchool, green));

  DCHECK_EQ(default_containers.size(), kDefaultContainerIds.size());

  return default_containers;
}

}  // namespace containers
