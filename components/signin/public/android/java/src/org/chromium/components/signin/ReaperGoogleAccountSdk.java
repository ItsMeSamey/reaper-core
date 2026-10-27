/* Copyright (c) 2026 The Reaper Authors. All rights reserved.
 * Use of this source code is governed by the Mozilla Public License, v. 2.0. */

package org.chromium.components.signin;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.accounts.OperationCanceledException;
import android.accounts.AuthenticatorException;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;

import org.chromium.base.Callback;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;

import java.io.IOException;

/** Public platform calls, separated so account and consent contracts can use local mocks. */
@NullMarked
interface ReaperGoogleAccountSdk {
    Account[] getAccounts();
    String getGaiaId(Account account) throws IOException, GoogleAuthException;
    String getToken(Account account, String oauth2Scope) throws IOException, GoogleAuthException;
    void clearToken(String token) throws IOException, GoogleAuthException;
    void attachObserver(Runnable observer);
    void createAddAccountIntent(
            @Nullable String prefilledEmail, Callback<@Nullable Intent> callback);
    void updateCredentials(Account account, Activity activity, Callback<Boolean> callback);
    void confirmCredentials(
            Account account, @Nullable Activity activity, Callback<@Nullable Bundle> callback);

    /** Uses the installed authenticator and the existing GoogleAuthUtil public API. */
    final class Platform implements ReaperGoogleAccountSdk {
        private final Context mContext;
        private final AccountManager mAccountManager;
        private final Handler mMainHandler = new Handler(Looper.getMainLooper());
        private @Nullable OnAccountsUpdateListener mListener;

        Platform(Context context) {
            mContext = context.getApplicationContext();
            mAccountManager = AccountManager.get(mContext);
        }

        @Override
        public Account[] getAccounts() {
            return mAccountManager.getAccountsByType(GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE);
        }

        @Override
        public String getGaiaId(Account account) throws IOException, GoogleAuthException {
            return GoogleAuthUtil.getAccountId(mContext, account.name);
        }

        @Override
        public String getToken(Account account, String oauth2Scope)
                throws IOException, GoogleAuthException {
            return GoogleAuthUtil.getToken(mContext, account, oauth2Scope);
        }

        @Override
        public void clearToken(String token) throws IOException, GoogleAuthException {
            GoogleAuthUtil.clearToken(mContext, token);
        }

        @Override
        public void attachObserver(Runnable observer) {
            if (mListener != null) mAccountManager.removeOnAccountsUpdatedListener(mListener);
            mListener = accounts -> observer.run();
            mAccountManager.addOnAccountsUpdatedListener(mListener, mMainHandler, false);
        }

        @Override
        public void createAddAccountIntent(
                @Nullable String prefilledEmail, Callback<@Nullable Intent> callback) {
            Bundle options = new Bundle();
            if (prefilledEmail != null) {
                options.putString(AccountManager.KEY_ACCOUNT_NAME, prefilledEmail);
            }
            mAccountManager.addAccount(
                    GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE,
                    null,
                    null,
                    options,
                    null,
                    future -> {
                        try {
                            callback.onResult(
                                    future.getResult().getParcelable(AccountManager.KEY_INTENT));
                        } catch (OperationCanceledException | AuthenticatorException | IOException e) {
                            callback.onResult(null);
                        }
                    },
                    mMainHandler);
        }

        @Override
        public void updateCredentials(
                Account account, Activity activity, Callback<Boolean> callback) {
            mAccountManager.updateCredentials(
                    account,
                    null,
                    null,
                    activity,
                    future -> {
                        try {
                            Bundle result = future.getResult();
                            String resultName = result.getString(AccountManager.KEY_ACCOUNT_NAME);
                            String resultType = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
                            boolean reportedAccountMatches =
                                    (resultName == null || account.name.equals(resultName))
                                            && (resultType == null || account.type.equals(resultType));
                            boolean sameAccount =
                                    account.name.equals(resultName) && account.type.equals(resultType);
                            callback.onResult(
                                    reportedAccountMatches
                                            && (sameAccount
                                                    || result.getBoolean(
                                                            AccountManager.KEY_BOOLEAN_RESULT, false)));
                        } catch (OperationCanceledException | AuthenticatorException | IOException e) {
                            callback.onResult(false);
                        }
                    },
                    mMainHandler);
        }

        @Override
        public void confirmCredentials(
                Account account,
                @Nullable Activity activity,
                Callback<@Nullable Bundle> callback) {
            mAccountManager.confirmCredentials(
                    account,
                    null,
                    activity,
                    future -> {
                        try {
                            Bundle result = future.getResult();
                            String resultName = result.getString(AccountManager.KEY_ACCOUNT_NAME);
                            String resultType = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
                            if ((resultName != null && !account.name.equals(resultName))
                                    || (resultType != null && !account.type.equals(resultType))) {
                                callback.onResult(null);
                                return;
                            }
                            callback.onResult(result);
                        } catch (OperationCanceledException | AuthenticatorException | IOException e) {
                            callback.onResult(null);
                        }
                    },
                    mMainHandler);
        }
    }
}
