// Copyright 2026 The Reaper Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.extensions.api.messaging;

import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.OsConstants;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** One AIDL frame of serialized UTF-8 JSON, not desktop native-process pipe framing. */
public final class NativeMessagePayloadCodec {
    // Match Chromium's desktop host receive bound and mojom::kMaxMessageBytes send bound.
    public static final int MAX_FROM_HOST_BYTES = 1024 * 1024;
    public static final int MAX_TO_HOST_BYTES = 64 * 1024 * 1024;
    private static final int MAX_INLINE_BYTES = 64 * 1024;

    private NativeMessagePayloadCodec() {}

    /** Owns the sender descriptor until the one-way Binder transaction has marshalled it. */
    public static final class Encoded implements AutoCloseable {
        public final MessagePayload payload;
        public final int byteLength;
        private final SharedMemory mMemory;

        private Encoded(MessagePayload payload, int length, SharedMemory memory) {
            this.payload = payload;
            byteLength = length;
            mMemory = memory;
        }

        @Override
        public void close() {
            if (mMemory != null) mMemory.close();
        }
    }

    public static Encoded encodeToHost(String json) throws IOException {
        return encode(json, MAX_TO_HOST_BYTES);
    }

    public static Encoded encodeToBrowser(String json) throws IOException {
        return encode(json, MAX_FROM_HOST_BYTES);
    }

    public static String decodeFromHost(MessagePayload payload) throws IOException {
        return decode(payload, MAX_FROM_HOST_BYTES);
    }

    public static String decodeFromBrowser(MessagePayload payload) throws IOException {
        return decode(payload, MAX_TO_HOST_BYTES);
    }

    private static Encoded encode(String json, int maxBytes) throws IOException {
        int length = utf8Length(json, maxBytes);
        MessagePayload payload = new MessagePayload();
        if (length <= MAX_INLINE_BYTES) {
            payload.setInlineBytes(json.getBytes(StandardCharsets.UTF_8));
            return new Encoded(payload, length, null);
        }
        SharedMemory memory = null;
        boolean transferred = false;
        try {
            // Exact UTF-8 length has been validated before memory allocation or mapping.
            memory = SharedMemory.create("reaper-native-message", length);
            ByteBuffer mapped = memory.mapReadWrite();
            try {
                CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                CoderResult result = encoder.encode(CharBuffer.wrap(json), mapped, true);
                if (result.isError()) result.throwException();
                if (!result.isUnderflow()) throw new IOException("Invalid UTF-8 frame length");
                result = encoder.flush(mapped);
                if (result.isError()) result.throwException();
                if (!result.isUnderflow() || mapped.position() != length) {
                    throw new IOException("Invalid UTF-8 frame length");
                }
            } finally {
                SharedMemory.unmap(mapped);
            }
            if (!memory.setProtect(OsConstants.PROT_READ)) {
                throw new IOException("Unable to protect native message memory");
            }
            payload.setSharedMemory(memory);
            transferred = true;
            return new Encoded(payload, length, memory);
        } catch (ErrnoException | IllegalArgumentException | IllegalStateException e) {
            throw new IOException("Invalid native message memory", e);
        } finally {
            if (!transferred && memory != null) memory.close();
        }
    }

    private static String decode(MessagePayload payload, int maxBytes) throws IOException {
        if (payload == null) throw new IOException("Missing native message frame");
        byte[] bytes;
        try {
            switch (payload.getTag()) {
                case MessagePayload.inlineBytes:
                    byte[] inline = payload.getInlineBytes();
                    if (inline == null) throw new IOException("Missing native message bytes");
                    checkLength(inline.length, maxBytes);
                    bytes = inline.clone();
                    break;
                case MessagePayload.sharedMemory:
                    SharedMemory memory = payload.getSharedMemory();
                    if (memory == null) throw new IOException("Missing native message memory");
                    try {
                        int length = memory.getSize();
                        checkLength(length, maxBytes);
                        ByteBuffer mapped = memory.mapReadOnly();
                        try {
                            if (mapped.remaining() != length) {
                                throw new IOException("Invalid native message frame length");
                            }
                            bytes = new byte[length];
                            mapped.get(bytes);
                        } finally {
                            SharedMemory.unmap(mapped);
                        }
                    } finally {
                        memory.close();
                    }
                    break;
                default:
                    throw new IOException("Unknown native message payload variant");
            }
        } catch (ErrnoException | IllegalArgumentException | IllegalStateException e) {
            throw new IOException("Invalid native message memory", e);
        }
        // Snapshot first: a writable producer mapping cannot mutate the decoded String later.
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void checkLength(int length, int maxBytes) throws IOException {
        if (maxBytes <= 0 || length <= 0 || length > maxBytes) {
            throw new IOException("Native message exceeded bounds or has an empty frame");
        }
    }

    private static int utf8Length(String value, int maxBytes) throws IOException {
        if (value == null || maxBytes <= 0) throw new IOException("Invalid native message");
        long length = 0;
        for (int i = 0; i < value.length(); i++) {
            char item = value.charAt(i);
            if (item < 0x80) length++;
            else if (item < 0x800) length += 2;
            else if (Character.isHighSurrogate(item)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IOException("Malformed native message UTF-16");
                }
                length += 4;
            } else if (Character.isLowSurrogate(item)) {
                throw new IOException("Malformed native message UTF-16");
            } else length += 3;
            if (length > maxBytes) throw new IOException("Native message exceeded bounds");
        }
        checkLength((int) length, maxBytes);
        return (int) length;
    }
}
