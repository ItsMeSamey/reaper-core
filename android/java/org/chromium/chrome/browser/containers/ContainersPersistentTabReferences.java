// Copyright 2026 The Reaper Authors
// SPDX-License-Identifier: MPL-2.0
package org.chromium.chrome.browser.containers;

import androidx.core.util.AtomicFile;
import androidx.annotation.VisibleForTesting;

import org.jni_zero.CalledByNative;
import org.jni_zero.JniType;
import org.jni_zero.NativeMethods;

import org.chromium.base.Callback;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabState;
import org.chromium.chrome.browser.tabpersistence.TabMetadataFileManager;
import org.chromium.chrome.browser.tabpersistence.TabStateDirectory;
import org.chromium.chrome.browser.tabpersistence.TabStateFileManager;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Complete disk inventory barrier for safe removal of Android container jars. */
@NullMarked
public final class ContainersPersistentTabReferences {
    private static final Map<Profile, State> sStates = new WeakHashMap<>();

    private static final class State {
        long generation;
        boolean scanning;
        boolean refreshPending;
        int pendingWrites;
    }

    private ContainersPersistentTabReferences() {}

    /** Invalidate before adding/restoring a container tab or changing its persisted state. */
    public static void invalidate(Profile profile) {
        ThreadUtils.assertOnUiThread();
        if (!isUsable(profile)) return;
        State state = sStates.computeIfAbsent(profile, unused -> new State());
        state.generation++;
        state.refreshPending = true;
        ContainersBridge.invalidatePersistentContainerReferences(profile);
    }

    private static boolean isUsable(Profile profile) {
        return profile.isNativeInitialized() && !profile.shutdownStarted()
                && !profile.isOffTheRecord();
    }

    /** Keep cleanup blocked for the entire lifetime of an asynchronous disk write. */
    public static void beginPersistenceWrite(Profile profile) {
        ThreadUtils.assertOnUiThread();
        if (!isUsable(profile)) return;
        State state = sStates.computeIfAbsent(profile, unused -> new State());
        state.pendingWrites++;
        invalidate(profile);
    }

    /** Release only after the worker has finished, including cancellation. */
    public static void endPersistenceWrite(Profile profile) {
        ThreadUtils.assertOnUiThread();
        State state = sStates.get(profile);
        if (state == null) return;
        if (state.pendingWrites <= 0) throw new IllegalStateException("Unbalanced persistence write");
        state.pendingWrites--;
        if (!isUsable(profile)) {
            if (state.pendingWrites == 0) sStates.remove(profile);
            return;
        }
        invalidate(profile);
        if (state.pendingWrites == 0) refresh(profile);
    }

    /** Called by the native cleanup/discovery delegate, after its waiter is queued. */
    @CalledByNative
    public static void refresh(@JniType("Profile*") Profile profile) {
        ThreadUtils.assertOnUiThread();
        if (!isUsable(profile)) return;
        State state = sStates.computeIfAbsent(profile, unused -> new State());
        if (state.pendingWrites != 0 || state.scanning) {
            state.refreshPending = true;
            return;
        }
        state.scanning = true;
        state.refreshPending = false;
        final long generation = state.generation;
        ContainersBridge.invalidatePersistentContainerReferences(profile);
        final long nativeGeneration =
                ContainersBridge.getPersistentContainerReferencesGeneration(profile);
        new AsyncTask<@Nullable Set<String>>() {
            @Override
            protected @Nullable Set<String> doInBackground() {
                try {
                    Set<String> ids = new HashSet<>();
                    scanDirectory(TabStateDirectory.getOrCreateBaseStateDirectory(),
                            ids, new HashSet<>());
                    return ids;
                } catch (IOException | RuntimeException ex) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(@Nullable Set<String> legacyIds) {
                if (!isUsable(profile)) {
                    state.scanning = false;
                    return;
                }
                ContainersPersistentTabReferencesJni.get().loadDatabaseContainerIds(
                        profile,
                        databaseIds -> {
                            state.scanning = false;
                            boolean stale = generation != state.generation;
                            Set<String> completeIds = legacyIds;
                            if (!stale && !state.refreshPending && state.pendingWrites == 0
                                    && isUsable(profile) && completeIds != null && databaseIds != null) {
                                for (String id : databaseIds) {
                                    if (!Tab.isContainerIdWellFormed(id)) {
                                        completeIds = null;
                                        break;
                                    }
                                    if (!id.isEmpty()) completeIds.add(id);
                                }
                                if (completeIds != null) {
                                    if (!ContainersBridge.setPersistentContainerReferences(
                                            profile, completeIds.toArray(new String[0]), nativeGeneration)) {
                                        state.refreshPending = true;
                                    }
                                }
                            }
                            if ((stale || state.refreshPending) && state.pendingWrites == 0
                                    && isUsable(profile)) refresh(profile);
                        });
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    static void scanDirectory(File directory, Set<String> ids, Set<String> visited)
            throws IOException {
        String path = directory.getCanonicalPath();
        if (!visited.add(path)) throw new IOException("Cyclic tab storage directory");
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Unreadable tab storage directory");
        Map<Integer, String> indexedIds = new HashMap<>();
        Set<String> metadataNames = new HashSet<>();
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".bak")) name = name.substring(0, name.length() - 4);
            if (!file.isDirectory() && TabMetadataFileManager.isMetadataFile(name)) {
                if (!metadataNames.add(name)) continue;
                try (DataInputStream input = new DataInputStream(
                        new AtomicFile(new File(directory, name)).openRead())) {
                    TabMetadataFileManager.readSavedMetadataFile(input,
                            new TabMetadataFileManager.OnTabStateReadCallback() {
                                @Override
                                public void onDetailsRead(int index, int id, String url,
                                        int incognito, boolean normalActive, boolean otrActive) {}

                                @Override
                                public void onDetailsReadWithContainer(int index, int id, String url,
                                        int incognito, boolean normalActive, boolean otrActive,
                                        @Nullable String containerId) {
                                    if (containerId == null) return;
                                    indexedIds.put(id, containerId);
                                    if (!containerId.isEmpty()) ids.add(containerId);
                                }
                            }, null);
                }
            }
        }
        for (File file : files) {
            if (file.isDirectory()) {
                if (!file.getCanonicalPath().startsWith(path + File.separator)) {
                    throw new IOException("Tab storage directory escapes its owner");
                }
                scanDirectory(file, ids, visited);
                continue;
            }
            var info = TabStateFileManager.parseInfoFromFilename(file.getName());
            if (info == null || info.second) continue; // Containers are not admitted in OTR.
            TabState state = TabStateFileManager.restoreTabStateInternal(file, false, null);
            if (state == null) {
                if (!indexedIds.containsKey(info.first)) {
                    throw new IOException("Unindexed unreadable tab state");
                }
                continue; // Version 6 index retains the fallback tab's exact jar.
            }
            if (state.contentsState != null) {
                try {
                    String @Nullable [] savedIds = state.contentsState.getSerializedContainerIds();
                    if (savedIds == null) throw new IOException("Unreadable serialized jar identity");
                    for (String savedId : savedIds) {
                        if (!savedId.equals(state.containerId)) {
                            throw new IOException("Conflicting serialized jar identity");
                        }
                    }
                } finally {
                    ThreadUtils.postOnUiThread(state.contentsState::destroy);
                }
            }
            if (!Tab.isContainerIdWellFormed(state.containerId)) {
                throw new IOException("Malformed persisted container identity");
            }
            String indexed = indexedIds.get(info.first);
            if (indexed != null && !indexed.equals(state.containerId)) {
                throw new IOException("Conflicting persisted container identity");
            }
            if (!state.containerId.isEmpty()) ids.add(state.containerId);
        }
    }

    @NativeMethods
    interface Natives {
        void loadDatabaseContainerIds(
                @JniType("Profile*") Profile profile,
                Callback<String @Nullable []> callback);
    }
}
