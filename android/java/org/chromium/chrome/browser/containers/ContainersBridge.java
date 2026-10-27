// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.containers;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;
import org.jni_zero.JniType;
import org.jni_zero.NativeMethods;

import org.chromium.base.Callback;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content.browser.webcontents.WebContentsImpl;
import org.chromium.content_public.browser.WebContents;

import java.util.ArrayList;
import java.util.List;

/** Android access to the existing profile container service. */
@JNINamespace("containers::android")
@NullMarked
public final class ContainersBridge {
    public static final int SUCCESS = 0;
    public static final int UNAVAILABLE = -1;

    // Keep aligned with containers/core/browser/pref_names.h.
    public static final String PREF_CONTAINERS_ENABLED = "brave.containers.enabled";
    public static final String PREF_CONTAINERS_LIST = "brave.containers.list";
    public static final String PREF_CONTAINERS_USED = "brave.containers.used";

    /** Stable service metadata; an empty ID is represented separately as Default. */
    public static final class ContainerInfo {
        public final String id;
        public final String name;
        public final int icon;
        public final int backgroundColor;

        private ContainerInfo(String id, String name, int icon, int backgroundColor) {
            this.id = id;
            this.name = name;
            this.icon = icon;
            this.backgroundColor = backgroundColor;
        }
    }

    private ContainersBridge() {}

    /** Pure validation for tab-state parsers, including parsers before native startup. */
    public static boolean isContainerIdWellFormed(@Nullable String id) {
        return Tab.isContainerIdWellFormed(id);
    }

    /** Whether regular-profile container controls should be displayed. */
    public static boolean isEnabled(Profile profile) {
        ThreadUtils.assertOnUiThread();
        return ContainersBridgeJni.get().isEnabled(profile);
    }

    /** Hiding the controls preserves existing tabs and their isolated storage. */
    public static boolean setEnabled(Profile profile, boolean enabled) {
        ThreadUtils.assertOnUiThread();
        return ContainersBridgeJni.get().setEnabled(profile, enabled);
    }

    public static boolean isContainerIdValid(Profile profile, @Nullable String id) {
        if (id == null || !isContainerIdWellFormed(id)) return false;
        if (id.isEmpty()) return true;
        ThreadUtils.assertOnUiThread();
        return ContainersBridgeJni.get().isContainerIdValid(profile, id);
    }

    /** Only persisted metadata and decoded Sync restore paths may call this method. */
    public static boolean restoreContainerIdentity(Profile profile, @Nullable String id) {
        if (id == null || !isContainerIdWellFormed(id)) return false;
        if (id.isEmpty()) return true;
        ThreadUtils.assertOnUiThread();
        return ContainersBridgeJni.get().restoreContainerIdentity(profile, id);
    }

    public static String getContainerId(@Nullable WebContents contents) {
        if (!(contents instanceof WebContentsImpl) || contents.isDestroyed()) return "";
        ThreadUtils.assertOnUiThread();
        return ContainersBridgeJni.get().getContainerId(contents);
    }

    /** Proves actual native partition ownership before adopting or using a WebContents. */
    public static boolean isWebContentsInContainer(
            Profile profile, @Nullable WebContents contents, @Nullable String id) {
        if (id == null || !isContainerIdWellFormed(id)
                || !(contents instanceof WebContentsImpl)
                || contents.isDestroyed()) return false;
        ThreadUtils.assertOnUiThread();
        return ContainersBridgeJni.get().isWebContentsInContainer(profile, contents, id);
    }

    public static List<ContainerInfo> getContainers(Profile profile) {
        ThreadUtils.assertOnUiThread();
        List<ContainerInfo> result = new ArrayList<>();
        ContainersBridgeJni.get().getContainers(profile, result);
        return result;
    }

    /** Configured or removed-but-used metadata, including locally used temporary IDs. */
    public static @Nullable ContainerInfo getContainerInfo(Profile profile, @Nullable String id) {
        if (id == null || id.isEmpty() || !isContainerIdWellFormed(id)) return null;
        ThreadUtils.assertOnUiThread();
        return ContainersBridgeJni.get().getContainerInfo(profile, id);
    }

    /** Errors use the existing ContainersSettingsHandler operation-error values. */
    public static void addContainer(
            Profile profile, String name, int icon, int color, Callback<Integer> callback) {
        ThreadUtils.assertOnUiThread();
        ContainersBridgeJni.get().addContainer(profile, name, icon, color, callback);
    }

    public static void updateContainer(Profile profile, String id, String name, int icon,
            int color, Callback<Integer> callback) {
        ThreadUtils.assertOnUiThread();
        ContainersBridgeJni.get().updateContainer(profile, id, name, icon, color, callback);
    }

    /** Removes the selector entry. Referenced storage remains owned by the service. */
    public static void removeContainer(Profile profile, String id, Callback<Integer> callback) {
        ThreadUtils.assertOnUiThread();
        ContainersBridgeJni.get().removeContainer(profile, id, callback);
    }

    public static void reorderContainers(
            Profile profile, String[] ids, Callback<Integer> callback) {
        ThreadUtils.assertOnUiThread();
        ContainersBridgeJni.get().reorderContainers(profile, ids, callback);
    }

    public static void invalidatePersistentContainerReferences(Profile profile) {
        ThreadUtils.assertOnUiThread();
        ContainersBridgeJni.get().invalidatePersistentContainerReferences(profile);
    }

    public static long getPersistentContainerReferencesGeneration(Profile profile) {
        ThreadUtils.assertOnUiThread();
        return ContainersBridgeJni.get().getPersistentContainerReferencesGeneration(profile);
    }

    /** Only a complete scan of the still-current native generation may publish. */
    public static boolean setPersistentContainerReferences(
            Profile profile, String[] ids, long expectedGeneration) {
        ThreadUtils.assertOnUiThread();
        for (String id : ids) {
            if (!isContainerIdWellFormed(id)) {
                invalidatePersistentContainerReferences(profile);
                return false;
            }
        }
        return ContainersBridgeJni.get().setPersistentContainerReferences(
                profile, ids, expectedGeneration);
    }

    @CalledByNative
    private static void appendContainer(List<ContainerInfo> output,
            @JniType("std::string") String id, @JniType("std::string") String name,
            int icon, int backgroundColor) {
        output.add(new ContainerInfo(id, name, icon, backgroundColor));
    }

    @CalledByNative
    private static ContainerInfo createContainerInfo(
            @JniType("std::string") String id, @JniType("std::string") String name,
            int icon, int backgroundColor) {
        return new ContainerInfo(id, name, icon, backgroundColor);
    }

    @NativeMethods
    interface Natives {
        boolean isEnabled(@JniType("Profile*") Profile profile);
        boolean setEnabled(@JniType("Profile*") Profile profile, boolean enabled);
        boolean isContainerIdValid(@JniType("Profile*") Profile profile,
                @JniType("std::string") String id);
        boolean restoreContainerIdentity(@JniType("Profile*") Profile profile,
                @JniType("std::string") String id);
        @JniType("std::string")
        String getContainerId(@JniType("content::WebContents*") WebContents contents);
        boolean isWebContentsInContainer(@JniType("Profile*") Profile profile,
                @JniType("content::WebContents*") WebContents contents,
                @JniType("std::string") String id);
        void getContainers(@JniType("Profile*") Profile profile, List<ContainerInfo> output);
        @Nullable ContainerInfo getContainerInfo(@JniType("Profile*") Profile profile,
                @JniType("std::string") String id);
        void addContainer(@JniType("Profile*") Profile profile,
                @JniType("std::string") String name, int icon, int color,
                Callback<Integer> callback);
        void updateContainer(@JniType("Profile*") Profile profile,
                @JniType("std::string") String id, @JniType("std::string") String name,
                int icon, int color, Callback<Integer> callback);
        void removeContainer(@JniType("Profile*") Profile profile,
                @JniType("std::string") String id, Callback<Integer> callback);
        void reorderContainers(@JniType("Profile*") Profile profile,
                @JniType("std::vector<std::string>") String[] ids, Callback<Integer> callback);
        void invalidatePersistentContainerReferences(@JniType("Profile*") Profile profile);
        long getPersistentContainerReferencesGeneration(@JniType("Profile*") Profile profile);
        boolean setPersistentContainerReferences(@JniType("Profile*") Profile profile,
                @JniType("std::vector<std::string>") String[] ids, long expectedGeneration);
    }
}
