/* Copyright (c) 2026 The Reaper Authors. All rights reserved.
 * Use of this source code is governed by the Mozilla Public License, v. 2.0. */

package org.chromium.components.signin;

import android.accounts.Account;
import android.app.Activity;
import android.content.Intent;

import com.google.android.gms.auth.GoogleAuthUtil;

import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.components.signin.base.CoreAccountInfo;
import org.chromium.ui.base.WindowAndroid;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** One explicitly interactive, cancelable request owned by an approved account and window. */
@NullMarked
public final class ReaperGoogleAccountConsentRequest implements WindowAndroid.IntentCallback {
    public interface ResultCallback {
        void onResult(@Nullable AccessTokenData token, @Nullable AuthException error);
    }

    interface ConsentUi {
        int show(Intent intent, WindowAndroid.IntentCallback callback);
        void cancel(int requestCode, WindowAndroid.IntentCallback callback);
    }

    private final ReaperGoogleAccountTokenBroker mBroker;
    private final Account mAccount;
    private final org.chromium.google_apis.gaia.GaiaId mApprovedGaiaId;
    private final String mExistingOAuth2Scope;
    private final boolean mInteractive;
    private final ConsentUi mConsentUi;
    private final Executor mWorker;
    private final Executor mUi;
    private final ResultCallback mCallback;
    private boolean mStarted;
    private boolean mFinished;
    private boolean mConsentShown;
    private int mRequestCode = -1;
    private final AtomicInteger mGeneration = new AtomicInteger();

    public ReaperGoogleAccountConsentRequest(
            CoreAccountInfo approvedAccount,
            String existingOAuth2Scope,
            boolean interactive,
            WindowAndroid window,
            ResultCallback callback) {
        this(
                new ReaperGoogleAccountTokenBroker(
                        new ReaperGoogleAccountSdk.Platform(
                                org.chromium.base.ContextUtils.getApplicationContext())),
                new Account(approvedAccount.getEmail(), GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE),
                approvedAccount.getGaiaId(),
                existingOAuth2Scope,
                interactive,
                new ConsentUi() {
                    @Override
                    public int show(Intent intent, WindowAndroid.IntentCallback result) {
                        return window.showCancelableIntent(intent, result, null);
                    }

                    @Override
                    public void cancel(int requestCode, WindowAndroid.IntentCallback result) {
                        window.removeIntentCallback(result);
                        window.cancelIntent(requestCode);
                    }
                },
                AsyncTask.THREAD_POOL_EXECUTOR,
                ThreadUtils::postOnUiThread,
                callback);
    }

    ReaperGoogleAccountConsentRequest(
            ReaperGoogleAccountTokenBroker broker,
            Account account,
            org.chromium.google_apis.gaia.GaiaId approvedGaiaId,
            String existingOAuth2Scope,
            boolean interactive,
            ConsentUi consentUi,
            Executor worker,
            Executor ui,
            ResultCallback callback) {
        mBroker = broker;
        mAccount = account;
        mApprovedGaiaId = approvedGaiaId;
        mExistingOAuth2Scope = existingOAuth2Scope;
        mInteractive = interactive;
        mConsentUi = consentUi;
        mWorker = worker;
        mUi = ui;
        mCallback = callback;
    }

    public void start() {
        ThreadUtils.assertOnUiThread();
        if (mStarted || mFinished) return;
        mStarted = true;
        fetch();
    }

    private void fetch() {
        int generation = mGeneration.get();
        mWorker.execute(
                () -> {
                    if (generation != mGeneration.get()) return;
                    @Nullable AccessTokenData token = null;
                    @Nullable AuthException error = null;
                    try {
                        token = mBroker.fetch(mAccount, mApprovedGaiaId, mExistingOAuth2Scope);
                    } catch (AuthException e) {
                        error = e;
                    }
                    @Nullable AccessTokenData result = token;
                    @Nullable AuthException failure = error;
                    mUi.execute(() -> onFetchComplete(generation, result, failure));
                });
    }

    private void onFetchComplete(
            int generation, @Nullable AccessTokenData token, @Nullable AuthException error) {
        if (mFinished || generation != mGeneration.get()) return;
        if (error instanceof ReaperGoogleAccountTokenBroker.ConsentRequiredException consent
                && mInteractive
                && !mConsentShown) {
            mConsentShown = true;
            mRequestCode = mConsentUi.show(consent.getIntent(), this);
            if (mRequestCode >= 0) return;
        }
        mFinished = true;
        mCallback.onResult(token, error);
    }

    @Override
    public void onIntentCompleted(int resultCode, @Nullable Intent data) {
        ThreadUtils.assertOnUiThread();
        if (mFinished || mRequestCode < 0) return;
        mRequestCode = -1;
        if (resultCode != Activity.RESULT_OK) {
            mFinished = true;
            mCallback.onResult(
                    null,
                    new AuthException(
                            AuthException.NONTRANSIENT,
                            new IllegalStateException("Account consent was not completed.")));
            return;
        }
        // A successful UI result grants no token by itself. Re-fetch and re-check the same account.
        fetch();
    }

    public void cancel() {
        ThreadUtils.assertOnUiThread();
        if (mFinished) return;
        mFinished = true;
        mGeneration.incrementAndGet();
        if (mRequestCode >= 0) {
            mConsentUi.cancel(mRequestCode, this);
            mRequestCode = -1;
        }
    }
}
