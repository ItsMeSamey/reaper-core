// Copyright 2026 The Reaper Authors.
// SPDX-License-Identifier: MPL-2.0

package org.chromium.chrome.browser.extensions;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.ui.extensions.R;

/**
 * Non-exported owner of the ordinary system folder picker. It does not initialize native
 * import state, choose a folder, or interpret persisted permissions as an import request.
 */
@NullMarked
public final class ReaperExtensionImportActivity extends ComponentActivity {
    static final String EXTRA_REQUEST = "reaper.extension_import.request";
    static final String EXTRA_NONCE = "reaper.extension_import.nonce";
    private static final int PICK_DIRECTORY = 1;
    private @Nullable ReaperExtensionImportStore mStore;
    private @Nullable String mRequestId;
    private @Nullable String mNonce;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ReaperExtensionImportStore store = new ReaperExtensionImportStore(this);
        mStore = store;
        mRequestId = getIntent().getStringExtra(EXTRA_REQUEST);
        mNonce = getIntent().getStringExtra(EXTRA_NONCE);
        ReaperExtensionImportStore.Request request = currentRequest();
        if (request == null) {
            finish();
            return;
        }
        showWaitingUi();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                complete(ReaperExtensionImportStore.CANCELLED, null);
            }
        });
        if (request.state == ReaperExtensionImportStore.PREPARED) {
            if (!store.transition(request.id, request.nonce, request.state,
                        ReaperExtensionImportStore.WAITING, null)) {
                ReaperExtensionImportCoordinator.showStorageError();
                finish();
                return;
            }
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                startActivityForResult(intent, PICK_DIRECTORY);
            } catch (RuntimeException e) {
                complete(ReaperExtensionImportStore.FAILED, null);
            }
        } else if (request.state != ReaperExtensionImportStore.WAITING) {
            finish();
        }
        // WAITING is restored without launching another picker. Android owns its result.
    }

    private void showWaitingUi() {
        setTitle(R.string.reaper_extension_import_title);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return insets;
        });
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        TextView message = new TextView(this);
        message.setText(R.string.reaper_extension_import_waiting);
        message.setGravity(Gravity.CENTER);
        content.addView(message);
        ProgressBar progress = new ProgressBar(this);
        progress.setContentDescription(getString(R.string.reaper_extension_import_waiting));
        content.addView(progress);
        Button cancel = new Button(this);
        cancel.setText(R.string.reaper_extension_import_cancel);
        cancel.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        cancel.setOnClickListener(view -> complete(ReaperExtensionImportStore.CANCELLED, null));
        content.addView(cancel);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_DIRECTORY) return;
        ReaperExtensionImportStore.Request request = currentRequest();
        if (request == null || request.state != ReaperExtensionImportStore.WAITING) {
            finish();
            return;
        }
        if (resultCode == RESULT_CANCELED) {
            complete(ReaperExtensionImportStore.CANCELLED, null);
            return;
        }
        Uri uri = validateResult(resultCode, data, request.requireCallerCheck,
                new ResultAccess() {
                    @Override
                    public boolean callerCanRead(Uri uri) {
                        return hasCurrentCallerPermission(uri);
                    }

                    @Override
                    public boolean appCanRead(Uri uri) {
                        return checkUriPermission(uri, Process.myPid(), Process.myUid(),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                == PackageManager.PERMISSION_GRANTED;
                    }

                    @Override
                    public void persistRead(Uri uri) {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    }
                });
        if (uri == null) {
            complete(ReaperExtensionImportStore.FAILED, null);
            return;
        }
        complete(ReaperExtensionImportStore.APPROVED, uri.toString());
    }

    interface ResultAccess {
        boolean callerCanRead(Uri uri);
        boolean appCanRead(Uri uri);
        void persistRead(Uri uri);
    }

    /** Called synchronously from the live result callback, never on a native-ready task. */
    static @Nullable Uri validateResult(int resultCode, @Nullable Intent data,
            boolean requireCallerCheck, ResultAccess access) {
        Uri uri = checkedResultUri(resultCode, data);
        if (uri == null || (requireCallerCheck && !access.callerCanRead(uri))
                || !access.appCanRead(uri)) {
            return null;
        }
        try {
            // No write grant is requested or persisted by this extension folder reader.
            access.persistRead(uri);
            return uri;
        } catch (SecurityException e) {
            return null;
        }
    }

    /** Pure result-shape validation; permission checks still run in the live callback. */
    static @Nullable Uri checkedResultUri(int resultCode, @Nullable Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getClipData() != null) {
            return null;
        }
        Uri selected = data.getData();
        if (selected == null) return null;
        Uri uri = selected.normalizeScheme();
        String authority = uri.getAuthority();
        int required = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        if (!"content".equals(uri.getScheme()) || authority == null
                || authority.isEmpty() || !DocumentsContract.isTreeUri(uri)
                || DocumentsContract.getTreeDocumentId(uri).isEmpty()
                || uri.toString().length() > 16384 || (data.getFlags() & required) != required) {
            return null;
        }
        return uri;
    }

    @SuppressLint("NewApi")
    private boolean hasCurrentCallerPermission(Uri uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return true;
        try {
            // Must run synchronously here, before any native-ready or resume callback.
            var caller = getCurrentCaller();
            return caller != null && caller.checkContentUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private ReaperExtensionImportStore.@Nullable Request currentRequest() {
        if (mStore == null || mRequestId == null || mNonce == null) return null;
        ReaperExtensionImportStore.Request request = mStore.read();
        return request != null && request.id.equals(mRequestId) && request.nonce.equals(mNonce)
                ? request : null;
    }

    private void complete(int state, @Nullable String uri) {
        ReaperExtensionImportStore.Request request = currentRequest();
        if (request != null && mStore != null) {
            if (!mStore.transition(request.id, request.nonce,
                        ReaperExtensionImportStore.WAITING, state, uri)) {
                ReaperExtensionImportCoordinator.showStorageError();
            }
        }
        finish();
    }
}
