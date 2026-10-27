/* Copyright (c) 2026 The Reaper Authors. All rights reserved.
 * Use of this source code is governed by the Mozilla Public License, v. 2.0. */

package org.chromium.components.signin;

import android.accounts.Account;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;

import org.chromium.base.Callback;
import org.chromium.base.ContextUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.build.annotations.ServiceImpl;
import org.chromium.google_apis.gaia.GaiaId;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/** Legacy Android account delegate. Registration does not confer browser mint eligibility. */
@NullMarked
@ServiceImpl(AccountManagerDelegate.class)
public final class ReaperGoogleAccountManagerDelegate implements AccountManagerDelegate {
    private final ReaperGoogleAccountSdk mSdk;
    private final ReaperGoogleAccountTokenBroker mBroker;
    private final ConcurrentHashMap<String, GaiaId> mKnownGaiaIds = new ConcurrentHashMap<>();

    public ReaperGoogleAccountManagerDelegate() {
        this(new ReaperGoogleAccountSdk.Platform(ContextUtils.getApplicationContext()));
    }

    ReaperGoogleAccountManagerDelegate(ReaperGoogleAccountSdk sdk) {
        mSdk = sdk;
        mBroker = new ReaperGoogleAccountTokenBroker(sdk);
    }

    @Override
    public void attachAccountsChangeObserver(AccountsChangeObserver observer) {
        ThreadUtils.assertOnUiThread();
        mSdk.attachObserver(
                () -> {
                    mKnownGaiaIds.clear();
                    observer.onAccountsChanged();
                });
    }

    @Override
    public Account[] getAccountsSynchronous() throws AccountManagerDelegateException {
        ThreadUtils.assertOnBackgroundThread();
        try {
            return mSdk.getAccounts();
        } catch (SecurityException e) {
            throw new AccountManagerDelegateException("Unable to read visible Google accounts.");
        }
    }

    @Override
    public @Nullable GaiaId getAccountGaiaId(String accountEmail) {
        ThreadUtils.assertOnBackgroundThread();
        Account requested = new Account(accountEmail, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE);
        try {
            boolean visible = false;
            for (Account account : mSdk.getAccounts()) {
                if (requested.equals(account)) {
                    visible = true;
                    break;
                }
            }
            if (!visible) return null;
            String id = mSdk.getGaiaId(requested);
            if (id.isEmpty()) return null;
            GaiaId gaiaId = new GaiaId(id);
            mKnownGaiaIds.put(accountEmail, gaiaId);
            return gaiaId;
        } catch (IOException | GoogleAuthException | SecurityException e) {
            return null;
        }
    }

    @Override
    public AccessTokenData getAccessToken(Account account, String authTokenScope)
            throws AuthException {
        ThreadUtils.assertOnBackgroundThread();
        @Nullable GaiaId approvedGaiaId = mKnownGaiaIds.get(account.name);
        if (approvedGaiaId == null) {
            throw new AuthException(
                    AuthException.NONTRANSIENT,
                    new IllegalStateException("The facade has not resolved this account."));
        }
        return mBroker.fetch(account, approvedGaiaId, authTokenScope);
    }

    @Override
    public void invalidateAccessToken(String authToken) throws AuthException {
        ThreadUtils.assertOnBackgroundThread();
        mBroker.invalidate(authToken);
    }

    @Override
    public @CapabilityResponse int hasCapability(@Nullable Account account, String capability) {
        // The public SDK contract captured here provides no capability API.
        return CapabilityResponse.EXCEPTION;
    }

    @Override
    public void createAddAccountIntent(
            @Nullable String prefilledEmail, Callback<@Nullable Intent> callback) {
        mSdk.createAddAccountIntent(prefilledEmail, callback);
    }

    @Override
    public void updateCredentials(
            Account account, Activity activity, @Nullable Callback<Boolean> callback) {
        mSdk.updateCredentials(
                account,
                activity,
                success -> {
                    if (callback != null) callback.onResult(success);
                });
    }

    @Override
    public void confirmCredentials(
            Account account, @Nullable Activity activity, Callback<@Nullable Bundle> callback) {
        mSdk.confirmCredentials(account, activity, callback);
    }
}
