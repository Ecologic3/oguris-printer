package app.oguri.printer;

/**
 * Pure, Android-free maths for the text renderer: per-line horizontal
 * offset for the chosen alignment, and the invert flip applied to the
 * finished boolean raster. Kept separate from MainActivity so the JVM
 * tests can cover it (Paint-based wrapping stays in the activity).
 */
public final class TextLayout {

    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT = 2;

    private TextLayout() { }

    /**
     * X offset of a line inside the content area for the alignment.
     * Left = 0, centre = half the slack, right = all the slack.
     * A line at least as wide as the content area gets 0.
     */
    public static int lineXOffset(int align, int contentWidth, int lineWidth) {
        int slack = contentWidth - lineWidth;
        if (slack <= 0) return 0;
        switch (align) {
            case ALIGN_CENTER: return slack / 2;
            case ALIGN_RIGHT: return slack;
            default: return 0;
        }
    }

    /** A flipped copy of the raster (black <-> white); the input is not mutated. */
    public static boolean[] invertedCopy(boolean[] black) {
        boolean[] out = new boolean[black.length];
        for (int i = 0; i < black.length; i++) out[i] = !black[i];
        return out;
    }

    /** The raster to print/preview: flipped when invert is on, the input otherwise. */
    public static boolean[] applyInvert(boolean[] black, boolean invert) {
        return invert ? invertedCopy(black) : black;
    }
}
