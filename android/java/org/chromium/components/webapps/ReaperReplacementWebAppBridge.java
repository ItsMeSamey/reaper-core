// Copyright 2026 The Reaper Authors.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.components.webapps;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;
import org.jni_zero.NativeMethods;

import org.chromium.base.ActivityState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.ContextUtils;
import org.chromium.base.IntentUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.chrome.browser.BravePwaShortcutIconHelper;
import org.chromium.chrome.browser.IntentHandler;
import org.chromium.chrome.browser.ShortcutHelper;
import org.chromium.chrome.browser.browserservices.intents.BrowserServicesIntentDataProvider;
import org.chromium.chrome.browser.browserservices.intents.WebappConstants;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.chrome.browser.webapps.WebappIntentDataProviderFactory;
import org.chromium.chrome.browser.webapps.WebappLauncherActivity;
import org.chromium.chrome.browser.webapps.WebappRegistry;
import org.chromium.components.embedder_support.util.Origin;
import org.chromium.content_public.browser.WebContents;
import org.chromium.content_public.browser.Visibility;
import org.chromium.ui.base.WindowAndroid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Chromium install consent followed by a confirmed Android standalone PWA pin. */
@JNINamespace("extensions")
public final class ReaperReplacementWebAppBridge implements AddToHomescreenViewDelegate {
    private final Context mContext = ContextUtils.getApplicationContext();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final WebContents mContents;
    private final Profile mProfile;
    private final String mId;
    private final String mStartUrl;
    private final String mScope;
    private final String mName;
    private final String mShortName;
    private final Bitmap mIcon;
    private final boolean mMaskable;
    private final int mDisplay;
    private final int mOrientation;
    private final long mTheme;
    private final long mBackground;
    private long mRequestId;
    private AddToHomescreenDialogView mView;
    private BroadcastReceiver mReceiver;
    private PendingIntent mPendingIntent;
    private boolean mAccepted;
    private boolean mPinConfirmed;
    private boolean mReceiverRegistered;

    private ReaperReplacementWebAppBridge(long requestId, WebContents contents, Profile profile,
            String manifestId, String startUrl, String scope, String name, String shortName,
            Bitmap icon, boolean maskable, int display, int orientation, long theme,
            long background) {
        mRequestId = requestId;
        mContents = contents;
        mProfile = profile;
        mId = stableId(manifestId);
        mStartUrl = startUrl;
        mScope = scope;
        mName = name;
        mShortName = shortName;
        mIcon = icon;
        mMaskable = maskable;
        mDisplay = display;
        mOrientation = orientation;
        mTheme = theme;
        mBackground = background;
    }

    // WebappActivity uses the initial regular profile. Never silently turn an
    // incognito or another profile's extension call into an initial-profile app.
    @CalledByNative
    static boolean canInstall(Profile profile) {
        ThreadUtils.assertOnUiThread();
        return profile != null && !profile.shutdownStarted() && !profile.isOffTheRecord()
                && profile.isInitialProfile()
                && profile.equals(ProfileManager.getLastUsedRegularProfile());
    }

    @CalledByNative
    static ReaperReplacementWebAppBridge create(long requestId, WebContents contents,
            Profile profile, String manifestId, String startUrl, String scope, String name,
            String shortName, Bitmap icon, boolean maskable, int display, int orientation,
            long theme, long background, String displayOrigin) {
        ThreadUtils.assertOnUiThread();
        ReaperReplacementWebAppBridge bridge = new ReaperReplacementWebAppBridge(requestId,
                contents, profile, manifestId, startUrl, scope, name, shortName, icon, maskable,
                display, orientation, theme, background);
        // Post so the native owner stores the Java reference before any completion.
        bridge.mHandler.post(() -> bridge.start(displayOrigin));
        return bridge;
    }

    private boolean isCurrent() {
        return mRequestId != 0 && canInstall(mProfile) && !mContents.isDestroyed()
                && ReaperReplacementWebAppBridgeJni.get().isCurrent(mRequestId);
    }

    private Activity getForegroundActivity() {
        if (!isCurrent() || mContents.getVisibility() != Visibility.VISIBLE) return null;
        WindowAndroid window = mContents.getTopLevelNativeWindow();
        Activity activity = window == null ? null : window.getActivity().get();
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                        && ApplicationStatus.getStateForActivity(activity) == ActivityState.RESUMED
                ? activity
                : null;
    }

    private ShortcutManager manager() {
        return mContext.getSystemService(ShortcutManager.class);
    }

    static String stableId(String manifestId) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    manifestId.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("reaper.webapp.v1.");
            for (byte value : bytes) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Intent findPinnedIntent() {
        ShortcutManager manager = manager();
        if (manager == null) return null;
        for (ShortcutInfo shortcut : manager.getPinnedShortcuts()) {
            if (!mId.equals(shortcut.getId()) || !shortcut.isPinned() || !shortcut.isEnabled()) {
                continue;
            }
            Intent intent = shortcut.getIntent();
            if (intent == null || !mContext.getPackageName().equals(intent.getPackage())
                    || !WebappLauncherActivity.ACTION_START_WEBAPP.equals(intent.getAction())
                    || !mId.equals(intent.getStringExtra(WebappConstants.EXTRA_ID))
                    || !hasExpectedOriginAndScope(intent)) {
                return null;
            }
            return intent;
        }
        return null;
    }

    private boolean hasExpectedOriginAndScope(Intent intent) {
        String start = intent.getStringExtra(WebappConstants.EXTRA_URL);
        String scope = intent.getStringExtra(WebappConstants.EXTRA_SCOPE);
        if (start == null || scope == null || !start.startsWith(scope)) return false;
        Origin expected = Origin.create(mStartUrl);
        Origin storedStart = Origin.create(start);
        Origin storedScope = Origin.create(scope);
        // The exact manifest-derived ID proves identity. Like desktop's installed
        // app path, launch its stored start URL even if the live manifest changed.
        return expected != null && expected.equals(storedStart) && expected.equals(storedScope);
    }

    private void start(String displayOrigin) {
        if (getForegroundActivity() == null) {
            finish(false);
            return;
        }
        try {
            Intent pinned = findPinnedIntent();
            if (pinned != null) {
                registerConfirmed(pinned, true);
                return;
            }
            if (manager() == null || !manager().isRequestPinShortcutSupported()) {
                finish(false);
                return;
            }
            WindowAndroid window = mContents.getTopLevelNativeWindow();
            if (window == null || window.getModalDialogManager() == null) {
                finish(false);
                return;
            }
            mView = new AddToHomescreenDialogView(
                    getForegroundActivity(), window.getModalDialogManager(), this);
            // Reuse the crafted-webapp view: a fixed manifest name, origin, icon
            // and Install button; no synthetic editable bookmark fallback.
            mView.setType(AppType.WEBAPK);
            mView.setTitle(mName);
            mView.setUrl(displayOrigin);
            mView.setIcon(mIcon, mMaskable);
            mView.setCanSubmit(true);
        } catch (RuntimeException failure) {
            finish(false);
        }
    }

    @Override
    public void onAddToHomescreen(String ignoredTitle) {
        if (mAccepted) return;
        if (getForegroundActivity() == null) {
            finish(false);
            return;
        }
        mAccepted = true;
        // The existing dialog dismisses itself after this delegate returns.
        mView = null;
        new AsyncTask<Intent>() {
            @Override
            protected Intent doInBackground() {
                try {
                    String encoded =
                            BravePwaShortcutIconHelper.encodeDownscaledBitmapAsString(mIcon);
                    if (encoded.length() > 60000) return null;
                    Intent intent =
                            ShortcutHelper.createWebappShortcutIntent(mId, mStartUrl, mScope, mName,
                                    mShortName, encoded, WebappConstants.WEBAPP_SHORTCUT_VERSION,
                                    mDisplay, mOrientation, mTheme, mBackground, false, mMaskable);
                    intent.putExtra(WebappConstants.EXTRA_MAC,
                            ShortcutHelper.getEncodedMac(mStartUrl, encoded));
                    intent.putExtra(WebappConstants.EXTRA_SOURCE,
                            ShortcutSource.ADD_TO_HOMESCREEN_STANDALONE);
                    return intent;
                } catch (RuntimeException failure) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Intent intent) {
                if (mRequestId == 0) return;
                if (intent == null || getForegroundActivity() == null) {
                    finish(false);
                    return;
                }
                requestPin(intent);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void requestPin(Intent shortcutIntent) {
        try {
            ShortcutManager manager = manager();
            if (manager == null || !manager.isRequestPinShortcutSupported()) {
                finish(false);
                return;
            }
            String action =
                    mContext.getPackageName() + ".REPLACEMENT_WEBAPP_PIN." + UUID.randomUUID();
            Intent result = new Intent(action).setPackage(mContext.getPackageName());
            IntentUtils.addTrustedIntentExtras(result);
            mReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!IntentUtils.isTrustedIntentFromSelf(intent) || !isCurrent()
                            || mPinConfirmed)
                        return;
                    try {
                        Intent pinned = findPinnedIntent();
                        if (pinned == null) {
                            finish(false);
                            return;
                        }
                        mPinConfirmed = true;
                        unregisterPinReceiver();
                        registerConfirmed(pinned, false);
                    } catch (RuntimeException failure) {
                        finish(false);
                    }
                }
            };
            ContextUtils.registerNonExportedBroadcastReceiver(
                    mContext, mReceiver, new IntentFilter(action));
            mReceiverRegistered = true;
            mPendingIntent = PendingIntent.getBroadcast(mContext, 0, result,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            ShortcutInfo shortcut =
                    new ShortcutInfo.Builder(mContext, mId)
                            .setShortLabel(mShortName.isEmpty() ? mName : mShortName)
                            .setLongLabel(mName)
                            .setIcon(mMaskable ? Icon.createWithAdaptiveBitmap(mIcon)
                                               : Icon.createWithBitmap(mIcon))
                            .setIntent(shortcutIntent)
                            .build();
            // true means only that the request was accepted. The private success
            // IntentSender plus an enabled pinned record are required to finish.
            if (!manager.requestPinShortcut(shortcut, mPendingIntent.getIntentSender())) {
                finish(false);
            }
        } catch (RuntimeException failure) {
            finish(false);
        }
    }

    private void registerConfirmed(Intent intent, boolean launch) {
        BrowserServicesIntentDataProvider data = WebappIntentDataProviderFactory.create(intent);
        if (data == null || !isCurrent()) {
            finish(false);
            return;
        }
        PostTask.postTask(TaskTraits.USER_VISIBLE_MAY_BLOCK, () -> {
            try {
                // register() persists the entire in-memory ID set. Initialize all
                // existing records first so early startup cannot drop other apps.
                // warmUpSharedPrefs posts initialization on UI_DEFAULT; posting
                // registration to that same runner preserves their order.
                WebappRegistry.warmUpSharedPrefs();
                PostTask.postTask(TaskTraits.UI_DEFAULT, () -> {
                    if (isCurrent()) registerAfterWarmup(intent, data, launch);
                });
            } catch (RuntimeException failure) {
                PostTask.postTask(TaskTraits.UI_DEFAULT, () -> finish(false));
            }
        });
    }

    private void registerAfterWarmup(
            Intent intent, BrowserServicesIntentDataProvider data, boolean launch) {
        WebappRegistry.getInstance().register(mId, storage -> {
            storage.updateFromWebappIntentDataProvider(data);
            if (!isCurrent()) return;
            if (launch) {
                try {
                    // Use the complete pinned intent, preserving URL/icon MAC.
                    Intent launchIntent = new Intent(intent);
                    launchIntent.setComponent(null);
                    launchIntent.setSelector(null);
                    launchIntent.setFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
                    IntentHandler.startActivityForTrustedIntent(launchIntent);
                } catch (RuntimeException failure) {
                    finish(false);
                    return;
                }
            }
            finish(true);
        });
    }

    @Override
    public boolean onAppDetailsRequested() {
        return false;
    }

    @Override
    public void onViewDismissed() {
        mView = null;
        finish(false);
    }

    private void finish(boolean success) {
        if (mRequestId == 0) return;
        long requestId = mRequestId;
        destroy();
        ReaperReplacementWebAppBridgeJni.get().onFinished(requestId, success);
    }

    private void unregisterPinReceiver() {
        if (mReceiverRegistered) {
            mReceiverRegistered = false;
            mContext.unregisterReceiver(mReceiver);
        }
        mReceiver = null;
        if (mPendingIntent != null) {
            mPendingIntent.cancel();
            mPendingIntent = null;
        }
    }

    @CalledByNative
    void destroy() {
        mRequestId = 0;
        mHandler.removeCallbacksAndMessages(null);
        unregisterPinReceiver();
        if (mView != null) {
            AddToHomescreenDialogView view = mView;
            mView = null;
            view.dismissForExternalCancellation();
        }
    }

    @NativeMethods
    interface Natives {
        boolean isCurrent(long requestId);
        void onFinished(long requestId, boolean success);
    }
}
