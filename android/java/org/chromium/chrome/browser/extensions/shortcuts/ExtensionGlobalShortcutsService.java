// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package org.chromium.chrome.browser.extensions.shortcuts;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.RequiresApi;

import org.chromium.base.ContextUtils;
import org.chromium.base.ObserverList;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.firstrun.FirstRunStatus;
import org.chromium.chrome.browser.init.ChromeBrowserInitializer;
import org.chromium.chrome.browser.init.EmptyBrowserParts;
import org.chromium.chrome.browser.preferences.ChromePreferenceKeys;
import org.chromium.chrome.browser.profiles.ProfileManager;

/** Optional key filtering, connected exclusively by Android after explicit user enablement. */
@NullMarked
public class ExtensionGlobalShortcutsService extends AccessibilityService {
    public enum Status {
        DISABLED,
        ENABLED_NOT_CONNECTED,
        CONNECTED,
        CONFLICT,
        UNAVAILABLE
    }

    private static final ObserverList<Runnable> sStatusObservers = new ObserverList<>();
    private static @Nullable ExtensionGlobalShortcutsService sConnectedService;

    private @Nullable InputManager mInputManager;
    private @Nullable ExtensionGlobalShortcutsBridge mBridge;
    private @Nullable Runnable mRemoveServicesListener;
    private boolean mConnected;
    private boolean mDestroyed;
    private boolean mWatchingSettings;
    private boolean mStarting;
    private boolean mBackendFailed;
    private int mStartupGeneration;
    private Status mPlatformStatus = Status.UNAVAILABLE;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ConsumedShortcutKeys mKeys = new ConsumedShortcutKeys();
    private final ContentObserver mSettingsObserver =
            new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    refreshConnection();
                }
            };
    private final SharedPreferences.OnSharedPreferenceChangeListener mFirstRunObserver =
            (preferences, key) -> {
                if (key == null || ChromePreferenceKeys.FIRST_RUN_FLOW_COMPLETE.equals(key)) {
                    mHandler.post(
                            () -> {
                                if (!mConnected || mDestroyed) return;
                                mBackendFailed = false;
                                refreshConnection();
                            });
                }
            };

    private final InputManager.InputDeviceListener mInputDeviceListener =
            new InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {}

                @Override
                public void onInputDeviceChanged(int deviceId) {}

                @Override
                public void onInputDeviceRemoved(int deviceId) {
                    mKeys.removeDevice(deviceId);
                }
            };

    public static Status getStatus(Context context) {
        ThreadUtils.assertOnUiThread();
        Status platform = readPlatformStatus(context);
        if (platform != Status.ENABLED_NOT_CONNECTED) return platform;
        ExtensionGlobalShortcutsService service = sConnectedService;
        if (service == null || !service.mConnected) return Status.ENABLED_NOT_CONNECTED;
        if (service.mBackendFailed) return Status.UNAVAILABLE;
        ExtensionGlobalShortcutsBridge bridge = service.mBridge;
        return bridge != null && bridge.isReady() ? Status.CONNECTED : Status.ENABLED_NOT_CONNECTED;
    }

    public static void addStatusObserver(Runnable observer) {
        ThreadUtils.assertOnUiThread();
        sStatusObservers.addObserver(observer);
    }

    public static void removeStatusObserver(Runnable observer) {
        ThreadUtils.assertOnUiThread();
        sStatusObservers.removeObserver(observer);
    }

    private static void notifyStatusChanged() {
        for (Runnable observer : sStatusObservers) observer.run();
    }

    static Status readPlatformStatus(Context context) {
        try {
            AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
            if (manager == null) return Status.UNAVAILABLE;
            ComponentName ours = new ComponentName(context, ExtensionGlobalShortcutsService.class);
            boolean enabled = false;
            boolean conflicting = false;
            boolean configured = false;
            for (AccessibilityServiceInfo info :
                    manager.getEnabledAccessibilityServiceList(
                            AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                ResolveInfo resolve = info.getResolveInfo();
                if (resolve == null || resolve.serviceInfo == null) continue;
                ComponentName component =
                        new ComponentName(
                                resolve.serviceInfo.packageName, resolve.serviceInfo.name);
                boolean filtersKeys =
                        (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
                                && (info.getCapabilities()
                                                & AccessibilityServiceInfo
                                                        .CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS)
                                        != 0;
                if (component.equals(ours)) {
                    enabled = true;
                    configured = filtersKeys;
                } else if (filtersKeys) {
                    conflicting = true;
                }
            }
            if (!enabled) return Status.DISABLED;
            if (conflicting) return Status.CONFLICT;
            return configured ? Status.ENABLED_NOT_CONNECTED : Status.UNAVAILABLE;
        } catch (RuntimeException unavailable) {
            return Status.UNAVAILABLE;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (mDestroyed) return;
        disconnect();
        mConnected = true;
        mBackendFailed = false;
        sConnectedService = this;
        try {
            observePlatformState();
            refreshConnection();
        } catch (RuntimeException unavailable) {
            mBackendFailed = true;
            retireBackend();
            notifyStatusChanged();
        }
    }

    @SuppressWarnings("UseSharedPreferencesManagerFromChromeCheck")
    private void observePlatformState() {
        // Read-only observers; neither Android enablement nor browser prefs are written.
        mWatchingSettings = true;
        getContentResolver()
                .registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                        false,
                        mSettingsObserver);
        getContentResolver()
                .registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                        false,
                        mSettingsObserver);
        ContextUtils.getAppSharedPreferences()
                .registerOnSharedPreferenceChangeListener(mFirstRunObserver);
        mInputManager = getSystemService(InputManager.class);
        if (mInputManager != null) {
            mInputManager.registerInputDeviceListener(mInputDeviceListener, mHandler);
        }
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && manager != null) {
            mRemoveServicesListener = Api33.observeServices(manager, this::refreshConnection);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static final class Api33 {
        static Runnable observeServices(AccessibilityManager manager, Runnable changed) {
            AccessibilityManager.AccessibilityServicesStateChangeListener listener =
                    ignored -> changed.run();
            manager.addAccessibilityServicesStateChangeListener(listener);
            return () -> manager.removeAccessibilityServicesStateChangeListener(listener);
        }
    }

    private void refreshConnection() {
        if (!mConnected || mDestroyed) return;
        Status platform = readPlatformStatus(this);
        if (platform != mPlatformStatus) mBackendFailed = false;
        mPlatformStatus = platform;
        if (platform != Status.ENABLED_NOT_CONNECTED) {
            retireBackend();
            notifyStatusChanged();
            return;
        }
        if (!FirstRunStatus.getFirstRunFlowComplete()) {
            retireBackend();
            mBackendFailed = true;
        } else if (mBridge == null && !mStarting && !mBackendFailed) {
            ensureBrowser();
        }
        notifyStatusChanged();
    }

    private void ensureBrowser() {
        if (!FirstRunStatus.getFirstRunFlowComplete()) {
            mBackendFailed = true;
            return;
        }
        mStarting = true;
        int generation = ++mStartupGeneration;
        EmptyBrowserParts parts =
                new EmptyBrowserParts() {
                    @Override
                    public boolean isActivityFinishingOrDestroyed() {
                        return mDestroyed || !mConnected || generation != mStartupGeneration;
                    }

                    @Override
                    public void finishNativeInitialization() {
                        if (isActivityFinishingOrDestroyed()) return;
                        mStarting = false;
                        if (!FirstRunStatus.getFirstRunFlowComplete()
                                || readPlatformStatus(ExtensionGlobalShortcutsService.this)
                                        != Status.ENABLED_NOT_CONNECTED) {
                            refreshConnection();
                            return;
                        }
                        try {
                            ExtensionGlobalShortcutsBridge bridge =
                                    new ExtensionGlobalShortcutsBridge(
                                            ProfileManager.getLastUsedRegularProfile(),
                                            mKeys,
                                            () -> onBackendUnavailable(generation));
                            if (isActivityFinishingOrDestroyed()) {
                                bridge.destroy();
                                return;
                            }
                            if (bridge.isReady()) {
                                mBridge = bridge;
                            } else {
                                bridge.destroy();
                                mBackendFailed = true;
                            }
                        } catch (RuntimeException unavailable) {
                            mKeys.clearRegistrations();
                            mBackendFailed = true;
                        }
                        notifyStatusChanged();
                    }

                    @Override
                    public void onStartupFailure(@Nullable Exception failure) {
                        if (isActivityFinishingOrDestroyed()) return;
                        retireBackend();
                        mBackendFailed = true;
                        notifyStatusChanged();
                    }
                };
        try {
            startBrowser(parts);
        } catch (RuntimeException unavailable) {
            if (generation == mStartupGeneration) {
                retireBackend();
                mBackendFailed = true;
            }
        }
    }

    void startBrowser(EmptyBrowserParts parts) {
        ChromeBrowserInitializer.getInstance().handlePreNativeStartupAndLoadLibraries(parts);
        ChromeBrowserInitializer.getInstance().handlePostNativeStartup(true, parts);
    }

    private void onBackendUnavailable(int generation) {
        if (generation != mStartupGeneration || !mConnected || mDestroyed) return;
        retireBackend();
        mBackendFailed = true;
        notifyStatusChanged();
    }

    private void retireBackend() {
        ++mStartupGeneration;
        mStarting = false;
        mKeys.clearRegistrations();
        ExtensionGlobalShortcutsBridge bridge = mBridge;
        mBridge = null;
        if (bridge != null) bridge.destroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!mConnected || mDestroyed) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return mKeys.onKeyUp(event.getDeviceId(), event.getKeyCode(), event.getDownTime());
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        return mKeys.onKeyDown(
                event.getDeviceId(),
                event.getKeyCode(),
                event.getDownTime(),
                event.getRepeatCount(),
                ExtensionGlobalShortcutsBridge.normalizeModifiers(event.getMetaState()),
                () -> {
                    // Recheck before each new matched DOWN, including on Android
                    // versions without the services-state-change listener. A
                    // mid-press backend loss still consumes its paired UP.
                    if (!FirstRunStatus.getFirstRunFlowComplete()
                            || readPlatformStatus(this) != Status.ENABLED_NOT_CONNECTED) {
                        refreshConnection();
                        return false;
                    }
                    ExtensionGlobalShortcutsBridge bridge = mBridge;
                    return bridge != null && bridge.dispatchKeyDown(event);
                });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No accessibility events or window content are inspected.
    }

    @Override
    public void onInterrupt() {
        // No spoken or other accessibility feedback to interrupt.
    }

    @SuppressWarnings("UseSharedPreferencesManagerFromChromeCheck")
    private void disconnect() {
        mConnected = false;
        mKeys.resetConnection();
        retireBackend();
        mHandler.removeCallbacksAndMessages(null);
        if (mWatchingSettings) {
            mWatchingSettings = false;
            getContentResolver().unregisterContentObserver(mSettingsObserver);
            ContextUtils.getAppSharedPreferences()
                    .unregisterOnSharedPreferenceChangeListener(mFirstRunObserver);
        }
        if (mInputManager != null) {
            mInputManager.unregisterInputDeviceListener(mInputDeviceListener);
            mInputManager = null;
        }
        Runnable remove = mRemoveServicesListener;
        mRemoveServicesListener = null;
        if (remove != null) remove.run();
        if (sConnectedService == this) sConnectedService = null;
        notifyStatusChanged();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        disconnect();
        super.onDestroy();
    }
}
