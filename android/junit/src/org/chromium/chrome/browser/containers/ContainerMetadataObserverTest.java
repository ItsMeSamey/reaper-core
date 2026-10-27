// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
package org.chromium.chrome.browser.containers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.components.prefs.PrefChangeRegistrar;
import org.chromium.components.prefs.PrefChangeRegistrarJni;
import org.chromium.components.prefs.PrefService;
import org.chromium.components.user_prefs.UserPrefs;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Real registrar callbacks with mocked JNI; no production testing facade. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ContainerMetadataObserverTest {
    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock private PrefChangeRegistrar.Natives mNative;
    @Mock private PrefService mPrefService;
    @Mock private Profile mProfile;
    @Mock private Profile mOtherProfile;
    @Mock private Runnable mOnChanged;
    private final List<PrefChangeRegistrar> mRegistrars = new ArrayList<>();
    private ContainerMetadataObserver mObserver;

    @Before
    public void setUp() {
        PrefChangeRegistrarJni.setInstanceForTesting(mNative);
        UserPrefs.setPrefServiceForTesting(mPrefService);
        when(mProfile.getNativeBrowserContextPointer()).thenReturn(1L);
        when(mOtherProfile.getNativeBrowserContextPointer()).thenReturn(2L);
        doAnswer(
                        invocation -> {
                            mRegistrars.add(invocation.getArgument(0));
                            return 99L + mRegistrars.size();
                        })
                .when(mNative)
                .init(any(), eq(mPrefService));
        mObserver = new ContainerMetadataObserver(mOnChanged);
    }

    @After
    public void tearDown() {
        mObserver.destroy();
    }

    @Test
    public void testListAndUsedChangesRefreshWithoutControlsPreference() throws Exception {
        mObserver.setProfile(mProfile);
        verify(mNative).add(100L, ContainersBridge.PREF_CONTAINERS_LIST);
        verify(mNative).add(100L, ContainersBridge.PREF_CONTAINERS_USED);
        verify(mNative, never()).add(100L, ContainersBridge.PREF_CONTAINERS_ENABLED);
        notifyChange(mRegistrars.get(0), ContainersBridge.PREF_CONTAINERS_LIST);
        notifyChange(mRegistrars.get(0), ContainersBridge.PREF_CONTAINERS_USED);
        verify(mOnChanged, times(2)).run();
    }

    @Test
    public void testProfileReplacementDestroysRegistrarAndRejectsOldCallback() throws Exception {
        mObserver.setProfile(mProfile);
        mObserver.setProfile(mOtherProfile);
        verify(mNative).destroy(100L);
        notifyChange(mRegistrars.get(0), ContainersBridge.PREF_CONTAINERS_USED);
        verify(mOnChanged, never()).run();
        notifyChange(mRegistrars.get(1), ContainersBridge.PREF_CONTAINERS_USED);
        verify(mOnChanged).run();
    }

    @Test
    public void testSameProfileDoesNotDuplicateNativeRegistrar() {
        mObserver.setProfile(mProfile);
        mObserver.setProfile(mProfile);
        verify(mNative).init(any(), eq(mPrefService));
        verify(mNative, never()).destroy(100L);
    }

    @Test
    public void testDestroyRejectsQueuedAndProfileCallbacks() throws Exception {
        mObserver.setProfile(mProfile);
        mObserver.destroy();
        mObserver.destroy();
        notifyChange(mRegistrars.get(0), ContainersBridge.PREF_CONTAINERS_LIST);
        ProfileManager.onProfileDestroyed(mProfile);
        verify(mNative).destroy(100L);
        verify(mOnChanged, never()).run();
    }

    @Test
    public void testProfileDestructionClearsRegistrarAndRefreshesOnce() throws Exception {
        mObserver.setProfile(mProfile);
        ProfileManager.onProfileDestroyed(mOtherProfile);
        verify(mOnChanged, never()).run();
        when(mProfile.shutdownStarted()).thenReturn(true);
        ProfileManager.onProfileDestroyed(mProfile);
        verify(mNative).destroy(100L);
        notifyChange(mRegistrars.get(0), ContainersBridge.PREF_CONTAINERS_USED);
        verify(mOnChanged).run();
    }

    @Test
    public void testOffTheRecordAndUnavailableProfilesNeverCreateRegistrar() {
        when(mProfile.isOffTheRecord()).thenReturn(true);
        mObserver.setProfile(mProfile);
        when(mProfile.isOffTheRecord()).thenReturn(false);
        when(mProfile.shutdownStarted()).thenReturn(true);
        mObserver.setProfile(mProfile);
        when(mProfile.shutdownStarted()).thenReturn(false);
        when(mProfile.getNativeBrowserContextPointer()).thenReturn(0L);
        mObserver.setProfile(mProfile);
        mObserver.setProfile(null);
        verify(mNative, never()).init(any(), any());
    }

    @Test
    public void testShutdownAndZeroPointerRejectQueuedMetadata() throws Exception {
        mObserver.setProfile(mProfile);
        when(mProfile.shutdownStarted()).thenReturn(true);
        notifyChange(mRegistrars.get(0), ContainersBridge.PREF_CONTAINERS_LIST);
        when(mProfile.shutdownStarted()).thenReturn(false);
        when(mProfile.getNativeBrowserContextPointer()).thenReturn(0L);
        notifyChange(mRegistrars.get(0), ContainersBridge.PREF_CONTAINERS_USED);
        verify(mOnChanged, never()).run();
    }

    @Test
    public void testSettingsAlsoObservesControlsVisibility() throws Exception {
        mObserver.destroy();
        mObserver = new ContainerMetadataObserver(mOnChanged, true);
        mObserver.setProfile(mProfile);
        verify(mNative).add(100L, ContainersBridge.PREF_CONTAINERS_ENABLED);
        notifyChange(mRegistrars.get(0), ContainersBridge.PREF_CONTAINERS_ENABLED);
        verify(mOnChanged).run();
    }

    private static void notifyChange(PrefChangeRegistrar registrar, String preference)
            throws Exception {
        Method callback =
                PrefChangeRegistrar.class.getDeclaredMethod("onPreferenceChange", String.class);
        callback.setAccessible(true);
        callback.invoke(registrar, preference);
    }
}
