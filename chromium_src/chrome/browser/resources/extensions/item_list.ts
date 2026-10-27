// Copyright (c) 2025 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import { ExtensionsItemListElement } from './item_list-chromium.js'

(ExtensionsItemListElement.prototype as unknown as { shouldShowEmptyItemsMessage_: () => boolean }).shouldShowEmptyItemsMessage_ = () => true

// <if expr="is_android">
// Store discovery stays visible independently of whether a search matched.
type ItemListSearchState = {
  apps: unknown[]
  extensions: unknown[]
  shownAppsCount_: number
  shownExtensionsCount_: number
  shouldShowEmptySearchMessage_: () => boolean
}

;(ExtensionsItemListElement.prototype as unknown as ItemListSearchState)
  .shouldShowEmptySearchMessage_ = function (this: ItemListSearchState) {
    return (this.apps.length > 0 || this.extensions.length > 0) &&
      this.shownAppsCount_ === 0 && this.shownExtensionsCount_ === 0
  }
// </if>

export * from './item_list-chromium.js'
