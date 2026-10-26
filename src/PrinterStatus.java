package app.oguri.printer;

/**
 * Pure, Android-free decoding of the PPS1 status byte
 * (reply to the 10 FF 40 query) and of the unsolicited
 * 2-byte FF <n> error pushes.
 */
public final class PrinterStatus {

    // App-level codes, matching the vendor app's PrinterStatus.
    public static final int CODE_OUT_OF_PAPER = 0;
    public static final int CODE_COVER_OPEN = 1;
    public static final int CODE_OVERHEAT = 2;
    public static final int CODE_LOW_BATTERY = 3;
    public static final int CODE_PRINTING = 4;
    public static final int CODE_CHARGING = 5;
    public static final int CODE_OK = -1;

    public final boolean printing;
    public final boolean coverOpen;
    public final boolean outOfPaper;
    public final boolean lowBattery;
    public final boolean overheat;
    public final boolean charging;

    private PrinterStatus(boolean printing, boolean coverOpen, boolean outOfPaper,
                            boolean lowBattery, boolean overheat, boolean charging) {
        this.printing = printing;
        this.coverOpen = coverOpen;
        this.outOfPaper = outOfPaper;
        this.lowBattery = lowBattery;
        this.overheat = overheat;
        this.charging = charging;
    }

    /** Decodes the one status bitfield byte. Bit 7 is not read by the vendor code. */
    public static PrinterStatus parse(int b) {
        return new PrinterStatus(
                (b & 0x01) != 0,
                (b & 0x02) != 0,
                (b & 0x04) != 0,
                (b & 0x08) != 0,
                (b & 0x10) != 0 || (b & 0x40) != 0,
                (b & 0x20) != 0);
    }

    /**
     * The blocking problem to report before printing, with the vendor
     * app's priority: overheat > cover open > out of paper > low
     * battery. CODE_OK (-1) when nothing blocks printing.
     */
    public int prePrintBlockCode() {
        if (overheat) return CODE_OVERHEAT;
        if (coverOpen) return CODE_COVER_OPEN;
        if (outOfPaper) return CODE_OUT_OF_PAPER;
        if (lowBattery) return CODE_LOW_BATTERY;
        return CODE_OK;
    }

    public String describeCode(int code) {
        return describe(code);
    }

    public static String describe(int code) {
        switch (code) {
            case CODE_OUT_OF_PAPER: return "Out of paper";
            case CODE_COVER_OPEN: return "Cover open";
            case CODE_OVERHEAT: return "Overheated";
            case CODE_LOW_BATTERY: return "Low battery";
            case CODE_PRINTING: return "Printing";
            case CODE_CHARGING: return "Charging";
            default: return "Ready";
        }
    }

    /** Short human-readable summary of every flag that is set. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (printing) append(sb, "Printing");
        if (overheat) append(sb, "Overheated");
        if (coverOpen) append(sb, "Cover open");
        if (outOfPaper) append(sb, "Out of paper");
        if (lowBattery) append(sb, "Low battery");
        if (charging) append(sb, "Charging");
        if (sb.length() == 0) return "Ready";
        return sb.toString();
    }

    private static void append(StringBuilder sb, String s) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(s);
    }

    /**
     * Maps the second byte of an unsolicited FF <n> error push to an
     * app-level code, or -1 when n is not a known error push.
     */
    public static int unsolicitedCode(int n) {
        switch (n & 0xFF) {
            case 0x01: return CODE_OUT_OF_PAPER;
            case 0x02: return CODE_COVER_OPEN;
            case 0x03: return CODE_OVERHEAT;
            case 0x13: return CODE_OVERHEAT;
            case 0x04: return CODE_LOW_BATTERY;
            default: return -1;
        }
    }

    /** True for a 2-byte frame FF <n> with a known error code. */
    public static boolean isUnsolicitedError(byte[] data, int len) {
        return data != null && len == 2 && (data[0] & 0xFF) == 0xFF
                && unsolicitedCode(data[1]) != -1;
    }
}
