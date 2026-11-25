package com.fadcam.utils;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Range;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Read-only view of what the device's video encoders actually advertise.
 *
 * <p>This exists because {@code MediaCodec.createEncoderByType()} only tells us that <em>some</em>
 * encoder claims a MIME type. It says nothing about whether that encoder can accept the requested
 * size, frame rate, bitrate, bitrate mode or {@code COLOR_FormatSurface}. When those do not match,
 * {@code configure()} throws and the old code could only report "failed to configure any encoder".
 *
 * <p>The helpers here are intentionally defensive: OEM codecs throw from capability queries more
 * often than the API contract suggests, and a broken query must never take down recording start.
 * Callers should treat this data as diagnostics and as a hint for a fallback, not as a hard gate —
 * some encoders under-report their capabilities and still encode the requested format.
 */
public final class VideoEncoderCapabilities {

    private VideoEncoderCapabilities() {
    }

    /** One encoder, inspected against a specific MIME/size request. */
    public static final class EncoderInfo {
        public final MediaCodecInfo codecInfo;
        public final String name;
        public final String mime;
        public final boolean hardware;
        public final boolean softwareOnly;
        public final boolean alias;

        public final boolean surfaceSupported;
        /** null when the query itself failed; false/true otherwise. */
        public final Boolean sizeSupported;
        public final Boolean sizeSupportedSwapped;

        public final Range<Integer> supportedWidths;
        public final Range<Integer> supportedHeights;
        public final int widthAlignment;
        public final int heightAlignment;

        /** Achievable frame-rate range for the requested size; null if unavailable. */
        public final Range<Double> frameRates;
        public final Range<Integer> bitrateRange;
        public final boolean cbrSupported;
        public final boolean vbrSupported;
        public final boolean cqSupported;

        public final String error;

        private EncoderInfo(
                MediaCodecInfo codecInfo,
                String mime,
                boolean hardware,
                boolean softwareOnly,
                boolean alias,
                boolean surfaceSupported,
                Boolean sizeSupported,
                Boolean sizeSupportedSwapped,
                Range<Integer> supportedWidths,
                Range<Integer> supportedHeights,
                int widthAlignment,
                int heightAlignment,
                Range<Double> frameRates,
                Range<Integer> bitrateRange,
                boolean cbrSupported,
                boolean vbrSupported,
                boolean cqSupported,
                String error) {
            this.codecInfo = codecInfo;
            this.name = codecInfo == null ? "?" : codecInfo.getName();
            this.mime = mime;
            this.hardware = hardware;
            this.softwareOnly = softwareOnly;
            this.alias = alias;
            this.surfaceSupported = surfaceSupported;
            this.sizeSupported = sizeSupported;
            this.sizeSupportedSwapped = sizeSupportedSwapped;
            this.supportedWidths = supportedWidths;
            this.supportedHeights = supportedHeights;
            this.widthAlignment = widthAlignment;
            this.heightAlignment = heightAlignment;
            this.frameRates = frameRates;
            this.bitrateRange = bitrateRange;
            this.cbrSupported = cbrSupported;
            this.vbrSupported = vbrSupported;
            this.cqSupported = cqSupported;
            this.error = error;
        }

        /** A single log-safe line. Never includes user data. */
        public String describe() {
            StringBuilder sb = new StringBuilder(220);
            sb.append("name=").append(name)
                    .append(" hw=").append(hardware)
                    .append(" swOnly=").append(softwareOnly)
                    .append(" alias=").append(alias)
                    .append(" surface=").append(surfaceSupported)
                    .append(" size=").append(sizeSupported)
                    .append(" sizeSwapped=").append(sizeSupportedSwapped)
                    .append(" widths=").append(formatRange(supportedWidths))
                    .append(" heights=").append(formatRange(supportedHeights))
                    .append(" align=").append(widthAlignment).append('x').append(heightAlignment)
                    .append(" fps=").append(formatRange(frameRates))
                    .append(" bitrate=").append(formatRange(bitrateRange))
                    .append(" modes=")
                    .append(cbrSupported ? "CBR," : "")
                    .append(vbrSupported ? "VBR," : "")
                    .append(cqSupported ? "CQ," : "");
            if (sb.charAt(sb.length() - 1) == ',') {
                sb.setLength(sb.length() - 1);
            }
            if (error != null && !error.isEmpty()) {
                sb.append(" error=").append(error);
            }
            return sb.toString();
        }
    }

    /**
     * Inspects every encoder that advertises {@code mime}. Sorted hardware-first, then by name
     * (aliases last) so callers get a sensible candidate order.
     */
    public static List<EncoderInfo> inspectAll(String mime, int width, int height) {
        List<EncoderInfo> result = new ArrayList<>();
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            MediaCodecInfo[] infos = list.getCodecInfos();
            if (infos == null) {
                return result;
            }
            for (MediaCodecInfo info : infos) {
                if (info == null || !info.isEncoder()) {
                    continue;
                }
                if (!supportsMime(info, mime)) {
                    continue;
                }
                result.add(inspect(info, mime, width, height));
            }
        } catch (Throwable t) {
            // An OEM MediaCodecList can theoretically throw; never break recording start.
            return result;
        }
        Collections.sort(result, new Comparator<EncoderInfo>() {
            @Override
            public int compare(EncoderInfo a, EncoderInfo b) {
                if (a.alias != b.alias) {
                    return a.alias ? 1 : -1;
                }
                if (a.hardware != b.hardware) {
                    return a.hardware ? -1 : 1;
                }
                if (a.softwareOnly != b.softwareOnly) {
                    return a.softwareOnly ? 1 : -1;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return result;
    }

    /** Inspects one encoder. Never throws. */
    public static EncoderInfo inspect(MediaCodecInfo info, String mime, int width, int height) {
        boolean hardware = isHardwareAccelerated(info);
        boolean softwareOnly = isSoftwareOnly(info);
        boolean alias = isAlias(info);
        boolean surface = false;
        Boolean sizeSupported = null;
        Boolean sizeSwapped = null;
        Range<Integer> widths = null;
        Range<Integer> heights = null;
        int widthAlignment = 1;
        int heightAlignment = 1;
        Range<Double> frameRates = null;
        Range<Integer> bitrates = null;
        boolean cbr = false;
        boolean vbr = false;
        boolean cq = false;
        String error = null;

        try {
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
            if (caps != null) {
                surface = supportsColorFormat(caps,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                if (video != null) {
                    widths = safe(() -> video.getSupportedWidths());
                    heights = safe(() -> video.getSupportedHeights());
                    widthAlignment = Math.max(1, video.getWidthAlignment());
                    heightAlignment = Math.max(1, video.getHeightAlignment());
                    sizeSupported = safe(() -> video.isSizeSupported(width, height));
                    sizeSwapped = safe(() -> video.isSizeSupported(height, width));
                    if (Boolean.TRUE.equals(sizeSupported)) {
                        frameRates = safe(() -> video.getSupportedFrameRatesFor(width, height));
                    } else if (Boolean.TRUE.equals(sizeSwapped)) {
                        // Query the swapped pair too: some encoders only answer when the size is
                        // in their preferred orientation.
                        frameRates = safe(() -> video.getSupportedFrameRatesFor(height, width));
                    }
                    bitrates = safe(video::getBitrateRange);
                }

                MediaCodecInfo.EncoderCapabilities encoder = caps.getEncoderCapabilities();
                if (encoder != null) {
                    cbr = safeBool(() -> encoder.isBitrateModeSupported(
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR));
                    vbr = safeBool(() -> encoder.isBitrateModeSupported(
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR));
                    cq = safeBool(() -> encoder.isBitrateModeSupported(
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ));
                }
            }
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }

        return new EncoderInfo(info, mime, hardware, softwareOnly, alias, surface,
                sizeSupported, sizeSwapped, widths, heights, widthAlignment, heightAlignment,
                frameRates, bitrates, cbr, vbr, cq, error);
    }

    /**
     * A multi-line report for the dossier. One line per encoder, including rejected ones and why.
     */
    public static String buildReport(String mime, int width, int height) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("mime=").append(mime).append(" requested=").append(width).append('x').append(height);
        List<EncoderInfo> infos = inspectAll(mime, width, height);
        sb.append(" encoders=").append(infos.size()).append('\n');
        for (int i = 0; i < infos.size(); i++) {
            sb.append("  #").append(i).append(' ').append(infos.get(i).describe()).append('\n');
        }
        if (infos.isEmpty()) {
            sb.append("  (none advertised)\n");
        }
        return sb.toString();
    }

    /**
     * Clamps a requested size to one the encoder advertises. Returns the requested size unchanged
     * when either orientation is advertised (or when the query fails), because encoders sometimes
     * under-report and configure() is the final judge.
     */
    public static int[] clampSize(MediaCodecInfo info, String mime, int width, int height) {
        try {
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
            MediaCodecInfo.VideoCapabilities video = caps == null ? null : caps.getVideoCapabilities();
            if (video == null) {
                return new int[]{width, height};
            }
            if (Boolean.TRUE.equals(safe(() -> video.isSizeSupported(width, height)))
                    || Boolean.TRUE.equals(safe(() -> video.isSizeSupported(height, width)))) {
                return new int[]{width, height};
            }
            Range<Integer> wRange = safe(video::getSupportedWidths);
            Range<Integer> hRange = safe(video::getSupportedHeights);
            if (wRange == null || hRange == null) {
                return new int[]{width, height};
            }
            int clampedW = clampTo(width, wRange);
            clampedW = alignDown(clampedW, video.getWidthAlignment(), wRange.getLower());
            final int widthForHeightQuery = clampedW;
            Range<Integer> heightsForWidth = safe(() -> video.getSupportedHeightsFor(widthForHeightQuery));
            if (heightsForWidth == null) {
                heightsForWidth = hRange;
            }
            int clampedH = clampTo(height, heightsForWidth);
            clampedH = alignDown(clampedH, video.getHeightAlignment(), heightsForWidth.getLower());
            return new int[]{clampedW, clampedH};
        } catch (Throwable t) {
            return new int[]{width, height};
        }
    }

    /** Highest advertised frame rate not above {@code desired}, or the advertised floor. */
    public static int clampFps(Range<Double> supported, int desired) {
        if (supported == null) {
            return desired;
        }
        // 59.94 is the advertised form of 60 fps; round, do not floor, or 60 becomes 59.
        int lo = (int) Math.round(supported.getLower());
        int hi = (int) Math.round(supported.getUpper());
        if (lo > hi) {
            return (int) Math.round((supported.getLower() + supported.getUpper()) / 2.0);
        }
        if (desired < lo) {
            return lo;
        }
        if (desired > hi) {
            return hi;
        }
        return desired;
    }

    /** True when an integer camera rate fits an advertised (often fractional) range. */
    public static boolean supportsFrameRate(Range<Double> supported, int rate) {
        if (supported == null) {
            return false;
        }
        double tolerance = 0.51; // 59.94 is the encoder's form of 60 fps
        return rate >= supported.getLower() - tolerance
                && rate <= supported.getUpper() + tolerance;
    }

    /** Clamps a bitrate into the encoder's advertised range. */
    public static int clampBitrate(Range<Integer> supported, int desired) {
        if (supported == null || desired <= 0) {
            return desired;
        }
        if (desired < supported.getLower()) {
            return supported.getLower();
        }
        if (desired > supported.getUpper()) {
            return supported.getUpper();
        }
        return desired;
    }

    /**
     * Picks the requested bitrate mode when advertised, otherwise the other standard mode.
     * Returns -1 when the encoder advertises neither (caller should omit the key).
     */
    public static int chooseBitrateMode(MediaCodecInfo info, String mime, int desired) {
        try {
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
            MediaCodecInfo.EncoderCapabilities encoder =
                    caps == null ? null : caps.getEncoderCapabilities();
            if (encoder == null) {
                return -1;
            }
            if (encoder.isBitrateModeSupported(desired)) {
                return desired;
            }
            int alternate = desired == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    ? MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    : MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;
            if (encoder.isBitrateModeSupported(alternate)) {
                return alternate;
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean isHardwareAccelerated(MediaCodecInfo info) {
        if (info == null) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return info.isHardwareAccelerated();
            }
        } catch (Throwable ignored) {
        }
        return !isSoftwareName(info.getName());
    }

    public static boolean isSoftwareOnly(MediaCodecInfo info) {
        if (info == null) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return info.isSoftwareOnly();
            }
        } catch (Throwable ignored) {
        }
        return isSoftwareName(info.getName());
    }

    public static boolean isAlias(MediaCodecInfo info) {
        if (info == null) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return info.isAlias();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static String mimeLabel(String mime) {
        if (MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime)) {
            return "HEVC";
        }
        if (MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime)) {
            return "AVC";
        }
        return String.valueOf(mime);
    }

    public static String formatRange(Range<?> range) {
        if (range == null) {
            return "?";
        }
        return String.format(Locale.US, "[%s..%s]", range.getLower(), range.getUpper());
    }

    // ------------------------------------------------------------------ internals

    private static boolean supportsMime(MediaCodecInfo info, String mime) {
        try {
            String[] types = info.getSupportedTypes();
            if (types == null) {
                return false;
            }
            for (String type : types) {
                if (mime.equalsIgnoreCase(type)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean supportsColorFormat(MediaCodecInfo.CodecCapabilities caps, int colorFormat) {
        int[] formats = caps.colorFormats;
        if (formats == null) {
            return false;
        }
        for (int format : formats) {
            if (format == colorFormat) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSoftwareName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        return lower.startsWith("omx.google.")
                || lower.startsWith("c2.android.")
                || lower.contains(".sw.")
                || lower.contains("software");
    }

    private static int clampTo(int value, Range<Integer> range) {
        if (value < range.getLower()) {
            return range.getLower();
        }
        if (value > range.getUpper()) {
            return range.getUpper();
        }
        return value;
    }

    private static int alignDown(int value, int alignment, int floor) {
        if (alignment <= 1) {
            return Math.max(value, floor);
        }
        int aligned = (value / alignment) * alignment;
        if (aligned < floor) {
            aligned = ((floor + alignment - 1) / alignment) * alignment;
        }
        return aligned;
    }

    private interface ValueSupplier<T> {
        T get() throws Exception;
    }

    private static <T> T safe(ValueSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return null;
        }
    }

    private interface BoolSupplier {
        boolean get() throws Exception;
    }

    private static boolean safeBool(BoolSupplier supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return false;
        }
    }
}
