// Copyright 2026 The Reaper Authors
// SPDX-License-Identifier: MPL-2.0
package org.chromium.chrome.browser.containers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.annotation.Config;

import org.chromium.base.Callback;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.base.test.RobolectricUtil;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.tabpersistence.TabMetadataFileManager;
import org.chromium.chrome.browser.tabpersistence.TabMetadataFileManager.TabModelMetadata;
import org.chromium.chrome.browser.tabpersistence.TabMetadataFileManager.TabModelSelectorMetadata;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ContainersPersistentTabReferencesTest {
    @Rule public TemporaryFolder mFolder = new TemporaryFolder();
    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock private Profile mProfile;
    @Mock private ContainersBridge.Natives mBridge;
    @Mock private ContainersPersistentTabReferences.Natives mPersistence;

    @Before
    public void setUp() {
        ContainersBridgeJni.setInstanceForTesting(mBridge);
        ContainersPersistentTabReferencesJni.setInstanceForTesting(mPersistence);
        when(mProfile.isNativeInitialized()).thenReturn(true);
        when(mBridge.getPersistentContainerReferencesGeneration(mProfile)).thenReturn(1L);
        when(mBridge.setPersistentContainerReferences(eq(mProfile), any(String[].class), anyLong()))
                .thenReturn(true);
        doAnswer(invocation -> {
                    Callback<String[]> callback = invocation.getArgument(1);
                    callback.onResult(new String[0]);
                    return null;
                })
                .when(mPersistence).loadDatabaseContainerIds(eq(mProfile), any());
    }
    private static final String ID = "4a4d13b6-c971-4c61-ae11-e7a864f58165";

    private void metadata(File directory, int tabId, String id) {
        TabModelMetadata normal = new TabModelMetadata(0);
        normal.ids.add(tabId);
        normal.urls.add("https://same.example/");
        normal.containerIds.add(id);
        TabMetadataFileManager.saveListToFile(new File(directory, "tab_state0"),
                new TabModelSelectorMetadata(normal, new TabModelMetadata(-1)));
    }

    @Test
    public void testAllUnopenedWindowsAndArchiveMetadataAreIncluded() throws Exception {
        File window = mFolder.newFolder("unopened-window");
        File archive = mFolder.newFolder("archive");
        metadata(window, 1, ID);
        metadata(archive, 2, "t-" + ID);
        Set<String> result = new HashSet<>();
        ContainersPersistentTabReferences.scanDirectory(mFolder.getRoot(), result, new HashSet<>());
        assertEquals(new HashSet<>(List.of(ID, "t-" + ID)), result);
    }

    @Test
    public void testCorruptPerTabStateRetainsIndexedContainerFallbackReference() throws Exception {
        metadata(mFolder.getRoot(), 1, ID);
        try (FileOutputStream out = new FileOutputStream(new File(mFolder.getRoot(), "tab1"))) {
            out.write(new byte[] {1, 2, 3});
        }
        Set<String> result = new HashSet<>();
        ContainersPersistentTabReferences.scanDirectory(mFolder.getRoot(), result, new HashSet<>());
        assertTrue(result.contains(ID));
    }

    @Test(expected = IOException.class)
    public void testUnindexedCorruptStateCannotPublishCompleteInventory() throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(mFolder.getRoot(), "tab1"))) {
            out.write(new byte[] {1, 2, 3});
        }
        ContainersPersistentTabReferences.scanDirectory(
                mFolder.getRoot(), new HashSet<>(), new HashSet<>());
    }
    @Test
    public void testEmptyDirectoryIsACompleteEmptyDiskInventory() throws Exception {
        Set<String> result = new HashSet<>();
        ContainersPersistentTabReferences.scanDirectory(
                mFolder.getRoot(), result, new HashSet<>());
        assertTrue(result.isEmpty());
    }

    @Test(expected = IOException.class)
    public void testCorruptMetadataCannotPublishCompleteInventory() throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(mFolder.getRoot(), "tab_state0"))) {
            out.write(new byte[] {1, 2, 3});
        }
        ContainersPersistentTabReferences.scanDirectory(
                mFolder.getRoot(), new HashSet<>(), new HashSet<>());
    }

    @Test
    public void testActivePersistenceWriteDefersScanUntilWorkerCompletion() {
        ContainersPersistentTabReferences.beginPersistenceWrite(mProfile);
        ContainersPersistentTabReferences.refresh(mProfile);
        verify(mBridge, never()).getPersistentContainerReferencesGeneration(mProfile);
        verify(mPersistence, never()).loadDatabaseContainerIds(eq(mProfile), any());
        ContainersPersistentTabReferences.endPersistenceWrite(mProfile);
        RobolectricUtil.runAllBackgroundAndUi();
        verify(mPersistence).loadDatabaseContainerIds(eq(mProfile), any());
        verify(mBridge).setPersistentContainerReferences(eq(mProfile), any(String[].class), eq(1L));
    }

    @Test
    public void testRejectedNativeGenerationRetriesACompleteFreshScan() {
        when(mBridge.getPersistentContainerReferencesGeneration(mProfile)).thenReturn(1L, 2L);
        when(mBridge.setPersistentContainerReferences(eq(mProfile), any(String[].class), anyLong()))
                .thenReturn(false, true);
        ContainersPersistentTabReferences.refresh(mProfile);
        RobolectricUtil.runAllBackgroundAndUi();
        verify(mPersistence, times(2)).loadDatabaseContainerIds(eq(mProfile), any());
        verify(mBridge).setPersistentContainerReferences(eq(mProfile), any(String[].class), eq(1L));
        verify(mBridge).setPersistentContainerReferences(eq(mProfile), any(String[].class), eq(2L));
    }

}
