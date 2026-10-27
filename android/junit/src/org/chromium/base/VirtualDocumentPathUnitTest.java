// Copyright 2026 The Reaper Authors.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.chromium.base.test.BaseRobolectricTestRunner;

/** Verifies SAF lookup results and ownership of acquired provider cursors. */
@RunWith(BaseRobolectricTestRunner.class)
public class VirtualDocumentPathUnitTest {
    private static final String ROOT_PATH = "/SAF/test.documents/tree/root";
    private static final Uri ROOT_URI =
            Uri.parse("content://test.documents/tree/root/document/root");
    private static final Uri CHILDREN_URI =
            Uri.parse("content://test.documents/tree/root/document/root/children");
    private static final String CHILD_URI =
            "content://test.documents/tree/root/document/root%2Fchild";
    private static final String[] ROOT_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    };
    private static final String[] CHILD_COLUMNS = {
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    };
    private static final String CHILD_SELECTION =
            DocumentsContract.Document.COLUMN_DISPLAY_NAME + " = ?";
    private static final String[] CHILD_ARGUMENTS = {"child"};

    @Mock private Context mContext;
    @Mock private ContentResolver mResolver;
    @Mock private Cursor mCursor;
    private AutoCloseable mMocks;

    @Before
    public void setUp() {
        mMocks = MockitoAnnotations.openMocks(this);
        when(mContext.getContentResolver()).thenReturn(mResolver);
        ContextUtils.initApplicationContextForTests(mContext);
    }

    @After
    public void tearDown() throws Exception {
        mMocks.close();
    }

    private VirtualDocumentPath path(String suffix) {
        VirtualDocumentPath path = VirtualDocumentPath.parse(ROOT_PATH + suffix);
        assertNotNull(path);
        return path;
    }

    private void rootCursor() {
        when(mResolver.query(ROOT_URI, ROOT_COLUMNS, null, null, null)).thenReturn(mCursor);
    }

    private void childCursor() {
        when(mResolver.query(
                        CHILDREN_URI, CHILD_COLUMNS, CHILD_SELECTION, CHILD_ARGUMENTS, null))
                .thenReturn(mCursor);
    }

    private void verifyRootQuery() {
        verify(mResolver).query(ROOT_URI, ROOT_COLUMNS, null, null, null);
    }

    private void verifyChildQuery() {
        verify(mResolver)
                .query(CHILDREN_URI, CHILD_COLUMNS, CHILD_SELECTION, CHILD_ARGUMENTS, null);
    }

    private void childRow(String name, String documentId) {
        when(mCursor.moveToFirst()).thenReturn(true);
        when(mCursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)).thenReturn(0);
        when(mCursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)).thenReturn(1);
        when(mCursor.getString(0)).thenReturn(name);
        when(mCursor.getString(1)).thenReturn(documentId);
    }

    @Test
    public void rootSuccessClosesCursor() {
        rootCursor();
        when(mCursor.moveToFirst()).thenReturn(true);
        assertEquals(ROOT_URI.toString(), path("").resolveToContentUriString());
        verifyRootQuery();
        verify(mCursor).close();
    }

    @Test
    public void rootEmptyClosesCursor() {
        rootCursor();
        when(mCursor.moveToFirst()).thenReturn(false);
        assertEquals("", path("").resolveToContentUriString());
        verifyRootQuery();
        verify(mCursor).close();
    }

    @Test
    public void rootQueryFailureReturnsMissing() {
        when(mResolver.query(ROOT_URI, ROOT_COLUMNS, null, null, null))
                .thenThrow(new IllegalStateException("query failed"));
        assertEquals("", path("").resolveToContentUriString());
        verifyRootQuery();
        verifyNoInteractions(mCursor);
    }

    @Test
    public void rootMoveFailureClosesCursorAndReturnsMissing() {
        rootCursor();
        when(mCursor.moveToFirst()).thenThrow(new IllegalStateException("move failed"));
        assertEquals("", path("").resolveToContentUriString());
        verifyRootQuery();
        verify(mCursor).close();
    }

    @Test
    public void childMatchClosesCursor() {
        childCursor();
        childRow("child", "root/child");
        assertEquals(CHILD_URI, path("/child").resolveToContentUriString());
        verifyChildQuery();
        verify(mCursor).close();
    }

    @Test
    public void childEmptyClosesCursor() {
        childCursor();
        when(mCursor.moveToFirst()).thenReturn(false);
        assertEquals("", path("/child").resolveToContentUriString());
        verifyChildQuery();
        verify(mCursor).close();
    }

    @Test
    public void childWithoutMatchingNameClosesCursor() {
        childCursor();
        childRow("other", "root/other");
        when(mCursor.moveToNext()).thenReturn(false);
        assertEquals("", path("/child").resolveToContentUriString());
        verifyChildQuery();
        verify(mCursor).close();
    }

    @Test
    public void childQueryFailureReturnsMissing() {
        when(mResolver.query(
                        CHILDREN_URI, CHILD_COLUMNS, CHILD_SELECTION, CHILD_ARGUMENTS, null))
                .thenThrow(new IllegalStateException("query failed"));
        assertEquals("", path("/child").resolveToContentUriString());
        verifyChildQuery();
        verifyNoInteractions(mCursor);
    }

    @Test
    public void childFirstMoveFailurePropagatesAndClosesCursor() {
        childCursor();
        IllegalStateException failure = new IllegalStateException("move failed");
        when(mCursor.moveToFirst()).thenThrow(failure);
        assertSame(
                failure,
                assertThrows(IllegalStateException.class, path("/child")::resolveToContentUriString));
        verifyChildQuery();
        verify(mCursor).close();
    }

    @Test
    public void childAdvanceFailurePropagatesAndClosesCursor() {
        childCursor();
        childRow("other", "root/other");
        IllegalStateException failure = new IllegalStateException("advance failed");
        when(mCursor.moveToNext()).thenThrow(failure);
        assertSame(
                failure,
                assertThrows(IllegalStateException.class, path("/child")::resolveToContentUriString));
        verifyChildQuery();
        verify(mCursor).close();
    }

    @Test
    public void nullRootAndChildCursorsReturnMissing() {
        when(mResolver.query(ROOT_URI, ROOT_COLUMNS, null, null, null)).thenReturn(null);
        when(mResolver.query(
                        CHILDREN_URI, CHILD_COLUMNS, CHILD_SELECTION, CHILD_ARGUMENTS, null))
                .thenReturn(null);
        assertEquals("", path("").resolveToContentUriString());
        assertEquals("", path("/child").resolveToContentUriString());
        verifyRootQuery();
        verifyChildQuery();
        verifyNoInteractions(mCursor);
    }
}
