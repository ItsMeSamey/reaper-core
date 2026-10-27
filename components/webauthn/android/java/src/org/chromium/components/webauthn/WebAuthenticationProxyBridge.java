// Copyright 2026 The Reaper Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.components.webauthn;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;
import org.jni_zero.NativeMethods;

import org.chromium.base.Callback;
import org.chromium.blink.mojom.GetAssertionAuthenticatorResponse;
import org.chromium.blink.mojom.GetAssertionResponse;
import org.chromium.blink.mojom.GetCredentialOptions;
import org.chromium.blink.mojom.GetCredentialResponse;
import org.chromium.blink.mojom.MakeCredentialAuthenticatorResponse;
import org.chromium.blink.mojom.PublicKeyCredentialCreationOptions;
import org.chromium.blink.mojom.WebAuthnDomExceptionDetails;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.content_public.browser.RenderFrameHost;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Document-scoped adapter for the browser's existing WebAuthn request proxy. */
@JNINamespace("webauthn")
@NullMarked
final class WebAuthenticationProxyBridge {
    private long mNativeBridge;
    private long mNextRequestGuardId;
    private long mRequestGuardId;
    private @Nullable Runnable mOnRequestInvalidated;
    private @Nullable Callback<WebauthnRequestResponse> mPendingResponse;
    private final Map<Integer, Callback<Boolean>> mQueries = new HashMap<>();
    private int mNextQuery;
    private long mNextConfirmationId;
    private long mConfirmationId;
    private @Nullable Runnable mOnConfirmationCancelled;

    WebAuthenticationProxyBridge(RenderFrameHost frame) {
        mNativeBridge = WebAuthenticationProxyBridgeJni.get().create(this, frame);
    }

    boolean startRequestGuard(boolean conditional, Runnable onInvalidated) {
        endRequestGuard();
        if (mNativeBridge == 0) return false;
        mRequestGuardId = ++mNextRequestGuardId;
        if (mRequestGuardId == 0) mRequestGuardId = ++mNextRequestGuardId;
        mOnRequestInvalidated = onInvalidated;
        return WebAuthenticationProxyBridgeJni.get()
                .startRequestGuard(mNativeBridge, mRequestGuardId, conditional);
    }

    boolean isRequestValid() {
        return mNativeBridge != 0
                && mRequestGuardId != 0
                && WebAuthenticationProxyBridgeJni.get()
                        .isRequestValid(mNativeBridge, mRequestGuardId);
    }

    boolean beginRequestInteraction() {
        return mNativeBridge != 0
                && mRequestGuardId != 0
                && WebAuthenticationProxyBridgeJni.get()
                        .beginRequestInteraction(mNativeBridge, mRequestGuardId);
    }

    boolean canCompleteRequest() {
        return mNativeBridge != 0
                && mRequestGuardId != 0
                && WebAuthenticationProxyBridgeJni.get()
                        .canCompleteRequest(mNativeBridge, mRequestGuardId);
    }

    void endRequestGuard() {
        long id = mRequestGuardId;
        mRequestGuardId = 0;
        mOnRequestInvalidated = null;
        if (mNativeBridge != 0 && id != 0) {
            WebAuthenticationProxyBridgeJni.get().endRequestGuard(mNativeBridge, id);
        }
    }

    @CalledByNative
    void onRequestInvalidated(long id) {
        if (mNativeBridge == 0 || id == 0 || id != mRequestGuardId) return;
        Runnable callback = mOnRequestInvalidated;
        mOnRequestInvalidated = null;
        if (callback != null) callback.run();
    }

    boolean isActive() {
        return mNativeBridge != 0 && WebAuthenticationProxyBridgeJni.get().isActive(mNativeBridge);
    }

    void watchConfirmation(Runnable onCancelled) {
        mConfirmationId = ++mNextConfirmationId;
        mOnConfirmationCancelled = onCancelled;
        if (mNativeBridge != 0) {
            WebAuthenticationProxyBridgeJni.get().watchConfirmation(mNativeBridge, mConfirmationId);
        }
    }

    boolean finishConfirmation() {
        boolean valid =
                mNativeBridge != 0
                        && mConfirmationId != 0
                        && WebAuthenticationProxyBridgeJni.get()
                                .finishConfirmation(mNativeBridge, mConfirmationId);
        clearConfirmation();
        return valid;
    }

    void clearConfirmation() {
        mConfirmationId = 0;
        mOnConfirmationCancelled = null;
        if (mNativeBridge != 0) {
            WebAuthenticationProxyBridgeJni.get().watchConfirmation(mNativeBridge, 0);
        }
    }

    @CalledByNative
    void onConfirmationCancelled(long id) {
        if (mNativeBridge == 0 || id != mConfirmationId) return;
        Runnable callback = mOnConfirmationCancelled;
        mConfirmationId = 0;
        mOnConfirmationCancelled = null;
        if (callback != null) callback.run();
    }

    boolean makeCredential(
            PublicKeyCredentialCreationOptions options,
            Callback<WebauthnRequestResponse> callback) {
        if (mNativeBridge == 0) return false;
        assert mPendingResponse == null;
        mPendingResponse = callback;
        boolean accepted =
                WebAuthenticationProxyBridgeJni.get()
                        .makeCredential(mNativeBridge, options.serialize());
        if (!accepted) mPendingResponse = null;
        return accepted;
    }

    boolean getCredential(
            GetCredentialOptions options, Callback<WebauthnRequestResponse> callback) {
        if (mNativeBridge == 0 || options.publicKey == null) return false;
        assert mPendingResponse == null;
        mPendingResponse = callback;
        boolean accepted =
                WebAuthenticationProxyBridgeJni.get()
                        .getCredential(mNativeBridge, options.serialize());
        if (!accepted) mPendingResponse = null;
        return accepted;
    }

    boolean isUvpaa(Callback<Boolean> callback) {
        if (mNativeBridge == 0) return false;
        // Query identifiers must not alias a still-pending request after wraparound.
        int id;
        do {
            id = ++mNextQuery;
        } while (mQueries.containsKey(id));
        mQueries.put(id, callback);
        if (!WebAuthenticationProxyBridgeJni.get().isUvpaa(mNativeBridge, id)) {
            mQueries.remove(id);
            return false;
        }
        return true;
    }

    boolean cancel() {
        if (mNativeBridge == 0 || mPendingResponse == null) return false;
        WebAuthenticationProxyBridgeJni.get().cancel(mNativeBridge);
        return true;
    }

    void destroy() {
        endRequestGuard();
        long nativeBridge = mNativeBridge;
        mNativeBridge = 0;
        mPendingResponse = null;
        mOnConfirmationCancelled = null;
        mConfirmationId = 0;
        mQueries.clear();
        if (nativeBridge != 0) WebAuthenticationProxyBridgeJni.get().destroy(nativeBridge);
    }

    @CalledByNative
    void onMakeResponse(int status, byte @Nullable [] response, byte @Nullable [] exception) {
        if (mNativeBridge == 0) return;
        Callback<WebauthnRequestResponse> callback = mPendingResponse;
        mPendingResponse = null;
        if (callback == null) return;
        callback.onResult(
                WebauthnRequestResponse.forProxyMakeCredential(
                        status,
                        response == null
                                ? null
                                : MakeCredentialAuthenticatorResponse.deserialize(
                                        ByteBuffer.wrap(response)),
                        exception == null
                                ? null
                                : WebAuthnDomExceptionDetails.deserialize(
                                        ByteBuffer.wrap(exception))));
    }

    @CalledByNative
    void onGetResponse(int status, byte @Nullable [] response, byte @Nullable [] exception) {
        if (mNativeBridge == 0) return;
        Callback<WebauthnRequestResponse> callback = mPendingResponse;
        mPendingResponse = null;
        if (callback == null) return;
        GetAssertionResponse assertion = new GetAssertionResponse();
        assertion.status = status;
        assertion.credential =
                response == null
                        ? null
                        : GetAssertionAuthenticatorResponse.deserialize(ByteBuffer.wrap(response));
        assertion.domExceptionDetails =
                exception == null
                        ? null
                        : WebAuthnDomExceptionDetails.deserialize(ByteBuffer.wrap(exception));
        GetCredentialResponse result = new GetCredentialResponse();
        result.setGetAssertionResponse(assertion);
        callback.onResult(
                WebauthnRequestResponse.forSuccessfulGetCredential(
                        result, new RequestMetrics.Builder().build()));
    }

    @CalledByNative
    void onUvpaaResponse(int id, boolean available) {
        if (mNativeBridge == 0) return;
        Callback<Boolean> callback = mQueries.remove(id);
        if (callback != null) callback.onResult(available);
    }

    @NativeMethods
    interface Natives {
        long create(WebAuthenticationProxyBridge caller, RenderFrameHost frame);

        boolean isActive(long pointer);

        boolean startRequestGuard(long pointer, long id, boolean conditional);

        boolean isRequestValid(long pointer, long id);

        boolean beginRequestInteraction(long pointer, long id);

        boolean canCompleteRequest(long pointer, long id);

        void endRequestGuard(long pointer, long id);

        void watchConfirmation(long pointer, long confirmationId);

        boolean finishConfirmation(long pointer, long confirmationId);

        boolean makeCredential(long pointer, ByteBuffer options);

        boolean getCredential(long pointer, ByteBuffer options);

        boolean isUvpaa(long pointer, int queryId);

        void cancel(long pointer);

        void destroy(long pointer);
    }
}
