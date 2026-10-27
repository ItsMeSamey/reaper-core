// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.
package org.chromium.chrome.browser.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.webkit.MimeTypeMap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.GraphicsMode;

import org.chromium.base.test.BaseRobolectricTestRunner;

@RunWith(BaseRobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReaperDownloadFileIconTest {
    @Test
    public void mimeLookupUsesSuffixAndNormalizesCase() {
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("pdf", "application/pdf");
        assertEquals("application/pdf", ReaperDownloadFileIcon.mimeTypeForExtension(".PDF"));
        assertEquals("application/pdf", ReaperDownloadFileIcon.mimeTypeForExtension("pdf"));
    }

    @Test
    public void unknownAndMissingSuffixUsePlatformGenericType() {
        assertEquals("application/octet-stream", ReaperDownloadFileIcon.mimeTypeForExtension(""));
        assertEquals("application/octet-stream", ReaperDownloadFileIcon.mimeTypeForExtension(".unmapped_reaper"));
    }

    @Test
    public void rasterizesRequestedPixelSizesAndActualDrawablePixels() {
        for (int size : new int[] {16, 32, 64}) {
            Bitmap bitmap = ReaperDownloadFileIcon.render(new ColorDrawable(Color.RED), size);
            assertNotNull(bitmap);
            assertEquals(size, bitmap.getWidth());
            assertEquals(size, bitmap.getHeight());
            assertEquals(Color.RED, bitmap.getPixel(size / 2, size / 2));
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.getConfig());
        }
    }

    @Test
    public void invalidSizesDoNotAllocateBitmaps() {
        for (int size : new int[] {-1, 0, 257, Integer.MAX_VALUE}) {
            assertNull(ReaperDownloadFileIcon.render(new ColorDrawable(Color.RED), size));
        }
    }
}
