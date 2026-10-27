// Copyright 2026 The Reaper Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package org.chromium.chrome.browser.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import org.chromium.base.FeatureOverrides;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.download.items.OfflineContentAggregatorFactory;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.profiles.OtrProfileId;
import org.chromium.components.messages.MessageDispatcher;
import org.chromium.components.offline_items_collection.LegacyHelpers;
import org.chromium.components.offline_items_collection.OfflineContentProvider;
import org.chromium.components.offline_items_collection.OfflineItem;
import org.chromium.components.offline_items_collection.OfflineItemState;
import org.chromium.ui.modaldialog.ModalDialogManager;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Real controller state transitions; only its platform dependencies/render boundary are replaced.
 */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class DownloadMessageUiPolicyTest {
    private static class TestDelegate implements DownloadMessageUiController.Delegate {
        final Set<String> suppressedProfiles = new HashSet<>();

        @Override
        public Context getContext() {
            return RuntimeEnvironment.getApplication();
        }

        @Override
        public @Nullable MessageDispatcher getMessageDispatcher() {
            return null;
        }

        @Override
        public @Nullable ModalDialogManager getModalDialogManager() {
            return null;
        }

        @Override
        public boolean maybeSwitchToFocusedActivity() {
            return false;
        }

        @Override
        public void openDownloadsPage(@Nullable OtrProfileId profile, int source) {}

        @Override
        public void openDownload(
                @Nullable OfflineItem item,
                @Nullable OtrProfileId profile,
                int source,
                Context context) {}

        @Override
        public void removeNotification(int notificationId, DownloadInfo info) {}

        @Override
        public boolean shouldSuppressDownloadUi(@Nullable String profileId) {
            return suppressedProfiles.contains(profileId);
        }
    }

    private static class ObservedController extends DownloadMessageUiControllerImpl {
        @Nullable DownloadProgressMessageUiData shown;

        ObservedController(TestDelegate delegate) {
            super(delegate);
        }

        @Override
        protected void showMessage(@UiState int state, DownloadProgressMessageUiData info) {
            shown = info;
        }

        @Override
        protected void closePreviousMessage() {
            shown = null;
        }
    }

    private final TestDelegate mDelegate = new TestDelegate();
    private ObservedController mController;

    @Before
    public void setUp() {
        FeatureOverrides.newBuilder()
                .disable(ChromeFeatureList.MALICIOUS_APK_DOWNLOAD_CHECK)
                .disable(ChromeFeatureList.SHOW_BLOCKED_SENSITIVE_DOWNLOAD)
                .apply();
        OfflineContentAggregatorFactory.setOfflineContentProviderForTests(
                mock(OfflineContentProvider.class));
        mController = new ObservedController(mDelegate);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static OfflineItem item(@Nullable String profileId) {
        OfflineItem item = new OfflineItem();
        item.id = LegacyHelpers.buildLegacyContentId(false, UUID.randomUUID().toString());
        item.state = OfflineItemState.IN_PROGRESS;
        item.title = "Policy test download";
        item.filePath = "/data/local/tmp/policy-test.bin";
        item.mimeType = "application/octet-stream";
        item.otrProfileId = profileId;
        item.isOffTheRecord = profileId != null;
        return item;
    }

    private void update(OfflineItem item) {
        mController.onItemUpdated(item.clone(), null);
    }

    private void assertProgress(int count) {
        assertNotNull(mController.shown);
        assertEquals(count, mController.shown.downloadCount.inProgress);
    }

    private void hideRegularUi() {
        mDelegate.suppressedProfiles.add(null);
        mController.onDownloadUiPolicyChanged();
    }

    private void enableRegularUi() {
        mDelegate.suppressedProfiles.remove(null);
        mController.onDownloadUiPolicyChanged();
    }

    @Test
    public void completionWhileHiddenDoesNotReturnAsProgress() {
        OfflineItem item = item(null);
        update(item);
        assertProgress(1);
        hideRegularUi();
        assertNull(mController.shown);
        item.state = OfflineItemState.COMPLETE;
        update(item);
        enableRegularUi();
        assertNull(mController.shown);
    }

    @Test
    public void cancellationWhileHiddenDoesNotReturnAsProgress() {
        OfflineItem item = item(null);
        update(item);
        hideRegularUi();
        item.state = OfflineItemState.CANCELLED;
        update(item);
        enableRegularUi();
        assertNull(mController.shown);
    }

    @Test
    public void enablingRestoresRealStillActiveItem() {
        OfflineItem item = item(null);
        hideRegularUi();
        update(item);
        assertNull(mController.shown);
        enableRegularUi();
        assertProgress(1);
        item.state = OfflineItemState.CANCELLED;
        update(item);
        assertNull(mController.shown);
    }

    @Test
    public void regularPolicyDoesNotHideOtrItemAndViceVersa() {
        OfflineItem regular = item(null);
        OfflineItem otr = item("otr-test");
        update(regular);
        update(otr);
        assertProgress(2);
        hideRegularUi();
        assertProgress(1);
        mDelegate.suppressedProfiles.add("otr-test");
        mController.onDownloadUiPolicyChanged();
        assertNull(mController.shown);
        enableRegularUi();
        assertProgress(1);
        mController.onItemRemoved(regular.id);
        assertNull(mController.shown);
    }

    @Test
    public void realRemovalDiscardsHiddenSnapshot() {
        OfflineItem item = item(null);
        hideRegularUi();
        update(item);
        mController.onItemRemoved(item.id);
        enableRegularUi();
        assertNull(mController.shown);
    }

    @Test
    public void oldResultTimerCannotReshowSuppressedUi() {
        OfflineItem item = item(null);
        item.state = OfflineItemState.FAILED;
        update(item);
        assertNotNull(mController.shown);
        hideRegularUi();
        assertNull(mController.shown);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1));
        assertNull(mController.shown);
        enableRegularUi();
        assertNull(mController.shown);
    }
}
