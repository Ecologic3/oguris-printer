package app.oguri.printer;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, Android-free PPS1 protocol core, implemented from the
 * decompiled Luck Jingle SDK (see research/PROTOCOL.md).
 *
 * PPS1: 384 dots wide, 203 dpi, raster-only. All commands are
 * transport-independent byte sequences.
 */
public final class PrinterProtocol {

    public static final int PRINT_WIDTH = 384;
    public static final int BYTES_PER_ROW = PRINT_WIDTH / 8; // 48
    public static final int MIN_DENSITY = 0;
    public static final int MAX_DENSITY = 2;
    public static final int DEFAULT_DENSITY = 1;
    public static final int END_LINE_DOT = 80;

    private PrinterProtocol() { }

    // ---------- command builders ----------

    /** Enable printer: 10 FF F1 03 */
    public static byte[] enablePrinter() {
        return new byte[]{0x10, (byte) 0xFF, (byte) 0xF1, 0x03};
    }

    /** Wakeup: twelve 00 bytes. (10 FF 40 is NOT wake, it is the status query.) */
    public static byte[] wakeup() {
        return new byte[12];
    }

    /** Status query: 10 FF 40 -> 1 bitfield byte reply. */
    public static byte[] statusQuery() {
        return new byte[]{0x10, (byte) 0xFF, 0x40};
    }

    /** Battery query: 10 FF 50 F1 -> battery value is reply byte [1]. */
    public static byte[] batteryQuery() {
        return new byte[]{0x10, (byte) 0xFF, 0x50, (byte) 0xF1};
    }

    /** Model string query: 10 FF 20 F0 */
    public static byte[] modelQuery() {
        return new byte[]{0x10, (byte) 0xFF, 0x20, (byte) 0xF0};
    }

    /** Firmware version query: 10 FF 20 F1 */
    public static byte[] versionQuery() {
        return new byte[]{0x10, (byte) 0xFF, 0x20, (byte) 0xF1};
    }

    /** Serial number query: 10 FF 20 F2 */
    public static byte[] serialQuery() {
        return new byte[]{0x10, (byte) 0xFF, 0x20, (byte) 0xF2};
    }

    /** Density set: 10 FF 10 00 <density>, density clamped to [0,2] for PPS1. */
    public static byte[] setDensity(int density) {
        return new byte[]{0x10, (byte) 0xFF, 0x10, 0x00, (byte) clampDensity(density)};
    }

    public static int clampDensity(int density) {
        if (density < MIN_DENSITY) return MIN_DENSITY;
        if (density > MAX_DENSITY) return MAX_DENSITY;
        return density;
    }

    /** Feed n dots: 1B 4A <n> (ESC J). n clamped to 0..255. */
    public static byte[] feedDots(int dots) {
        int n = dots;
        if (n < 0) n = 0;
        if (n > 255) n = 255;
        return new byte[]{0x1B, 0x4A, (byte) n};
    }

    /**
     * Feed used at end of a page and by the Feed button:
     * 1B 4A <80 dots>. The vendor code holds endLineDot as the Java
     * int 80 and casts it to a byte (BaseNormalDevice
     * printLineDotsLuck: new byte[]{ESC, 74, (byte) i}), so the byte
     * on the wire is 80 decimal = 0x50.
     */
    public static byte[] feed() {
        return feedDots(END_LINE_DOT);
    }

    /** Mark print last page: 1B BB BB */
    public static byte[] markPrintLast() {
        return new byte[]{0x1B, (byte) 0xBB, (byte) 0xBB};
    }

    /** End of job: 10 FF F1 45, reply starts with AA or "OK". */
    public static byte[] endPrintJob() {
        return new byte[]{0x10, (byte) 0xFF, (byte) 0xF1, 0x45};
    }

    /** True if an end-of-job reply matches: first byte AA, or text starts with "OK". */
    public static boolean isEndReply(byte[] data, int len) {
        if (data == null || len <= 0) return false;
        if ((data[0] & 0xFF) == 0xAA) return true;
        return len >= 2 && data[0] == 'O' && data[1] == 'K';
    }

    // ---------- battery reply ----------

    /** Battery value is reply byte [1]; -1 if the reply is too short. Scale is raw firmware level. */
    public static int parseBattery(byte[] reply, int len) {
        if (reply == null || len < 2) return -1;
        return reply[1] & 0xFF;
    }

    // ---------- raster encoding (GS v 0) ----------

    /**
     * GS v 0 header: 1D 76 30 00 <xL> <xH> <yL> <yH>, where x is bytes
     * per row and y is height in dots, both little-endian.
     */
    public static byte[] rasterHeader(int bytesPerRow, int height) {
        return new byte[]{
                0x1D, 0x76, 0x30, 0x00,
                (byte) (bytesPerRow & 0xFF), (byte) ((bytesPerRow >> 8) & 0xFF),
                (byte) (height & 0xFF), (byte) ((height >> 8) & 0xFF)};
    }

    /** A pixel is black iff (R+G+B)/3 < 128. rgb is 0xRRGGBB. */
    public static boolean isBlackRgb(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (r + g + b) / 3 < 128;
    }

    /**
     * Encodes row-major RGB pixels (0xRRGGBB, length width*height) as a
     * complete GS v 0 raster payload: 8-byte header, then bytes-per-row
     * * height bytes, MSB = leftmost pixel, rows top first. Widths not a
     * multiple of 8 are padded with white.
     */
    public static byte[] encodeRasterFromRgb(int[] rgb, int width, int height) {
        boolean[] black = new boolean[width * height];
        for (int i = 0; i < black.length; i++) {
            black[i] = isBlackRgb(rgb[i]);
        }
        return encodeRaster(black, width, height);
    }

    /** Same as encodeRasterFromRgb, but from a black/white boolean map. */
    public static byte[] encodeRaster(boolean[] black, int width, int height) {
        int bytesPerRow = (width + 7) / 8;
        byte[] header = rasterHeader(bytesPerRow, height);
        byte[] out = new byte[header.length + bytesPerRow * height];
        System.arraycopy(header, 0, out, 0, header.length);
        Rasterizer.packBits(black, width, height, out, header.length);
        return out;
    }

    // ---------- full print page ----------

    /**
     * The segments of one printed page, in send order:
     * enable, wake, raster, feed (80 dots), mark-last, end-of-job.
     * The caller sends every segment as a plain write, except the last
     * one (end-of-job), which it should send waiting for the AA / OK
     * reply. Density is sent separately, once before the job.
     */
    public static List<byte[]> printPageSegments(boolean[] black, int width, int height) {
        List<byte[]> parts = new ArrayList<byte[]>();
        parts.add(enablePrinter());
        parts.add(wakeup());
        parts.add(encodeRaster(black, width, height));
        parts.add(feed());
        parts.add(markPrintLast());
        parts.add(endPrintJob());
        return parts;
    }
}
