// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.

package org.chromium.chrome.browser.containers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.profiles.Profile;

/** Native identity-restore recorder for tab-store tests; the real Java bridge still runs. */
public final class ContainersBridgeRestoreTestSupport {
    public boolean restoreSucceeded;
    public @Nullable Profile restoredProfile;
    public @Nullable String restoredContainerId;
    public int restoreCallCount;

    public ContainersBridgeRestoreTestSupport() {
        ContainersBridge.Natives nativeMock =
                mock(
                        ContainersBridge.Natives.class,
                        invocation -> {
                            throw new AssertionError(
                                    "Unexpected ContainersBridge native call: "
                                            + invocation.getMethod().getName());
                        });
        doAnswer(
                        invocation -> {
                            restoredProfile = invocation.getArgument(0);
                            restoredContainerId = invocation.getArgument(1);
                            restoreCallCount++;
                            return restoreSucceeded;
                        })
                .when(nativeMock)
                .restoreContainerIdentity(any(), any());
        ContainersBridgeJni.setInstanceForTesting(nativeMock);
    }
}
