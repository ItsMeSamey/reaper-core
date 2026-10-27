/* Copyright (c) 2023 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.shields;

import static org.chromium.build.NullUtil.assumeNonNull;

import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.chromium.base.supplier.MonotonicObservableSupplier;
import org.chromium.base.supplier.ObservableSuppliers;
import org.chromium.base.supplier.SettableMonotonicObservableSupplier;
import org.chromium.brave_shields.mojom.FilterListAndroidHandler;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.customtabs.CustomTabActivity;
import org.chromium.chrome.browser.settings.BottomInsetViewProvider;
import org.chromium.chrome.browser.settings.BravePreferenceFragment;
import org.chromium.chrome.browser.theme.BraveDynamicColors;
import org.chromium.ui.text.ChromeClickableSpan;
import org.chromium.ui.widget.Toast;

@NullMarked
public class CreateCustomFiltersFragment extends BravePreferenceFragment
        implements BottomInsetViewProvider {
    public static final String BRAVE_ADBLOCK_FILTER_SYNTAX_PAGE =
            "https://support.brave.app/hc/en-us/articles/6449369961741";

    private static final String CUSTOM_FILTERS_DRAFT = "custom_filters_draft";

    private @Nullable FilterListAndroidHandler mFilterListAndroidHandler;
    private @Nullable EditText mEtCustomFilters;
    private @Nullable Button mSaveButton;
    // Null means the initial model has not arrived and the user has not edited.
    private @Nullable String mCustomFiltersDraft;
    private @Nullable TextWatcher mCustomFiltersTextWatcher;
    private final SettableMonotonicObservableSupplier<String> mPageTitle =
            ObservableSuppliers.createMonotonic();

    @Override
    public View onCreateView(
            LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_create_custom_filters, container, false);
    }

    @Override
    public View getBottomInsetView(View fragmentView) {
        return fragmentView;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        mPageTitle.set(getString(R.string.create_custom_filters_title));
        super.onActivityCreated(savedInstanceState);

        setData();
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        EditText editor = mEtCustomFilters;
        if (editor == null) return;

        if (mCustomFiltersDraft == null && savedInstanceState != null) {
            mCustomFiltersDraft = savedInstanceState.getString(CUSTOM_FILTERS_DRAFT);
        }
        if (mCustomFiltersDraft != null
                && !mCustomFiltersDraft.contentEquals(editor.getText())) {
            editor.setText(mCustomFiltersDraft);
        }
        updateSaveButtonState();
        // Observe after hierarchy restoration so an untouched loading placeholder
        // is not mistaken for an intentionally empty draft.
        mCustomFiltersTextWatcher =
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence text, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable text) {
                        if (mEtCustomFilters == editor) {
                            mCustomFiltersDraft = text.toString();
                            updateSaveButtonState();
                        }
                    }
                };
        editor.addTextChangedListener(mCustomFiltersTextWatcher);
        if (mCustomFiltersDraft == null) getCustomFilters();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mCustomFiltersDraft != null) {
            outState.putString(CUSTOM_FILTERS_DRAFT, mCustomFiltersDraft);
        }
    }

    @Override
    public MonotonicObservableSupplier<String> getPageTitle() {
        return mPageTitle;
    }

    private void setData() {
        initFilterListAndroidHandler();
        mEtCustomFilters = assumeNonNull(getView()).findViewById(R.id.enter_custom_filters);
        TextView tvSummary = getView().findViewById(R.id.summary);
        String summaryText =
                String.format(
                        getResources().getString(R.string.create_custom_filter_summary),
                        getResources().getString(R.string.adblock_filter_syntax));
        Spanned summaryTextSpanned = Html.fromHtml(summaryText, Html.FROM_HTML_MODE_LEGACY);
        SpannableString summaryTextSpannableString =
                new SpannableString(summaryTextSpanned.toString());

        if (getActivity() != null) {
            ChromeClickableSpan summaryTextClickableSpan =
                    new ChromeClickableSpan(
                            BraveDynamicColors.getTextButtonColor(
                                    requireContext().getTheme(),
                                    requireContext().getColor(R.color.brave_link)),
                            (textView) -> {
                                CustomTabActivity.showInfoPage(
                                        getActivity(), BRAVE_ADBLOCK_FILTER_SYNTAX_PAGE);
                            });

            String linkText = getString(R.string.adblock_filter_syntax);
            int linkStart = summaryTextSpannableString.toString().indexOf(linkText);
            if (linkStart >= 0) {
                summaryTextSpannableString.setSpan(
                        summaryTextClickableSpan,
                        linkStart,
                        linkStart + linkText.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            tvSummary.setMovementMethod(LinkMovementMethod.getInstance());
            tvSummary.setText(summaryTextSpannableString);
        }

        Button saveBtn = getView().findViewById(R.id.btn_save);
        mSaveButton = saveBtn;
        updateSaveButtonState();
        BraveDynamicColors.applyToFilledButtonIfEnabled(saveBtn);
        saveBtn.setOnClickListener(
                view -> {
                    updateCustomFilters();
                });
    }

    private void updateSaveButtonState() {
        Button saveButton = mSaveButton;
        if (saveButton != null) {
            saveButton.setEnabled(mCustomFiltersDraft != null);
        }
    }

    private void initFilterListAndroidHandler() {
        if (mFilterListAndroidHandler != null) {
            return;
        }

        mFilterListAndroidHandler =
                FilterListServiceFactory.getInstance()
                        .getFilterListAndroidHandler(getProfile(), null);
    }

    private void getCustomFilters() {
        EditText editor = mEtCustomFilters;
        if (mFilterListAndroidHandler != null && editor != null) {
            mFilterListAndroidHandler.getCustomFilters(
                    customFilters -> {
                        if (mEtCustomFilters != editor || mCustomFiltersDraft != null) return;
                        mCustomFiltersDraft = customFilters;
                        editor.setText(customFilters);
                        updateSaveButtonState();
                    });
        }
    }

    private void updateCustomFilters() {
        EditText editor = mEtCustomFilters;
        if (mFilterListAndroidHandler != null && editor != null && mCustomFiltersDraft != null) {
            mFilterListAndroidHandler.updateCustomFilters(
                    editor.getText().toString(),
                    isUpdated -> {
                        if (mEtCustomFilters != editor) return;
                        int messageId =
                                isUpdated
                                        ? R.string.saved_changes_success
                                        : R.string.brave_rewards_local_general_grant_error_title;
                        if (getActivity() != null) {
                            Toast.makeText(getActivity(), messageId, Toast.LENGTH_SHORT).show();
                        }
                    });
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        MenuItem closeItem = menu.findItem(R.id.close_menu_id);
        if (closeItem != null) {
            closeItem.setVisible(false);
        }
    }

    @Override
    public void onDestroyView() {
        // Retire this editor before proxy closure can deliver any outstanding reply.
        if (mEtCustomFilters != null && mCustomFiltersTextWatcher != null) {
            mEtCustomFilters.removeTextChangedListener(mCustomFiltersTextWatcher);
        }
        mCustomFiltersTextWatcher = null;
        mEtCustomFilters = null;
        mSaveButton = null;
        if (mFilterListAndroidHandler != null) {
            mFilterListAndroidHandler.close();
            mFilterListAndroidHandler = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        if (mFilterListAndroidHandler != null) {
            mFilterListAndroidHandler.close();
            mFilterListAndroidHandler = null;
        }
        super.onDestroy();
    }
}
