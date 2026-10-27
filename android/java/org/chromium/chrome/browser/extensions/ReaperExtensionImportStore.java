// Copyright 2026 The Reaper Authors.
// SPDX-License-Identifier: MPL-2.0

package org.chromium.chrome.browser.extensions;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.StrictModeContext;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;

import java.util.UUID;

/** Small durable receipt for one explicitly requested extension folder import. */
@NullMarked
final class ReaperExtensionImportStore {
    static final int PREPARED = 0;
    static final int WAITING = 1;
    static final int APPROVED = 2;
    static final int CANCELLED = 3;
    static final int FAILED = 4;
    static final int INTERRUPTED = 5;
    static final int DISPATCHING = 6;
    private static final int SCHEMA = 1;
    static final String PROCESS_TOKEN = UUID.randomUUID().toString();
    private static final String PREFS = "reaper_extension_import";
    private static final String KEY = "pending";
    private final SharedPreferences mPrefs;
    // A failed commit can still change SharedPreferences in-memory data. Never dispatch it.
    private static @Nullable String sFailedWriteId;

    static final class Request {
        final String id;
        final String nonce;
        final String profileBaseName;
        // Opaque native Profile creation value, in Windows-epoch microseconds.
        final long profileCreationTimeMicros;
        final int tabId;
        final boolean requireCallerCheck;
        final int state;
        final @Nullable String uri;
        final @Nullable String approvalProcess;

        Request(String id, String nonce, String profileBaseName, long profileCreationTimeMicros,
                int tabId, boolean requireCallerCheck, int state, @Nullable String uri,
                @Nullable String approvalProcess) {
            this.id = id;
            this.nonce = nonce;
            this.profileBaseName = profileBaseName;
            this.profileCreationTimeMicros = profileCreationTimeMicros;
            this.tabId = tabId;
            this.requireCallerCheck = requireCallerCheck;
            this.state = state;
            this.uri = uri;
            this.approvalProcess = approvalProcess;
        }

        Request withState(int state, @Nullable String uri) {
            String process = state == APPROVED || state == DISPATCHING
                    ? (approvalProcess != null ? approvalProcess : PROCESS_TOKEN) : null;
            return new Request(id, nonce, profileBaseName, profileCreationTimeMicros, tabId,
                    requireCallerCheck, state, uri, process);
        }
    }

    ReaperExtensionImportStore(Context context) {
        try (StrictModeContext ignored = StrictModeContext.allowDiskReads()) {
            mPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    ReaperExtensionImportStore(SharedPreferences prefs) {
        mPrefs = prefs;
    }

    static @Nullable Request create(String id, String profileBaseName,
            long profileCreationTimeMicros, int tabId, boolean requireCallerCheck) {
        if (!isToken(id) || !isProfileBaseName(profileBaseName)
                || profileCreationTimeMicros <= 0 || tabId < 0) {
            return null;
        }
        return new Request(id, UUID.randomUUID().toString(), profileBaseName,
                profileCreationTimeMicros, tabId, requireCallerCheck, PREPARED, null, null);
    }

    @Nullable Request read() {
        try (StrictModeContext ignored = StrictModeContext.allowDiskReads()) {
            String value = mPrefs.getString(KEY, null);
            if (value == null) return null;
            JSONObject object = new JSONObject(value);
            if (object.getInt("schema") != SCHEMA) return null;
            String id = object.getString("id");
            String nonce = object.getString("nonce");
            String profile = object.getString("profile");
            long creation = object.getLong("creation");
            int tab = object.getInt("tab");
            int state = object.getInt("state");
            boolean checkCaller = object.getBoolean("checkCaller");
            String uri = object.has("uri") ? object.getString("uri") : null;
            String process = object.has("process") ? object.getString("process") : null;
            if (!isToken(id) || !isToken(nonce) || !isProfileBaseName(profile)
                    || creation <= 0 || tab < 0 || state < PREPARED || state > DISPATCHING) {
                return null;
            }
            if ((state == APPROVED || state == DISPATCHING) != (uri != null)) return null;
            if ((uri != null) != (process != null)) return null;
            if (process != null && !isToken(process)) return null;
            if (uri != null && (uri.isEmpty() || uri.length() > 16384)) return null;
            if (id.equals(sFailedWriteId)) {
                state = FAILED;
                uri = null;
                process = null;
            }
            return new Request(id, nonce, profile, creation, tab, checkCaller, state, uri, process);
        } catch (JSONException | ClassCastException e) {
            return null;
        }
    }

    boolean write(Request request) {
        try (StrictModeContext ignored = StrictModeContext.allowDiskWrites()) {
            JSONObject object = new JSONObject();
            object.put("schema", SCHEMA);
            object.put("id", request.id);
            object.put("nonce", request.nonce);
            object.put("profile", request.profileBaseName);
            object.put("creation", request.profileCreationTimeMicros);
            object.put("tab", request.tabId);
            object.put("checkCaller", request.requireCallerCheck);
            object.put("state", request.state);
            if (request.uri != null) object.put("uri", request.uri);
            if (request.approvalProcess != null) object.put("process", request.approvalProcess);
            // This bounded record must reach disk before leaving for another Activity.
            boolean committed = mPrefs.edit().putString(KEY, object.toString()).commit();
            if (!committed) {
                sFailedWriteId = request.id;
            } else if (request.id.equals(sFailedWriteId)
                    && (request.state == CANCELLED || request.state == FAILED)) {
                sFailedWriteId = null;
            }
            return committed;
        } catch (JSONException e) {
            return false;
        }
    }

    boolean transition(String id, String nonce, int expectedState, int state,
            @Nullable String uri) {
        Request request = read();
        return request != null && request.id.equals(id) && request.nonce.equals(nonce)
                && request.state == expectedState && write(request.withState(state, uri));
    }

    boolean confirmForCurrentProcess(String id, String nonce) {
        Request request = read();
        return request != null && request.id.equals(id) && request.nonce.equals(nonce)
                && request.state == APPROVED && write(new Request(request.id, request.nonce,
                        request.profileBaseName, request.profileCreationTimeMicros, request.tabId,
                        request.requireCallerCheck, APPROVED, request.uri, PROCESS_TOKEN));
    }

    boolean clear(String id) {
        Request request = read();
        if (request == null || !request.id.equals(id)) return false;
        try (StrictModeContext ignored = StrictModeContext.allowDiskWrites()) {
            boolean committed = mPrefs.edit().remove(KEY).commit();
            if (committed && id.equals(sFailedWriteId)) sFailedWriteId = null;
            if (!committed) sFailedWriteId = id;
            return committed;
        }
    }

    private static boolean isToken(String value) {
        if (value.length() != 36) return false;
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isProfileBaseName(String value) {
        return !value.isEmpty() && value.length() <= 128 && !value.equals(".")
                && !value.equals("..") && value.indexOf('/') < 0 && value.indexOf('\\') < 0
                && value.indexOf('\u0000') < 0;
    }
}
