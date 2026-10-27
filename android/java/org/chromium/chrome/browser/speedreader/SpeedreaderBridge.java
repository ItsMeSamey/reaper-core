// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.

package org.chromium.chrome.browser.speedreader;

import androidx.annotation.IntDef;

import org.jni_zero.JNINamespace;
import org.jni_zero.JniType;
import org.jni_zero.NativeMethods;

import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content_public.browser.WebContents;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Android entrypoints for Brave's existing Speedreader engine. */
@NullMarked
@JNINamespace("speedreader")
public final class SpeedreaderBridge {
    @IntDef({State.UNAVAILABLE, State.ORIGINAL, State.BUSY, State.READER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {
        int UNAVAILABLE = 0;
        int ORIGINAL = 1;
        int BUSY = 2;
        int READER = 3;
    }

    private SpeedreaderBridge() {}

    private static @Nullable WebContents getContents(@Nullable Tab tab) {
        if (tab == null || tab.isDestroyed()) return null;
        WebContents contents = tab.getWebContents();
        return contents == null || contents.isDestroyed() ? null : contents;
    }

    public static @State int getState(@Nullable Tab tab) {
        ThreadUtils.assertOnUiThread();
        WebContents contents = getContents(tab);
        return contents == null
                ? State.UNAVAILABLE
                : SpeedreaderBridgeJni.get().getState(contents);
    }

    /** Re-check the current tab and native document state when the menu is selected. */
    public static boolean toggle(@Nullable Tab tab) {
        ThreadUtils.assertOnUiThread();
        WebContents contents = getContents(tab);
        if (contents == null) return false;
        int state = SpeedreaderBridgeJni.get().getState(contents);
        if (state != State.ORIGINAL && state != State.READER) return false;
        return SpeedreaderBridgeJni.get().toggle(contents);
    }

    public static boolean isAvailable(Profile profile) {
        ThreadUtils.assertOnUiThread();
        return SpeedreaderBridgeJni.get().isAvailable(profile);
    }

    public static boolean isEnabled(Profile profile) {
        ThreadUtils.assertOnUiThread();
        return SpeedreaderBridgeJni.get().isEnabled(profile);
    }

    public static boolean isAutomaticReadingManaged(Profile profile) {
        ThreadUtils.assertOnUiThread();
        return SpeedreaderBridgeJni.get().isAutomaticReadingManaged(profile);
    }

    public static boolean getAutomaticReading(Profile profile) {
        ThreadUtils.assertOnUiThread();
        return SpeedreaderBridgeJni.get().getAutomaticReading(profile);
    }

    public static boolean setAutomaticReading(Profile profile, boolean enabled) {
        ThreadUtils.assertOnUiThread();
        return SpeedreaderBridgeJni.get().setAutomaticReading(profile, enabled);
    }

    @NativeMethods
    public interface Natives {
        @State int getState(@JniType("content::WebContents*") WebContents contents);
        boolean toggle(@JniType("content::WebContents*") WebContents contents);
        boolean isAvailable(@JniType("Profile*") Profile profile);
        boolean isEnabled(@JniType("Profile*") Profile profile);
        boolean isAutomaticReadingManaged(@JniType("Profile*") Profile profile);
        boolean getAutomaticReading(@JniType("Profile*") Profile profile);
        boolean setAutomaticReading(@JniType("Profile*") Profile profile, boolean enabled);
    }
}
