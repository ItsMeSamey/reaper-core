/**
 * Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.chromium.chrome.browser.omnibox.suggestions;

import androidx.annotation.NonNull;

import org.chromium.base.supplier.MonotonicObservableSupplier;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.browser_controls.BrowserControlsStateProvider.ControlsPosition;
import org.chromium.chrome.browser.omnibox.styles.OmniboxResourceProvider;
import org.chromium.chrome.browser.omnibox.suggestions.basic.BasicSuggestionProcessor.BookmarkState;
import org.chromium.chrome.browser.preferences.BravePref;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.components.omnibox.AutocompleteInput;
import org.chromium.components.omnibox.AutocompleteResult;
import org.chromium.components.omnibox.suggestions.OmniboxSuggestionUiType;
import org.chromium.components.user_prefs.UserPrefs;

import java.util.List;
import java.util.function.Supplier;

class BraveDropdownItemViewInfoListBuilder extends DropdownItemViewInfoListBuilder {
    private final @NonNull Supplier<Tab> mActivityTabSupplier;
    private @Nullable Profile mProfile;

    BraveDropdownItemViewInfoListBuilder(
            Supplier<@Nullable Tab> tabSupplier,
            BookmarkState bookmarkState,
            MonotonicObservableSupplier<@ControlsPosition Integer> toolbarPositionSupplier,
            OmniboxResourceProvider resourceProvider) {
        super(tabSupplier, bookmarkState, toolbarPositionSupplier, resourceProvider);

        mActivityTabSupplier = tabSupplier;
    }

    @Override
    void setProfile(Profile profile) {
        super.setProfile(profile);
        mProfile = profile;
    }

    @Override
    @NonNull
    List<DropdownItemViewInfo> buildDropdownViewInfoList(
            AutocompleteInput input, AutocompleteResult autocompleteResult) {
        List<DropdownItemViewInfo> viewInfoList =
                super.buildDropdownViewInfoList(input, autocompleteResult);

        Tab tab = mActivityTabSupplier.get();
        boolean autocompleteEnabled;
        if (tab != null) {
            autocompleteEnabled =
                    UserPrefs.get(tab.getProfile()).getBoolean(BravePref.AUTOCOMPLETE_ENABLED);
        } else {
            // The search widget launches SearchActivity without a tab, so use the profile
            // pushed in via setProfile to honor the "Show browser suggestions" preference.
            // If the profile hasn't been pushed in yet (e.g. cold start from the widget after
            // a force-stop), suppress suggestions until we can read the pref.
            autocompleteEnabled =
                    mProfile != null
                            && UserPrefs.get(mProfile).getBoolean(BravePref.AUTOCOMPLETE_ENABLED);
        }
        if (!autocompleteEnabled && viewInfoList.size() > 0) {
            DropdownItemViewInfo firstObj = viewInfoList.get(0);
            viewInfoList.clear();
            if (firstObj.processor != null
                    && (firstObj.processor.getViewTypeId()
                                    == OmniboxSuggestionUiType.EDIT_URL_SUGGESTION
                            || firstObj.processor.getViewTypeId()
                                    == OmniboxSuggestionUiType.CLIPBOARD_SUGGESTION)) {
                viewInfoList.add(firstObj);
            }
        }

        return viewInfoList;
    }

}
