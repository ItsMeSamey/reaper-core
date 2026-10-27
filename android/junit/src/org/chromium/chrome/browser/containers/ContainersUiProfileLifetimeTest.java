// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
package org.chromium.chrome.browser.containers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.appcompat.app.AlertDialog;

import org.chromium.base.Callback;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tabmodel.TabCreatorManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ContainersUiProfileLifetimeTest {
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock private ContainersBridge.Natives mNative;
    @Mock private Profile mProfile;
    @Mock private Tab mSource;
    @Mock private TabCreatorManager mCreators;
    @Captor private ArgumentCaptor<Callback<Integer>> mOperationCallback;

    @Before
    public void setUp() {
        ContainersBridgeJni.setInstanceForTesting(mNative);
        when(mProfile.getNativeBrowserContextPointer()).thenReturn(1L);
        when(mSource.getProfile()).thenReturn(mProfile);
    }

    @Test
    public void testShutdownSelectionNeverQueriesJNIOrCreator() {
        when(mProfile.shutdownStarted()).thenReturn(true);
        assertFalse(ContainersUi.openInContainer(mSource, mSource, mCreators, ""));
        verify(mNative, never()).isEnabled(any());
        verify(mCreators, never()).getTabCreator(false);
    }

    @Test
    public void testZeroPointerSelectionNeverQueriesJNIOrCreator() {
        when(mProfile.getNativeBrowserContextPointer()).thenReturn(0L);
        assertFalse(ContainersUi.openInContainer(mSource, mSource, mCreators, ""));
        verify(mNative, never()).isEnabled(any());
        verify(mCreators, never()).getTabCreator(false);
    }

    @Test
    public void testOffTheRecordProfileWithoutIncognitoFlagNeverQueriesJNI() {
        when(mProfile.isOffTheRecord()).thenReturn(true);
        assertFalse(ContainersUi.openInContainer(mSource, mSource, mCreators, ""));
        verify(mNative, never()).isEnabled(any());
        verify(mCreators, never()).getTabCreator(false);
    }

    @Test
    public void testUnavailableMetadataQueryNeverCallsJNI() {
        when(mProfile.shutdownStarted()).thenReturn(true);
        assertNull(ContainersUi.getInfo(mProfile, ID));
        when(mProfile.shutdownStarted()).thenReturn(false);
        when(mProfile.getNativeBrowserContextPointer()).thenReturn(0L);
        assertNull(ContainersUi.getInfo(mProfile, ID));
        verify(mNative, never()).getContainerInfo(any(), anyString());
    }

    @Test
    public void testTemporaryFallbackNamesIncludeDistinctIdentity() {
        String first =
                ContainersUi.getName(RuntimeEnvironment.getApplication(), mProfile, "t-" + ID);
        String second =
                ContainersUi.getName(
                        RuntimeEnvironment.getApplication(),
                        mProfile,
                        "t-22222222-2222-4222-8222-222222222222");
        assertTrue(first.contains("11111111"));
        assertTrue(second.contains("22222222"));
        assertFalse(first.equals(second));
    }

    @Test
    public void testEditorRepairsOversizedIconAndTransparentPaletteColor() throws Exception {
        assertEditorRepair(Integer.MAX_VALUE, 0x001a73e8, 10, 0xff1a73e8);
    }

    @Test
    public void testEditorRepairsNegativeIconAndTransparentCustomColor() throws Exception {
        assertEditorRepair(-1, 0x00010203, 0, 0xff010203);
    }

    @Test
    public void testAddRejectsUnicodeBlankNameThenSavesUnchangedUnicode() throws Exception {
        assertBlankNameRejectedThenSaved(false);
    }

    @Test
    public void testUpdateRejectsUnicodeBlankNameThenSavesUnchangedUnicode() throws Exception {
        assertBlankNameRejectedThenSaved(true);
    }

    private void assertBlankNameRejectedThenSaved(boolean update) throws Exception {
        Constructor<ContainersBridge.ContainerInfo> constructor =
                ContainersBridge.ContainerInfo.class.getDeclaredConstructor(
                        String.class, String.class, int.class, int.class);
        constructor.setAccessible(true);
        ContainersBridge.ContainerInfo info =
                update ? constructor.newInstance(ID, "Original", 1, 0xffd93025) : null;
        when(mNative.isEnabled(mProfile)).thenReturn(true);
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        controller.get().setTheme(org.chromium.chrome.R.style.Theme_Chromium_Settings);
        AlertDialog dialog = null;
        int[] refreshes = {0};
        try {
            dialog = ContainersUi.showEditor(controller.get(), mProfile, info, () -> refreshes[0]++);
            // Install the Save listener from the dialog\'s posted OnShow callback first.
            ShadowLooper.idleMainLooper();
            List<EditText> editors = new ArrayList<>();
            collectEditors(dialog.getWindow().getDecorView(), editors);
            assertEquals(1, editors.size());
            EditText name = editors.get(0);
            View save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setFocusableInTouchMode(true);
            for (String blank :
                    List.of("", " \t ", "\u2003", "\u00a0", "\u0085", "\u3000", "\u202f",
                            "\u2003\u00a0\u0085\t ")) {
                name.setText(blank);
                // Model keyboard activation from Save, not an already-focused name field.
                assertTrue(save.requestFocus());
                assertTrue(save.isFocused());
                assertFalse(name.isFocused());
                save.performClick();
                assertTrue(name.isFocused());
                assertEquals(name, dialog.getWindow().getDecorView().findFocus());
                assertTrue(dialog.isShowing());
                assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                assertNotNull(name.getError());
                assertEquals(
                        controller.get().getString(org.chromium.chrome.R.string.reaper_container_invalid_name),
                        name.getError().toString());
                assertEquals(0, refreshes[0]);
                verify(mNative, never()).addContainer(any(), anyString(), anyInt(), anyInt(), any());
                verify(mNative, never())
                        .updateContainer(any(), anyString(), anyString(), anyInt(), anyInt(), any());
            }
            // A single-line editor displays pasted newlines as spaces but preserves the raw
            // name sent to native validation. Keep another field focused during Save activation.
            String nativeRejectedName = "Work\nPersonal";
            name.setText(nativeRejectedName);
            name.setError(null);
            List<Spinner> choices = new ArrayList<>();
            collectSpinners(dialog.getWindow().getDecorView(), choices);
            assertEquals(2, choices.size());
            Spinner icon = choices.get(0);
            icon.setFocusableInTouchMode(true);
            assertTrue(icon.requestFocus());
            assertTrue(icon.isFocused());
            save.performClick();
            assertTrue(dialog.isShowing());
            assertFalse(save.isEnabled());
            assertTrue(icon.isFocused());
            assertFalse(name.isFocused());
            assertNull(name.getError());
            if (update) {
                verify(mNative)
                        .updateContainer(
                                eq(mProfile), eq(ID), eq(nativeRejectedName), eq(1), eq(0xffd93025),
                                mOperationCallback.capture());
            } else {
                verify(mNative)
                        .addContainer(
                                eq(mProfile), eq(nativeRejectedName), eq(0), eq(0xff1a73e8),
                                mOperationCallback.capture());
            }
            mOperationCallback.getValue().onResult(3);
            assertTrue(dialog.isShowing());
            assertTrue(save.isEnabled());
            assertTrue(name.isFocused());
            assertEquals(name, dialog.getWindow().getDecorView().findFocus());
            assertNotNull(name.getError());
            assertEquals(
                    controller.get().getString(org.chromium.chrome.R.string.reaper_container_invalid_name),
                    name.getError().toString());
            assertEquals(0, refreshes[0]);
            // A corrected Unicode name still retries normally after the native validation error.
            // Keep ordinary Unicode spaces and a language's zero-width non-joiner inside a real name.
            String validName = "仕事\u3000می\u200cروم\u00a0🙂";
            name.setText(validName);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            if (update) {
                verify(mNative)
                        .updateContainer(
                                eq(mProfile), eq(ID), eq(validName), eq(1), eq(0xffd93025),
                                mOperationCallback.capture());
                verify(mNative, never()).addContainer(any(), anyString(), anyInt(), anyInt(), any());
            } else {
                verify(mNative)
                        .addContainer(
                                eq(mProfile), eq(validName), eq(0), eq(0xff1a73e8), mOperationCallback.capture());
                verify(mNative, never())
                        .updateContainer(any(), anyString(), anyString(), anyInt(), anyInt(), any());
            }
            assertTrue(dialog.isShowing());
            assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            mOperationCallback.getValue().onResult(ContainersBridge.SUCCESS);
            assertFalse(dialog.isShowing());
            assertEquals(1, refreshes[0]);
        } finally {
            if (dialog != null) dialog.dismiss();
            controller.destroy();
        }
    }

    private static void collectEditors(View view, List<EditText> result) {
        if (view instanceof EditText) result.add((EditText) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                collectEditors(group.getChildAt(i), result);
        }
    }

    private void assertEditorRepair(int badIcon, int badColor, int expectedIcon, int expectedColor)
            throws Exception {
        Constructor<ContainersBridge.ContainerInfo> constructor =
                ContainersBridge.ContainerInfo.class.getDeclaredConstructor(
                        String.class, String.class, int.class, int.class);
        constructor.setAccessible(true);
        ContainersBridge.ContainerInfo info =
                constructor.newInstance(ID, "Work", badIcon, badColor);
        when(mNative.isEnabled(mProfile)).thenReturn(true);
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        controller.get().setTheme(org.chromium.chrome.R.style.Theme_Chromium_Settings);
        AlertDialog dialog = null;
        try {
            dialog = ContainersUi.showEditor(controller.get(), mProfile, info, () -> {});
            // Install the Save listener from the dialog\'s posted OnShow callback first.
            ShadowLooper.idleMainLooper();
            List<Spinner> spinners = new ArrayList<>();
            collectSpinners(dialog.getWindow().getDecorView(), spinners);
            assertEquals(2, spinners.size());
            assertEquals(expectedIcon, spinners.get(0).getSelectedItemPosition());
            assertTrue(spinners.get(0).getSelectedItemPosition() < spinners.get(0).getCount());
            assertTrue(spinners.get(1).getSelectedItemPosition() >= 0);
            assertTrue(spinners.get(1).getSelectedItemPosition() < spinners.get(1).getCount());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            verify(mNative)
                    .updateContainer(
                            eq(mProfile),
                            eq(ID),
                            eq("Work"),
                            eq(expectedIcon),
                            eq(expectedColor),
                            any());
            verify(mNative, never())
                    .addContainer(
                            any(),
                            anyString(),
                            org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.anyInt(),
                            any());
        } finally {
            if (dialog != null) dialog.dismiss();
            controller.destroy();
        }
    }

    private static void collectSpinners(View view, List<Spinner> result) {
        if (view instanceof Spinner) result.add((Spinner) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                collectSpinners(group.getChildAt(i), result);
        }
    }
}
