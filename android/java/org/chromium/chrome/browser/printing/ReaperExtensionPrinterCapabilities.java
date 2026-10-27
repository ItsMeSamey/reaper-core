// Copyright 2026 The Reaper Authors
// Use of this source code is governed by a BSD-style license.

package org.chromium.chrome.browser.printing;

import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Resolution;
import android.print.PrintJobInfo;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Converts the finite PDF/PWG subset of CDD 1.0 to Android capabilities and validated CJT 1.0. */
@NullMarked
public final class ReaperExtensionPrinterCapabilities {
    private static final int MAX_OPTIONS = 256;
    private static final int MAX_JSON_LENGTH = 256 * 1024;
    private final PrinterId mPrinterId;
    private final Map<String, JSONObject> mMedia = new HashMap<>();
    private final Map<String, MediaSize> mAndroidMedia = new HashMap<>();
    private final Map<String, Resolution> mResolutions = new HashMap<>();
    private final Map<Integer, JSONObject> mColors = new HashMap<>();
    private final Map<Integer, String> mDuplex = new HashMap<>();
    private final Set<String> mOrientations = new HashSet<>();
    private final JSONObject mFixedTicket = new JSONObject();
    private final PrinterCapabilitiesInfo mInfo;
    private int mMaxCopies = 1;
    private final boolean mHasCopies;
    private final boolean mHasDuplex;
    private boolean mCollated = true;

    /** Returns null when CDD cannot be faithfully represented by this print service. */
    public static @Nullable ReaperExtensionPrinterCapabilities parse(
            PrinterId printerId, String cddJson) {
        if (cddJson.length() > MAX_JSON_LENGTH) return null;
        try {
            return new ReaperExtensionPrinterCapabilities(printerId, new JSONObject(cddJson));
        } catch (JSONException | IllegalArgumentException | IllegalStateException exception) {
            return null;
        }
    }

    private ReaperExtensionPrinterCapabilities(PrinterId printerId, JSONObject root)
            throws JSONException {
        require("1.0".equals(root.getString("version")), "Unsupported CDD version");
        mPrinterId = printerId;
        JSONObject printer = root.getJSONObject("printer");
        // Unrepresented vendor/finishing/PIN/media-type settings must not silently acquire defaults.
        knownKeys(printer, "supported_content_type", "media_size", "dpi", "color", "duplex",
                "copies", "page_orientation", "fit_to_page", "collate", "reverse_order",
                "page_range", "pwg_raster_config", "printing_speed");
        JSONArray mimeTypes = bounded(printer.getJSONArray("supported_content_type"));
        boolean pdf = false;
        boolean pwg = false;
        for (int i = 0; i < mimeTypes.length(); i++) {
            JSONObject item = mimeTypes.getJSONObject(i);
            String type = item.getString("content_type");
            if ("application/pdf".equals(type) || "*/*".equals(type)) {
                // PDF version restrictions need inspection of the actual document, not guessing.
                knownKeys(item, "content_type");
                pdf = true;
            } else if ("image/pwg-raster".equals(type)) {
                knownKeys(item, "content_type");
                pwg = true;
            }
        }
        require(pdf || pwg, "Printer does not advertise PDF or PWG raster");
        if (!pdf && printer.has("pwg_raster_config")) {
            validatePwgConfig(printer.getJSONObject("pwg_raster_config"));
        }
        PrinterCapabilitiesInfo.Builder builder = new PrinterCapabilitiesInfo.Builder(printerId);
        parseMedia(builder, options(printer, "media_size"));
        parseResolutions(builder, options(printer, "dpi"));
        parseColors(builder, options(printer, "color"));
        mHasDuplex = printer.has("duplex");
        if (mHasDuplex) {
            JSONArray duplex = options(printer, "duplex");
            int defaultIndex = defaultIndex(duplex);
            int mask = 0;
            int defaultMode = 0;
            for (int i = 0; i < duplex.length(); i++) {
                JSONObject option = duplex.getJSONObject(i);
                knownKeys(option, "type", "is_default");
                String type = option.getString("type");
                int mode;
                switch (type) {
                    case "NO_DUPLEX": mode = PrintAttributes.DUPLEX_MODE_NONE; break;
                    case "LONG_EDGE": mode = PrintAttributes.DUPLEX_MODE_LONG_EDGE; break;
                    case "SHORT_EDGE": mode = PrintAttributes.DUPLEX_MODE_SHORT_EDGE; break;
                    default: throw new IllegalArgumentException("Unsupported duplex mode");
                }
                require(mDuplex.put(mode, type) == null, "Ambiguous duplex mode");
                mask |= mode;
                if (i == defaultIndex) defaultMode = mode;
            }
            builder.setDuplexModes(mask, defaultMode);
        } else {
            // Missing optional duplex capability is simplex; omit duplex from its ticket.
            mDuplex.put(PrintAttributes.DUPLEX_MODE_NONE, "NO_DUPLEX");
            builder.setDuplexModes(PrintAttributes.DUPLEX_MODE_NONE, PrintAttributes.DUPLEX_MODE_NONE);
        }
        mHasCopies = printer.has("copies");
        if (mHasCopies) {
            JSONObject copies = printer.getJSONObject("copies");
            knownKeys(copies, "default", "max");
            mMaxCopies = positive(copies, "max");
            require(positive(copies, "default") <= mMaxCopies, "Invalid default copies");
        }
        if (printer.has("page_orientation")) {
            JSONArray orientations = options(printer, "page_orientation");
            defaultIndex(orientations);
            for (int i = 0; i < orientations.length(); i++) {
                JSONObject option = orientations.getJSONObject(i);
                knownKeys(option, "type", "is_default");
                String type = option.getString("type");
                require(type.equals("AUTO") || type.equals("PORTRAIT") || type.equals("LANDSCAPE"),
                        "Unknown orientation");
                mOrientations.add(type);
            }
            // Android exposes both orientations and cannot hide just one of them.
            require(mOrientations.contains("AUTO")
                            || (mOrientations.contains("PORTRAIT")
                                    && mOrientations.contains("LANDSCAPE")),
                    "Printer cannot support both Android orientations");
        }
        if (printer.has("fit_to_page")) {
            JSONArray fit = options(printer, "fit_to_page");
            boolean hasNone = false;
            defaultIndex(fit);
            for (int i = 0; i < fit.length(); i++) {
                JSONObject option = fit.getJSONObject(i);
                knownKeys(option, "type", "is_default");
                hasNone |= "NONE".equals(option.getString("type"));
            }
            require(hasNone, "Printer requires a second PDF scaling operation");
            mFixedTicket.put("fit_to_page", new JSONObject().put("type", "NONE"));
        }
        if (printer.has("collate")) {
            JSONObject collate = printer.getJSONObject("collate");
            knownKeys(collate, "default");
            mCollated = bool(collate, "default", true);
            mFixedTicket.put("collate", new JSONObject().put("collate", mCollated));
        }
        if (printer.has("reverse_order")) {
            JSONObject reverse = printer.getJSONObject("reverse_order");
            knownKeys(reverse, "default");
            require(!bool(reverse, "default", false), "Reverse-order default is unsupported");
            mFixedTicket.put("reverse_order", new JSONObject().put("reverse_order", false));
        }
        if (printer.has("page_range")) {
            knownKeys(printer.getJSONObject("page_range"), "supported");
        }
        mInfo = builder.build();
    }

    private static void validatePwgConfig(JSONObject config) throws JSONException {
        knownKeys(config, "document_type_supported", "document_sheet_back",
                "reverse_order_streaming", "rotate_all_pages");
        if (config.has("document_type_supported")) {
            JSONArray types = config.getJSONArray("document_type_supported");
            require(types.length() <= MAX_OPTIONS, "Too many PWG raster types");
            for (int i = 0; i < types.length(); i++) {
                Object type = types.get(i);
                require("SGRAY_8".equals(type) || "SRGB_8".equals(type),
                        "Unsupported PWG raster encoding");
            }
        }
        if (config.has("document_sheet_back")) {
            Object back = config.get("document_sheet_back");
            require("NORMAL".equals(back) || "ROTATED".equals(back)
                            || "MANUAL_TUMBLE".equals(back) || "FLIPPED".equals(back),
                    "Unsupported PWG sheet-back transform");
        }
        bool(config, "reverse_order_streaming", false);
        bool(config, "rotate_all_pages", false);
    }

    public PrinterCapabilitiesInfo getInfo() {
        return mInfo;
    }

    /** Validates fresh capabilities against the spooled job before passing its unchanged PDF on. */
    public String buildTicket(PrintJobInfo job) throws JSONException {
        require(mPrinterId.equals(job.getPrinterId()), "Printer changed");
        PrintAttributes attributes = present(job.getAttributes(), "Missing print attributes");
        MediaSize selectedMedia = present(attributes.getMediaSize(), "Missing media");
        Resolution selectedResolution = present(attributes.getResolution(), "Missing resolution");
        MediaSize advertisedMedia = present(mAndroidMedia.get(selectedMedia.getId()), "Unsupported media");
        require(sameMediaDimensions(advertisedMedia, selectedMedia), "Media dimensions changed");
        Resolution advertisedResolution = present(
                mResolutions.get(selectedResolution.getId()), "Unsupported resolution");
        require(advertisedResolution.getHorizontalDpi() == selectedResolution.getHorizontalDpi()
                        && advertisedResolution.getVerticalDpi() == selectedResolution.getVerticalDpi(),
                "Unsupported resolution");
        JSONObject color = present(mColors.get(attributes.getColorMode()), "Unsupported color mode");
        String duplex = present(mDuplex.get(attributes.getDuplexMode()), "Unsupported duplex mode");
        require(job.getCopies() > 0 && job.getCopies() <= mMaxCopies, "Unsupported copy count");
        require(mCollated || job.getCopies() == 1, "Uncollated multiple copies are unsupported");
        PageRange[] ranges = job.getPages();
        // A PrintDocumentAdapter may supply all pages despite a subset request. There is no safe
        // way here to infer a document-to-original-page mapping or slice the unchanged PDF.
        require(ranges == null || (ranges.length == 1 && PageRange.ALL_PAGES.equals(ranges[0])),
                "Selected page ranges require PDF slicing");
        JSONObject ticket = new JSONObject(mFixedTicket.toString());
        JSONObject media = present(mMedia.get(selectedMedia.getId()), "Missing media description");
        validateMargins(attributes, media);
        ticket.put("media_size", new JSONObject(media.toString()));
        ticket.put("dpi", new JSONObject()
                .put("horizontal_dpi", selectedResolution.getHorizontalDpi())
                .put("vertical_dpi", selectedResolution.getVerticalDpi()));
        ticket.put("color", new JSONObject(color.toString()));
        if (mHasDuplex) ticket.put("duplex", new JSONObject().put("type", duplex));
        if (mHasCopies) ticket.put("copies", new JSONObject().put("copies", job.getCopies()));
        if (!mOrientations.isEmpty()) {
            // The PDF has already been rendered with the Android-selected orientation.
            String orientation = mOrientations.contains("AUTO") ? "AUTO"
                    : selectedMedia.isPortrait() ? "PORTRAIT" : "LANDSCAPE";
            require(mOrientations.contains(orientation), "Unsupported orientation");
            ticket.put("page_orientation", new JSONObject().put("type", orientation));
        }
        return new JSONObject().put("version", "1.0").put("print", ticket).toString();
    }

    private void parseMedia(PrinterCapabilitiesInfo.Builder builder, JSONArray options)
            throws JSONException {
        int defaultIndex = defaultIndex(options);
        int maximumMargin = 0;
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.getJSONObject(i);
            knownKeys(option, "name", "custom_display_name", "vendor_id", "width_microns",
                    "height_microns", "is_default", "is_continuous_feed",
                    "imageable_area_left_microns", "imageable_area_bottom_microns",
                    "imageable_area_right_microns", "imageable_area_top_microns",
                    "has_borderless_variant");
            require(!bool(option, "is_continuous_feed", false), "Continuous media is unsupported");
            int width = positive(option, "width_microns");
            int height = positive(option, "height_microns");
            String name = option.optString("name", "");
            String vendor = option.optString("vendor_id", "");
            String label = option.optString("custom_display_name", name);
            if (label.isEmpty()) label = width + " × " + height + " µm";
            String id = width + ":" + height + ":" + name + ":" + vendor;
            require(id.length() <= 1024 && label.length() <= 1024, "Media name too long");
            MediaSize media = new MediaSize(id, label, micronsToMils(width), micronsToMils(height));
            require(mAndroidMedia.put(id, media) == null, "Ambiguous media");
            JSONObject description = new JSONObject(option.toString());
            description.remove("is_default");
            description.remove("has_borderless_variant");
            mMedia.put(id, description);
            builder.addMediaSize(media, i == defaultIndex);
            for (int margin : marginsMicrons(description)) maximumMargin = Math.max(maximumMargin, margin);
        }
        // Android accepts only one margin tuple for all media and both orientations. The maximum
        // in every direction is conservative; round outward, preserving the exact CDD in tickets.
        int mils = micronsToMilsCeil(maximumMargin);
        for (MediaSize media : mAndroidMedia.values()) {
            require((long) mils * 2 < Math.min(media.getWidthMils(), media.getHeightMils()),
                    "Unrepresentable printable area");
        }
        builder.setMinMargins(new PrintAttributes.Margins(mils, mils, mils, mils));
    }

    private void parseResolutions(PrinterCapabilitiesInfo.Builder builder, JSONArray options)
            throws JSONException {
        int defaultIndex = defaultIndex(options);
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.getJSONObject(i);
            knownKeys(option, "horizontal_dpi", "vertical_dpi", "is_default");
            int horizontal = positive(option, "horizontal_dpi");
            int vertical = positive(option, "vertical_dpi");
            String id = horizontal + "x" + vertical;
            Resolution resolution = new Resolution(id, id + " dpi", horizontal, vertical);
            require(mResolutions.put(id, resolution) == null, "Ambiguous resolution");
            builder.addResolution(resolution, i == defaultIndex);
        }
    }

    private void parseColors(PrinterCapabilitiesInfo.Builder builder, JSONArray options)
            throws JSONException {
        int defaultIndex = defaultIndex(options);
        int mask = 0;
        int defaultMode = 0;
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.getJSONObject(i);
            knownKeys(option, "type", "vendor_id", "custom_display_name", "is_default");
            String type = option.getString("type");
            int mode;
            switch (type) {
                case "STANDARD_COLOR": mode = PrintAttributes.COLOR_MODE_COLOR; break;
                case "STANDARD_MONOCHROME": mode = PrintAttributes.COLOR_MODE_MONOCHROME; break;
                default: throw new IllegalArgumentException("Unsupported custom/automatic color");
            }
            JSONObject value = new JSONObject(option.toString());
            value.remove("is_default");
            require(mColors.put(mode, value) == null, "Ambiguous color mode");
            mask |= mode;
            if (i == defaultIndex) defaultMode = mode;
        }
        builder.setColorModes(mask, defaultMode);
    }

    private static int[] marginsMicrons(JSONObject media) throws JSONException {
        String[] keys = {"imageable_area_left_microns", "imageable_area_bottom_microns",
                "imageable_area_right_microns", "imageable_area_top_microns"};
        int count = 0;
        for (String key : keys) if (media.has(key)) count++;
        if (count == 0) return new int[] {0, 0, 0, 0};
        require(count == 4, "Incomplete printable area");
        int left = nonnegative(media, keys[0]);
        int bottom = nonnegative(media, keys[1]);
        int right = nonnegative(media, keys[2]);
        int top = nonnegative(media, keys[3]);
        int width = positive(media, "width_microns");
        int height = positive(media, "height_microns");
        require(right > left && right <= width && top > bottom && top <= height,
                "Invalid printable area");
        return new int[] {left, height - top, width - right, bottom};
    }

    private static void validateMargins(PrintAttributes attributes, JSONObject media)
            throws JSONException {
        PrintAttributes.Margins margins = present(attributes.getMinMargins(), "Missing margins");
        int maximum = 0;
        for (int margin : marginsMicrons(media)) maximum = Math.max(maximum, margin);
        int needed = micronsToMilsCeil(maximum);
        require(margins.getLeftMils() >= needed && margins.getTopMils() >= needed
                        && margins.getRightMils() >= needed && margins.getBottomMils() >= needed,
                "Margins exceed printable area");
    }

    private static boolean sameMediaDimensions(MediaSize a, MediaSize b) {
        return (a.getWidthMils() == b.getWidthMils() && a.getHeightMils() == b.getHeightMils())
                || (a.getWidthMils() == b.getHeightMils() && a.getHeightMils() == b.getWidthMils());
    }

    private static JSONArray options(JSONObject printer, String key) throws JSONException {
        JSONObject capability = printer.getJSONObject(key);
        knownKeys(capability, "option");
        return bounded(capability.getJSONArray("option"));
    }

    private static JSONArray bounded(JSONArray values) {
        require(values.length() > 0 && values.length() <= MAX_OPTIONS, "Invalid option count");
        return values;
    }

    private static int defaultIndex(JSONArray options) throws JSONException {
        int result = -1;
        for (int i = 0; i < options.length(); i++) {
            if (!bool(options.getJSONObject(i), "is_default", false)) continue;
            require(result == -1, "Multiple defaults");
            result = i;
        }
        return result == -1 ? 0 : result;
    }

    private static void knownKeys(JSONObject object, String... supported) {
        Set<String> keys = new HashSet<>(Arrays.asList(supported));
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) require(keys.contains(iterator.next()), "Unsupported capability");
    }

    private static boolean bool(JSONObject object, String key, boolean fallback) throws JSONException {
        if (!object.has(key)) return fallback;
        Object value = object.get(key);
        require(value instanceof Boolean, "Non-boolean capability");
        return (Boolean) value;
    }

    private static int positive(JSONObject object, String key) throws JSONException {
        int value = nonnegative(object, key);
        require(value > 0, "Non-positive capability");
        return value;
    }

    private static int nonnegative(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        require(value instanceof Number, "Non-numeric capability");
        double number = ((Number) value).doubleValue();
        require(number >= 0 && number <= Integer.MAX_VALUE && number == Math.floor(number),
                "Out-of-range capability");
        return (int) number;
    }

    private static int micronsToMils(int microns) {
        long rounded = (microns * 10L + 127) / 254;
        require(rounded > 0 && rounded <= Integer.MAX_VALUE, "Unrepresentable media size");
        return (int) rounded;
    }

    private static int micronsToMilsCeil(int microns) {
        return (int) ((microns * 10L + 253) / 254);
    }

    private static <T> T present(@Nullable T value, String reason) {
        if (value == null) throw new IllegalArgumentException(reason);
        return value;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
