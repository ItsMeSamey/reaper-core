/* Copyright (c) 2026 The Reaper Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.shields;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.base.test.util.Features.DisableFeatures;
import org.chromium.base.test.util.Features.EnableFeatures;
import org.chromium.brave_shields.mojom.FilterListAndroidHandler;
import org.chromium.brave_shields.mojom.SubscriptionInfo;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.settings.BraveSettingsActivity;
import org.chromium.chrome.browser.settings.SettingsIntentUtil;
import org.chromium.mojo_base.mojom.DictionaryValue;
import org.chromium.mojo_base.mojom.ListValue;
import org.chromium.mojo_base.mojom.Value;
import org.chromium.url.mojom.Url;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Ordinary loading, refresh, deletion and repeated-click contracts for retained Shields. */
@RunWith(BaseRobolectricTestRunner.class)
public class ContentFilteringAdapterTest {
    private static final int HEADER = 1;
    private static final int CUSTOM = 2;
    private static final int REGIONAL = 3;

    private static ContentFilteringAdapter adapter(BraveContentFilteringListener listener) {
        return new ContentFilteringAdapter(mock(Context.class), listener);
    }

    /** Model-visible row counts must stay coherent for full or incremental notifications. */
    private static final class RowCountObserver extends RecyclerView.AdapterDataObserver {
        private final ContentFilteringAdapter mAdapter;
        int count;
        final List<Integer> updates = new ArrayList<>();

        RowCountObserver(ContentFilteringAdapter adapter) {
            mAdapter = adapter;
            count = adapter.getItemCount();
            adapter.registerAdapterDataObserver(this);
        }

        @Override
        public void onChanged() {
            count = mAdapter.getItemCount();
            updates.add(count);
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            count += itemCount;
            assertEquals(mAdapter.getItemCount(), count);
            updates.add(count);
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            count -= itemCount;
            assertEquals(mAdapter.getItemCount(), count);
            updates.add(count);
        }
    }

    private static ArrayList<SubscriptionInfo> subscriptions(int count) {
        ArrayList<SubscriptionInfo> result = new ArrayList<>();
        for (int i = 0; i < count; ++i) {
            SubscriptionInfo info = new SubscriptionInfo();
            info.subscriptionUrl = new Url();
            info.subscriptionUrl.url = "https://filters.example/list" + i;
            result.add(info);
        }
        return result;
    }

    private static Value[] regionalEntries(int count) {
        Value[] result = new Value[count];
        for (int i = 0; i < count; ++i) {
            DictionaryValue dictionary = new DictionaryValue();
            dictionary.storage = new HashMap<>();
            dictionary.storage.put("title", stringValue("Regional list"));
            dictionary.storage.put("desc", stringValue("Regional rules"));
            dictionary.storage.put("uuid", stringValue("regional-" + i));
            Value enabled = new Value();
            enabled.setBoolValue(false);
            dictionary.storage.put("enabled", enabled);
            Value entry = new Value();
            entry.setDictionaryValue(dictionary);
            result[i] = entry;
        }
        return result;
    }

    private static void assertRows(ContentFilteringAdapter adapter, int subscriptions, int regional) {
        assertEquals(subscriptions + regional + 4, adapter.getItemCount());
        assertEquals(HEADER, adapter.getItemViewType(0));
        for (int i = 1; i <= subscriptions + 2; ++i) {
            assertEquals(CUSTOM, adapter.getItemViewType(i));
        }
        assertEquals(HEADER, adapter.getItemViewType(subscriptions + 3));
        for (int i = subscriptions + 4; i < adapter.getItemCount(); ++i) {
            assertEquals(REGIONAL, adapter.getItemViewType(i));
        }
    }

    @Test
    public void initialLoadingKeepsCustomActionsAvailable() {
        assertRows(adapter(mock(BraveContentFilteringListener.class)), 0, 0);
    }

    @Test
    public void subscriptionsThenRegionalReplyPublishesAllRows() {
        ContentFilteringAdapter adapter = adapter(mock(BraveContentFilteringListener.class));
        RowCountObserver observer = new RowCountObserver(adapter);
        adapter.setSubscriptionFilterLists(subscriptions(1));
        assertRows(adapter, 1, 0);
        adapter.setFilterLists(regionalEntries(2));
        assertRows(adapter, 1, 2);
        assertEquals(List.of(5, 7), observer.updates);
        assertEquals(adapter.getItemCount(), observer.count);
    }

    @Test
    public void regionalThenSubscriptionsReplyPublishesAllRows() {
        ContentFilteringAdapter adapter = adapter(mock(BraveContentFilteringListener.class));
        RowCountObserver observer = new RowCountObserver(adapter);
        adapter.setFilterLists(regionalEntries(2));
        assertRows(adapter, 0, 2);
        adapter.setSubscriptionFilterLists(subscriptions(1));
        assertRows(adapter, 1, 2);
        assertEquals(List.of(6, 7), observer.updates);
        assertEquals(adapter.getItemCount(), observer.count);
    }

    @Test
    public void repeatedRefreshCanGrowShrinkAndEmptyBothDatasets() {
        ContentFilteringAdapter adapter = adapter(mock(BraveContentFilteringListener.class));
        RowCountObserver observer = new RowCountObserver(adapter);
        adapter.setFilterLists(regionalEntries(3));
        adapter.setFilterLists(regionalEntries(1));
        adapter.setSubscriptionFilterLists(subscriptions(2));
        assertRows(adapter, 2, 1);
        adapter.setSubscriptionFilterLists(subscriptions(0));
        adapter.setFilterLists(new Value[0]);
        assertRows(adapter, 0, 0);
        assertEquals(List.of(7, 5, 7, 5, 4), observer.updates);
        assertEquals(adapter.getItemCount(), observer.count);
    }

    private static void setField(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    @Test
    public void deletingSubscriptionPublishesTheActualRemainingRows() throws Exception {
        ContentFilteringFragment fragment = new ContentFilteringFragment();
        ContentFilteringAdapter adapter = adapter(fragment);
        ArrayList<SubscriptionInfo> subscriptions = subscriptions(1);
        Url removedUrl = subscriptions.get(0).subscriptionUrl;
        adapter.setSubscriptionFilterLists(subscriptions);
        RowCountObserver observer = new RowCountObserver(adapter);
        adapter.registerAdapterDataObserver(
                new RecyclerView.AdapterDataObserver() {
                    @Override
                    public void onItemRangeRemoved(int positionStart, int itemCount) {
                        // A full refresh is valid; an incremental deletion must target the
                        // subscription after the header and custom-filter action.
                        assertEquals(2, positionStart);
                        assertEquals(1, itemCount);
                    }
                });
        FilterListAndroidHandler handler = mock(FilterListAndroidHandler.class);
        setField(fragment, "mAdapter", adapter);
        setField(fragment, "mSubscriptionFilterLists", subscriptions);
        setField(fragment, "mFilterListAndroidHandler", handler);
        setField(fragment, "mEditItem", mock(MenuItem.class));
        setField(fragment, "mDoneItem", mock(MenuItem.class));
        setField(fragment, "mUpdateFilterListItem", mock(MenuItem.class));
        fragment.onSubscriptionFilterDelete(0);
        verify(handler).deleteSubscription(removedUrl);
        assertRows(adapter, 0, 0);
        assertEquals(List.of(4), observer.updates);
        assertEquals(adapter.getItemCount(), observer.count);
    }

    @Test
    public void fragmentStartsWithAnEmptySubscriptionSnapshot() throws Exception {
        ContentFilteringFragment fragment = new ContentFilteringFragment();
        Field field = ContentFilteringFragment.class.getDeclaredField("mSubscriptionFilterLists");
        field.setAccessible(true);
        assertEquals(0, ((ArrayList<?>) field.get(fragment)).size());
    }

    private static Value stringValue(String text) {
        Value value = new Value();
        value.setStringValue(text);
        return value;
    }

    @Test
    public void repeatedRegionalTapsSubmitTheCurrentSwitchState() {
        BraveContentFilteringListener listener = mock(BraveContentFilteringListener.class);
        ContentFilteringAdapter adapter = adapter(listener);
        DictionaryValue dictionary = new DictionaryValue();
        dictionary.storage = new HashMap<>();
        dictionary.storage.put("title", stringValue("Regional list"));
        dictionary.storage.put("desc", stringValue("Regional rules"));
        dictionary.storage.put("uuid", stringValue("regional"));
        Value enabled = new Value();
        enabled.setBoolValue(false);
        dictionary.storage.put("enabled", enabled);
        Value entry = new Value();
        entry.setDictionaryValue(dictionary);
        adapter.setFilterLists(new Value[] {entry});
        View root = mock(View.class);
        when(root.<TextView>findViewById(R.id.title_text)).thenReturn(mock(TextView.class));
        when(root.<TextView>findViewById(R.id.description_text)).thenReturn(mock(TextView.class));
        MaterialSwitch toggle = mock(MaterialSwitch.class);
        when(root.<MaterialSwitch>findViewById(R.id.toggle_switch)).thenReturn(toggle);
        ContentFilteringAdapter.FilterListViewHolder holder =
                new ContentFilteringAdapter.FilterListViewHolder(root);
        adapter.onBindViewHolder(holder, 4);
        ArgumentCaptor<View.OnClickListener> click =
                ArgumentCaptor.forClass(View.OnClickListener.class);
        verify(toggle).setOnClickListener(click.capture());
        when(toggle.isChecked()).thenReturn(true);
        click.getValue().onClick(toggle);
        assertEquals(true, enabled.getBoolValue());
        when(toggle.isChecked()).thenReturn(false);
        click.getValue().onClick(toggle);
        assertEquals(false, enabled.getBoolValue());
        InOrder order = inOrder(listener);
        order.verify(listener).onFilterToggle("regional", true);
        order.verify(listener).onFilterToggle("regional", false);
    }

    private static void invokeListRequest(ContentFilteringFragment fragment, String name)
            throws Exception {
        Method method = ContentFilteringFragment.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(fragment);
    }

    private static Object getField(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    @Test
    public void currentViewListRepliesPopulateTheCurrentAdapter() throws Exception {
        ContentFilteringFragment fragment = new ContentFilteringFragment();
        ContentFilteringAdapter adapter = mock(ContentFilteringAdapter.class);
        FilterListAndroidHandler handler = mock(FilterListAndroidHandler.class);
        setField(fragment, "mAdapter", adapter);
        setField(fragment, "mFilterListAndroidHandler", handler);
        invokeListRequest(fragment, "getSubscriptionFilters");
        invokeListRequest(fragment, "getFilterLists");
        ArgumentCaptor<FilterListAndroidHandler.GetSubscriptions_Response> subscriptionsReply =
                ArgumentCaptor.forClass(FilterListAndroidHandler.GetSubscriptions_Response.class);
        ArgumentCaptor<FilterListAndroidHandler.GetFilterLists_Response> regionalReply =
                ArgumentCaptor.forClass(FilterListAndroidHandler.GetFilterLists_Response.class);
        verify(handler).getSubscriptions(subscriptionsReply.capture());
        verify(handler).getFilterLists(regionalReply.capture());
        ArrayList<SubscriptionInfo> subscriptions = subscriptions(1);
        subscriptionsReply.getValue().call(subscriptions.toArray(new SubscriptionInfo[0]));
        ListValue regional = new ListValue();
        regional.storage = regionalEntries(1);
        regionalReply.getValue().call(regional);
        verify(adapter).setSubscriptionFilterLists(subscriptions);
        verify(adapter).setFilterLists(regional.storage);
    }

    @Test
    public void retiredViewRepliesDoNotReplaceTheRecreatedViewSnapshot() throws Exception {
        ContentFilteringFragment fragment = new ContentFilteringFragment();
        ContentFilteringAdapter oldAdapter = mock(ContentFilteringAdapter.class);
        FilterListAndroidHandler oldHandler = mock(FilterListAndroidHandler.class);
        RecyclerView oldRecycler = mock(RecyclerView.class);
        setField(fragment, "mAdapter", oldAdapter);
        setField(fragment, "mRecyclerView", oldRecycler);
        setField(fragment, "mFilterListAndroidHandler", oldHandler);
        setField(fragment, "mIsMenuLoaded", true);
        setField(fragment, "mIsGetSubscriptionsLoaded", true);
        invokeListRequest(fragment, "getSubscriptionFilters");
        invokeListRequest(fragment, "getFilterLists");
        ArgumentCaptor<FilterListAndroidHandler.GetSubscriptions_Response> oldSubscriptions =
                ArgumentCaptor.forClass(FilterListAndroidHandler.GetSubscriptions_Response.class);
        ArgumentCaptor<FilterListAndroidHandler.GetFilterLists_Response> oldRegional =
                ArgumentCaptor.forClass(FilterListAndroidHandler.GetFilterLists_Response.class);
        verify(oldHandler).getSubscriptions(oldSubscriptions.capture());
        verify(oldHandler).getFilterLists(oldRegional.capture());

        fragment.onDestroyView();
        verify(oldHandler).close();
        verify(oldRecycler).setAdapter(null);
        assertNull(getField(fragment, "mAdapter"));
        assertNull(getField(fragment, "mFilterListAndroidHandler"));
        assertEquals(false, getField(fragment, "mIsMenuLoaded"));
        assertEquals(false, getField(fragment, "mIsGetSubscriptionsLoaded"));
        oldSubscriptions.getValue().call(subscriptions(1).toArray(new SubscriptionInfo[0]));
        verifyNoInteractions(oldAdapter);

        // setData installs a new adapter and proxy when the retained fragment gets its next view.
        ContentFilteringAdapter newAdapter = mock(ContentFilteringAdapter.class);
        FilterListAndroidHandler newHandler = mock(FilterListAndroidHandler.class);
        setField(fragment, "mAdapter", newAdapter);
        setField(fragment, "mFilterListAndroidHandler", newHandler);
        ListValue regional = new ListValue();
        regional.storage = regionalEntries(1);
        oldRegional.getValue().call(regional);
        verifyNoInteractions(newAdapter);
        invokeListRequest(fragment, "getFilterLists");
        ArgumentCaptor<FilterListAndroidHandler.GetFilterLists_Response> newRegional =
                ArgumentCaptor.forClass(FilterListAndroidHandler.GetFilterLists_Response.class);
        verify(newHandler).getFilterLists(newRegional.capture());
        newRegional.getValue().call(regional);
        verify(newAdapter).setFilterLists(regional.storage);
    }

    @Test
    public void retiredUpdateReplyDoesNotRefreshTheRecreatedView() throws Exception {
        ContentFilteringFragment fragment = new ContentFilteringFragment();
        setField(fragment, "mAdapter", mock(ContentFilteringAdapter.class));
        FilterListAndroidHandler oldHandler = mock(FilterListAndroidHandler.class);
        setField(fragment, "mFilterListAndroidHandler", oldHandler);
        MenuItem update = mock(MenuItem.class);
        when(update.getItemId()).thenReturn(R.id.update_filter_list_id);
        assertEquals(true, fragment.onOptionsItemSelected(update));
        ArgumentCaptor<FilterListAndroidHandler.UpdateFilterLists_Response> reply =
                ArgumentCaptor.forClass(FilterListAndroidHandler.UpdateFilterLists_Response.class);
        verify(oldHandler).updateFilterLists(reply.capture());
        fragment.onDestroyView();
        verify(oldHandler).close();
        ContentFilteringAdapter newAdapter = mock(ContentFilteringAdapter.class);
        FilterListAndroidHandler newHandler = mock(FilterListAndroidHandler.class);
        setField(fragment, "mAdapter", newAdapter);
        setField(fragment, "mFilterListAndroidHandler", newHandler);
        reply.getValue().call(true);
        verifyNoInteractions(newHandler, newAdapter);
    }

    private static void requestCustomEditorFilters(CreateCustomFiltersFragment fragment)
            throws Exception {
        Method method = CreateCustomFiltersFragment.class.getDeclaredMethod("getCustomFilters");
        method.setAccessible(true);
        method.invoke(fragment);
    }

    @Test
    public void currentCustomEditorReplyPopulatesItsEditor() throws Exception {
        CreateCustomFiltersFragment fragment = new CreateCustomFiltersFragment();
        EditText editor = new EditText(RuntimeEnvironment.getApplication());
        FilterListAndroidHandler handler = mock(FilterListAndroidHandler.class);
        setField(fragment, "mEtCustomFilters", editor);
        setField(fragment, "mFilterListAndroidHandler", handler);
        requestCustomEditorFilters(fragment);
        ArgumentCaptor<FilterListAndroidHandler.GetCustomFilters_Response> reply =
                ArgumentCaptor.forClass(FilterListAndroidHandler.GetCustomFilters_Response.class);
        verify(handler).getCustomFilters(reply.capture());
        reply.getValue().call("current native filters");
        assertEquals("current native filters", editor.getText().toString());
    }

    @Test
    public void retiredCustomEditorReplyDoesNotReplaceRecreatedText() throws Exception {
        CreateCustomFiltersFragment fragment = new CreateCustomFiltersFragment();
        EditText oldEditor = new EditText(RuntimeEnvironment.getApplication());
        oldEditor.setText("old draft");
        FilterListAndroidHandler oldHandler = mock(FilterListAndroidHandler.class);
        setField(fragment, "mEtCustomFilters", oldEditor);
        setField(fragment, "mFilterListAndroidHandler", oldHandler);
        requestCustomEditorFilters(fragment);
        ArgumentCaptor<FilterListAndroidHandler.GetCustomFilters_Response> oldReply =
                ArgumentCaptor.forClass(FilterListAndroidHandler.GetCustomFilters_Response.class);
        verify(oldHandler).getCustomFilters(oldReply.capture());

        fragment.onDestroyView();
        verify(oldHandler).close();
        assertNull(getField(fragment, "mEtCustomFilters"));
        assertNull(getField(fragment, "mFilterListAndroidHandler"));
        oldReply.getValue().call("obsolete native filters");
        assertEquals("old draft", oldEditor.getText().toString());

        EditText newEditor = new EditText(RuntimeEnvironment.getApplication());
        newEditor.setText("new draft");
        FilterListAndroidHandler newHandler = mock(FilterListAndroidHandler.class);
        setField(fragment, "mEtCustomFilters", newEditor);
        setField(fragment, "mFilterListAndroidHandler", newHandler);
        oldReply.getValue().call("obsolete native filters");
        assertEquals("new draft", newEditor.getText().toString());
        requestCustomEditorFilters(fragment);
        ArgumentCaptor<FilterListAndroidHandler.GetCustomFilters_Response> currentReply =
                ArgumentCaptor.forClass(FilterListAndroidHandler.GetCustomFilters_Response.class);
        verify(newHandler).getCustomFilters(currentReply.capture());
        currentReply.getValue().call("new native filters");
        assertEquals("new native filters", newEditor.getText().toString());
        assertEquals("old draft", oldEditor.getText().toString());
    }

    @Test
    @DisableFeatures(ChromeFeatureList.SETTINGS_MULTI_COLUMN)
    public void filterListsRegistersResultsWithAnOrdinaryFragmentActivityHost() throws Exception {
        // CREATED reaches the real onAttach/registration contract without starting native-backed
        // view loading. Settings tabs also use a FragmentActivity rather than SettingsActivity.
        ActivityController<FragmentActivity> controller =
                Robolectric.buildActivity(FragmentActivity.class).create();
        try {
            ContentFilteringFragment fragment = new ContentFilteringFragment();
            controller.get()
                    .getSupportFragmentManager()
                    .beginTransaction()
                    .add(fragment, "filter-lists")
                    .commitNow();
            Field field =
                    ContentFilteringFragment.class.getDeclaredField(
                            "mAddCustomFilterResultLauncher");
            field.setAccessible(true);
            assertNotNull(field.get(fragment));
        } finally {
            controller.destroy();
        }
    }

    @Test
    @EnableFeatures({
        ChromeFeatureList.SETTINGS_IN_TAB,
        ChromeFeatureList.SETTINGS_IN_TAB_DESKTOP,
        ChromeFeatureList.SETTINGS_MULTI_COLUMN,
        ChromeFeatureList.SETTINGS_SINGLE_ACTIVITY
    })
    public void addCustomFilterIntentKeepsADedicatedResultActivity() {
        ActivityController<FragmentActivity> controller =
                Robolectric.buildActivity(FragmentActivity.class).create();
        try {
            Intent intent = ContentFilteringFragment.createAddCustomFilterIntent(controller.get());
            assertEquals(BraveSettingsActivity.class.getName(), intent.getComponent().getClassName());
            assertEquals(
                    AddCustomFilterListsFragment.class.getName(),
                    intent.getStringExtra(SettingsIntentUtil.EXTRA_SHOW_FRAGMENT));
            assertTrue(
                    intent.getBooleanExtra(
                            SettingsIntentUtil.EXTRA_SHOW_FRAGMENT_STANDALONE, false));
            assertEquals(
                    0,
                    intent.getFlags()
                            & (Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    | Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            assertNull(intent.getData());
            assertTrue(!intent.hasExtra(SettingsIntentUtil.EXTRA_ADD_TO_BACK_STACK));
        } finally {
            controller.destroy();
        }
    }
    private static CreateCustomFiltersFragment customEditorForStateCallbacks(
            EditText editor, FilterListAndroidHandler handler) throws Exception {
        CreateCustomFiltersFragment fragment = new CreateCustomFiltersFragment();
        setField(fragment, "mEtCustomFilters", editor);
        setField(fragment, "mFilterListAndroidHandler", handler);
        return fragment;
    }

    @Test
    public void customEditorKeepsTypedAndClearedDraftBeforeInitialReply() throws Exception {
        for (String draft : new String[] {"new user filter", ""}) {
            EditText editor = new EditText(RuntimeEnvironment.getApplication());
            FilterListAndroidHandler handler = mock(FilterListAndroidHandler.class);
            CreateCustomFiltersFragment fragment = customEditorForStateCallbacks(editor, handler);
            fragment.onViewStateRestored(null);
            ArgumentCaptor<FilterListAndroidHandler.GetCustomFilters_Response> reply =
                    ArgumentCaptor.forClass(FilterListAndroidHandler.GetCustomFilters_Response.class);
            verify(handler).getCustomFilters(reply.capture());

            editor.setText("temporary edit");
            editor.setText(draft);
            reply.getValue().call("previously saved filters");
            assertEquals(draft, editor.getText().toString());

            Method save = CreateCustomFiltersFragment.class.getDeclaredMethod("updateCustomFilters");
            save.setAccessible(true);
            save.invoke(fragment);
            ArgumentCaptor<String> submitted = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<FilterListAndroidHandler.UpdateCustomFilters_Response> saved =
                    ArgumentCaptor.forClass(
                            FilterListAndroidHandler.UpdateCustomFilters_Response.class);
            verify(handler).updateCustomFilters(submitted.capture(), saved.capture());
            assertEquals(draft, submitted.getValue());
            fragment.onDestroyView();
        }
    }

    @Test
    public void customEditorRestoresUnsavedAndEmptyDraftWithoutReloading() throws Exception {
        for (String draft : new String[] {"unsaved filter\nsecond filter", ""}) {
            EditText editor = new EditText(RuntimeEnvironment.getApplication());
            FilterListAndroidHandler handler = mock(FilterListAndroidHandler.class);
            CreateCustomFiltersFragment fragment = customEditorForStateCallbacks(editor, handler);
            fragment.onViewStateRestored(null);
            ArgumentCaptor<FilterListAndroidHandler.GetCustomFilters_Response> reply =
                    ArgumentCaptor.forClass(FilterListAndroidHandler.GetCustomFilters_Response.class);
            verify(handler).getCustomFilters(reply.capture());
            reply.getValue().call("saved filter");
            editor.setText(draft);

            Bundle state = new Bundle();
            fragment.onSaveInstanceState(state);
            fragment.onDestroyView();
            EditText restoredEditor = new EditText(RuntimeEnvironment.getApplication());
            FilterListAndroidHandler restoredHandler = mock(FilterListAndroidHandler.class);
            CreateCustomFiltersFragment restored =
                    customEditorForStateCallbacks(restoredEditor, restoredHandler);
            restored.onViewStateRestored(state);
            assertEquals(draft, restoredEditor.getText().toString());
            verifyNoInteractions(restoredHandler);
            restored.onDestroyView();
        }
    }

    @Test
    public void customEditorRetainsDraftAcrossSameFragmentViewRecreation() throws Exception {
        EditText oldEditor = new EditText(RuntimeEnvironment.getApplication());
        FilterListAndroidHandler oldHandler = mock(FilterListAndroidHandler.class);
        CreateCustomFiltersFragment fragment = customEditorForStateCallbacks(oldEditor, oldHandler);
        fragment.onViewStateRestored(null);
        oldEditor.setText("retained unsaved filter");
        fragment.onDestroyView();
        oldEditor.setText("edit on retired view");

        EditText newEditor = new EditText(RuntimeEnvironment.getApplication());
        FilterListAndroidHandler newHandler = mock(FilterListAndroidHandler.class);
        setField(fragment, "mEtCustomFilters", newEditor);
        setField(fragment, "mFilterListAndroidHandler", newHandler);
        fragment.onViewStateRestored(null);
        assertEquals("retained unsaved filter", newEditor.getText().toString());
        verifyNoInteractions(newHandler);
        fragment.onDestroyView();
    }

    @Test
    public void customEditorReloadsUntouchedLoadingViewAfterRecreation() throws Exception {
        EditText editor = new EditText(RuntimeEnvironment.getApplication());
        FilterListAndroidHandler handler = mock(FilterListAndroidHandler.class);
        CreateCustomFiltersFragment fragment = customEditorForStateCallbacks(editor, handler);
        fragment.onViewStateRestored(null);
        Bundle state = new Bundle();
        fragment.onSaveInstanceState(state);
        fragment.onDestroyView();

        EditText restoredEditor = new EditText(RuntimeEnvironment.getApplication());
        FilterListAndroidHandler restoredHandler = mock(FilterListAndroidHandler.class);
        CreateCustomFiltersFragment restored =
                customEditorForStateCallbacks(restoredEditor, restoredHandler);
        restored.onViewStateRestored(state);
        ArgumentCaptor<FilterListAndroidHandler.GetCustomFilters_Response> reply =
                ArgumentCaptor.forClass(FilterListAndroidHandler.GetCustomFilters_Response.class);
        verify(restoredHandler).getCustomFilters(reply.capture());
        reply.getValue().call("initial saved filters");
        assertEquals("initial saved filters", restoredEditor.getText().toString());
        restored.onDestroyView();
    }


    private static ArrayList<SubscriptionInfo> readySubscriptions() {
        ArrayList<SubscriptionInfo> result = subscriptions(1);
        SubscriptionInfo info = result.get(0);
        info.lastUpdateAttempt = new org.chromium.mojo_base.mojom.Time();
        info.lastUpdateAttempt.internalValue = 1;
        info.lastSuccessfulUpdateAttempt = new org.chromium.mojo_base.mojom.Time();
        info.lastSuccessfulUpdateAttempt.internalValue = 1;
        info.lastUpdatedPrettyText = "Just now";
        return result;
    }

    /** Bind one ordinary subscription through RecyclerView's real holder ownership path. */
    private static final class BoundSubscriptionRow {
        final RecyclerView recyclerView;
        final ContentFilteringAdapter adapter;
        final ArrayList<SubscriptionInfo> subscriptions = readySubscriptions();
        final ContentFilteringAdapter.CustomFilterListViewHolder holder;
        final View.OnClickListener toggle;
        final View.OnClickListener delete;

        BoundSubscriptionRow(BraveContentFilteringListener listener, boolean editable) {
            Context context = mock(Context.class);
            when(context.getResources()).thenReturn(mock(Resources.class));
            Context viewContext = RuntimeEnvironment.getApplication();
            recyclerView = new RecyclerView(viewContext);
            adapter =
                    new ContentFilteringAdapter(context, listener) {
                        @Override
                        public RecyclerView.ViewHolder onCreateViewHolder(
                                ViewGroup parent, int viewType) {
                            assertEquals(CUSTOM, viewType);
                            ContentFilteringAdapter.CustomFilterListViewHolder row =
                                    new ContentFilteringAdapter.CustomFilterListViewHolder(
                                            new View(parent.getContext()));
                            row.mTitleText = new TextView(viewContext);
                            row.mLastUpdateText = new TextView(viewContext);
                            row.mUrlText = new TextView(viewContext);
                            row.mDeleteImageView = mock(ImageView.class);
                            row.mArrowImageView = mock(ImageView.class);
                            row.mToggleSwitch = mock(MaterialSwitch.class);
                            row.mDivider = new View(viewContext);
                            return row;
                        }
                    };
            adapter.setSubscriptionFilterLists(subscriptions);
            adapter.setEditable(editable);
            recyclerView.setLayoutManager(
                    new RecyclerView.LayoutManager() {
                        @Override
                        public RecyclerView.LayoutParams generateDefaultLayoutParams() {
                            return new RecyclerView.LayoutParams(320, 48);
                        }

                        @Override
                        public void onLayoutChildren(
                                RecyclerView.Recycler recycler, RecyclerView.State state) {
                            detachAndScrapAttachedViews(recycler);
                            View row = recycler.getViewForPosition(2);
                            addView(row);
                            measureChildWithMargins(row, 0, 0);
                            layoutDecorated(row, 0, 0, 320, 48);
                        }
                    });
            recyclerView.setAdapter(adapter);
            recyclerView.measure(
                    View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY));
            recyclerView.layout(0, 0, 320, 48);
            holder =
                    (ContentFilteringAdapter.CustomFilterListViewHolder)
                            recyclerView.findViewHolderForAdapterPosition(2);
            assertNotNull(holder);
            assertEquals(2, holder.getBindingAdapterPosition());
            ArgumentCaptor<View.OnClickListener> click =
                    ArgumentCaptor.forClass(View.OnClickListener.class);
            verify(holder.mToggleSwitch).setOnClickListener(click.capture());
            toggle = click.getValue();
            verify(holder.mDeleteImageView).setOnClickListener(click.capture());
            delete = click.getValue();
        }
    }

    @Test
    public void subscriptionToggleUsesCurrentBoundRow() {
        BraveContentFilteringListener listener = mock(BraveContentFilteringListener.class);
        BoundSubscriptionRow row = new BoundSubscriptionRow(listener, false);
        row.toggle.onClick(row.holder.mToggleSwitch);
        assertEquals(true, row.subscriptions.get(0).enabled);
        row.toggle.onClick(row.holder.mToggleSwitch);
        assertEquals(false, row.subscriptions.get(0).enabled);
        InOrder order = inOrder(listener);
        order.verify(listener).onSubscriptionFilterToggle(0, true);
        order.verify(listener).onSubscriptionFilterToggle(0, false);
        row.recyclerView.setAdapter(null);
    }

    @Test
    public void subscriptionDeleteUsesCurrentBoundRow() {
        BraveContentFilteringListener listener = mock(BraveContentFilteringListener.class);
        BoundSubscriptionRow row = new BoundSubscriptionRow(listener, true);
        row.delete.onClick(row.holder.mDeleteImageView);
        verify(listener).onSubscriptionFilterDelete(0);
        row.recyclerView.setAdapter(null);
    }

    @Test
    public void detachedSubscriptionRowIgnoresActions() {
        BraveContentFilteringListener listener = mock(BraveContentFilteringListener.class);
        BoundSubscriptionRow row = new BoundSubscriptionRow(listener, true);
        row.recyclerView.setAdapter(null);
        assertEquals(RecyclerView.NO_POSITION, row.holder.getBindingAdapterPosition());
        row.toggle.onClick(row.holder.mToggleSwitch);
        row.delete.onClick(row.holder.mDeleteImageView);
        assertEquals(false, row.subscriptions.get(0).enabled);
        verifyNoInteractions(listener);
    }

    @Test
    public void replacedSubscriptionRowIgnoresOldActions() {
        BraveContentFilteringListener listener = mock(BraveContentFilteringListener.class);
        BoundSubscriptionRow row = new BoundSubscriptionRow(listener, true);
        ArrayList<SubscriptionInfo> replacement = readySubscriptions();
        row.adapter.setSubscriptionFilterLists(replacement);
        // Let RecyclerView retire or rebind the visible holder to the replacement model.
        row.recyclerView.measure(
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY));
        row.recyclerView.layout(0, 0, 320, 48);
        RecyclerView.ViewHolder replacementHolder =
                row.recyclerView.findViewHolderForAdapterPosition(2);
        assertNotNull(replacementHolder);
        assertEquals(2, replacementHolder.getBindingAdapterPosition());
        row.toggle.onClick(row.holder.mToggleSwitch);
        row.delete.onClick(row.holder.mDeleteImageView);
        assertEquals(false, row.subscriptions.get(0).enabled);
        assertEquals(false, replacement.get(0).enabled);
        verifyNoInteractions(listener);
        row.recyclerView.setAdapter(null);
    }

    private static void saveCustomEditorFilters(CreateCustomFiltersFragment fragment)
            throws Exception {
        Method save = CreateCustomFiltersFragment.class.getDeclaredMethod("updateCustomFilters");
        save.setAccessible(true);
        save.invoke(fragment);
    }

    @Test
    public void customEditorDoesNotSaveAnUnloadedPlaceholder() throws Exception {
        EditText editor = new EditText(RuntimeEnvironment.getApplication());
        Button saveButton = new Button(RuntimeEnvironment.getApplication());
        FilterListAndroidHandler handler = mock(FilterListAndroidHandler.class);
        CreateCustomFiltersFragment fragment = customEditorForStateCallbacks(editor, handler);
        setField(fragment, "mSaveButton", saveButton);
        fragment.onViewStateRestored(null);
        ArgumentCaptor<FilterListAndroidHandler.GetCustomFilters_Response> reply =
                ArgumentCaptor.forClass(FilterListAndroidHandler.GetCustomFilters_Response.class);
        verify(handler).getCustomFilters(reply.capture());
        assertTrue(!saveButton.isEnabled());
        saveCustomEditorFilters(fragment);
        verify(handler, never()).updateCustomFilters(anyString(), any());

        reply.getValue().call("existing saved filters");
        assertTrue(saveButton.isEnabled());
        assertEquals("existing saved filters", editor.getText().toString());
        saveCustomEditorFilters(fragment);
        verify(handler).updateCustomFilters(org.mockito.ArgumentMatchers.eq("existing saved filters"), any());
        fragment.onDestroyView();
        assertNull(getField(fragment, "mSaveButton"));
    }

    @Test
    public void customEditorLoadedEmptyDraftCanBeSaved() throws Exception {
        EditText editor = new EditText(RuntimeEnvironment.getApplication());
        Button saveButton = new Button(RuntimeEnvironment.getApplication());
        FilterListAndroidHandler handler = mock(FilterListAndroidHandler.class);
        CreateCustomFiltersFragment fragment = customEditorForStateCallbacks(editor, handler);
        setField(fragment, "mSaveButton", saveButton);
        fragment.onViewStateRestored(null);
        ArgumentCaptor<FilterListAndroidHandler.GetCustomFilters_Response> reply =
                ArgumentCaptor.forClass(FilterListAndroidHandler.GetCustomFilters_Response.class);
        verify(handler).getCustomFilters(reply.capture());
        reply.getValue().call("");
        assertTrue(saveButton.isEnabled());
        saveCustomEditorFilters(fragment);
        verify(handler).updateCustomFilters(org.mockito.ArgumentMatchers.eq(""), any());
        fragment.onDestroyView();
    }

    @Test
    public void customEditorEditedEmptyDraftCanBeSavedBeforeInitialReply() throws Exception {
        EditText editor = new EditText(RuntimeEnvironment.getApplication());
        Button saveButton = new Button(RuntimeEnvironment.getApplication());
        FilterListAndroidHandler handler = mock(FilterListAndroidHandler.class);
        CreateCustomFiltersFragment fragment = customEditorForStateCallbacks(editor, handler);
        setField(fragment, "mSaveButton", saveButton);
        fragment.onViewStateRestored(null);
        editor.setText("deliberate edit");
        editor.setText("");
        assertTrue(saveButton.isEnabled());
        saveCustomEditorFilters(fragment);
        verify(handler).updateCustomFilters(org.mockito.ArgumentMatchers.eq(""), any());
        fragment.onDestroyView();
    }
}
