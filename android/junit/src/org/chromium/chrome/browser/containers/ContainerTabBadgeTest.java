// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
package org.chromium.chrome.browser.containers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.tab.Tab;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.util.List;

@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ContainerTabBadgeTest {
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock private Profile mProfile;
    @Mock private Tab mTab;
    @Mock private Tab mOtherTab;
    @Mock private ContainersBridge.Natives mNative;
    private Context mContext;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        ContainersBridgeJni.setInstanceForTesting(mNative);
        when(mProfile.getNativeBrowserContextPointer()).thenReturn(1L);
        when(mTab.getProfile()).thenReturn(mProfile);
        when(mOtherTab.getProfile()).thenReturn(mProfile);
        when(mTab.getContainerId()).thenReturn(ID);
        when(mOtherTab.getContainerId()).thenReturn("");
    }

    @Test
    public void testDefaultIdentityHasNoBadgeOrNativeLookup() {
        when(mTab.getContainerId()).thenReturn("");
        assertNull(ContainerTabBadge.forTab(mContext, mTab));
        verify(mNative, never()).getContainerInfo(any(), anyString());
    }

    @Test
    public void testIncognitoAndDestroyedTabsHaveNoBadge() {
        when(mTab.isIncognito()).thenReturn(true);
        assertNull(ContainerTabBadge.forTab(mContext, mTab));
        when(mTab.isIncognito()).thenReturn(false);
        when(mTab.isDestroyed()).thenReturn(true);
        assertNull(ContainerTabBadge.forTab(mContext, mTab));
        verify(mNative, never()).getContainerInfo(any(), anyString());
    }

    @Test
    public void testShuttingDownProfileHasNoNativeLookup() {
        when(mProfile.shutdownStarted()).thenReturn(true);
        assertNull(ContainerTabBadge.forTab(mContext, mTab));
        verify(mNative, never()).getContainerInfo(any(), anyString());
    }

    @Test
    public void testLazyTabUsesImmutableIdentityWithoutLoadingContents() throws Exception {
        when(mNative.getContainerInfo(mProfile, ID)).thenReturn(metadata("Work", 1, 0xff1a73e8));
        ContainerTabBadge.Info badge = ContainerTabBadge.forTab(mContext, mTab);
        assertNotNull(badge);
        assertEquals("Work", badge.label);
        verify(mTab, never()).getWebContents();
        verify(mTab, never()).getTitle();
    }

    @Test
    public void testRefreshUsesNativeUsedSnapshotNameColorAndIcon() throws Exception {
        when(mNative.getContainerInfo(mProfile, ID))
                .thenReturn(metadata("Work", 1, 0xff1a73e8), metadata("Renamed", 7, 0xffd93025));
        ContainerTabBadge.Info first = ContainerTabBadge.forTab(mContext, mTab);
        ContainerTabBadge.Info refreshed = ContainerTabBadge.forTab(mContext, mTab);
        assertEquals("Work", first.label);
        assertEquals("Renamed", refreshed.label);
        assertEquals(0xffd93025, refreshed.color);
        assertEquals(7, refreshed.icon);
        verifyReadOnlyContainerMetadataLookup();
        assertTrue(refreshed.description.contains("Renamed"));
    }

    @Test
    public void testTransparentUsedSnapshotStillShowsSameContainerIdentity() throws Exception {
        ContainersBridge.ContainerInfo imported = metadata("Imported", 7, 0x001a73e8);
        when(mNative.getContainerInfo(mProfile, ID)).thenReturn(imported);
        ContainerTabBadge.Info badge = ContainerTabBadge.forTab(mContext, mTab);
        assertNotNull(badge);
        assertEquals("Imported", badge.label);
        assertEquals(7, badge.icon);
        assertEquals(0xff1a73e8, badge.color);
        assertEquals(0x001a73e8, imported.backgroundColor);
        verifyReadOnlyContainerMetadataLookup();
        verify(mNative).getContainerInfo(mProfile, ID);
        verify(mTab, never()).getWebContents();
    }

    @Test
    public void testBlankImportedNamesUseVisibleIdentityWithoutChangingJar() throws Exception {
        FrameLayout root = new FrameLayout(mContext);
        TextView view = new TextView(mContext);
        view.setId(R.id.reaper_tab_container_badge);
        root.addView(view);
        String expectedLabel =
                mContext.getString(R.string.reaper_container_unnamed, ID.substring(0, 8));
        String expectedDescription =
                mContext.getString(R.string.reaper_container_identity, expectedLabel);
        for (String importedName :
                List.of("", " \t ", "\u001b", "\u2003", "\u00a0", "\u0085", "\u3000", "\u202f",
                        "\u2003\u00a0\u0085\t ")) {
            ContainersBridge.ContainerInfo imported = metadata(importedName, 7, 0xffd93025);
            when(mNative.getContainerInfo(mProfile, ID)).thenReturn(imported);
            ContainerTabBadge.Info badge = ContainerTabBadge.forTab(mContext, mTab);
            assertNotNull(badge);
            ContainerTabBadge.bind(root, badge);
            assertEquals(View.VISIBLE, view.getVisibility());
            assertEquals(expectedLabel, view.getText().toString());
            assertEquals(expectedDescription, view.getContentDescription().toString());
            assertEquals(expectedDescription, view.getTooltipText().toString());
            assertEquals(expectedLabel, ContainersUi.getName(mContext, mProfile, ID));
            ContainerTabBadge.bind(root, badge, true);
            assertEquals("", view.getText().toString());
            assertNotNull(view.getCompoundDrawablesRelative()[0]);
            assertEquals(expectedDescription, view.getContentDescription().toString());
            assertEquals(expectedDescription, view.getTooltipText().toString());
            ContainerToolbarIndicator toolbar = new ContainerToolbarIndicator(mContext);
            toolbar.update(mTab);
            assertEquals(View.VISIBLE, toolbar.getVisibility());
            assertEquals(expectedDescription, toolbar.getContentDescription().toString());
            assertEquals(expectedDescription, toolbar.getTooltipText().toString());
            assertEquals(7, badge.icon);
            assertEquals(0xffd93025, badge.color);
            assertEquals(importedName, imported.name);
            assertEquals(ID, imported.id);
            verifyReadOnlyContainerMetadataLookup();
        }
        verify(mTab, never()).getWebContents();
        verify(mTab, never()).getTitle();
    }

    @Test
    public void testUnicodeNameRemainsExactInVisibleAndAccessibleIdentity() throws Exception {
        String name = "仕事\u3000می\u200cروم\u00a0🙂";
        ContainersBridge.ContainerInfo imported = metadata(name, 7, 0xffd93025);
        when(mNative.getContainerInfo(mProfile, ID)).thenReturn(imported);
        ContainerTabBadge.Info badge = ContainerTabBadge.forTab(mContext, mTab);
        assertNotNull(badge);
        assertEquals(name, badge.label);
        assertEquals(name, ContainersUi.getName(mContext, mProfile, ID));
        assertEquals(mContext.getString(R.string.reaper_container_identity, name), badge.description);
        assertEquals(name, imported.name);
        assertEquals(ID, imported.id);
        verifyReadOnlyContainerMetadataLookup();
        verify(mTab, never()).getWebContents();
        verify(mTab, never()).getTitle();
    }

    @Test
    public void testMixedGroupDoesNotUseRepresentativeIdentity() throws Exception {
        when(mNative.getContainerInfo(mProfile, ID)).thenReturn(metadata("Work", 1, 0xff1a73e8));
        ContainerTabBadge.Info badge =
                ContainerTabBadge.forTabs(mContext, List.of(mTab, mOtherTab));
        assertEquals(mContext.getString(R.string.reaper_container_mixed), badge.label);
        assertTrue(badge.description.contains("Work"));
        assertTrue(
                badge.description.contains(mContext.getString(R.string.reaper_container_default)));
    }

    @Test
    public void testUnknownAndTemporaryIdentitiesStayDistinctFromDefault() {
        ContainerTabBadge.Info unknown = ContainerTabBadge.forTab(mContext, mTab);
        assertNotNull(unknown);
        assertTrue(unknown.label.contains(ID.substring(0, 8)));
        when(mTab.getContainerId()).thenReturn("t-" + ID);
        ContainerTabBadge.Info temporary = ContainerTabBadge.forTab(mContext, mTab);
        assertEquals(
                mContext.getString(R.string.reaper_container_temporary_unknown, ID.substring(0, 8)),
                temporary.label);
        when(mTab.getContainerId()).thenReturn("t-22222222-2222-4222-8222-222222222222");
        ContainerTabBadge.Info otherTemporary = ContainerTabBadge.forTab(mContext, mTab);
        assertTrue(!temporary.label.equals(otherTemporary.label));
    }

    @Test
    public void testRecycledViewClearsIconAndAccessibility() {
        FrameLayout root = new FrameLayout(mContext);
        TextView view = new TextView(mContext);
        view.setId(R.id.reaper_tab_container_badge);
        root.addView(view);
        ContainerTabBadge.bind(
                root, new ContainerTabBadge.Info("Work", "Container: Work", 0xff1a73e8, 1));
        assertEquals(View.VISIBLE, view.getVisibility());
        assertNotNull(view.getCompoundDrawablesRelative()[0]);
        ContainerTabBadge.bind(root, null);
        assertEquals(View.GONE, view.getVisibility());
        assertEquals("", view.getText().toString());
        assertNull(view.getContentDescription());
        assertNull(view.getTooltipText());
        assertNull(view.getCompoundDrawablesRelative()[0]);
    }

    @Test
    public void testCollapsedRailKeepsIconAndFullAccessibleName() {
        FrameLayout root = new FrameLayout(mContext);
        TextView view = new TextView(mContext);
        view.setId(R.id.reaper_tab_container_badge);
        root.addView(view);
        ContainerTabBadge.Info info =
                new ContainerTabBadge.Info("Work", "Container: Work", 0xff1a73e8, 1);
        int selectedTitleColor = 0xff0a4d55;
        view.setTextColor(selectedTitleColor);
        ContainerTabBadge.bind(root, info, true);
        assertEquals("", view.getText().toString());
        assertNotNull(view.getCompoundDrawablesRelative()[0]);
        assertEquals("Container: Work", view.getContentDescription());
        LayerDrawable badgeIcon = (LayerDrawable) view.getCompoundDrawablesRelative()[0];
        assertTrue(
                "The compact badge glyph must remain recognizable inside its color circle",
                badgeIcon.getDrawable(1).getBounds().width()
                        >= badgeIcon.getBounds().width() * 0.6f);
        LayerDrawable chooserIcon = (LayerDrawable) ContainersUi.getIcon(mContext, 1, 0xff1a73e8);
        int chooserSize = Math.round(24 * mContext.getResources().getDisplayMetrics().density);
        chooserIcon.setBounds(0, 0, chooserSize, chooserSize);
        int chooserInset = Math.round(5 * mContext.getResources().getDisplayMetrics().density);
        assertEquals(
                chooserSize - 2 * chooserInset, chooserIcon.getDrawable(1).getBounds().width());
        assertEquals(selectedTitleColor, view.getCurrentTextColor());
        assertEquals(
                "Example, Container: Work", ContainerTabBadge.appendDescription("Example", info));
        ContainerTabBadge.bind(root, info, false);
        assertEquals("Work", view.getText().toString());
        assertEquals(selectedTitleColor, view.getCurrentTextColor());
    }

    @Test
    public void testOffTheRecordProfileHasNoNativeLookup() {
        when(mProfile.isOffTheRecord()).thenReturn(true);
        assertNull(ContainerTabBadge.forTab(mContext, mTab));
        verify(mNative, never()).getContainerInfo(any(), anyString());
    }

    @Test
    public void testRecreatedToolbarSamplesCurrentMetadataAndClearsDefault() throws Exception {
        when(mNative.getContainerInfo(mProfile, ID))
                .thenReturn(metadata("Work", 1, 0xff1a73e8), metadata("Renamed", 7, 0xffd93025));
        ContainerToolbarIndicator before = new ContainerToolbarIndicator(mContext);
        before.update(mTab);
        assertTrue(before.getContentDescription().toString().contains("Work"));
        ContainerToolbarIndicator recreated = new ContainerToolbarIndicator(mContext);
        recreated.update(mTab);
        assertTrue(recreated.getContentDescription().toString().contains("Renamed"));
        assertEquals(
                0xffd93025,
                ((android.graphics.drawable.ColorDrawable) recreated.getBackground()).getColor());
        verifyReadOnlyContainerMetadataLookup();
        recreated.update(null);
        assertEquals(View.GONE, recreated.getVisibility());
        assertNull(recreated.getContentDescription());
    }

    private void verifyReadOnlyContainerMetadataLookup() {
        verify(mNative, atLeastOnce()).getContainerInfo(mProfile, ID);
        verifyNoMoreInteractions(mNative);
    }

    private static ContainersBridge.ContainerInfo metadata(String name, int icon, int color)
            throws Exception {
        Constructor<ContainersBridge.ContainerInfo> constructor =
                ContainersBridge.ContainerInfo.class.getDeclaredConstructor(
                        String.class, String.class, int.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(ID, name, icon, color);
    }
}
