// Copyright 2026 The Reaper Authors.
// SPDX-License-Identifier: MPL-2.0

package org.chromium.chrome.browser.extensions;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.ViewGroup;

import org.jni_zero.CalledByNative;

import org.chromium.base.ActivityState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.app.ChromeActivity;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Android leases for the extension power API; owned by one regular profile's native service. */
@NullMarked
public final class ReaperExtensionPowerManager implements ApplicationStatus.ActivityStateListener {
    private static final String TAG = "ReaperExtensionPower";
    private static final long CPU_LOCK_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long CPU_LOCK_RENEWAL_DELAY_MS = CPU_LOCK_TIMEOUT_MS / 2;
    private final Handler mCpuLeaseHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRenewCpuLease = this::renewCpuLease;
    private final Profile mProfile;
    private final Map<Activity, View> mDisplayViews = new HashMap<>();
    private PowerManager.@Nullable WakeLock mCpuLock;
    private boolean mSystemRequested;
    private boolean mDisplayRequested;
    private boolean mDestroyed;

    @CalledByNative
    static ReaperExtensionPowerManager create(Profile profile) {
        return new ReaperExtensionPowerManager(profile);
    }

    private ReaperExtensionPowerManager(Profile profile) {
        ThreadUtils.assertOnUiThread();
        mProfile = profile;
        ApplicationStatus.registerStateListenerForAllActivities(this);
    }

    /**
     * New requests are transactional. Release/lifecycle updates revoke removed requests first,
     * even when Android subsequently rejects acquisition of a remaining display lease.
     */
    @CalledByNative
    String apply(
            boolean system, boolean display, boolean requireForegroundDisplay, boolean transactional) {
        ThreadUtils.assertOnUiThread();
        if (mDestroyed) return "The Android power manager has shut down.";

        if (!transactional) {
            if (!system) releaseCpu();
            for (Activity activity : new ArrayList<>(mDisplayViews.keySet())) {
                if (!display || !isEligibleForegroundActivity(activity)) {
                    View view = mDisplayViews.remove(activity);
                    if (view != null) removeView(view);
                }
            }
            mSystemRequested = system;
            mDisplayRequested = display;
        }

        List<Activity> foreground = new ArrayList<>();
        if (display) {
            for (Activity activity : ApplicationStatus.getRunningActivities()) {
                if (isEligibleForegroundActivity(activity)) foreground.add(activity);
            }
            if (requireForegroundDisplay && foreground.isEmpty()) {
                return "Display wake locks require a foreground browser window for this profile.";
            }
        }

        Map<Activity, View> addedViews = new HashMap<>();
        boolean acquiredCpu = false;
        try {
            for (Activity activity : foreground) {
                if (mDisplayViews.containsKey(activity)) continue;
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                // Own the view so releasing this lease never clears another component's flag.
                View lease = new View(activity);
                lease.setAlpha(0);
                lease.setClickable(false);
                lease.setFocusable(false);
                lease.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                lease.setKeepScreenOn(true);
                decor.addView(lease, new ViewGroup.LayoutParams(1, 1));
                addedViews.put(activity, lease);
            }
            if (system) {
                PowerManager.WakeLock cpuLock = mCpuLock;
                if (cpuLock == null) {
                    PowerManager manager =
                            (PowerManager) ContextUtils.getApplicationContext()
                                    .getSystemService(Context.POWER_SERVICE);
                    if (manager == null
                            || !manager.isWakeLockLevelSupported(PowerManager.PARTIAL_WAKE_LOCK)) {
                        removeViews(addedViews.values());
                        return "Android does not provide a CPU wake lock.";
                    }
                    cpuLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reaper:extensions");
                    cpuLock.setReferenceCounted(false);
                    mCpuLock = cpuLock;
                }
                boolean wasHeld = cpuLock.isHeld();
                // Uses Chrome's existing normal WAKE_LOCK permission. Android can still
                // suspend this process or ignore the lock under its power-saving policy.
                cpuLock.acquire(CPU_LOCK_TIMEOUT_MS);
                acquiredCpu = !wasHeld;
            }
        } catch (RuntimeException error) {
            removeViews(addedViews.values());
            if (acquiredCpu && !mSystemRequested) releaseCpu();
            Log.w(TAG, "Unable to acquire extension power lease", error);
            return "Android rejected the requested power lease.";
        }

        for (Activity activity : new ArrayList<>(mDisplayViews.keySet())) {
            if (!foreground.contains(activity)) {
                View view = mDisplayViews.remove(activity);
                if (view != null) removeView(view);
            }
        }
        mDisplayViews.putAll(addedViews);
        if (!system) releaseCpu();
        mSystemRequested = system;
        mDisplayRequested = display;
        if (system) scheduleCpuLeaseRenewal();
        return "";
    }

    private boolean isEligibleForegroundActivity(Activity activity) {
        if (!(activity instanceof ChromeActivity chromeActivity)
                || activity.isFinishing()
                || activity.isDestroyed()
                || ApplicationStatus.getStateForActivity(activity) != ActivityState.RESUMED
                || !(activity.getWindow().getDecorView() instanceof ViewGroup)) {
            return false;
        }
        ProfileProvider provider = chromeActivity.getProfileProviderSupplier().get();
        // PowerAPI deliberately shares regular/incognito requests upstream. Only windows
        // belonging to that same original profile may receive this profile's display lease.
        return provider != null && provider.getOriginalProfile() == mProfile;
    }

    @Override
    public void onActivityStateChange(Activity activity, int newState) {
        ThreadUtils.assertOnUiThread();
        if (mDestroyed || !mDisplayRequested) return;
        // Lifecycle revocation must not wait for acquisition on another window to
        // succeed. A failed replacement must never retain a paused/destroyed Activity.
        for (Activity previous : new ArrayList<>(mDisplayViews.keySet())) {
            if (!isEligibleForegroundActivity(previous)) {
                View view = mDisplayViews.remove(previous);
                if (view != null) removeView(view);
            }
        }
        String error = apply(mSystemRequested, mDisplayRequested, false, false);
        if (!error.isEmpty()) Log.w(TAG, error);
    }

    @CalledByNative
    void cancel() {
        ThreadUtils.assertOnUiThread();
        mSystemRequested = false;
        mDisplayRequested = false;
        releaseCpu();
        removeViews(mDisplayViews.values());
        mDisplayViews.clear();
    }

    @CalledByNative
    void destroy() {
        ThreadUtils.assertOnUiThread();
        if (mDestroyed) return;
        mDestroyed = true;
        ApplicationStatus.unregisterActivityStateListener(this);
        cancel();
    }

    private void scheduleCpuLeaseRenewal() {
        mCpuLeaseHandler.removeCallbacks(mRenewCpuLease);
        mCpuLeaseHandler.postDelayed(mRenewCpuLease, CPU_LOCK_RENEWAL_DELAY_MS);
    }

    private void renewCpuLease() {
        ThreadUtils.assertOnUiThread();
        if (mDestroyed || !mSystemRequested) return;
        PowerManager.WakeLock lock = mCpuLock;
        if (lock == null) return;
        try {
            // Non-reference-counted reacquisition extends the finite lease while the
            // native power API still owns the request; it does not add another owner.
            lock.acquire(CPU_LOCK_TIMEOUT_MS);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to renew extension CPU lease", error);
        }
        scheduleCpuLeaseRenewal();
    }

    private void releaseCpu() {
        mCpuLeaseHandler.removeCallbacks(mRenewCpuLease);
        PowerManager.WakeLock lock = mCpuLock;
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to release extension CPU lease", error);
        }
    }

    private static void removeViews(Iterable<View> views) {
        for (View view : views) removeView(view);
    }

    private static void removeView(View view) {
        try {
            view.setKeepScreenOn(false);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to clear extension display lease", error);
        }
        try {
            if (view.getParent() instanceof ViewGroup parent) parent.removeView(view);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to detach extension display lease", error);
        }
    }
}
