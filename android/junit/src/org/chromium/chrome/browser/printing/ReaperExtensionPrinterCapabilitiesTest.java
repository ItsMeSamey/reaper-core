// Copyright 2026 The Reaper Authors
// Use of this source code is governed by a BSD-style license.

package org.chromium.chrome.browser.printing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import android.content.ComponentName;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintJobInfo;
import android.print.PrinterId;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import org.chromium.base.test.BaseRobolectricTestRunner;

import org.json.JSONArray;
import org.json.JSONObject;

/** Capability parsing and ticket tests use Android's actual print value classes. */
@RunWith(BaseRobolectricTestRunner.class)
public class ReaperExtensionPrinterCapabilitiesTest {
    private PrinterId mPrinter;
    private JSONObject mCdd;

    @Before
    public void setUp() throws Exception {
        mPrinter = printerId("extension:printer");
        mCdd = new JSONObject("""
                {"version":"1.0","printer":{
                  "supported_content_type":[{"content_type":"application/pdf"}],
                  "media_size":{"option":[{"name":"ISO_A4","vendor_id":"tray-a4",
                    "width_microns":210000,"height_microns":297000,"is_default":true}]},
                  "dpi":{"option":[{"horizontal_dpi":600,"vertical_dpi":1200}]},
                  "color":{"option":[{"type":"STANDARD_COLOR","is_default":true},
                    {"type":"STANDARD_MONOCHROME"}]},
                  "duplex":{"option":[{"type":"NO_DUPLEX","is_default":true},
                    {"type":"LONG_EDGE"},{"type":"SHORT_EDGE"}]},
                  "copies":{"default":1,"max":5},
                  "page_orientation":{"option":[{"type":"PORTRAIT"},{"type":"LANDSCAPE"}]}
                }}
                """);
    }

    @Test
    public void ticketPreservesExactMicronsAndSelectedModes() throws Exception {
        ReaperExtensionPrinterCapabilities caps = parse();
        PrintAttributes attributes = attributes(caps, true, PrintAttributes.DUPLEX_MODE_LONG_EDGE);
        JSONObject ticket = new JSONObject(caps.buildTicket(job(attributes, 3, PageRange.ALL_PAGES)))
                .getJSONObject("print");
        assertEquals(210000, ticket.getJSONObject("media_size").getInt("width_microns"));
        assertEquals(297000, ticket.getJSONObject("media_size").getInt("height_microns"));
        assertEquals("tray-a4", ticket.getJSONObject("media_size").getString("vendor_id"));
        assertEquals("LANDSCAPE", ticket.getJSONObject("page_orientation").getString("type"));
        assertEquals("LONG_EDGE", ticket.getJSONObject("duplex").getString("type"));
        assertEquals(3, ticket.getJSONObject("copies").getInt("copies"));
        assertEquals(1200, ticket.getJSONObject("dpi").getInt("vertical_dpi"));
        assertEquals("STANDARD_COLOR", ticket.getJSONObject("color").getString("type"));
    }

    @Test
    public void supportedDocumentFormatMustBeAdvertised() throws Exception {
        mCdd.getJSONObject("printer").put("supported_content_type",
                new JSONArray().put(new JSONObject().put("content_type", "image/jpeg")));
        assertNull(ReaperExtensionPrinterCapabilities.parse(mPrinter, mCdd.toString()));
    }

    @Test
    public void pwgAndPdfWildcardDeclarationsAreAccepted() throws Exception {
        for (String type : new String[] {"image/pwg-raster", "*/*"}) {
            mCdd.getJSONObject("printer").put("supported_content_type",
                    new JSONArray().put(new JSONObject().put("content_type", type)));
            assertNotNull(ReaperExtensionPrinterCapabilities.parse(mPrinter, mCdd.toString()));
        }
    }

    @Test
    public void pwgTransformsAndEncodingsMustMatchTheConverter() throws Exception {
        JSONObject printer = mCdd.getJSONObject("printer");
        printer.put("supported_content_type",
                new JSONArray().put(new JSONObject().put("content_type", "image/pwg-raster")));
        JSONObject config = new JSONObject().put("document_sheet_back", "FLIPPED")
                .put("rotate_all_pages", true).put("reverse_order_streaming", true)
                .put("document_type_supported", new JSONArray().put("SGRAY_8").put("SRGB_8"));
        printer.put("pwg_raster_config", config);
        assertNotNull(ReaperExtensionPrinterCapabilities.parse(mPrinter, mCdd.toString()));
        config.put("document_sheet_back", "UNKNOWN");
        assertNull(ReaperExtensionPrinterCapabilities.parse(mPrinter, mCdd.toString()));
        config.put("document_sheet_back", "NORMAL").put("rotate_all_pages", "true");
        assertNull(ReaperExtensionPrinterCapabilities.parse(mPrinter, mCdd.toString()));
        config.put("rotate_all_pages", false)
                .put("document_type_supported", new JSONArray().put("SRGB_16"));
        assertNull(ReaperExtensionPrinterCapabilities.parse(mPrinter, mCdd.toString()));
    }

    @Test
    public void vendorCapabilitiesAreRejected() throws Exception {
        mCdd.getJSONObject("printer").put("vendor_capability", new JSONArray());
        assertNull(ReaperExtensionPrinterCapabilities.parse(mPrinter, mCdd.toString()));
    }

    @Test
    public void fractionalAndStringDimensionsAreRejected() throws Exception {
        media().put("width_microns", 210000.5);
        assertNull(ReaperExtensionPrinterCapabilities.parse(mPrinter, mCdd.toString()));
        media().put("width_microns", "210000");
        assertNull(ReaperExtensionPrinterCapabilities.parse(mPrinter, mCdd.toString()));
    }

    @Test
    public void contradictoryDefaultsAreRejected() throws Exception {
        mCdd.getJSONObject("printer").getJSONObject("color").getJSONArray("option")
                .getJSONObject(1).put("is_default", true);
        assertNull(ReaperExtensionPrinterCapabilities.parse(mPrinter, mCdd.toString()));
    }

    @Test
    public void excessiveCopiesAndPageSubsetsAreRejected() throws Exception {
        ReaperExtensionPrinterCapabilities caps = parse();
        PrintAttributes attrs = attributes(caps, false, PrintAttributes.DUPLEX_MODE_NONE);
        assertThrows(IllegalArgumentException.class,
                () -> caps.buildTicket(job(attrs, 6, PageRange.ALL_PAGES)));
        assertThrows(IllegalArgumentException.class,
                () -> caps.buildTicket(job(attrs, 1, new PageRange(1, 3))));
    }

    @Test
    public void differentPrinterIsRejected() throws Exception {
        ReaperExtensionPrinterCapabilities caps = parse();
        PrintJobInfo info = job(attributes(caps, false, PrintAttributes.DUPLEX_MODE_NONE),
                1, PageRange.ALL_PAGES);
        ReflectionHelpers.setField(info, "mPrinterId", printerId("other:printer"));
        assertThrows(IllegalArgumentException.class, () -> caps.buildTicket(info));
    }

    @Test
    public void refreshedDimensionsAndResolutionAreValidated() throws Exception {
        ReaperExtensionPrinterCapabilities original = parse();
        PrintJobInfo info = job(attributes(original, false, PrintAttributes.DUPLEX_MODE_NONE),
                1, PageRange.ALL_PAGES);
        media().put("height_microns", 290000);
        ReaperExtensionPrinterCapabilities refreshed = parse();
        assertThrows(IllegalArgumentException.class, () -> refreshed.buildTicket(info));
    }

    @Test
    public void missingDuplexAndCopiesOnlyPermitSimplexSingleCopy() throws Exception {
        mCdd.getJSONObject("printer").remove("duplex");
        mCdd.getJSONObject("printer").remove("copies");
        ReaperExtensionPrinterCapabilities caps = parse();
        PrintAttributes attrs = attributes(caps, false, PrintAttributes.DUPLEX_MODE_NONE);
        JSONObject ticket = new JSONObject(caps.buildTicket(job(attrs, 1, PageRange.ALL_PAGES)))
                .getJSONObject("print");
        assertEquals(false, ticket.has("duplex"));
        assertEquals(false, ticket.has("copies"));
        assertThrows(IllegalArgumentException.class,
                () -> caps.buildTicket(job(attrs, 2, PageRange.ALL_PAGES)));
        assertThrows(IllegalArgumentException.class,
                () -> caps.buildTicket(job(attributes(caps, false, PrintAttributes.DUPLEX_MODE_LONG_EDGE),
                        1, PageRange.ALL_PAGES)));
    }

    @Test
    public void marginsRoundOutwardAndDoNotClaimBorderless() throws Exception {
        media().put("imageable_area_left_microns", 5001)
                .put("imageable_area_bottom_microns", 5001)
                .put("imageable_area_right_microns", 204999)
                .put("imageable_area_top_microns", 291999);
        ReaperExtensionPrinterCapabilities caps = parse();
        assertEquals(197, caps.getInfo().getMinMargins().getLeftMils());
        PrintAttributes attrs = attributes(caps, false, PrintAttributes.DUPLEX_MODE_NONE);
        caps.buildTicket(job(attrs, 1, PageRange.ALL_PAGES));
        PrintAttributes borderless = new PrintAttributes.Builder()
                .setMediaSize(attrs.getMediaSize()).setResolution(attrs.getResolution())
                .setColorMode(attrs.getColorMode()).setDuplexMode(attrs.getDuplexMode())
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build();
        assertThrows(IllegalArgumentException.class,
                () -> caps.buildTicket(job(borderless, 1, PageRange.ALL_PAGES)));
    }

    @Test
    public void partialPrintableAreaIsRejected() throws Exception {
        media().put("imageable_area_left_microns", 5000);
        assertNull(ReaperExtensionPrinterCapabilities.parse(mPrinter, mCdd.toString()));
    }

    @Test
    public void unsupportedOrientationAndForcedScalingAreRejected() throws Exception {
        mCdd.getJSONObject("printer").put("page_orientation",
                new JSONObject().put("option", new JSONArray()
                        .put(new JSONObject().put("type", "PORTRAIT"))));
        assertNull(ReaperExtensionPrinterCapabilities.parse(mPrinter, mCdd.toString()));
        mCdd.getJSONObject("printer").remove("page_orientation");
        mCdd.getJSONObject("printer").put("fit_to_page",
                new JSONObject().put("option", new JSONArray()
                        .put(new JSONObject().put("type", "FIT"))));
        assertNull(ReaperExtensionPrinterCapabilities.parse(mPrinter, mCdd.toString()));
    }

    private ReaperExtensionPrinterCapabilities parse() {
        ReaperExtensionPrinterCapabilities caps =
                ReaperExtensionPrinterCapabilities.parse(mPrinter, mCdd.toString());
        assertNotNull(caps);
        return caps;
    }

    private JSONObject media() throws Exception {
        return mCdd.getJSONObject("printer").getJSONObject("media_size")
                .getJSONArray("option").getJSONObject(0);
    }

    private static PrintAttributes attributes(ReaperExtensionPrinterCapabilities caps,
            boolean landscape, int duplex) {
        PrintAttributes.MediaSize media = caps.getInfo().getMediaSizes().get(0);
        return new PrintAttributes.Builder().setMediaSize(landscape ? media.asLandscape() : media)
                .setResolution(caps.getInfo().getResolutions().get(0))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR).setDuplexMode(duplex)
                .setMinMargins(caps.getInfo().getMinMargins()).build();
    }

    private PrintJobInfo job(PrintAttributes attrs, int copies, PageRange pages) {
        PrintJobInfo.Builder builder = new PrintJobInfo.Builder(null);
        builder.setAttributes(attrs);
        builder.setCopies(copies);
        builder.setPages(new PageRange[] {pages});
        PrintJobInfo info = builder.build();
        ReflectionHelpers.setField(info, "mPrinterId", mPrinter);
        return info;
    }

    private static PrinterId printerId(String id) {
        return ReflectionHelpers.callConstructor(PrinterId.class,
                ClassParameter.from(ComponentName.class, new ComponentName("app.reaper", "Service")),
                ClassParameter.from(String.class, id));
    }
}
