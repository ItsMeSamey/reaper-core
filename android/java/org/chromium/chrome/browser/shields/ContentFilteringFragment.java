/* Copyright (c) 2023 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.shields;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.chromium.base.supplier.MonotonicObservableSupplier;
import org.chromium.base.supplier.ObservableSuppliers;
import org.chromium.base.supplier.SettableMonotonicObservableSupplier;
import org.chromium.brave_shields.mojom.FilterListAndroidHandler;
import org.chromium.brave_shields.mojom.SubscriptionInfo;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.settings.BottomInsetViewProvider;
import org.chromium.chrome.browser.settings.BravePreferenceFragment;
import org.chromium.chrome.browser.settings.BraveSettingsIntentUtil;
import org.chromium.chrome.browser.settings.SettingsIntentUtil;
import org.chromium.components.browser_ui.settings.FragmentSettingsNavigation;
import org.chromium.components.browser_ui.settings.SettingsNavigation;
import org.chromium.mojo_base.mojom.Value;
import org.chromium.ui.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;

public class ContentFilteringFragment extends BravePreferenceFragment
        implements FragmentSettingsNavigation,
                BraveContentFilteringListener,
                BottomInsetViewProvider {
    private RecyclerView mRecyclerView;

    private ContentFilteringAdapter mAdapter;
    private FilterListAndroidHandler mFilterListAndroidHandler;
    private ArrayList<SubscriptionInfo> mSubscriptionFilterLists = new ArrayList<>();
    private Value mFilterLists[];
    private MenuItem mEditItem;
    private MenuItem mDoneItem;
    private MenuItem mUpdateFilterListItem;
    private boolean mIsMenuLoaded;
    private boolean mIsGetSubscriptionsLoaded;

    // SettingsNavigation injected from main Settings Activity.
    private SettingsNavigation mSettingsLauncher;
    private ActivityResultLauncher<Intent> mAddCustomFilterResultLauncher;
    private final SettableMonotonicObservableSupplier<String> mPageTitle =
            ObservableSuppliers.createMonotonic();

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_content_filtering, container, false);
    }

    @Override
    public View getBottomInsetView(View fragmentView) {
        return fragmentView;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        mPageTitle.set(getString(R.string.content_filters_title));
        super.onActivityCreated(savedInstanceState);
        setData();
    }

    @Override
    public MonotonicObservableSupplier<String> getPageTitle() {
        return mPageTitle;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        mAddCustomFilterResultLauncher =
                registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        result -> {
                            if (result.getResultCode() == Activity.RESULT_OK) {
                                getSubscriptionFilters();
                            }
                        });
    }

    private void setData() {
        initFilterListAndroidHandler();
        mRecyclerView = (RecyclerView) getView().findViewById(R.id.recyclerview);
        LinearLayoutManager linearLayoutManager =
                new LinearLayoutManager(getActivity(), LinearLayoutManager.VERTICAL, false);
        mRecyclerView.setLayoutManager(linearLayoutManager);
        mAdapter = new ContentFilteringAdapter(getActivity(), this);
        mRecyclerView.setAdapter(mAdapter);
        getSubscriptionFilters();
        getFilterLists();
    }

    private void getSubscriptionFilters() {
        ContentFilteringAdapter adapter = mAdapter;
        if (mFilterListAndroidHandler != null && adapter != null) {
            mFilterListAndroidHandler.getSubscriptions(
                    subscriptions -> {
                        if (mAdapter != adapter) return;
                        mSubscriptionFilterLists = new ArrayList<>(Arrays.asList(subscriptions));
                        adapter.setSubscriptionFilterLists(mSubscriptionFilterLists);
                        mIsGetSubscriptionsLoaded = true;
                        if (mIsMenuLoaded) {
                            checkForEmptySubscriptionFilters(true);
                        }
                    });
        }
    }

    private void getFilterLists() {
        ContentFilteringAdapter adapter = mAdapter;
        if (mFilterListAndroidHandler != null && adapter != null) {
            mFilterListAndroidHandler.getFilterLists(
                    filterLists -> {
                        if (mAdapter != adapter) return;
                        mFilterLists = filterLists.storage;
                        adapter.setFilterLists(mFilterLists);
                    });
        }
    }

    @Override
    public void onSubscriptionFilterToggle(int position, boolean isEnable) {
        if (mFilterListAndroidHandler != null) {
            SubscriptionInfo customFilter = mSubscriptionFilterLists.get(position);
            mFilterListAndroidHandler.enableSubscription(customFilter.subscriptionUrl, isEnable);
            customFilter.enabled = isEnable;
        }
    }

    /** Keep the add-list result in its own activity when settings are embedded in a tab. */
    static Intent createAddCustomFilterIntent(Activity activity) {
        Intent intent =
                BraveSettingsIntentUtil.createIntent(
                        activity,
                        AddCustomFilterListsFragment.class.getName(),
                        /* fragmentArgs= */ null,
                        /* addToBackStack= */ false,
                        /* tag= */ null,
                        /* useSettingsInTab= */ false);
        // AddCustomFilterListsFragment returns a result and finishes its activity. It must not
        // reuse the browser or the existing single-activity settings host.
        intent.removeFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(SettingsIntentUtil.EXTRA_SHOW_FRAGMENT_STANDALONE, true);
        return intent;
    }

    @Override
    public void onAddSubscriptionFilter() {
        if (mSubscriptionFilterLists.size() > 0) {
            isEditSelected(false);
        }
        mAddCustomFilterResultLauncher.launch(createAddCustomFilterIntent(requireActivity()));
    }

    @Override
    public void onSubscriptionFilterDelete(int position) {
        if (mFilterListAndroidHandler != null) {
            SubscriptionInfo customFilter = mSubscriptionFilterLists.get(position);
            mFilterListAndroidHandler.deleteSubscription(customFilter.subscriptionUrl);
            mSubscriptionFilterLists.remove(position);
            mAdapter.setSubscriptionFilterLists(mSubscriptionFilterLists);
            checkForEmptySubscriptionFilters(false);
        }
    }

    @Override
    public void onCustomFilters() {
        if (mSubscriptionFilterLists.size() > 0) {
            isEditSelected(false);
        }
        Intent intent =
                mSettingsLauncher.createSettingsIntent(
                        getActivity(),
                        CreateCustomFiltersFragment.class,
                        /* fragmentArgs= */ null,
                        /* addToBackStack= */ true);
        getActivity().startActivity(intent);
    }

    @Override
    public void onFilterToggle(String uuid, boolean isEnable) {
        if (mFilterListAndroidHandler != null) {
            mFilterListAndroidHandler.enableFilter(uuid, isEnable);
        }
    }

    private void checkForEmptySubscriptionFilters(boolean shouldEditVisible) {
        if (mSubscriptionFilterLists.size() == 0) {
            isEditSelected(false);
            mEditItem.setVisible(false);
        } else if (shouldEditVisible) {
            mEditItem.setVisible(true);
        }
    }

    @Override
    public void setSettingsNavigation(SettingsNavigation settingsLauncher) {
        mSettingsLauncher = settingsLauncher;
    }

    private void initFilterListAndroidHandler() {
        if (mFilterListAndroidHandler != null) {
            return;
        }

        mFilterListAndroidHandler =
                FilterListServiceFactory.getInstance()
                        .getFilterListAndroidHandler(getProfile(), null);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        MenuItem closeItem = menu.findItem(R.id.close_menu_id);
        if (closeItem != null) {
            closeItem.setVisible(false);
        }
        inflater.inflate(R.menu.menu_custom_filter_list, menu);
        mEditItem = menu.findItem(R.id.menu_id_edit);
        mDoneItem = menu.findItem(R.id.menu_id_done);
        mUpdateFilterListItem = menu.findItem(R.id.update_filter_list_id);
        mIsMenuLoaded = true;
        if (mIsGetSubscriptionsLoaded) {
            checkForEmptySubscriptionFilters(true);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_id_edit) {
            isEditSelected(true);
            return true;
        } else if (id == R.id.menu_id_done) {
            isEditSelected(false);
            return true;
        } else if (item.getItemId() == R.id.update_filter_list_id) {
            ContentFilteringAdapter adapter = mAdapter;
            if (mFilterListAndroidHandler != null && adapter != null) {
                mFilterListAndroidHandler.updateFilterLists(
                        isSuccess -> {
                            if (mAdapter != adapter) return;
                            if (isSuccess) {
                                setData();
                            }
                            String message =
                                    isSuccess
                                            ? getString(R.string.update_filter_list_success_text)
                                            : getString(R.string.update_filter_list_fail_text);
                            Toast.makeText(getActivity(), message, Toast.LENGTH_SHORT).show();
                        });
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void isEditSelected(boolean isEditable) {
        mDoneItem.setVisible(isEditable);
        mEditItem.setVisible(!isEditable);
        mUpdateFilterListItem.setVisible(!isEditable);
        mAdapter.setEditable(isEditable);
    }

    @Override
    public void onDestroyView() {
        // Retire callbacks before closing the proxy or detaching the old view.
        mAdapter = null;
        if (mFilterListAndroidHandler != null) {
            mFilterListAndroidHandler.close();
            mFilterListAndroidHandler = null;
        }
        if (mRecyclerView != null) {
            mRecyclerView.setAdapter(null);
            mRecyclerView = null;
        }
        mEditItem = null;
        mDoneItem = null;
        mUpdateFilterListItem = null;
        mIsMenuLoaded = false;
        mIsGetSubscriptionsLoaded = false;
        mSubscriptionFilterLists = new ArrayList<>();
        mFilterLists = null;
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
