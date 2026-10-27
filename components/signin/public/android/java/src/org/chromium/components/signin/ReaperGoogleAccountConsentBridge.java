/* Copyright (c) 2026 The Reaper Authors. All rights reserved.
 * Use of this source code is governed by the Mozilla Public License, v. 2.0. */

package org.chromium.components.signin;

import android.app.Activity;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;
import org.jni_zero.JniType;
import org.jni_zero.NativeMethods;

import org.chromium.base.ActivityState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.components.signin.base.CoreAccountInfo;
import org.chromium.ui.base.WindowAndroid;

/** SDK recovery owned exclusively by an admitted interactive extension identity request. */
@NullMarked
@JNINamespace("extensions")
public final class ReaperGoogleAccountConsentBridge
        implements ApplicationStatus.ActivityStateListener {
    private long mNativeIdentityGetAuthTokenFunction;
    private final ReaperGoogleAccountConsentRequest mRequest;
    private boolean mObservingActivity;

    private ReaperGoogleAccountConsentBridge(
            long nativeIdentityGetAuthTokenFunction,
            CoreAccountInfo approvedAccount,
            String existingBrowserOAuth2Scope,
            WindowAndroid window,
            Activity activity) {
        mNativeIdentityGetAuthTokenFunction = nativeIdentityGetAuthTokenFunction;
        mRequest = new ReaperGoogleAccountConsentRequest(
                approvedAccount, existingBrowserOAuth2Scope, true, window, this::onResult);
        ApplicationStatus.registerStateListenerForActivity(this, activity);
        mObservingActivity = true;
    }

    @CalledByNative
    private static @Nullable ReaperGoogleAccountConsentBridge create(
            long nativeIdentityGetAuthTokenFunction,
            @JniType("CoreAccountInfo") CoreAccountInfo approvedAccount,
            @JniType("std::string") String existingBrowserOAuth2Scope,
            @JniType("ui::WindowAndroid*") WindowAndroid window) {
        ThreadUtils.assertOnUiThread();
        @Nullable Activity activity = window.getActivity().get();
        if (nativeIdentityGetAuthTokenFunction == 0
                || activity == null
                || activity.isFinishing()
                || activity.isDestroyed()
                || window.getIntentRequestTracker() == null
                || !ApplicationStatus.isInitialized()
                || ApplicationStatus.getStateForActivity(activity) == ActivityState.DESTROYED) {
            return null;
        }
        return new ReaperGoogleAccountConsentBridge(
                nativeIdentityGetAuthTokenFunction,
                approvedAccount,
                existingBrowserOAuth2Scope,
                window,
                activity);
    }

    @CalledByNative
    private void start() {
        ThreadUtils.assertOnUiThread();
        if (mNativeIdentityGetAuthTokenFunction != 0) mRequest.start();
    }

    private void onResult(@Nullable AccessTokenData token, @Nullable AuthException error) {
        ThreadUtils.assertOnUiThread();
        if (mNativeIdentityGetAuthTokenFunction == 0) return;
        long nativeOwner = mNativeIdentityGetAuthTokenFunction;
        // Retire before JNI, since completion can destroy the native owner synchronously.
        mNativeIdentityGetAuthTokenFunction = 0;
        detachActivityObserver();
        ReaperGoogleAccountConsentBridgeJni.get().onAndroidAccountConsentResult(
                nativeOwner, token != null && error == null ? token.getToken() : "");
    }

    private void detachActivityObserver() {
        if (!mObservingActivity) return;
        mObservingActivity = false;
        ApplicationStatus.unregisterActivityStateListener(this);
    }

    @CalledByNative
    private void cancel() {
        ThreadUtils.assertOnUiThread();
        mNativeIdentityGetAuthTokenFunction = 0;
        detachActivityObserver();
        mRequest.cancel();
    }

    @Override
    public void onActivityStateChange(Activity activity, @ActivityState int newState) {
        ThreadUtils.assertOnUiThread();
        if (newState != ActivityState.DESTROYED
                || mNativeIdentityGetAuthTokenFunction == 0) return;
        long nativeOwner = mNativeIdentityGetAuthTokenFunction;
        cancel();
        ReaperGoogleAccountConsentBridgeJni.get().onAndroidAccountConsentResult(nativeOwner, "");
    }

    @NativeMethods
    interface Natives {
        void onAndroidAccountConsentResult(
                long nativeIdentityGetAuthTokenFunction,
                @JniType("std::string") String browserLoginToken);
    }
}
