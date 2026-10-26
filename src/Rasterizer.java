package app.oguri.printer;

/**
 * Pure, Android-free bitmap helpers: grayscale conversion, the
 * plain threshold rule, Floyd-Steinberg dithering, and MSB-first
 * bit packing. Kept separate from Android graphics so the exact
 * pixels that will be printed are unit-testable on the JVM.
 */
public final class Rasterizer {

    private Rasterizer() { }

    /** Grayscale value of an 0xRRGGBB pixel: the plain (R+G+B)/3 mean. */
    public static int grayOf(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (r + g + b) / 3;
    }

    public static int[] toGray(int[] rgb) {
        int[] gray = new int[rgb.length];
        for (int i = 0; i < rgb.length; i++) gray[i] = grayOf(rgb[i]);
        return gray;
    }

    /** Plain threshold: black iff gray < 128. */
    public static boolean[] threshold(int[] gray) {
        boolean[] black = new boolean[gray.length];
        for (int i = 0; i < gray.length; i++) black[i] = gray[i] < 128;
        return black;
    }

    /** Floyd-Steinberg dithering of a grayscale map (row-major, width*height). */
    public static boolean[] floydSteinberg(int[] gray, int width, int height) {
        float[] work = new float[gray.length];
        for (int i = 0; i < gray.length; i++) work[i] = gray[i];
        boolean[] black = new boolean[gray.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                float old = work[i];
                boolean isBlack = old < 128f;
                black[i] = isBlack;
                float err = old - (isBlack ? 0f : 255f);
                if (x + 1 < width) work[i + 1] += err * 7f / 16f;
                if (y + 1 < height) {
                    if (x > 0) work[i + width - 1] += err * 3f / 16f;
                    work[i + width] += err * 5f / 16f;
                    if (x + 1 < width) work[i + width + 1] += err * 1f / 16f;
                }
            }
        }
        return black;
    }

    /**
     * Packs a black/white boolean map into bytes, MSB = leftmost
     * pixel, row-major, top row first. Widths not a multiple of 8
     * are padded with white. Output size is ceil(width/8) * height.
     */
    public static byte[] packBits(boolean[] black, int width, int height) {
        int bytesPerRow = (width + 7) / 8;
        byte[] out = new byte[bytesPerRow * height];
        packBits(black, width, height, out, 0);
        return out;
    }

    public static void packBits(boolean[] black, int width, int height, byte[] out, int offset) {
        int bytesPerRow = (width + 7) / 8;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (black[y * width + x]) {
                    int at = offset + y * bytesPerRow + x / 8;
                    out[at] = (byte) (out[at] | (0x80 >> (x % 8)));
                }
            }
        }
    }
}
