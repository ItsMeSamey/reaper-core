/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.annotation.Config;

import org.chromium.base.ApplicationStatus;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.brave.browser.custom_search_engines.settings.AddCustomSearchEnginePreferenceFragment;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.ChromeTabbedActivity2;
import org.chromium.chrome.browser.IntentHandler;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.autofill.settings.AutofillLocalCardEditor;
import org.chromium.chrome.browser.browsing_data.BraveClearBrowsingDataFragment;
import org.chromium.components.browser_ui.site_settings.AllSiteSettings;
import org.chromium.components.browser_ui.site_settings.SiteSettingsCategory;

import java.util.List;
import java.util.Map;

/** Tests Settings footer insets and close navigation without running the browser process. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class BraveSettingsActivityTest {
    private static final String PACKAGE_NAME = "app.smallthingz.reaper";
    private static final int SETTINGS_TASK_ID = 17;
    private FrameLayout mRoot;
    private RecyclerView mList;

    @Before
    public void setUp() {
        mRoot = new FrameLayout(ApplicationProvider.getApplicationContext());
        mList = new RecyclerView(mRoot.getContext());
        mList.setId(R.id.recycler_view);
        mRoot.addView(mList);
    }

    @Test
    public void testCustomSearchEngineInsetsIncludeFooter() {
        assertSame(
                mRoot,
                BraveSettingsActivity.getInsetView(
                        new AddCustomSearchEnginePreferenceFragment(), mRoot));
    }

    @Test
    public void testCardEditorInsetsIncludeFooter() {
        assertSame(mRoot, BraveSettingsActivity.getInsetView(new AutofillLocalCardEditor(), mRoot));
    }

    @Test
    public void testClearBrowsingDataInsetsIncludeFooter() {
        assertSame(
                mRoot,
                BraveSettingsActivity.getInsetView(new BraveClearBrowsingDataFragment(), mRoot));
    }

    @Test
    public void testStorageInsetsIncludeFooter() {
        assertSame(
                mRoot,
                BraveSettingsActivity.getInsetView(
                        createSiteSettings(SiteSettingsCategory.Type.USE_STORAGE), mRoot));
    }

    @Test
    public void testZoomInsetsIncludeFooter() {
        assertSame(
                mRoot,
                BraveSettingsActivity.getInsetView(
                        createSiteSettings(SiteSettingsCategory.Type.ZOOM), mRoot));
    }

    @Test
    public void testAllSitesInsetsOnlyTheList() {
        assertSame(
                mList,
                BraveSettingsActivity.getInsetView(
                        createSiteSettings(SiteSettingsCategory.Type.ALL_SITES), mRoot));
    }

    @Test
    public void testKeyboardDoesNotAddNavigationPaddingTwice() {
        Map<View, Integer> originalBottomPaddings = Map.of(mList, 8);
        WindowInsetsCompat.Builder insets =
                new WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE);

        BraveSettingsActivity.applyContentInsets(mRoot, originalBottomPaddings, insets.build());
        assertEquals(0, mRoot.getPaddingBottom());
        assertEquals(56, mList.getPaddingBottom());

        insets.setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300));
        BraveSettingsActivity.applyContentInsets(mRoot, originalBottomPaddings, insets.build());
        assertEquals(300, mRoot.getPaddingBottom());
        assertEquals(8, mList.getPaddingBottom());

        insets.setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE);
        BraveSettingsActivity.applyContentInsets(mRoot, originalBottomPaddings, insets.build());
        assertEquals(0, mRoot.getPaddingBottom());
        assertEquals(56, mList.getPaddingBottom());
    }

    @Test
    public void testCloseRestoredAliasTaskDoesNotReplayPayloadOrRetryFailedUp() {
        // Android retains task records after process recreation, without live browser/Settings A
        // objects. Main is the manifest alias; baseActivity reports its CTA implementation.
        assertTrue(ApplicationStatus.getRunningActivities().isEmpty());
        ComponentName main = new ComponentName(PACKAGE_NAME, "com.google.android.apps.chrome.Main");
        ActivityManager.RecentTaskInfo info =
                taskInfo(
                        SETTINGS_TASK_ID,
                        new ComponentName(PACKAGE_NAME, ChromeTabbedActivity.class.getName()),
                        main);
        info.baseIntent
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("https://example.test/existing-tab"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BraveActivity.OPEN_URL, "https://example.test/do-not-open")
                .putExtra(IntentHandler.EXTRA_OPEN_NEW_INCOGNITO_TAB, true);
        List<ActivityManager.AppTask> tasks = List.of(appTask(info));
        SettingsActivity current = currentSettings(tasks);
        doReturn(false).when(current).navigateUpTo(any(Intent.class));

        BraveSettingsActivity.closeSettingsInTask(current);

        ArgumentCaptor<Intent> up = ArgumentCaptor.forClass(Intent.class);
        verify(current).navigateUpTo(up.capture());
        assertEquals(main, up.getValue().getComponent());
        assertNotSame(info.baseIntent, up.getValue());
        assertEquals(Intent.FLAG_ACTIVITY_SINGLE_TOP, up.getValue().getFlags());
        assertNull(up.getValue().getAction());
        assertNull(up.getValue().getData());
        assertNull(up.getValue().getCategories());
        assertNull(up.getValue().getExtras());
        assertNull(up.getValue().getSelector());
        assertNull(up.getValue().getClipData());
        verify(current, never()).finish();
        assertNoLaunchOrTaskRemoval(current, tasks);
        // A false Up result can already have unwound the task. It must not cause another action.
        assertEquals("https://example.test/existing-tab", info.baseIntent.getDataString());
        assertTrue(info.baseIntent.getBooleanExtra(IntentHandler.EXTRA_OPEN_NEW_INCOGNITO_TAB, false));
    }

    @Test
    public void testCloseRestoredSecondaryTaskKeepsItsOwnBrowserRoot() {
        assertTrue(ApplicationStatus.getRunningActivities().isEmpty());
        ComponentName secondary =
                new ComponentName(PACKAGE_NAME, ChromeTabbedActivity2.class.getName());
        List<ActivityManager.AppTask> tasks =
                List.of(
                        appTask(
                                taskInfo(
                                        SETTINGS_TASK_ID + 1,
                                        new ComponentName(
                                                PACKAGE_NAME, ChromeTabbedActivity.class.getName()),
                                        null)),
                        appTask(taskInfo(SETTINGS_TASK_ID, secondary, null)));
        SettingsActivity current = currentSettings(tasks);
        doReturn(true).when(current).navigateUpTo(any(Intent.class));

        BraveSettingsActivity.closeSettingsInTask(current);

        ArgumentCaptor<Intent> up = ArgumentCaptor.forClass(Intent.class);
        verify(current).navigateUpTo(up.capture());
        assertEquals(secondary, up.getValue().getComponent());
        verify(current, never()).finish();
        assertNoLaunchOrTaskRemoval(current, tasks);
    }

    @Test
    public void testCloseForeignOrUnsupportedRootOnlyFinishesCurrentPage() {
        ComponentName main = new ComponentName(PACKAGE_NAME, "com.google.android.apps.chrome.Main");
        for (ActivityManager.RecentTaskInfo info :
                List.of(
                        taskInfo(
                                SETTINGS_TASK_ID,
                                new ComponentName("other.browser", ChromeTabbedActivity.class.getName()),
                                null),
                        taskInfo(
                                SETTINGS_TASK_ID,
                                new ComponentName(PACKAGE_NAME, SettingsActivity.class.getName()),
                                null),
                        taskInfo(
                                SETTINGS_TASK_ID,
                                new ComponentName(PACKAGE_NAME, ChromeTabbedActivity2.class.getName()),
                                main))) {
            List<ActivityManager.AppTask> tasks = List.of(appTask(info));
            SettingsActivity current = currentSettings(tasks);

            BraveSettingsActivity.closeSettingsInTask(current);

            assertOnlyCurrentPageFinished(current, tasks);
        }
    }

    @Test
    public void testCloseMissingOwnTaskRecordOnlyFinishesCurrentPage() {
        ComponentName browser =
                new ComponentName(PACKAGE_NAME, ChromeTabbedActivity.class.getName());
        for (List<ActivityManager.AppTask> tasks :
                List.of(
                        List.<ActivityManager.AppTask>of(),
                        List.of(appTask(null)),
                        List.of(appTask(taskInfo(SETTINGS_TASK_ID + 1, browser, null))))) {
            SettingsActivity current = currentSettings(tasks);

            BraveSettingsActivity.closeSettingsInTask(current);

            assertOnlyCurrentPageFinished(current, tasks);
        }
    }

    @Test
    public void testCloseIncompleteTaskIdentityOnlyFinishesCurrentPage() {
        ComponentName browser =
                new ComponentName(PACKAGE_NAME, ChromeTabbedActivity.class.getName());
        ActivityManager.RecentTaskInfo noRoot = taskInfo(SETTINGS_TASK_ID, null, null);
        ActivityManager.RecentTaskInfo noIntent = taskInfo(SETTINGS_TASK_ID, browser, null);
        noIntent.baseIntent = null;
        ActivityManager.RecentTaskInfo noComponent = taskInfo(SETTINGS_TASK_ID, browser, null);
        noComponent.baseIntent = new Intent();
        for (ActivityManager.RecentTaskInfo info : List.of(noRoot, noIntent, noComponent)) {
            List<ActivityManager.AppTask> tasks = List.of(appTask(info));
            SettingsActivity current = currentSettings(tasks);

            BraveSettingsActivity.closeSettingsInTask(current);

            assertOnlyCurrentPageFinished(current, tasks);
        }
    }

    @Test
    public void testCloseExpiredTaskOnlyFinishesCurrentPage() {
        ActivityManager.AppTask expiredTask = mock(ActivityManager.AppTask.class);
        when(expiredTask.getTaskInfo()).thenThrow(new IllegalArgumentException("task no longer exists"));
        List<ActivityManager.AppTask> tasks = List.of(expiredTask);
        SettingsActivity current = currentSettings(tasks);

        BraveSettingsActivity.closeSettingsInTask(current);

        assertOnlyCurrentPageFinished(current, tasks);
    }

    private static ActivityManager.RecentTaskInfo taskInfo(
            int taskId, ComponentName root, ComponentName original) {
        ActivityManager.RecentTaskInfo info = new ActivityManager.RecentTaskInfo();
        info.taskId = taskId;
        info.baseActivity = root;
        info.origActivity = original;
        info.baseIntent = new Intent().setComponent(original == null ? root : original);
        return info;
    }

    private static ActivityManager.AppTask appTask(ActivityManager.RecentTaskInfo info) {
        ActivityManager.AppTask task = mock(ActivityManager.AppTask.class);
        when(task.getTaskInfo()).thenReturn(info);
        return task;
    }

    private static SettingsActivity currentSettings(List<ActivityManager.AppTask> tasks) {
        ActivityManager manager = mock(ActivityManager.class);
        when(manager.getAppTasks()).thenReturn(tasks);
        SettingsActivity current = mock(SettingsActivity.class, CALLS_REAL_METHODS);
        doReturn(false).when(current).isFinishing();
        doReturn(false).when(current).isDestroyed();
        doReturn(SETTINGS_TASK_ID).when(current).getTaskId();
        doReturn(PACKAGE_NAME).when(current).getPackageName();
        // Context.getSystemService(Class) is final. Let its real adapter call these public,
        // overridable methods instead of relying on Mockito's ability to mock final methods.
        doReturn(Context.ACTIVITY_SERVICE)
                .when(current)
                .getSystemServiceName(ActivityManager.class);
        doReturn(manager).when(current).getSystemService(Context.ACTIVITY_SERVICE);
        doReturn(false).when(current).navigateUpTo(any(Intent.class));
        doNothing().when(current).finish();
        doNothing().when(current).startActivity(any(Intent.class));
        doNothing().when(current).startActivity(any(Intent.class), any());
        doNothing().when(current).finishAffinity();
        doNothing().when(current).finishAndRemoveTask();
        doReturn(false).when(current).moveTaskToBack(anyBoolean());
        return current;
    }

    private static void assertOnlyCurrentPageFinished(
            SettingsActivity current, List<ActivityManager.AppTask> tasks) {
        verify(current).finish();
        verify(current, never()).navigateUpTo(any(Intent.class));
        assertNoLaunchOrTaskRemoval(current, tasks);
    }

    private static void assertNoLaunchOrTaskRemoval(
            SettingsActivity current, List<ActivityManager.AppTask> tasks) {
        verify(current, never()).startActivity(any(Intent.class));
        verify(current, never()).startActivity(any(Intent.class), any());
        verify(current, never()).finishAffinity();
        verify(current, never()).finishAndRemoveTask();
        verify(current, never()).moveTaskToBack(anyBoolean());
        for (ActivityManager.AppTask task : tasks) {
            verify(task, never()).finishAndRemoveTask();
        }
    }

    private static AllSiteSettings createSiteSettings(@SiteSettingsCategory.Type int category) {
        AllSiteSettings fragment = new AllSiteSettings();
        Bundle args = new Bundle();
        args.putString(
                AllSiteSettings.EXTRA_CATEGORY, SiteSettingsCategory.preferenceKey(category));
        fragment.setArguments(args);
        return fragment;
    }
}
