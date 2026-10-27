// Copyright 2026 The Reaper Authors.
// SPDX-License-Identifier: MPL-2.0

package org.chromium.chrome.browser.extensions;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.Toast;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;
import org.jni_zero.JniType;
import org.jni_zero.NativeMethods;

import org.chromium.base.ContextUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.ui.extensions.R;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.WindowAndroid;

import java.lang.ref.WeakReference;

/** Hands only a matching, explicitly approved picker operation to the native import path. */
@NullMarked
@JNINamespace("extensions")
public final class ReaperExtensionImportCoordinator {
    static final int DEFERRED = 0;
    static final int STARTED = 1;
    static final int FINISHED = 2;
    private static @Nullable String sInFlight;
    private static @Nullable WeakReference<AlertDialog> sConfirmationDialog;

    private ReaperExtensionImportCoordinator() {}

    @CalledByNative
    public static boolean start(WindowAndroid window, WebContents source,
            @JniType("std::string") String requestId,
            @JniType("std::string") String profileBaseName, long profileCreationTimeMicros,
            int tabId, boolean requireCallerCheck) {
        ThreadUtils.assertOnUiThread();
        Activity activity = window.getActivity().get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        ReaperExtensionImportStore store = store();
        ReaperExtensionImportStore.Request existing = store.read();
        if (existing != null || sInFlight != null) {
            if (existing != null && sInFlight == null && !hasActiveConfirmation()) {
                String id = existing.id;
                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle(R.string.reaper_extension_import_pending_title)
                        .setMessage(R.string.reaper_extension_import_pending_message)
                        .setNegativeButton(R.string.reaper_extension_import_keep_waiting, null)
                        .setPositiveButton(R.string.reaper_extension_import_cancel_pending,
                                (ignored, which) -> discardPending(source, tabId, id))
                        .create();
                bindConfirmation(dialog, activity);
                dialog.show();
            } else {
                Toast.makeText(activity, R.string.reaper_extension_import_busy,
                        Toast.LENGTH_LONG).show();
            }
            return false;
        }
        ReaperExtensionImportStore.Request request = ReaperExtensionImportStore.create(
                requestId, profileBaseName, profileCreationTimeMicros, tabId, requireCallerCheck);
        if (request == null) return false;
        if (!store.write(request)) {
            store.clear(request.id);
            showStorageError();
            return false;
        }
        Intent intent = new Intent(activity, ReaperExtensionImportActivity.class)
                .putExtra(ReaperExtensionImportActivity.EXTRA_REQUEST, request.id)
                .putExtra(ReaperExtensionImportActivity.EXTRA_NONCE, request.nonce);
        try {
            activity.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            store.clear(request.id);
            return false;
        }
    }

    /** Called after native/profile readiness and on restored-tab availability or normal resume. */
    public static void maybeResume(WebContents source, int tabId) {
        ThreadUtils.assertOnUiThread();
        if (source.isDestroyed() || sInFlight != null) return;
        ReaperExtensionImportStore store = store();
        ReaperExtensionImportStore.Request request = store.read();
        if (request == null || request.tabId != tabId
                || request.state == ReaperExtensionImportStore.PREPARED
                || request.state == ReaperExtensionImportStore.WAITING) {
            return;
        }
        int state = request.state;
        if (state == ReaperExtensionImportStore.APPROVED
                && !ReaperExtensionImportStore.PROCESS_TOKEN.equals(request.approvalProcess)) {
            offerContinuation(source, tabId, request);
            return;
        }
        if (state == ReaperExtensionImportStore.APPROVED) {
            if (!store.transition(request.id, request.nonce, state,
                        ReaperExtensionImportStore.DISPATCHING, request.uri)) {
                showStorageError();
                // Failed writes are exposed as FAILED, never as an approval in this process.
                request = store.read();
                if (request == null || request.state != ReaperExtensionImportStore.FAILED) return;
                state = ReaperExtensionImportStore.FAILED;
            }
        } else if (state == ReaperExtensionImportStore.DISPATCHING) {
            // A prior process may have reached native loading. Never silently repeat it.
            state = ReaperExtensionImportStore.INTERRUPTED;
        }
        sInFlight = request.id;
        int status = ReaperExtensionImportCoordinatorJni.get().resume(
                source, tabId, request.id, request.profileBaseName,
                request.profileCreationTimeMicros, state,
                state == ReaperExtensionImportStore.APPROVED && request.uri != null
                        ? request.uri : "");
        // Native may complete synchronously; never revive a cleared/replaced request.
        if (!request.id.equals(sInFlight)) return;
        if (status == STARTED) return;
        sInFlight = null;
        if (status == DEFERRED) {
            if (state == ReaperExtensionImportStore.APPROVED
                    && !store.transition(request.id, request.nonce,
                            ReaperExtensionImportStore.DISPATCHING,
                            ReaperExtensionImportStore.APPROVED, request.uri)) {
                showStorageError();
            }
        } else if (status == FINISHED) {
            if (!store.clear(request.id)) showStorageError();
        } else {
            // Unknown native status cannot make the approved operation replayable.
            showStorageError();
        }
    }

    private static void offerContinuation(WebContents source, int tabId,
            ReaperExtensionImportStore.Request request) {
        if (hasActiveConfirmation()) return;
        var url = source.getLastCommittedUrl();
        if (url == null || !"chrome".equals(url.getScheme())
                || !"extensions".equals(url.getHost())) return;
        WindowAndroid window = source.getTopLevelNativeWindow();
        Activity activity = window == null ? null : window.getActivity().get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        String id = request.id;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.reaper_extension_import_continue_title)
                .setMessage(R.string.reaper_extension_import_continue_message)
                .setNegativeButton(R.string.reaper_extension_import_cancel,
                        (ignored, which) -> discardPending(source, tabId, id))
                .setPositiveButton(R.string.reaper_extension_import_continue,
                        (ignored, which) -> {
                            if (!store().confirmForCurrentProcess(id, request.nonce)) {
                                showStorageError();
                                return;
                            }
                            // Confirmation does not replace native profile/policy validation.
                            maybeResume(source, tabId);
                        })
                .create();
        bindConfirmation(dialog, activity);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    static boolean hasActiveConfirmation() {
        AlertDialog dialog = sConfirmationDialog == null ? null : sConfirmationDialog.get();
        Activity owner = dialog == null ? null : dialog.getOwnerActivity();
        if (dialog == null || !dialog.isShowing() || owner == null
                || owner.isDestroyed() || owner.isFinishing()) {
            sConfirmationDialog = null;
            return false;
        }
        return true;
    }

    static void bindConfirmation(AlertDialog dialog, Activity owner) {
        dialog.setOwnerActivity(owner);
        sConfirmationDialog = new WeakReference<>(dialog);
        dialog.setOnDismissListener(ignored -> {
            // A stale dismiss callback from the old Activity must not clear a replacement dialog.
            if (sConfirmationDialog != null && sConfirmationDialog.get() == dialog) {
                sConfirmationDialog = null;
            }
        });
    }

    /** Explicit cancellation is safe from another Extensions tab; it never installs anything. */
    static boolean discardPending(WebContents source, int currentTabId, String requestId) {
        ThreadUtils.assertOnUiThread();
        if (source.isDestroyed() || sInFlight != null) return false;
        ReaperExtensionImportStore store = store();
        ReaperExtensionImportStore.Request request = store.read();
        if (request == null || !request.id.equals(requestId)) return false;
        if (!store.transition(request.id, request.nonce, request.state,
                    ReaperExtensionImportStore.CANCELLED, null)) {
            showStorageError();
            return false;
        }
        // Native checks this current trusted UI, then only retires the matching old callback.
        // The CANCELLED fence reaches disk before native retirement/removal of the receipt.
        boolean discarded = ReaperExtensionImportCoordinatorJni.get().discard(
                source, currentTabId, requestId);
        if (!discarded) {
            Toast.makeText(ContextUtils.getApplicationContext(),
                    R.string.reaper_extension_import_cancel_error, Toast.LENGTH_LONG).show();
        }
        return discarded;
    }

    @CalledByNative
    public static void onNativeComplete(@JniType("std::string") String requestId,
            @JniType("std::string") String error) {
        ThreadUtils.assertOnUiThread();
        ReaperExtensionImportStore store = store();
        ReaperExtensionImportStore.Request request = store.read();
        if (request == null || !request.id.equals(requestId)) return;
        if (requestId.equals(sInFlight)) sInFlight = null;
        if (!store.clear(requestId)) showStorageError();
        if (!error.isEmpty()) {
            Toast.makeText(ContextUtils.getApplicationContext(), localizeError(error),
                    Toast.LENGTH_LONG).show();
        }
    }

    @CalledByNative
    @JniType("std::string")
    static String localizeError(@JniType("std::string") String error) {
        int resource = switch (error) {
            case "reaper.import.source_changed" ->
                    R.string.reaper_extension_import_source_changed;
            case "reaper.import.interrupted" -> R.string.reaper_extension_import_interrupted;
            case "reaper.import.folder_unavailable" ->
                    R.string.reaper_extension_import_folder_unavailable;
            case "reaper.import.file_policy_blocked" ->
                    R.string.reaper_extension_import_file_policy_blocked;
            default -> 0;
        };
        return resource == 0 ? error : ContextUtils.getApplicationContext().getString(resource);
    }

    static void showStorageError() {
        Toast.makeText(ContextUtils.getApplicationContext(),
                R.string.reaper_extension_import_storage_error, Toast.LENGTH_LONG).show();
    }

    private static ReaperExtensionImportStore store() {
        return new ReaperExtensionImportStore(ContextUtils.getApplicationContext());
    }

    @NativeMethods
    interface Natives {
        boolean discard(@JniType("content::WebContents*") WebContents source, int currentTabId,
                @JniType("std::string") String requestId);
        int resume(@JniType("content::WebContents*") WebContents source, int tabId,
                @JniType("std::string") String requestId,
                @JniType("std::string") String profileBaseName, long profileCreationTimeMicros,
                int resultState, @JniType("std::string") String uri);
    }
}
