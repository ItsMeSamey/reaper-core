// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package org.chromium.chrome.browser.extensions.shortcuts;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Tracks registrations and only the currently consumed physical key presses. */
@NullMarked
final class ConsumedShortcutKeys {
    private static final class Pair {
        final int first;
        final int second;

        Pair(int first, int second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Pair
                    && first == ((Pair) other).first
                    && second == ((Pair) other).second;
        }

        @Override
        public int hashCode() {
            return 31 * first + second;
        }
    }

    private final Set<Pair> mRegistered = new HashSet<>();
    // One active press per physical device/key, never a log of completed events.
    private final Map<Pair, Long> mConsumedDownTimes = new HashMap<>();
    private long mConnectionEpoch;

    boolean setRegistration(int keyCode, int modifiers, boolean registered) {
        if (keyCode <= 0) return false;
        Pair accelerator = new Pair(keyCode, modifiers);
        if (registered) {
            mRegistered.add(accelerator);
        } else {
            mRegistered.remove(accelerator);
        }
        return true;
    }

    boolean onKeyDown(
            int deviceId,
            int keyCode,
            long downTime,
            int repeatCount,
            int modifiers,
            BooleanSupplier dispatch) {
        Pair physicalKey = new Pair(deviceId, keyCode);
        Long consumedTime = mConsumedDownTimes.get(physicalKey);
        if (consumedTime != null && consumedTime == downTime) return true;
        // A repeat without the initial consumed DOWN cannot start a command.
        if (repeatCount != 0) return false;
        mConsumedDownTimes.remove(physicalKey);
        if (!mRegistered.contains(new Pair(keyCode, modifiers))) return false;
        long epoch = mConnectionEpoch;
        boolean accepted = dispatch.getAsBoolean();
        // Dispatch can synchronously disable the backend or disconnect the OS
        // service. Keep pairing only while the same OS connection still exists.
        if (accepted && epoch == mConnectionEpoch) {
            mConsumedDownTimes.put(physicalKey, downTime);
        }
        return accepted;
    }

    boolean onKeyUp(int deviceId, int keyCode, long downTime) {
        Pair physicalKey = new Pair(deviceId, keyCode);
        Long consumedTime = mConsumedDownTimes.get(physicalKey);
        if (consumedTime == null || consumedTime != downTime) return false;
        mConsumedDownTimes.remove(physicalKey);
        return true;
    }

    void removeDevice(int deviceId) {
        ++mConnectionEpoch;
        mConsumedDownTimes.keySet().removeIf(key -> key.first == deviceId);
    }

    void clearRegistrations() {
        mRegistered.clear();
    }

    void resetConnection() {
        ++mConnectionEpoch;
        mRegistered.clear();
        mConsumedDownTimes.clear();
    }
}
