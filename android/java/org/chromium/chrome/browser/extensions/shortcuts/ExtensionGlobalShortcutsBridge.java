// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package org.chromium.chrome.browser.extensions.shortcuts;

import android.view.KeyEvent;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;
import org.jni_zero.JniType;
import org.jni_zero.NativeMethods;

import org.chromium.build.annotations.NullMarked;
import org.chromium.chrome.browser.profiles.Profile;

/** A request-free native registration broker; no hardware events are queued. */
@JNINamespace("extensions")
@NullMarked
final class ExtensionGlobalShortcutsBridge {
    static final int MODIFIER_MASK =
            KeyEvent.META_CTRL_ON
                    | KeyEvent.META_ALT_ON
                    | KeyEvent.META_SHIFT_ON
                    | KeyEvent.META_META_ON
                    | KeyEvent.META_FUNCTION_ON;

    private final ConsumedShortcutKeys mKeys;
    private final Runnable mOnUnavailable;
    private long mNativeBridge;
    private boolean mRetired;

    ExtensionGlobalShortcutsBridge(
            Profile profile, ConsumedShortcutKeys keys, Runnable onUnavailable) {
        mKeys = keys;
        mOnUnavailable = onUnavailable;
        // Registration callbacks may arrive during initialization or on a later
        // UI turn. The service publishes readiness only after this constructor.
        long handle = ExtensionGlobalShortcutsBridgeJni.get().init(profile, this);
        if (mRetired) {
            if (handle != 0) ExtensionGlobalShortcutsBridgeJni.get().destroy(handle);
            return;
        }
        mNativeBridge = handle;
        if (handle == 0) destroy();
    }

    boolean isReady() {
        return !mRetired && mNativeBridge != 0;
    }

    boolean dispatchKeyDown(KeyEvent event) {
        long handle = mNativeBridge;
        return !mRetired
                && handle != 0
                && ExtensionGlobalShortcutsBridgeJni.get().onKeyDown(handle, event);
    }

    void destroy() {
        if (mRetired) return;
        mRetired = true;
        mKeys.clearRegistrations();
        long handle = mNativeBridge;
        mNativeBridge = 0;
        if (handle != 0) ExtensionGlobalShortcutsBridgeJni.get().destroy(handle);
    }

    @CalledByNative
    boolean setRegistration(int androidKeyCode, int androidMetaState, boolean registered) {
        if (mRetired
                || androidKeyCode <= KeyEvent.KEYCODE_UNKNOWN
                || androidKeyCode > KeyEvent.getMaxKeyCode()
                || androidMetaState != normalizeModifiers(androidMetaState)) {
            return false;
        }
        return mKeys.setRegistration(androidKeyCode, androidMetaState, registered);
    }

    @CalledByNative
    void onNativeUnavailable() {
        if (mRetired) return;
        destroy();
        // The service can synchronously release this bridge. No work follows.
        mOnUnavailable.run();
    }

    static int normalizeModifiers(int modifiers) {
        return KeyEvent.normalizeMetaState(modifiers) & MODIFIER_MASK;
    }

    @NativeMethods
    interface Natives {
        long init(@JniType("Profile*") Profile profile, ExtensionGlobalShortcutsBridge caller);

        void destroy(long bridge);

        boolean onKeyDown(long bridge, @JniType("ui::KeyEventAndroid") KeyEvent event);
    }
}
