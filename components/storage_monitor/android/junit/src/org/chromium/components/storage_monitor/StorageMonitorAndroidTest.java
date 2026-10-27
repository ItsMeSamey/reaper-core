// Copyright 2026 The Reaper Authors
// Use of this source code is governed by a BSD-style license.

package org.chromium.components.storage_monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.StorageVolumeBuilder;

import org.chromium.base.ThreadUtils;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.components.storage_monitor.StorageMonitorAndroid.Volume;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Tests storage admission, reconciliation, and native lifetime across queued snapshots. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.Q)
public class StorageMonitorAndroidTest {
    private static final long NATIVE_POINTER = 37L;
    private static final Volume PRIMARY =
            new Volume("primary", "primary/app/Download", "Internal storage", false, 64000L);
    private static final Volume REMOVABLE =
            new Volume("uuid:ABCD", "sd/app/Download", "SD card", true, 32000L);

    private final ArrayDeque<Runnable> mBackgroundTasks = new ArrayDeque<>();
    private final ArrayDeque<Runnable> mUiTasks = new ArrayDeque<>();
    private Context mContext;
    private StorageMonitorAndroid.Natives mNative;
    private StorageMonitorAndroid mMonitor;
    private BroadcastReceiver mReceiver;
    private IntentFilter mFilter;
    private Map<String, Volume> mNextSnapshot = Collections.emptyMap();
    private boolean mFailScan;
    private boolean mRegistrationAttempted;

    @Before
    public void setUp() {
        mContext = mock(Context.class);
        mNative = mock(StorageMonitorAndroid.Natives.class);
        StorageMonitorAndroidJni.setInstanceForTesting(mNative);
        when(mContext.registerReceiver(any(), any(), isNull(), isNull(), eq(0)))
                .thenAnswer(
                        invocation -> {
                            mRegistrationAttempted = true;
                            mReceiver = invocation.getArgument(0);
                            mFilter = invocation.getArgument(1);
                            return null;
                        });
        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        mMonitor =
                                new StorageMonitorAndroid(
                                        NATIVE_POINTER,
                                        mContext,
                                        () -> {
                                            if (mFailScan) throw new SecurityException("Unavailable");
                                            return mNextSnapshot;
                                        },
                                        task -> {
                                            assertTrue(mRegistrationAttempted);
                                            mBackgroundTasks.add(task);
                                        },
                                        mUiTasks::add));
    }

    @After
    public void tearDown() {
        ThreadUtils.runOnUiThreadBlocking(mMonitor::stop);
        StorageMonitorAndroidJni.setInstanceForTesting(null);
    }

    private void start(Volume... volumes) {
        mNextSnapshot = snapshot(volumes);
        ThreadUtils.runOnUiThreadBlocking(mMonitor::start);
    }

    private void broadcast() {
        assertNotNull(mReceiver);
        ThreadUtils.runOnUiThreadBlocking(
                () -> mReceiver.onReceive(mContext, new Intent(Intent.ACTION_MEDIA_MOUNTED)));
    }

    private void finishNextSnapshot() {
        assertEquals(1, mBackgroundTasks.size());
        mBackgroundTasks.remove().run();
        assertEquals(1, mUiTasks.size());
        ThreadUtils.runOnUiThreadBlocking(mUiTasks.remove());
    }

    private static Map<String, Volume> snapshot(Volume... volumes) {
        Map<String, Volume> result = new LinkedHashMap<>();
        for (Volume volume : volumes) result.put(volume.id, volume);
        return result;
    }

    private void verifyAttached(Volume volume) {
        verify(mNative)
                .onVolumeAttached(
                        NATIVE_POINTER,
                        volume.id,
                        volume.path,
                        volume.label,
                        volume.removable,
                        volume.capacity);
    }

    @Test
    public void registersProtectedFileBroadcastsBeforeInitialSnapshot() {
        start(PRIMARY, REMOVABLE);
        assertTrue(mFilter.hasAction(Intent.ACTION_MEDIA_MOUNTED));
        assertTrue(mFilter.hasAction(Intent.ACTION_MEDIA_UNMOUNTED));
        assertTrue(mFilter.hasAction(Intent.ACTION_MEDIA_REMOVED));
        assertTrue(mFilter.hasAction(Intent.ACTION_MEDIA_BAD_REMOVAL));
        assertTrue(mFilter.hasAction(Intent.ACTION_MEDIA_EJECT));
        assertEquals(5, mFilter.countActions());
        assertTrue(mFilter.hasDataScheme("file"));
        verifyNoInteractions(mNative);

        // The background completion must not call native until its UI continuation runs.
        mBackgroundTasks.remove().run();
        verifyNoInteractions(mNative);
        ThreadUtils.runOnUiThreadBlocking(mUiTasks.remove());

        InOrder order = inOrder(mNative);
        order.verify(mNative)
                .onVolumeAttached(
                        NATIVE_POINTER,
                        PRIMARY.id,
                        PRIMARY.path,
                        PRIMARY.label,
                        PRIMARY.removable,
                        PRIMARY.capacity);
        order.verify(mNative)
                .onVolumeAttached(
                        NATIVE_POINTER,
                        REMOVABLE.id,
                        REMOVABLE.path,
                        REMOVABLE.label,
                        REMOVABLE.removable,
                        REMOVABLE.capacity);
        order.verify(mNative).onInitialized(NATIVE_POINTER);
        verifyNoMoreInteractions(mNative);
    }

    @Test
    public void initialEmptySnapshotStillCompletesInitializationOnce() {
        start();
        finishNextSnapshot();
        broadcast();
        finishNextSnapshot();

        verify(mNative).onInitialized(NATIVE_POINTER);
        verifyNoMoreInteractions(mNative);
    }

    @Test
    public void failedInitialSnapshotCompletesInitializationAndLaterScanRecovers() {
        mFailScan = true;
        start(PRIMARY);
        finishNextSnapshot();
        verify(mNative).onInitialized(NATIVE_POINTER);
        verifyNoMoreInteractions(mNative);

        mFailScan = false;
        broadcast();
        finishNextSnapshot();
        verifyAttached(PRIMARY);
        verifyNoMoreInteractions(mNative);
    }

    @Test
    public void receiverRegistrationFailureDoesNotHangInitialization() {
        doAnswer(
                        invocation -> {
                            mRegistrationAttempted = true;
                            throw new SecurityException("Registration rejected");
                        })
                .when(mContext)
                .registerReceiver(any(), any(), isNull(), isNull(), eq(0));
        start();
        finishNextSnapshot();

        verify(mNative).onInitialized(NATIVE_POINTER);
        ThreadUtils.runOnUiThreadBlocking(mMonitor::stop);
        verify(mContext).registerReceiver(any(), any(), isNull(), isNull(), eq(0));
        verifyNoMoreInteractions(mContext);
    }

    @Test
    public void repeatedStartAndUnchangedBroadcastsDoNotDuplicateAttachments() {
        start(PRIMARY);
        ThreadUtils.runOnUiThreadBlocking(mMonitor::start);
        finishNextSnapshot();
        broadcast();
        finishNextSnapshot();

        verify(mContext).registerReceiver(any(), any(), isNull(), isNull(), eq(0));
        verifyAttached(PRIMARY);
        verify(mNative).onInitialized(NATIVE_POINTER);
        verifyNoMoreInteractions(mNative);
    }

    @Test
    public void changesDuringSnapshotAreCoalescedIntoOneFollowupScan() {
        start(PRIMARY);
        broadcast();
        broadcast();
        broadcast();
        assertEquals(1, mBackgroundTasks.size());
        finishNextSnapshot();
        assertEquals(1, mBackgroundTasks.size());

        mNextSnapshot = Collections.emptyMap();
        finishNextSnapshot();

        verifyAttached(PRIMARY);
        verify(mNative).onInitialized(NATIVE_POINTER);
        verify(mNative).onVolumeDetached(NATIVE_POINTER, PRIMARY.id);
        verifyNoMoreInteractions(mNative);
        assertTrue(mBackgroundTasks.isEmpty());
    }

    @Test
    public void removedVolumesDetachOnlyOnce() {
        start(PRIMARY, REMOVABLE);
        finishNextSnapshot();
        mNextSnapshot = snapshot(PRIMARY);
        broadcast();
        finishNextSnapshot();
        broadcast();
        finishNextSnapshot();

        verifyAttached(PRIMARY);
        verifyAttached(REMOVABLE);
        verify(mNative).onInitialized(NATIVE_POINTER);
        verify(mNative).onVolumeDetached(NATIVE_POINTER, REMOVABLE.id);
        verifyNoMoreInteractions(mNative);
    }

    @Test
    public void failedRescanPreservesAttachmentsUntilSuccessfulEmptySnapshot() {
        start(PRIMARY);
        finishNextSnapshot();
        verifyAttached(PRIMARY);
        verify(mNative).onInitialized(NATIVE_POINTER);

        mFailScan = true;
        broadcast();
        finishNextSnapshot();
        verifyNoMoreInteractions(mNative);

        mFailScan = false;
        mNextSnapshot = Collections.emptyMap();
        broadcast();
        finishNextSnapshot();
        verify(mNative).onVolumeDetached(NATIVE_POINTER, PRIMARY.id);
        verifyNoMoreInteractions(mNative);
    }

    @Test
    public void changedPathDetachesBeforeReplacingSameIdentity() {
        start(REMOVABLE);
        finishNextSnapshot();
        Volume moved = new Volume(REMOVABLE.id, "new/app/Download", "SD card", true, 32000L);
        mNextSnapshot = snapshot(moved);
        broadcast();
        finishNextSnapshot();

        InOrder order = inOrder(mNative);
        order.verify(mNative)
                .onVolumeAttached(
                        NATIVE_POINTER,
                        REMOVABLE.id,
                        REMOVABLE.path,
                        REMOVABLE.label,
                        true,
                        REMOVABLE.capacity);
        order.verify(mNative).onInitialized(NATIVE_POINTER);
        order.verify(mNative).onVolumeDetached(NATIVE_POINTER, REMOVABLE.id);
        order.verify(mNative)
                .onVolumeAttached(
                        NATIVE_POINTER, moved.id, moved.path, moved.label, true, moved.capacity);
        verifyNoMoreInteractions(mNative);
    }

    @Test
    public void stoppingBeforeWorkerCompletesSuppressesNativeCallbacksAndPendingRescan() {
        start(PRIMARY);
        broadcast();
        ThreadUtils.runOnUiThreadBlocking(mMonitor::stop);
        finishNextSnapshot();
        broadcast();
        ThreadUtils.runOnUiThreadBlocking(mMonitor::start);
        ThreadUtils.runOnUiThreadBlocking(mMonitor::stop);

        verifyNoInteractions(mNative);
        verify(mContext).unregisterReceiver(mReceiver);
        assertTrue(mBackgroundTasks.isEmpty());
        assertTrue(mUiTasks.isEmpty());
    }

    @Test
    public void stoppingBetweenWorkerAndUiCompletionSuppressesNativeCallbacks() {
        start(PRIMARY);
        mBackgroundTasks.remove().run();
        ThreadUtils.runOnUiThreadBlocking(mMonitor::stop);
        ThreadUtils.runOnUiThreadBlocking(mUiTasks.remove());

        verifyNoInteractions(mNative);
    }

    @Test
    public void nativeCallbackCanDestroyMonitorBeforeRemainingNotifications() {
        doAnswer(
                        invocation -> {
                            mMonitor.stop();
                            return null;
                        })
                .when(mNative)
                .onVolumeAttached(
                        NATIVE_POINTER,
                        PRIMARY.id,
                        PRIMARY.path,
                        PRIMARY.label,
                        PRIMARY.removable,
                        PRIMARY.capacity);
        start(PRIMARY, REMOVABLE);
        finishNextSnapshot();

        verifyAttached(PRIMARY);
        verifyNoMoreInteractions(mNative);
        verify(mContext).unregisterReceiver(mReceiver);
    }

    private static File directory(String path) {
        File directory = mock(File.class);
        when(directory.getAbsolutePath()).thenReturn(path);
        when(directory.isDirectory()).thenReturn(true);
        when(directory.canRead()).thenReturn(true);
        when(directory.getTotalSpace()).thenReturn(123456L);
        return directory;
    }

    private static StorageVolume storage(
            boolean primary, boolean removable, String uuid, String state) {
        return new StorageVolumeBuilder(
                        "android-internal-id",
                        new File("volume"),
                        "Storage label",
                        Process.myUserHandle(),
                        state)
                .setIsPrimary(primary)
                .setIsRemovable(removable)
                .setFsUuid(uuid)
                .build();
    }

    @Test
    public void enumeratesPrimaryAndReadOnlyRemovableUsingAppPathsOnAndroid29() {
        StorageManager manager = mock(StorageManager.class);
        File primary = directory("primary/app/Download");
        File removable = directory("sd/app/Download");
        when(manager.getStorageVolume(primary))
                .thenReturn(storage(true, false, "", Environment.MEDIA_MOUNTED));
        when(manager.getStorageVolume(removable))
                .thenReturn(storage(false, true, "ABCD", Environment.MEDIA_MOUNTED_READ_ONLY));

        Map<String, Volume> volumes =
                StorageMonitorAndroid.readVolumes(
                        mContext, manager, Arrays.asList(primary, removable));

        assertEquals(2, volumes.size());
        Volume fixed = volumes.get("primary");
        assertNotNull(fixed);
        assertEquals("primary/app/Download", fixed.path);
        assertEquals("Storage label", fixed.label);
        assertFalse(fixed.removable);
        assertEquals(123456L, fixed.capacity);
        Volume sd = volumes.get("uuid:ABCD");
        assertNotNull(sd);
        assertEquals("sd/app/Download", sd.path);
        assertTrue(sd.removable);
    }

    @Test
    public void excludesUnmountedUnreadableUnknownAndUnidentifiedVolumes() {
        StorageManager manager = mock(StorageManager.class);
        File unmounted = directory("unmounted");
        File unreadable = directory("unreadable");
        File missing = directory("missing");
        File unknown = directory("unknown");
        File unidentified = directory("unidentified");
        when(manager.getStorageVolume(unmounted))
                .thenReturn(storage(false, true, "ONE", Environment.MEDIA_UNMOUNTED));
        when(manager.getStorageVolume(unreadable))
                .thenReturn(storage(false, true, "TWO", Environment.MEDIA_MOUNTED));
        when(unreadable.canRead()).thenReturn(false);
        when(manager.getStorageVolume(missing))
                .thenReturn(storage(false, true, "THREE", Environment.MEDIA_MOUNTED));
        when(missing.isDirectory()).thenReturn(false);
        when(manager.getStorageVolume(unidentified))
                .thenReturn(storage(false, true, "", Environment.MEDIA_MOUNTED));

        assertTrue(
                StorageMonitorAndroid.readVolumes(
                                mContext,
                                manager,
                                Arrays.asList(unmounted, unreadable, missing, unknown, unidentified))
                        .isEmpty());
    }

    @Test
    public void deduplicatesPathsOnSameVolumeAndClampsInvalidCapacity() {
        StorageManager manager = mock(StorageManager.class);
        File first = directory("sd/app/Download");
        File duplicate = directory("sd/other/Download");
        StorageVolume storage = storage(false, true, "ABCD", Environment.MEDIA_MOUNTED);
        when(manager.getStorageVolume(first)).thenReturn(storage);
        when(manager.getStorageVolume(duplicate)).thenReturn(storage);
        when(first.getTotalSpace()).thenReturn(-1L);

        Map<String, Volume> volumes =
                StorageMonitorAndroid.readVolumes(
                        mContext, manager, Arrays.asList(first, duplicate));

        assertEquals(1, volumes.size());
        assertEquals("sd/app/Download", volumes.get("uuid:ABCD").path);
        assertEquals(0L, volumes.get("uuid:ABCD").capacity);
        verify(duplicate, times(0)).getTotalSpace();
    }
}
