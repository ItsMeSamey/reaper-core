/* Copyright (c) 2026 The Reaper Authors. All rights reserved.
 * Use of this source code is governed by the Mozilla Public License, v. 2.0. */

package org.chromium.components.signin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accounts.Account;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import org.chromium.base.Callback;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.google_apis.gaia.GaiaId;
import org.chromium.ui.base.WindowAndroid;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/** Local positive contracts. This source is not a live authenticator or browser-mint test. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ReaperGoogleAccountBrokerTest {
    private static final Account ACCOUNT =
            new Account("approved@example.test", GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE);
    private static final GaiaId GAIA = new GaiaId("approved-gaia");
    private static final String SCOPE = "oauth2:https://example.test/approved-scope";

    private ReaperGoogleAccountSdk sdk() throws Exception {
        ReaperGoogleAccountSdk sdk = mock(ReaperGoogleAccountSdk.class);
        when(sdk.getAccounts()).thenReturn(new Account[] {ACCOUNT});
        when(sdk.getGaiaId(ACCOUNT)).thenReturn(GAIA.toString());
        when(sdk.getToken(ACCOUNT, SCOPE)).thenReturn("local-mock-token");
        return sdk;
    }

    @Test
    public void exactApprovedAccountAndScopeReachPublicSdk() throws Exception {
        ReaperGoogleAccountSdk sdk = sdk();
        AccessTokenData token = new ReaperGoogleAccountTokenBroker(sdk).fetch(ACCOUNT, GAIA, SCOPE);
        assertEquals("local-mock-token", token.getToken());
        assertEquals(AccessTokenData.NO_KNOWN_EXPIRATION_TIME, token.getExpirationTimeSecs());
        verify(sdk).getToken(ACCOUNT, SCOPE);
        verify(sdk, org.mockito.Mockito.times(2)).getGaiaId(ACCOUNT);
    }

    @Test
    public void invalidationUsesTheSamePlatformBroker() throws Exception {
        ReaperGoogleAccountSdk sdk = sdk();
        new ReaperGoogleAccountTokenBroker(sdk).invalidate("local-mock-token");
        verify(sdk).clearToken("local-mock-token");
    }

    @Test
    public void delegateForwardsAccountCredentialCallback() throws Exception {
        ReaperGoogleAccountSdk sdk = sdk();
        Activity activity = mock(Activity.class);
        AtomicReference<Boolean> result = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            Callback<Boolean> callback = invocation.getArgument(2);
                            callback.onResult(true);
                            return null;
                        })
                .when(sdk)
                .updateCredentials(eq(ACCOUNT), eq(activity), any());
        new ReaperGoogleAccountManagerDelegate(sdk)
                .updateCredentials(ACCOUNT, activity, result::set);
        assertEquals(Boolean.TRUE, result.get());
    }

    @Test
    public void delegateReturnsActualCredentialBundle() throws Exception {
        ReaperGoogleAccountSdk sdk = sdk();
        Bundle actual = new Bundle();
        AtomicReference<Bundle> result = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            Callback<Bundle> callback = invocation.getArgument(2);
                            callback.onResult(actual);
                            return null;
                        })
                .when(sdk)
                .confirmCredentials(eq(ACCOUNT), eq(null), any());
        new ReaperGoogleAccountManagerDelegate(sdk)
                .confirmCredentials(ACCOUNT, null, result::set);
        assertSame(actual, result.get());
    }

    @Test
    public void explicitConsentRefetchesApprovedAccountAfterUserResult() throws Exception {
        ReaperGoogleAccountSdk sdk = sdk();
        Intent recovery = new Intent("local.mock.CONSENT");
        when(sdk.getToken(ACCOUNT, SCOPE))
                .thenThrow(new UserRecoverableAuthException("local consent", recovery))
                .thenReturn("local-mock-token");
        ReaperGoogleAccountConsentRequest.ConsentUi ui =
                mock(ReaperGoogleAccountConsentRequest.ConsentUi.class);
        when(ui.show(any(), any())).thenReturn(12);
        AtomicReference<AccessTokenData> result = new AtomicReference<>();
        ReaperGoogleAccountConsentRequest request =
                new ReaperGoogleAccountConsentRequest(
                        new ReaperGoogleAccountTokenBroker(sdk),
                        ACCOUNT,
                        GAIA,
                        SCOPE,
                        true,
                        ui,
                        Runnable::run,
                        Runnable::run,
                        (token, error) -> result.set(token));
        request.start();
        assertNull(result.get());
        request.onIntentCompleted(Activity.RESULT_OK, null);
        assertEquals("local-mock-token", result.get().getToken());
        verify(sdk, org.mockito.Mockito.times(2)).getToken(ACCOUNT, SCOPE);
    }

    @Test
    public void cancellationSuppressesQueuedSdkFetchAndCallbacks() throws Exception {
        ReaperGoogleAccountSdk sdk = sdk();
        ArrayDeque<Runnable> worker = new ArrayDeque<>();
        AtomicInteger callbacks = new AtomicInteger();
        ReaperGoogleAccountConsentRequest.ConsentUi ui =
                mock(ReaperGoogleAccountConsentRequest.ConsentUi.class);
        ReaperGoogleAccountConsentRequest request =
                new ReaperGoogleAccountConsentRequest(
                        new ReaperGoogleAccountTokenBroker(sdk),
                        ACCOUNT,
                        GAIA,
                        SCOPE,
                        true,
                        ui,
                        worker::add,
                        Runnable::run,
                        (token, error) -> callbacks.incrementAndGet());
        request.start();
        request.cancel();
        worker.remove().run();
        assertEquals(0, callbacks.get());
        verify(sdk, org.mockito.Mockito.never()).getToken(any(), any());
    }

    @Test
    public void cancellationRetiresShownIntentAndItsResultCallback() throws Exception {
        ReaperGoogleAccountSdk sdk = sdk();
        when(sdk.getToken(ACCOUNT, SCOPE))
                .thenThrow(
                        new UserRecoverableAuthException(
                                "local consent", new Intent("local.mock.CONSENT")));
        ReaperGoogleAccountConsentRequest.ConsentUi ui =
                mock(ReaperGoogleAccountConsentRequest.ConsentUi.class);
        when(ui.show(any(), any())).thenReturn(12);
        AtomicInteger callbacks = new AtomicInteger();
        ReaperGoogleAccountConsentRequest request =
                new ReaperGoogleAccountConsentRequest(
                        new ReaperGoogleAccountTokenBroker(sdk),
                        ACCOUNT,
                        GAIA,
                        SCOPE,
                        true,
                        ui,
                        Runnable::run,
                        Runnable::run,
                        (token, error) -> callbacks.incrementAndGet());
        request.start();
        request.cancel();
        verify(ui).cancel(12, request);
        request.onIntentCompleted(Activity.RESULT_OK, null);
        assertEquals(0, callbacks.get());
        verify(sdk).getToken(ACCOUNT, SCOPE);
    }
}
