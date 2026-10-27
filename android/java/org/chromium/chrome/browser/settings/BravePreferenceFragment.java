/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;

import org.chromium.base.ContextUtils;
import org.chromium.build.annotations.NonNull;
import org.chromium.chrome.R;

public abstract class BravePreferenceFragment extends ChromeBaseSettingsFragment {
    protected static final int STORAGE_PERMISSION_EXPORT_REQUEST_CODE = 8000;
    protected static final int STORAGE_PERMISSION_IMPORT_REQUEST_CODE =
            STORAGE_PERMISSION_EXPORT_REQUEST_CODE + 1;

    @Override
    protected @NonNull PreferenceGroupAdapter onCreateAdapter(
            @NonNull PreferenceScreen preferenceScreen) {
        return new BraveSettingsPreferenceGroupAdapter(preferenceScreen);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if (!(getActivity() instanceof SettingsActivity)) {
            // Embedded settings use the browser tab's existing close and Back controls.
            super.onCreateOptionsMenu(menu, inflater);
            return;
        }
        menu.clear();
        inflater.inflate(R.menu.exit_settings_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.close_menu_id
                && getActivity() instanceof SettingsActivity activity) {
            BraveSettingsActivity.closeSettingsInTask(activity);
            return true;
        }
        return false;
    }

    @Override
    public void onCreatePreferences(Bundle bundle, String s) {
        setHasOptionsMenu(true);
    }

    @Override
    public @AnimationType int getAnimationType() {
        return AnimationType.PROPERTY;
    }

    protected boolean isStoragePermissionGranted(boolean isExport) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Context context = ContextUtils.getApplicationContext();
            if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            } else {
                requestPermissions(
                        new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        isExport
                                ? STORAGE_PERMISSION_EXPORT_REQUEST_CODE
                                : STORAGE_PERMISSION_IMPORT_REQUEST_CODE);
                return false;
            }
        }
        return true;
    }
}
