// Copyright 2026 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.ui.base;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.Intent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.chromium.base.ContextUtils;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.ui.R;
import org.chromium.ui.base.WindowAndroid.IntentCallback;

/** Unit tests for {@link IntentRequestTrackerImpl}. */
@RunWith(BaseRobolectricTestRunner.class)
public class IntentRequestTrackerImplTest {
    private IntentRequestTracker.Delegate mDelegate;
    private IntentRequestTrackerImpl mTracker;

    @Before
    public void setUp() {
        mDelegate = mock(IntentRequestTracker.Delegate.class);
        when(mDelegate.startActivityForResult(any(), anyInt())).thenReturn(true);
        // An unexpected stale error must be observable without displaying a real toast.
        when(mDelegate.onCallbackNotFoundError(anyString())).thenReturn(true);
        mTracker = new IntentRequestTrackerImpl(mDelegate);
    }

    @Test
    public void testRemoveIntentCallbackDoesNotDeliverResultOrError() {
        IntentCallback retainedCallback = mock(IntentCallback.class);
        IntentCallback removedCallback = mock(IntentCallback.class);
        int retainedRequestCode =
                mTracker.showCancelableIntent(
                        new Intent(), retainedCallback, R.string.opening_file_error);
        int removedRequestCode =
                mTracker.showCancelableIntent(
                        new Intent(), removedCallback, R.string.opening_file_error);
        assertTrue(retainedRequestCode != removedRequestCode);

        assertTrue(mTracker.removeIntentCallback(removedCallback));
        assertFalse(
                mTracker.onActivityResult(removedRequestCode, Activity.RESULT_OK, new Intent()));
        verifyNoInteractions(removedCallback);
        assertFalse(mTracker.removeIntentCallback(removedCallback));
        verify(mDelegate, never()).onCallbackNotFoundError(anyString());

        Intent retainedResult = new Intent("retained-result");
        assertTrue(
                mTracker.onActivityResult(
                        retainedRequestCode, Activity.RESULT_OK, retainedResult));
        verify(retainedCallback).onIntentCompleted(Activity.RESULT_OK, retainedResult);
        assertFalse(
                mTracker.onActivityResult(
                        retainedRequestCode, Activity.RESULT_OK, retainedResult));
        verifyNoMoreInteractions(retainedCallback);
    }

    @Test
    public void testRemoveUnknownIntentCallbackPreservesOtherRequests() {
        IntentCallback unknownCallback = mock(IntentCallback.class);
        IntentCallback retainedCallback = mock(IntentCallback.class);
        int nullableRequestCode =
                mTracker.showCancelableIntent(new Intent(), null, R.string.opening_file_error);
        int retainedRequestCode =
                mTracker.showCancelableIntent(new Intent(), retainedCallback, null);
        assertTrue(nullableRequestCode != retainedRequestCode);

        assertFalse(mTracker.removeIntentCallback(unknownCallback));
        assertFalse(mTracker.onActivityResult(nullableRequestCode, Activity.RESULT_CANCELED, null));
        String errorMessage =
                ContextUtils.getApplicationContext().getString(R.string.opening_file_error);
        verify(mDelegate).onCallbackNotFoundError(errorMessage);
        verifyNoInteractions(unknownCallback, retainedCallback);

        Intent retainedResult = new Intent("retained-result");
        assertTrue(
                mTracker.onActivityResult(
                        retainedRequestCode, Activity.RESULT_OK, retainedResult));
        verify(retainedCallback).onIntentCompleted(Activity.RESULT_OK, retainedResult);
        verifyNoMoreInteractions(retainedCallback);
    }
}
