// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.download;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.webkit.MimeTypeMap;

import androidx.annotation.VisibleForTesting;

import org.jni_zero.CalledByNative;
import org.jni_zero.JniType;

import org.chromium.base.ContextUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;

import java.util.Locale;

/** Android's own MIME-type artwork, without opening downloads or querying installed applications. */
@NullMarked
public final class ReaperDownloadFileIcon {
    private static final int MAX_ICON_PIXELS = 256;

    private ReaperDownloadFileIcon() {}

    @CalledByNative
    static @Nullable Bitmap loadIcon(@JniType("std::string") String extension, int sizePixels) {
        ThreadUtils.assertOnUiThread();
        if (sizePixels < 1 || sizePixels > MAX_ICON_PIXELS) return null;
        try {
            Context context = ContextUtils.getApplicationContext();
            Drawable drawable = context.getContentResolver()
                    .getTypeInfo(mimeTypeForExtension(extension)).getIcon().loadDrawable(context);
            return drawable == null ? null : render(drawable, sizePixels);
        } catch (RuntimeException | OutOfMemoryError failure) {
            // IconLoader delivers an empty image, preserving downloads.getFileIcon's normal error.
            return null;
        }
    }

    @VisibleForTesting
    static String mimeTypeForExtension(String extension) {
        String suffix = extension.startsWith(".") ? extension.substring(1) : extension;
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                suffix.toLowerCase(Locale.ROOT));
        // The platform supplies its actual generic file icon when a suffix has no MIME mapping.
        return mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
    }

    @VisibleForTesting
    static @Nullable Bitmap render(Drawable drawable, int sizePixels) {
        if (sizePixels < 1 || sizePixels > MAX_ICON_PIXELS) return null;
        Bitmap bitmap = Bitmap.createBitmap(sizePixels, sizePixels, Bitmap.Config.ARGB_8888);
        Drawable isolated = drawable.mutate();
        int width = isolated.getIntrinsicWidth();
        int height = isolated.getIntrinsicHeight();
        if (width <= 0 || height <= 0) width = height = sizePixels;
        float scale = (float) sizePixels / Math.max(width, height);
        int drawWidth = Math.max(1, Math.round(width * scale));
        int drawHeight = Math.max(1, Math.round(height * scale));
        int left = (sizePixels - drawWidth) / 2;
        int top = (sizePixels - drawHeight) / 2;
        isolated.setBounds(left, top, left + drawWidth, top + drawHeight);
        isolated.draw(new Canvas(bitmap));
        return bitmap;
    }
}
