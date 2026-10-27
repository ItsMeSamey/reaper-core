// Copyright 2026 The Reaper Authors
// Use of this source code is governed by a BSD-style license.

package org.chromium.components.storage_monitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import androidx.annotation.VisibleForTesting;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;
import org.jni_zero.JniType;
import org.jni_zero.NativeMethods;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.PathUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Observes app-accessible Android storage without broad storage permissions.
 *
 * Lifecycle and native notifications run on the UI thread. Filesystem and platform volume queries
 * run on a worker. Native StorageMonitor converts these internal volume IDs to transient IDs before
 * exposing them to extensions.
 */
@JNINamespace("storage_monitor")
@NullMarked
public final class StorageMonitorAndroid {
    private static final String TAG = "StorageMonitor";

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    static final class Volume {
        final String id;
        final String path;
        final String label;
        final boolean removable;
        final long capacity;

        Volume(String id, String path, String label, boolean removable, long capacity) {
            this.id = id;
            this.path = path;
            this.label = label;
            this.removable = removable;
            this.capacity = capacity;
        }

        boolean hasSameMetadata(Volume other) {
            return path.equals(other.path)
                    && label.equals(other.label)
                    && removable == other.removable
                    && capacity == other.capacity;
        }
    }

    private final Context mContext;
    private final Supplier<Map<String, Volume>> mSnapshotSource;
    private final Executor mBackgroundExecutor;
    private final Executor mUiExecutor;
    private final BroadcastReceiver mReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    requestScan();
                }
            };

    // These fields are accessed only on the UI thread.
    private long mNativeStorageMonitorAndroid;
    private boolean mStarted;
    private boolean mReceiverRegistered;
    private boolean mScanInFlight;
    private boolean mRescanPending;
    private boolean mInitialized;
    private Map<String, Volume> mVolumes = Collections.emptyMap();

    @CalledByNative
    static StorageMonitorAndroid create(long nativePointer) {
        ThreadUtils.assertOnUiThread();
        Context context = ContextUtils.getApplicationContext();
        return new StorageMonitorAndroid(
                nativePointer,
                context,
                () -> readSnapshot(context),
                task -> PostTask.postTask(TaskTraits.USER_VISIBLE_MAY_BLOCK, task),
                task -> PostTask.postTask(TaskTraits.UI_DEFAULT, task));
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    StorageMonitorAndroid(
            long nativePointer,
            Context context,
            Supplier<Map<String, Volume>> snapshotSource,
            Executor backgroundExecutor,
            Executor uiExecutor) {
        ThreadUtils.assertOnUiThread();
        mNativeStorageMonitorAndroid = nativePointer;
        mContext = context;
        mSnapshotSource = snapshotSource;
        mBackgroundExecutor = backgroundExecutor;
        mUiExecutor = uiExecutor;
    }

    @CalledByNative
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    void start() {
        ThreadUtils.assertOnUiThread();
        if (mStarted || mNativeStorageMonitorAndroid == 0) return;
        mStarted = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addDataScheme("file");
        // Register before the first snapshot so a mount change during enumeration is rescanned.
        try {
            ContextUtils.registerProtectedBroadcastReceiver(mContext, mReceiver, filter);
            mReceiverRegistered = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to observe storage changes", e);
        }
        requestScan();
    }

    @CalledByNative
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    void stop() {
        ThreadUtils.assertOnUiThread();
        // stop() is terminal. Invalidate native access before unregistering or worker completion.
        mNativeStorageMonitorAndroid = 0;
        mStarted = false;
        mRescanPending = false;
        mVolumes = Collections.emptyMap();
        if (!mReceiverRegistered) return;
        mReceiverRegistered = false;
        try {
            mContext.unregisterReceiver(mReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Storage receiver was already unregistered", e);
        }
    }

    private void requestScan() {
        ThreadUtils.assertOnUiThread();
        if (!mStarted || mNativeStorageMonitorAndroid == 0) return;
        if (mScanInFlight) {
            mRescanPending = true;
            return;
        }
        mScanInFlight = true;
        mBackgroundExecutor.execute(
                () -> {
                    @Nullable Map<String, Volume> snapshot = null;
                    try {
                        snapshot = mSnapshotSource.get();
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Unable to enumerate storage volumes", e);
                    }
                    @Nullable Map<String, Volume> result = snapshot;
                    mUiExecutor.execute(() -> finishScan(result));
                });
    }

    private void finishScan(@Nullable Map<String, Volume> snapshot) {
        ThreadUtils.assertOnUiThread();
        if (mNativeStorageMonitorAndroid == 0) return;
        // Failed enumeration is not evidence that previously attached storage disappeared.
        if (snapshot != null) applySnapshot(snapshot);
        if (mNativeStorageMonitorAndroid == 0) return;
        if (!mInitialized) {
            mInitialized = true;
            StorageMonitorAndroidJni.get().onInitialized(mNativeStorageMonitorAndroid);
        }
        if (mNativeStorageMonitorAndroid == 0) return;
        mScanInFlight = false;
        if (mRescanPending) {
            mRescanPending = false;
            requestScan();
        }
    }

    private void applySnapshot(Map<String, Volume> snapshot) {
        Map<String, Volume> previous = mVolumes;
        mVolumes = snapshot;
        for (Volume old : previous.values()) {
            Volume current = snapshot.get(old.id);
            if (current != null && old.hasSameMetadata(current)) continue;
            if (mNativeStorageMonitorAndroid == 0) return;
            StorageMonitorAndroidJni.get().onVolumeDetached(mNativeStorageMonitorAndroid, old.id);
        }
        for (Volume current : snapshot.values()) {
            Volume old = previous.get(current.id);
            if (old != null && current.hasSameMetadata(old)) continue;
            if (mNativeStorageMonitorAndroid == 0) return;
            StorageMonitorAndroidJni.get()
                    .onVolumeAttached(
                            mNativeStorageMonitorAndroid,
                            current.id,
                            current.path,
                            current.label,
                            current.removable,
                            current.capacity);
        }
    }

    private static Map<String, Volume> readSnapshot(Context context) {
        StorageManager manager = context.getSystemService(StorageManager.class);
        if (manager == null) return Collections.emptyMap();
        List<File> directories = new ArrayList<>();
        for (String path : PathUtils.getAllPrivateDownloadsDirectories()) {
            if (!path.isEmpty()) directories.add(new File(path));
        }
        return readVolumes(context, manager, directories);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    static Map<String, Volume> readVolumes(
            Context context, StorageManager manager, List<File> directories) {
        Map<String, Volume> result = new LinkedHashMap<>();
        for (File directory : directories) {
            StorageVolume storage = manager.getStorageVolume(directory);
            if (storage == null) continue;
            String state = storage.getState();
            if (!Environment.MEDIA_MOUNTED.equals(state)
                    && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
                continue;
            }
            if (!directory.isDirectory() || !directory.canRead()) continue;
            String id;
            if (storage.isPrimary()) {
                id = "primary";
            } else {
                String uuid = storage.getUuid();
                // Non-primary volumes without an identity cannot be reliably tracked across mounts.
                if (uuid == null || uuid.isEmpty()) continue;
                id = "uuid:" + uuid;
            }
            if (result.containsKey(id)) continue;
            String label = storage.getDescription(context);
            result.put(
                    id,
                    new Volume(
                            id,
                            directory.getAbsolutePath(),
                            label == null ? "" : label,
                            storage.isRemovable(),
                            Math.max(0L, directory.getTotalSpace())));
        }
        return result;
    }

    @NativeMethods
    interface Natives {
        void onVolumeAttached(
                long nativeStorageMonitorAndroid,
                @JniType("std::string") String id,
                @JniType("std::string") String path,
                @JniType("std::u16string") String label,
                boolean removable,
                long capacity);

        void onVolumeDetached(
                long nativeStorageMonitorAndroid, @JniType("std::string") String id);

        void onInitialized(long nativeStorageMonitorAndroid);
    }
}
