/* Copyright (c) 2026 The Reaper Authors. All rights reserved.
 * Use of this source code is governed by the Mozilla Public License, v. 2.0. */

package org.chromium.components.signin;

import android.accounts.Account;
import android.content.Intent;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;

import org.chromium.build.annotations.NullMarked;
import org.chromium.google_apis.gaia.GaiaId;

import java.io.IOException;

/** Fetches a browser login token for an already approved exact platform account. */
@NullMarked
final class ReaperGoogleAccountTokenBroker {
    /** A real SDK recovery intent; the caller decides whether interactive consent is allowed. */
    static final class ConsentRequiredException extends AuthException {
        private final Intent mIntent;

        ConsentRequiredException(UserRecoverableAuthException cause) {
            super(NONTRANSIENT, cause);
            mIntent = new Intent(cause.getIntent());
        }

        Intent getIntent() {
            return new Intent(mIntent);
        }
    }

    private final ReaperGoogleAccountSdk mSdk;

    ReaperGoogleAccountTokenBroker(ReaperGoogleAccountSdk sdk) {
        mSdk = sdk;
    }

    AccessTokenData fetch(Account account, GaiaId approvedGaiaId, String existingOAuth2Scope)
            throws AuthException {
        if (!GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE.equals(account.type)
                || account.name.isEmpty()
                || approvedGaiaId.toString().isEmpty()
                || !existingOAuth2Scope.startsWith("oauth2:")
                || existingOAuth2Scope.length() == "oauth2:".length()) {
            throw rejectedAccount();
        }
        try {
            checkAccount(account, approvedGaiaId);
            String token = mSdk.getToken(account, existingOAuth2Scope);
            checkAccount(account, approvedGaiaId);
            if (token.isEmpty()) throw rejectedAccount();
            // The public API supplies no expiry. Preserve Chromium's unknown-expiry representation.
            return new AccessTokenData(token);
        } catch (UserRecoverableAuthException e) {
            throw new ConsentRequiredException(e);
        } catch (IOException e) {
            throw new AuthException(AuthException.TRANSIENT, e);
        } catch (GoogleAuthException | SecurityException e) {
            throw new AuthException(AuthException.NONTRANSIENT, e);
        }
    }

    private void checkAccount(Account account, GaiaId approvedGaiaId)
            throws IOException, GoogleAuthException, AuthException {
        boolean visible = false;
        for (Account candidate : mSdk.getAccounts()) {
            if (candidate.equals(account)) {
                visible = true;
                break;
            }
        }
        if (!visible || !approvedGaiaId.equals(new GaiaId(mSdk.getGaiaId(account)))) {
            throw rejectedAccount();
        }
    }

    void invalidate(String token) throws AuthException {
        try {
            mSdk.clearToken(token);
        } catch (IOException e) {
            throw new AuthException(AuthException.TRANSIENT, e);
        } catch (GoogleAuthException | SecurityException e) {
            throw new AuthException(AuthException.NONTRANSIENT, e);
        }
    }

    private static AuthException rejectedAccount() {
        return new AuthException(
                AuthException.NONTRANSIENT,
                new IllegalStateException("The approved platform account is unavailable."));
    }
}
