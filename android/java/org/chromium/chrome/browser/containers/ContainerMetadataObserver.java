// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
package org.chromium.chrome.browser.containers;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.components.prefs.PrefChangeRegistrar;
import org.chromium.components.user_prefs.UserPrefs;

/** Observes native metadata only while its owning controller and profile are alive. */
@NullMarked
public final class ContainerMetadataObserver {
    private final Runnable mOnChanged;
    private final boolean mObserveControlsVisibility;
    private @Nullable Profile mProfile;
    private @Nullable PrefChangeRegistrar mRegistrar;
    private boolean mDestroyed;
    private final ProfileManager.Observer mProfileObserver =
            new ProfileManager.Observer() {
                @Override
                public void onProfileAdded(Profile profile) {}

                @Override
                public void onProfileDestroyed(Profile profile) {
                    if (profile != mProfile) return;
                    clearProfile();
                    if (!mDestroyed) mOnChanged.run();
                }
            };

    public ContainerMetadataObserver(Runnable onChanged) {
        this(onChanged, false);
    }

    /** Settings may also observe control visibility; identity badges never depend on it. */
    public ContainerMetadataObserver(Runnable onChanged, boolean observeControlsVisibility) {
        mOnChanged = onChanged;
        mObserveControlsVisibility = observeControlsVisibility;
        ProfileManager.addObserver(mProfileObserver);
    }

    public void setProfile(@Nullable Profile profile) {
        if (mDestroyed || profile == mProfile) return;
        clearProfile();
        if (profile == null
                || profile.shutdownStarted()
                || profile.isOffTheRecord()
                || profile.getNativeBrowserContextPointer() == 0) return;
        mProfile = profile;
        PrefChangeRegistrar registrar = new PrefChangeRegistrar(UserPrefs.get(profile));
        mRegistrar = registrar;
        registrar.addObserver(
                ContainersBridge.PREF_CONTAINERS_LIST, () -> onMetadataChanged(profile));
        registrar.addObserver(
                ContainersBridge.PREF_CONTAINERS_USED, () -> onMetadataChanged(profile));
        if (mObserveControlsVisibility) {
            registrar.addObserver(
                    ContainersBridge.PREF_CONTAINERS_ENABLED, () -> onMetadataChanged(profile));
        }
    }

    private void onMetadataChanged(Profile observedProfile) {
        // A callback already queued by a replaced registrar must not refresh the new profile.
        if (!mDestroyed
                && mProfile == observedProfile
                && !observedProfile.shutdownStarted()
                && observedProfile.getNativeBrowserContextPointer() != 0) mOnChanged.run();
    }

    private void clearProfile() {
        if (mRegistrar != null) {
            mRegistrar.destroy();
            mRegistrar = null;
        }
        mProfile = null;
    }

    public void destroy() {
        if (mDestroyed) return;
        mDestroyed = true;
        clearProfile();
        ProfileManager.removeObserver(mProfileObserver);
    }
}
