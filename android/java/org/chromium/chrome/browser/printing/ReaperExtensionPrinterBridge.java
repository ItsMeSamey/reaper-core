// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.

package org.chromium.chrome.browser.printing;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;
import org.jni_zero.NativeMethods;

import org.chromium.base.ThreadUtils;
import org.chromium.chrome.browser.profiles.Profile;

/** UI-thread bridge to Chromium's regular-profile printerProvider dispatch. */
@JNINamespace("extensions")
public final class ReaperExtensionPrinterBridge {
    public interface Listener {
        void onPrinters(int requestId, String printersJson, boolean done);
        void onCapabilities(int requestId, String capabilitiesJson);
        void onPrintResult(int requestId, String error);
    }

    private long mNativePtr;
    private final Listener mListener;

    public ReaperExtensionPrinterBridge(Profile profile, Listener listener) {
        ThreadUtils.assertOnUiThread();
        if (profile.isOffTheRecord() || profile.shutdownStarted()) {
            throw new IllegalArgumentException("Printing requires an active regular profile");
        }
        mListener = listener;
        mNativePtr = ReaperExtensionPrinterBridgeJni.get().init(this, profile);
        if (mNativePtr == 0) throw new IllegalStateException("Printer provider initialization failed");
    }

    public void getPrinters(int requestId) {
        requireOpen();
        ReaperExtensionPrinterBridgeJni.get().getPrinters(mNativePtr, requestId);
    }

    public void getCapabilities(int requestId, String printerId) {
        requireOpen();
        ReaperExtensionPrinterBridgeJni.get().getCapabilities(mNativePtr, requestId, printerId);
    }

    /** Native duplicates this borrowed PDF descriptor before returning. */
    public void print(int requestId, String printerId, String title, String ticketJson, int pageWidthMils, int pageHeightMils, int borrowedFd) {
        requireOpen();
        ReaperExtensionPrinterBridgeJni.get()
                .print(mNativePtr, requestId, printerId, title, ticketJson, pageWidthMils, pageHeightMils, borrowedFd);
    }

    public void cancel(int requestId) {
        ThreadUtils.assertOnUiThread();
        if (mNativePtr != 0) ReaperExtensionPrinterBridgeJni.get().cancel(mNativePtr, requestId);
    }

    public void destroy() {
        ThreadUtils.assertOnUiThread();
        long pointer = mNativePtr;
        mNativePtr = 0;
        if (pointer != 0) ReaperExtensionPrinterBridgeJni.get().destroy(pointer);
    }

    private void requireOpen() {
        ThreadUtils.assertOnUiThread();
        if (mNativePtr == 0) throw new IllegalStateException("Printer provider has closed");
    }

    @CalledByNative
    void onPrinters(int requestId, String printersJson, boolean done) {
        if (mNativePtr != 0) mListener.onPrinters(requestId, printersJson, done);
    }

    @CalledByNative
    void onCapabilities(int requestId, String capabilitiesJson) {
        if (mNativePtr != 0) mListener.onCapabilities(requestId, capabilitiesJson);
    }

    @CalledByNative
    void onPrintResult(int requestId, String error) {
        if (mNativePtr != 0) mListener.onPrintResult(requestId, error);
    }

    @NativeMethods
    interface Natives {
        long init(ReaperExtensionPrinterBridge caller, Profile profile);
        void destroy(long bridge);
        void getPrinters(long bridge, int requestId);
        void getCapabilities(long bridge, int requestId, String printerId);
        void print(long bridge, int requestId, String printerId, String title, String ticketJson, int pageWidthMils, int pageHeightMils, int borrowedFd);
        void cancel(long bridge, int requestId);
    }
}
