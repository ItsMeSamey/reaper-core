// Copyright 2026 The Reaper Authors.
// SPDX-License-Identifier: MPL-2.0

package org.chromium.chrome.browser.extensions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import org.chromium.base.ContextUtils;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.content_public.browser.WebContents;

import java.util.UUID;

/**
 * Durable receipt and native handoff tests. These do not emulate Android process death or qualify
 * the system picker, actual caller checks, native extension installation, or a physical device.
 */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ReaperExtensionImportTest {
    private static final Uri TREE = Uri.parse("content://example.documents/tree/folder");
    private static final int TAB = 19;
    private static final long CREATION = 13300000000000000L;

    private Context mContext;
    private ReaperExtensionImportStore mStore;
    private ReaperExtensionImportStore.Request mRequest;
    private ReaperExtensionImportCoordinator.Natives mNative;
    private WebContents mContents;
    private ReaperExtensionImportActivity.ResultAccess mAccess;

    @Before
    public void setUp() {
        mContext = ContextUtils.getApplicationContext();
        mContext.getSharedPreferences("reaper_extension_import", Context.MODE_PRIVATE)
                .edit().clear().commit();
        ReflectionHelpers.setStaticField(
                ReaperExtensionImportCoordinator.class, "sInFlight", null);
        ReflectionHelpers.setStaticField(
                ReaperExtensionImportCoordinator.class, "sConfirmationDialog", null);
        ReflectionHelpers.setStaticField(
                ReaperExtensionImportStore.class, "sFailedWriteId", null);
        mStore = new ReaperExtensionImportStore(mContext);
        mRequest = ReaperExtensionImportStore.create(
                UUID.randomUUID().toString(), "Default", CREATION, TAB, true);
        assertNotNull(mRequest);
        mContents = mock(WebContents.class);
        mNative = mock(ReaperExtensionImportCoordinator.Natives.class);
        ReaperExtensionImportCoordinatorJni.setInstanceForTesting(mNative);
        mAccess = mock(ReaperExtensionImportActivity.ResultAccess.class);
        when(mAccess.callerCanRead(TREE)).thenReturn(true);
        when(mAccess.appCanRead(TREE)).thenReturn(true);
    }

    @After
    public void tearDown() {
        ReaperExtensionImportCoordinatorJni.setInstanceForTesting(null);
        // This is only the isolated Robolectric preference store, never installed app data.
        mContext.getSharedPreferences("reaper_extension_import", Context.MODE_PRIVATE)
                .edit().clear().commit();
        ReflectionHelpers.setStaticField(
                ReaperExtensionImportCoordinator.class, "sInFlight", null);
        ReflectionHelpers.setStaticField(
                ReaperExtensionImportCoordinator.class, "sConfirmationDialog", null);
        ReflectionHelpers.setStaticField(
                ReaperExtensionImportStore.class, "sFailedWriteId", null);
    }

    private Intent result() {
        return new Intent().setData(TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    }

    private void approve() {
        assertTrue(mStore.write(mRequest));
        assertTrue(mStore.transition(mRequest.id, mRequest.nonce,
                ReaperExtensionImportStore.PREPARED, ReaperExtensionImportStore.WAITING, null));
        assertTrue(mStore.transition(mRequest.id, mRequest.nonce,
                ReaperExtensionImportStore.WAITING, ReaperExtensionImportStore.APPROVED,
                TREE.toString()));
    }

    @Test
    public void acceptedResultSurvivesTwoStoreRecreationsWithOriginalIdentity() {
        approve();
        ReaperExtensionImportStore.Request once =
                new ReaperExtensionImportStore(mContext).read();
        assertNotNull(once);
        ReaperExtensionImportStore.Request twice =
                new ReaperExtensionImportStore(mContext).read();
        assertNotNull(twice);
        assertEquals(mRequest.id, twice.id);
        assertEquals(mRequest.nonce, twice.nonce);
        assertEquals("Default", twice.profileBaseName);
        assertEquals(CREATION, twice.profileCreationTimeMicros);
        assertEquals(TAB, twice.tabId);
        assertEquals(ReaperExtensionImportStore.APPROVED, twice.state);
        assertEquals(TREE.toString(), twice.uri);
    }

    @Test
    public void waitingReceiptIsNotAnApprovalOrNativeCall() {
        assertTrue(mStore.write(mRequest.withState(ReaperExtensionImportStore.WAITING, null)));
        ReaperExtensionImportCoordinator.maybeResume(mContents, TAB);
        verify(mNative, never()).resume(any(), anyInt(), anyString(), anyString(),
                anyLong(), anyInt(), anyString());
        assertEquals(ReaperExtensionImportStore.WAITING, mStore.read().state);
    }

    @Test
    public void permissionAloneWithoutReceiptDoesNotDispatch() {
        // Even a validly shaped URI/read permission does not create a pending operation.
        assertNotNull(ReaperExtensionImportActivity.validateResult(
                Activity.RESULT_OK, result(), true, mAccess));
        ReaperExtensionImportCoordinator.maybeResume(mContents, TAB);
        verify(mNative, never()).resume(any(), anyInt(), anyString(), anyString(),
                anyLong(), anyInt(), anyString());
    }

    @Test
    public void wrongNonceAndLateOldResultCannotChangeNewRequest() {
        assertTrue(mStore.write(mRequest.withState(ReaperExtensionImportStore.WAITING, null)));
        assertFalse(mStore.transition(mRequest.id, UUID.randomUUID().toString(),
                ReaperExtensionImportStore.WAITING, ReaperExtensionImportStore.APPROVED,
                TREE.toString()));
        assertTrue(mStore.clear(mRequest.id));
        ReaperExtensionImportStore.Request next = ReaperExtensionImportStore.create(
                UUID.randomUUID().toString(), "Profile 1", CREATION + 1, TAB + 1, true);
        assertNotNull(next);
        assertTrue(mStore.write(next));
        assertFalse(mStore.transition(mRequest.id, mRequest.nonce,
                ReaperExtensionImportStore.WAITING, ReaperExtensionImportStore.APPROVED,
                TREE.toString()));
        assertFalse(mStore.clear(mRequest.id));
        assertEquals(next.id, mStore.read().id);
    }

    @Test
    public void cancelIsTerminalAndCannotLaterApprove() {
        assertTrue(mStore.write(mRequest.withState(ReaperExtensionImportStore.WAITING, null)));
        assertTrue(mStore.transition(mRequest.id, mRequest.nonce,
                ReaperExtensionImportStore.WAITING, ReaperExtensionImportStore.CANCELLED, null));
        assertFalse(mStore.transition(mRequest.id, mRequest.nonce,
                ReaperExtensionImportStore.WAITING, ReaperExtensionImportStore.APPROVED,
                TREE.toString()));
        assertNull(mStore.read().uri);
    }

    private Intent waitingActivityIntent() {
        assertTrue(mStore.write(mRequest.withState(ReaperExtensionImportStore.WAITING, null)));
        return new Intent(mContext, ReaperExtensionImportActivity.class)
                .putExtra(ReaperExtensionImportActivity.EXTRA_REQUEST, mRequest.id)
                .putExtra(ReaperExtensionImportActivity.EXTRA_NONCE, mRequest.nonce);
    }

    @Test
    public void dispatcherBackCancelsWaitingRequestAndRejectsLateResult() {
        var controller = Robolectric.buildActivity(
                ReaperExtensionImportActivity.class, waitingActivityIntent()).setup();
        try {
            ReaperExtensionImportActivity activity = controller.get();
            assertTrue(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
            activity.getOnBackPressedDispatcher().onBackPressed();
            assertTrue(activity.isFinishing());
            assertEquals(ReaperExtensionImportStore.CANCELLED, mStore.read().state);
            assertNull(mStore.read().uri);
            // An already cancelled request cannot become approved by a late picker result.
            activity.onActivityResult(1, Activity.RESULT_OK, result());
            assertEquals(ReaperExtensionImportStore.CANCELLED, mStore.read().state);
            assertNull(mStore.read().uri);
            verifyNoInteractions(mNative);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void dispatcherBackAfterRecreationCancelsOriginalWaitingRequest() {
        var controller = Robolectric.buildActivity(
                ReaperExtensionImportActivity.class, waitingActivityIntent()).setup();
        try {
            controller.recreate();
            ReaperExtensionImportActivity activity = controller.get();
            assertEquals(ReaperExtensionImportStore.WAITING, mStore.read().state);
            activity.getOnBackPressedDispatcher().onBackPressed();
            assertTrue(activity.isFinishing());
            assertEquals(mRequest.id, mStore.read().id);
            assertEquals(mRequest.nonce, mStore.read().nonce);
            assertEquals(ReaperExtensionImportStore.CANCELLED, mStore.read().state);
            assertNull(mStore.read().uri);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void dispatcherBackFromStaleActivityDoesNotCancelReplacementRequest() {
        var controller = Robolectric.buildActivity(
                ReaperExtensionImportActivity.class, waitingActivityIntent()).setup();
        try {
            ReaperExtensionImportStore.Request replacement = ReaperExtensionImportStore.create(
                    UUID.randomUUID().toString(), "Profile 1", CREATION + 1, TAB + 1, true);
            assertNotNull(replacement);
            assertTrue(mStore.write(replacement));
            controller.get().getOnBackPressedDispatcher().onBackPressed();
            assertTrue(controller.get().isFinishing());
            assertEquals(replacement.id, mStore.read().id);
            assertEquals(replacement.nonce, mStore.read().nonce);
            assertEquals(ReaperExtensionImportStore.PREPARED, mStore.read().state);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void cancellationWithUriDoesNotCheckOrPersistAccess() {
        assertNull(ReaperExtensionImportActivity.validateResult(
                Activity.RESULT_CANCELED, result(), true, mAccess));
        verify(mAccess, never()).callerCanRead(any());
        verify(mAccess, never()).appCanRead(any());
        verify(mAccess, never()).persistRead(any());
    }

    @Test
    public void malformedOrMultipleResultCannotApprove() {
        assertNull(ReaperExtensionImportActivity.checkedResultUri(Activity.RESULT_OK, null));
        assertNull(ReaperExtensionImportActivity.checkedResultUri(
                Activity.RESULT_OK, new Intent()));
        assertNull(ReaperExtensionImportActivity.checkedResultUri(
                Activity.RESULT_OK, result().setData(Uri.parse("file:///sdcard/folder"))));
        assertNull(ReaperExtensionImportActivity.checkedResultUri(Activity.RESULT_OK,
                result().setData(Uri.parse("content://example.documents/document/folder"))));
        Intent multiple = result();
        multiple.setClipData(ClipData.newRawUri("folder", TREE));
        assertNull(ReaperExtensionImportActivity.checkedResultUri(Activity.RESULT_OK, multiple));
    }

    @Test
    public void missingPersistableOrReadGrantCannotApprove() {
        assertNull(ReaperExtensionImportActivity.checkedResultUri(
                Activity.RESULT_OK, result().setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)));
        assertNull(ReaperExtensionImportActivity.checkedResultUri(Activity.RESULT_OK,
                result().setFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)));
    }

    @Test
    public void deniedCallerDoesNotPersistOrFallBackToAppGrant() {
        when(mAccess.callerCanRead(TREE)).thenReturn(false);
        assertNull(ReaperExtensionImportActivity.validateResult(
                Activity.RESULT_OK, result(), true, mAccess));
        verify(mAccess, never()).appCanRead(any());
        verify(mAccess, never()).persistRead(any());
    }

    @Test
    public void revokedAppGrantDoesNotPersistApproval() {
        when(mAccess.appCanRead(TREE)).thenReturn(false);
        assertNull(ReaperExtensionImportActivity.validateResult(
                Activity.RESULT_OK, result(), true, mAccess));
        verify(mAccess).callerCanRead(TREE);
        verify(mAccess, never()).persistRead(any());
    }

    @Test
    public void permissionPersistenceFailureCannotApprove() {
        doThrow(new SecurityException("fixture denial")).when(mAccess).persistRead(TREE);
        assertNull(ReaperExtensionImportActivity.validateResult(
                Activity.RESULT_OK, result(), true, mAccess));
    }

    @Test
    public void successfulValidationChecksCallerThenAppGrantAndPersistsRead() {
        assertEquals(TREE, ReaperExtensionImportActivity.validateResult(
                Activity.RESULT_OK, result(), true, mAccess));
        var order = org.mockito.Mockito.inOrder(mAccess);
        order.verify(mAccess).callerCanRead(TREE);
        order.verify(mAccess).appCanRead(TREE);
        order.verify(mAccess).persistRead(TREE);
    }

    @Test
    public void disabledCallerFeatureStillRequiresAppGrantAndPersistence() {
        assertEquals(TREE, ReaperExtensionImportActivity.validateResult(
                Activity.RESULT_OK, result(), false, mAccess));
        verify(mAccess, never()).callerCanRead(any());
        verify(mAccess).appCanRead(TREE);
        verify(mAccess).persistRead(TREE);
    }

    @Test
    public void wrongTabDoesNotDispatchOrConsumeApproval() {
        approve();
        ReaperExtensionImportCoordinator.maybeResume(mContents, TAB + 1);
        verify(mNative, never()).resume(any(), anyInt(), anyString(), anyString(),
                anyLong(), anyInt(), anyString());
        assertEquals(ReaperExtensionImportStore.APPROVED, mStore.read().state);
    }

    @Test
    public void nativeDeferredContextRetainsApprovalAndExactOriginalProfile() {
        approve();
        when(mNative.resume(any(), anyInt(), anyString(), anyString(), anyLong(), anyInt(),
                anyString())).thenReturn(ReaperExtensionImportCoordinator.DEFERRED);
        ReaperExtensionImportCoordinator.maybeResume(mContents, TAB);
        verify(mNative).resume(mContents, TAB, mRequest.id, "Default", CREATION,
                ReaperExtensionImportStore.APPROVED, TREE.toString());
        assertEquals(ReaperExtensionImportStore.APPROVED, mStore.read().state);
    }

    @Test
    public void duplicateResumeDoesNotStartTwoNativeImports() {
        approve();
        when(mNative.resume(any(), anyInt(), anyString(), anyString(), anyLong(), anyInt(),
                anyString())).thenReturn(ReaperExtensionImportCoordinator.STARTED);
        ReaperExtensionImportCoordinator.maybeResume(mContents, TAB);
        ReaperExtensionImportCoordinator.maybeResume(mContents, TAB);
        verify(mNative, times(1)).resume(any(), anyInt(), anyString(), anyString(),
                anyLong(), anyInt(), anyString());
        assertEquals(ReaperExtensionImportStore.DISPATCHING, mStore.read().state);
        ReaperExtensionImportCoordinator.onNativeComplete(mRequest.id, "");
        assertNull(mStore.read());
    }

    @Test
    public void dispatchingReceiptAfterProcessStateLossReportsInterruptedWithoutUri() {
        approve();
        assertTrue(mStore.transition(mRequest.id, mRequest.nonce,
                ReaperExtensionImportStore.APPROVED, ReaperExtensionImportStore.DISPATCHING,
                TREE.toString()));
        // No live in-flight token: model durable state read by a new process, not process death.
        when(mNative.resume(any(), anyInt(), anyString(), anyString(), anyLong(), anyInt(),
                anyString())).thenReturn(ReaperExtensionImportCoordinator.FINISHED);
        ReaperExtensionImportCoordinator.maybeResume(mContents, TAB);
        verify(mNative).resume(mContents, TAB, mRequest.id, "Default", CREATION,
                ReaperExtensionImportStore.INTERRUPTED, "");
        assertNull(mStore.read());
    }

    @Test
    public void synchronousNativeCompletionCannotBeRevivedByDeferredReturn() {
        approve();
        doAnswer(invocation -> {
            ReaperExtensionImportCoordinator.onNativeComplete(mRequest.id, "");
            return ReaperExtensionImportCoordinator.DEFERRED;
        }).when(mNative).resume(any(), anyInt(), anyString(), anyString(), anyLong(),
                anyInt(), anyString());
        ReaperExtensionImportCoordinator.maybeResume(mContents, TAB);
        assertNull(mStore.read());
    }

    @Test
    public void staleNativeCompletionDoesNotClearAnotherRequest() {
        approve();
        ReaperExtensionImportCoordinator.onNativeComplete(UUID.randomUUID().toString(), "");
        assertEquals(mRequest.id, mStore.read().id);
    }

    @Test
    public void failedCommitCannotExposeInMemoryApprovalAsApproved() {
        SharedPreferences real = mContext.getSharedPreferences(
                "reaper_extension_import_failure_fixture", Context.MODE_PRIVATE);
        real.edit().clear().commit();
        SharedPreferences prefs = spy(real);
        SharedPreferences.Editor realEditor = real.edit();
        SharedPreferences.Editor editor = spy(realEditor);
        when(prefs.edit()).thenReturn(editor);
        doAnswer(invocation -> {
            realEditor.putString(invocation.getArgument(0), invocation.getArgument(1));
            return editor;
        }).when(editor).putString(anyString(), anyString());
        doAnswer(invocation -> {
            realEditor.commit();
            return false; // SharedPreferences can update memory even when disk commit fails.
        }).when(editor).commit();
        ReaperExtensionImportStore failingStore = new ReaperExtensionImportStore(prefs);
        assertFalse(failingStore.write(mRequest.withState(
                ReaperExtensionImportStore.APPROVED, TREE.toString())));
        assertNotNull(failingStore.read());
        assertEquals(ReaperExtensionImportStore.FAILED, failingStore.read().state);
        assertNull(failingStore.read().uri);
        real.edit().clear().commit();
    }

    @Test
    public void priorProcessApprovalNeedsExplicitContinuationBeforeNativeDispatch() {
        ReaperExtensionImportStore.Request old = new ReaperExtensionImportStore.Request(
                mRequest.id, mRequest.nonce, "Default", CREATION, TAB, true,
                ReaperExtensionImportStore.APPROVED, TREE.toString(),
                UUID.randomUUID().toString());
        assertTrue(mStore.write(old));
        // No renderable current Extensions window: never auto-continue this old approval.
        ReaperExtensionImportCoordinator.maybeResume(mContents, TAB);
        verify(mNative, never()).resume(any(), anyInt(), anyString(), anyString(),
                anyLong(), anyInt(), anyString());
        assertEquals(old.approvalProcess, mStore.read().approvalProcess);
        // Model the explicit Continue button's durable operation, not an automatic grant scan.
        assertTrue(mStore.confirmForCurrentProcess(old.id, old.nonce));
        when(mNative.resume(any(), anyInt(), anyString(), anyString(), anyLong(), anyInt(),
                anyString())).thenReturn(ReaperExtensionImportCoordinator.STARTED);
        ReaperExtensionImportCoordinator.maybeResume(mContents, TAB);
        verify(mNative).resume(mContents, TAB, old.id, "Default", CREATION,
                ReaperExtensionImportStore.APPROVED, TREE.toString());
    }

    @Test
    public void explicitCancelFromAnotherTabRetiresOrphanWithoutImport() {
        approve();
        doAnswer(invocation -> {
            assertEquals(ReaperExtensionImportStore.CANCELLED, mStore.read().state);
            assertNull(mStore.read().uri);
            ReaperExtensionImportCoordinator.onNativeComplete(mRequest.id, "");
            return true;
        }).when(mNative).discard(mContents, TAB + 1, mRequest.id);
        assertTrue(ReaperExtensionImportCoordinator.discardPending(
                mContents, TAB + 1, mRequest.id));
        verify(mNative).discard(mContents, TAB + 1, mRequest.id);
        verify(mNative, never()).resume(any(), anyInt(), anyString(), anyString(),
                anyLong(), anyInt(), anyString());
        assertNull(mStore.read());
    }

    @Test
    public void lateCancelConfirmationDoesNotRetireNewRequest() {
        approve();
        assertFalse(ReaperExtensionImportCoordinator.discardPending(
                mContents, TAB, UUID.randomUUID().toString()));
        verify(mNative, never()).discard(any(), anyInt(), anyString());
        assertEquals(mRequest.id, mStore.read().id);
    }

    @Test
    public void failedCommitThatReachedDiskStillRequiresPriorProcessConfirmation() {
        SharedPreferences real = mContext.getSharedPreferences(
                "reaper_extension_import", Context.MODE_PRIVATE);
        SharedPreferences prefs = spy(real);
        SharedPreferences.Editor realEditor = real.edit();
        SharedPreferences.Editor editor = spy(realEditor);
        when(prefs.edit()).thenReturn(editor);
        doAnswer(invocation -> {
            realEditor.putString(invocation.getArgument(0), invocation.getArgument(1));
            return editor;
        }).when(editor).putString(anyString(), anyString());
        doAnswer(invocation -> {
            realEditor.commit();
            return false;
        }).when(editor).commit();
        ReaperExtensionImportStore.Request old = new ReaperExtensionImportStore.Request(
                mRequest.id, mRequest.nonce, "Default", CREATION, TAB, true,
                ReaperExtensionImportStore.APPROVED, TREE.toString(),
                UUID.randomUUID().toString());
        assertFalse(new ReaperExtensionImportStore(prefs).write(old));
        assertEquals(ReaperExtensionImportStore.FAILED, mStore.read().state);
        // Process-local failure markers disappear on restart; durable consent remains uncertain.
        ReflectionHelpers.setStaticField(
                ReaperExtensionImportStore.class, "sFailedWriteId", null);
        assertEquals(ReaperExtensionImportStore.APPROVED, mStore.read().state);
        ReaperExtensionImportCoordinator.maybeResume(mContents, TAB);
        verify(mNative, never()).resume(any(), anyInt(), anyString(), anyString(),
                anyLong(), anyInt(), anyString());
    }

    @Test
    public void destroyedDialogOwnerDoesNotSuppressFutureConfirmation() {
        var controller = Robolectric.buildActivity(Activity.class).setup();
        Activity owner = controller.get();
        AlertDialog dialog = mock(AlertDialog.class);
        when(dialog.getOwnerActivity()).thenReturn(owner);
        when(dialog.isShowing()).thenReturn(true);
        ReaperExtensionImportCoordinator.bindConfirmation(dialog, owner);
        assertTrue(ReaperExtensionImportCoordinator.hasActiveConfirmation());
        controller.pause().stop().destroy();
        assertFalse(ReaperExtensionImportCoordinator.hasActiveConfirmation());
    }

    @Test
    public void oldDialogDismissCannotClearReplacementForSameRequest() {
        var controller = Robolectric.buildActivity(Activity.class).setup();
        Activity owner = controller.get();
        AlertDialog old = mock(AlertDialog.class);
        when(old.getOwnerActivity()).thenReturn(owner);
        when(old.isShowing()).thenReturn(true);
        ReaperExtensionImportCoordinator.bindConfirmation(old, owner);
        ArgumentCaptor<DialogInterface.OnDismissListener> listener =
                ArgumentCaptor.forClass(DialogInterface.OnDismissListener.class);
        verify(old).setOnDismissListener(listener.capture());
        AlertDialog replacement = mock(AlertDialog.class);
        when(replacement.getOwnerActivity()).thenReturn(owner);
        when(replacement.isShowing()).thenReturn(true);
        ReaperExtensionImportCoordinator.bindConfirmation(replacement, owner);
        listener.getValue().onDismiss(old);
        assertTrue(ReaperExtensionImportCoordinator.hasActiveConfirmation());
        controller.pause().stop().destroy();
    }

    @Test
    public void invalidProfileIdentityCannotCreateRequest() {
        assertNull(ReaperExtensionImportStore.create(
                mRequest.id, "../Default", CREATION, TAB, true));
        assertNull(ReaperExtensionImportStore.create(mRequest.id, "Default", 0, TAB, true));
        assertNull(ReaperExtensionImportStore.create(mRequest.id, "Default", CREATION, -1, true));
        assertNull(ReaperExtensionImportStore.create(
                "not-a-token", "Default", CREATION, TAB, true));
    }
}
