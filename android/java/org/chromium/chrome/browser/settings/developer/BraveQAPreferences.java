/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings.developer;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;

import org.chromium.base.supplier.MonotonicObservableSupplier;
import org.chromium.base.supplier.ObservableSuppliers;
import org.chromium.base.supplier.SettableMonotonicObservableSupplier;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.BraveConfig;
import org.chromium.chrome.browser.BraveRelaunchUtils;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.settings.BravePreferenceFragment;
import org.chromium.components.browser_ui.settings.ChromeSwitchPreference;
import org.chromium.components.browser_ui.settings.SettingsUtils;
import org.chromium.components.browser_ui.settings.search.BaseSearchIndexProvider;

/** Settings fragment containing preferences for QA team. */
public class BraveQAPreferences extends BravePreferenceFragment
        implements OnPreferenceChangeListener {
    private static final String PREF_QA_DEBUG_NTP = "qa_debug_ntp";
    private static final String PREF_QA_COMMAND_LINE = "qa_command_line";

    private ChromeSwitchPreference mDebugNTP;
    private Preference mCommandLine;

    private final SettableMonotonicObservableSupplier<String> mPageTitle =
            ObservableSuppliers.createMonotonic();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsUtils.addPreferencesFromResource(this, R.xml.qa_preferences);

        // Hardcoded because it is for internal use only, hidden by access code, not translated
        mPageTitle.set("QA Preferences");

        mDebugNTP = (ChromeSwitchPreference) findPreference(PREF_QA_DEBUG_NTP);
        if (mDebugNTP != null) {
            mDebugNTP.setOnPreferenceChangeListener(this);
        }

        mCommandLine = findPreference(PREF_QA_COMMAND_LINE);
        setCommandLineClickListener();

        checkQACode();
    }

    @Override
    public MonotonicObservableSupplier<String> getPageTitle() {
        return mPageTitle;
    }

    private void setCommandLineClickListener() {
        if (mCommandLine == null) {
            return;
        }
        mCommandLine.setOnPreferenceClickListener(preference -> {
            LayoutInflater inflater = (LayoutInflater) getActivity().getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            View view = inflater.inflate(R.layout.qa_command_line, null);
            final EditText input = (EditText) view.findViewById(R.id.qa_command_line);

            input.setText(getPreferenceString(PREF_QA_COMMAND_LINE));

            DialogInterface.OnClickListener onClickListener =
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int button) {
                            if (button == AlertDialog.BUTTON_POSITIVE) {
                                // OK was pressed
                                String newCommandLine = input.getText().toString();
                                setPreferenceString(PREF_QA_COMMAND_LINE, newCommandLine);
                                BraveRelaunchUtils.askForRelaunch(getActivity());
                            }
                        }
                    };

            input.setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    input.post(new Runnable() {
                        @Override
                        public void run() {
                            InputMethodManager inputMethodManager =
                                    (InputMethodManager) getActivity().getSystemService(
                                            Context.INPUT_METHOD_SERVICE);
                            inputMethodManager.showSoftInput(
                                    input, InputMethodManager.SHOW_IMPLICIT);
                        }
                    });
                }
            });
            input.requestFocus();

            AlertDialog.Builder alert = new AlertDialog.Builder(
                    getActivity(), R.style.ThemeOverlay_BrowserUI_AlertDialog);
            if (alert == null) {
                return true;
            }
            AlertDialog.Builder alertDialog =
                    alert.setTitle("Enter Command Line string")
                            .setView(view)
                            .setPositiveButton(R.string.ok, onClickListener)
                            .setNegativeButton(R.string.cancel, onClickListener)
                            .setCancelable(false);
            Dialog dialog = alertDialog.create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();

            return true;
        });
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (PREF_QA_DEBUG_NTP.equals(preference.getKey())) {
            setOnPreferenceValue(preference.getKey(), (boolean)newValue);
            BraveRelaunchUtils.askForRelaunch(getActivity());
        }
        return true;
    }

    private static void setOnPreferenceValue(String preferenceName, boolean newValue) {
        ChromeSharedPreferences.getInstance().writeBoolean(preferenceName, newValue);
    }

    private static void setPreferenceString(String preferenceName, String newValue) {
        ChromeSharedPreferences.getInstance().writeString(preferenceName, newValue);
    }

    private static String getPreferenceString(String preferenceName) {
        return ChromeSharedPreferences.getInstance().readString(preferenceName, "");
    }

    private void checkQACode() {
        LayoutInflater inflater =
            (LayoutInflater) getActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.qa_code_check, null);
        final EditText input = (EditText) view.findViewById(R.id.qa_code);

        DialogInterface.OnClickListener onClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int button) {
                if (button != AlertDialog.BUTTON_POSITIVE
                        || !input.getText().toString().equals(BraveConfig.DEVELOPER_OPTIONS_CODE)) {
                    getActivity().finish();
                }
            }
        };

        input.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                input.post(new Runnable() {
                    @Override
                    public void run() {
                        InputMethodManager inputMethodManager =
                            (InputMethodManager) getActivity().getSystemService(
                                Context.INPUT_METHOD_SERVICE);
                        inputMethodManager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                    }
                });
            }
        });
        input.requestFocus();

        AlertDialog.Builder alert =
                new AlertDialog.Builder(getActivity(), R.style.ThemeOverlay_BrowserUI_AlertDialog);
        if (alert == null) {
            return;
        }
        AlertDialog.Builder alertDialog = alert
                                          .setTitle("Enter QA code")
                                          .setView(view)
                                          .setPositiveButton(R.string.ok, onClickListener)
                                          .setNegativeButton(R.string.cancel, onClickListener)
                                          .setCancelable(false);
        Dialog dialog = alertDialog.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    @Override
    public void onCreatePreferences(Bundle bundle, String s) {}

    // QA-only screen protected by a password; exclude entirely from settings search.
    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(
                    BraveQAPreferences.class.getName(), BaseSearchIndexProvider.INDEX_OPT_OUT);
}
