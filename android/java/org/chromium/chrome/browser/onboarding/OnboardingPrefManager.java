/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */
package org.chromium.chrome.browser.onboarding;

import android.content.SharedPreferences;

import org.chromium.base.ContextUtils;

/**
 * Provides information regarding onboarding.
 */
public class OnboardingPrefManager {

    private static final String PREF_ONBOARDING = "onboarding";
    private static final String PREF_ONBOARDING_V2 = "onboarding_v2";
    public static final String PREF_BRAVE_STATS = "brave_stats";
    public static final String FROM_NOTIFICATION = "from_notification";
    public static final String FROM_STATS = "from_stats";
    public static final String ONE_TIME_NOTIFICATION = "one_time_notification";

    private static final String PREF_NOTIFICATION_PERMISSION_ENABLING_DIALOG_FROM_SETTING =
            "notification_permission_enabling_dialog_from_setting";

    public static final String SHOULD_SHOW_SEARCH_WIDGET_PROMO = "should_show_search_widget_promo";

    private static OnboardingPrefManager sInstance;

    private final SharedPreferences mSharedPreferences;

    public static final String BRAVE = "Brave";
    public static final String YANDEX = "Yandex";
    public static final String YAHOO_JP = "Yahoo! JAPAN";

    private OnboardingPrefManager() {
        mSharedPreferences = ContextUtils.getAppSharedPreferences();
    }

    /**
     * Returns the singleton instance of OnboardingPrefManager, creating it if needed.
     */
    public static OnboardingPrefManager getInstance() {
        if (sInstance == null) {
            sInstance = new OnboardingPrefManager();
        }
        return sInstance;
    }

    /**
     * Returns the user preference for whether the onboarding is shown.
     */
    public boolean isOnboardingShown() {
        return mSharedPreferences.getBoolean(PREF_ONBOARDING, false);
    }

    /**
     * Sets the user preference for whether the onboarding is shown.
     */
    public void setOnboardingShown(boolean isShown) {
        SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putBoolean(PREF_ONBOARDING, isShown);
        sharedPreferencesEditor.apply();
    }

    /** Sets the user preference for whether the onboarding is shown. */
    public void setNewOnboardingShown(boolean isShown) {
        SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putBoolean(PREF_ONBOARDING_V2, isShown);
        sharedPreferencesEditor.apply();
    }

    public boolean isBraveStatsEnabled() {
        return mSharedPreferences.getBoolean(PREF_BRAVE_STATS, false);
    }

    public void setBraveStatsEnabled(boolean enabled) {
        SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putBoolean(PREF_BRAVE_STATS, enabled);
        sharedPreferencesEditor.apply();
    }

    public boolean isFromNotification() {
        return mSharedPreferences.getBoolean(FROM_NOTIFICATION, false);
    }

    public void setFromNotification(boolean isFromNotification) {
        SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putBoolean(FROM_NOTIFICATION, isFromNotification);
        sharedPreferencesEditor.apply();
    }

    public boolean isOneTimeNotificationStarted() {
        return mSharedPreferences.getBoolean(ONE_TIME_NOTIFICATION, false);
    }

    public void setOneTimeNotificationStarted(boolean isOneTimeNotificationStarted) {
        SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putBoolean(ONE_TIME_NOTIFICATION, isOneTimeNotificationStarted);
        sharedPreferencesEditor.apply();
    }

    /**
     * Returns the user preference for whether the Notification Permission Enabling dialog is shown
     * From setting.
     */
    public boolean isNotificationPermissionEnablingDialogShownFromSetting() {
        return mSharedPreferences.getBoolean(
                PREF_NOTIFICATION_PERMISSION_ENABLING_DIALOG_FROM_SETTING, false);
    }

    /**
     * Sets the user preference for whether the Notification Permission Enabling dialog is shown
     * From setting.
     */
    public void setNotificationPermissionEnablingDialogShownFromSetting(boolean isShown) {
        SharedPreferences.Editor sharedPreferencesEditor = mSharedPreferences.edit();
        sharedPreferencesEditor.putBoolean(
                PREF_NOTIFICATION_PERMISSION_ENABLING_DIALOG_FROM_SETTING, isShown);
        sharedPreferencesEditor.apply();
    }
}
