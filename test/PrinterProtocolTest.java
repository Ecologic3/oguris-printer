package app.oguri.printer;

import java.util.List;

/** Plain JVM tests for the pure protocol core. No JUnit, no Android. */
public class PrinterProtocolTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name); }
    }

    private static boolean bytesEq(byte[] a, int[] expected) {
        if (a == null || a.length != expected.length) return false;
        for (int i = 0; i < a.length; i++) if ((a[i] & 0xFF) != expected[i]) return false;
        return true;
    }

    public static void main(String[] args) {
        // GS v 0 header for full width: 1D 76 30 00 30 00 <height LE>
        byte[] h = PrinterProtocol.rasterHeader(48, 300);
        check("header 384x300", bytesEq(h, new int[]{0x1D, 0x76, 0x30, 0x00, 0x30, 0x00, 0x2C, 0x01}));
        byte[] h2 = PrinterProtocol.rasterHeader(48, 8);
        check("header height 8", bytesEq(h2, new int[]{0x1D, 0x76, 0x30, 0x00, 0x30, 0x00, 0x08, 0x00}));

        // MSB-first packing on a known 8-pixel row: black at x=0 -> 0x80, x=7 -> 0x01
        boolean[] row = new boolean[]{true, false, false, false, false, false, false, true};
        byte[] packed = Rasterizer.packBits(row, 8, 1);
        check("pack MSB first", packed.length == 1 && (packed[0] & 0xFF) == 0x81);
        boolean[] row2 = new boolean[]{false, true, true, false, false, false, false, false};
        byte[] packed2 = Rasterizer.packBits(row2, 8, 1);
        check("pack bits 1,2", (packed2[0] & 0xFF) == 0x60);
        // Padding: width 5, first pixel black -> 0x80, one byte per row
        boolean[] row3 = new boolean[]{true, false, false, false, false};
        byte[] packed3 = Rasterizer.packBits(row3, 5, 1);
        check("pack padded width", packed3.length == 1 && (packed3[0] & 0xFF) == 0x80);

        // Threshold rule: black iff (R+G+B)/3 < 128
        check("threshold black 0", PrinterProtocol.isBlackRgb(0x000000));
        check("threshold white 255", !PrinterProtocol.isBlackRgb(0xFFFFFF));
        check("threshold 127 black", PrinterProtocol.isBlackRgb(0x7F7F7F));
        check("threshold 128 white", !PrinterProtocol.isBlackRgb(0x808080));
        check("threshold mean not channel", PrinterProtocol.isBlackRgb(0xFF0000) == ((255 / 3) < 128));
        check("grayOf red", Rasterizer.grayOf(0xFF0000) == 85);

        // Raster payload: header + 48*height data, first row all black -> 0xFF bytes
        boolean[] img = new boolean[384 * 2];
        for (int i = 0; i < 384; i++) img[i] = true;
        byte[] raster = PrinterProtocol.encodeRaster(img, 384, 2);
        check("raster length", raster.length == 8 + 96);
        check("raster row0 black", (raster[8] & 0xFF) == 0xFF && (raster[8 + 47] & 0xFF) == 0xFF);
        check("raster row1 white", raster[8 + 48] == 0);

        // RGB encoder uses the same threshold
        int[] rgb = new int[8];
        for (int i = 0; i < rgb.length; i++) rgb[i] = 0xFFFFFF;
        rgb[0] = 0x000000;
        byte[] r2 = PrinterProtocol.encodeRasterFromRgb(rgb, 8, 1);
        check("rgb raster pack", r2.length == 9 && (r2[8] & 0xFF) == 0x80);

        // Status bitfield decoding, each bit
        check("status printing", PrinterStatus.parse(0x01).printing);
        check("status cover", PrinterStatus.parse(0x02).coverOpen);
        check("status paper", PrinterStatus.parse(0x04).outOfPaper);
        check("status lowbat", PrinterStatus.parse(0x08).lowBattery);
        check("status overheat bit4", PrinterStatus.parse(0x10).overheat);
        check("status overheat bit6", PrinterStatus.parse(0x40).overheat);
        check("status charging", PrinterStatus.parse(0x20).charging);
        check("status zero ready", PrinterStatus.parse(0x00).prePrintBlockCode() == PrinterStatus.CODE_OK
                && "Ready".equals(PrinterStatus.parse(0x00).summary()));
        check("status bit7 ignored", PrinterStatus.parse(0x80).prePrintBlockCode() == PrinterStatus.CODE_OK);
        // Combinations and priority: overheat > cover > paper > low battery
        check("priority overheat wins", PrinterStatus.parse(0x1E).prePrintBlockCode() == PrinterStatus.CODE_OVERHEAT);
        check("priority cover next", PrinterStatus.parse(0x0E).prePrintBlockCode() == PrinterStatus.CODE_COVER_OPEN);
        check("priority paper next", PrinterStatus.parse(0x0C).prePrintBlockCode() == PrinterStatus.CODE_OUT_OF_PAPER);
        check("priority lowbat last", PrinterStatus.parse(0x08).prePrintBlockCode() == PrinterStatus.CODE_LOW_BATTERY);
        check("printing does not block", PrinterStatus.parse(0x01).prePrintBlockCode() == PrinterStatus.CODE_OK);
        check("charging does not block", PrinterStatus.parse(0x20).prePrintBlockCode() == PrinterStatus.CODE_OK);
        PrinterStatus combo = PrinterStatus.parse(0x25); // printing + paper + charging
        check("combo flags", combo.printing && combo.outOfPaper && combo.charging && !combo.coverOpen);

        // Unsolicited pushes
        check("unsolicited paper", PrinterStatus.unsolicitedCode(0x01) == PrinterStatus.CODE_OUT_OF_PAPER);
        check("unsolicited overheat 13", PrinterStatus.unsolicitedCode(0x13) == PrinterStatus.CODE_OVERHEAT);
        check("unsolicited unknown", PrinterStatus.unsolicitedCode(0x77) == -1);
        check("isUnsolicitedError", PrinterStatus.isUnsolicitedError(new byte[]{(byte) 0xFF, 0x02}, 2)
                && !PrinterStatus.isUnsolicitedError(new byte[]{(byte) 0xFF, 0x02}, 3));

        // Density clamp to [0,2]
        check("density clamp low", PrinterProtocol.clampDensity(-5) == 0);
        check("density clamp high", PrinterProtocol.clampDensity(7) == 2);
        check("density keep 1", PrinterProtocol.clampDensity(1) == 1);
        check("density bytes", bytesEq(PrinterProtocol.setDensity(2), new int[]{0x10, 0xFF, 0x10, 0x00, 0x02}));
        check("density bytes clamped", bytesEq(PrinterProtocol.setDensity(9), new int[]{0x10, 0xFF, 0x10, 0x00, 0x02}));

        // Fixed command bytes
        check("enable bytes", bytesEq(PrinterProtocol.enablePrinter(), new int[]{0x10, 0xFF, 0xF1, 0x03}));
        byte[] wake = PrinterProtocol.wakeup();
        boolean wakeOk = wake.length == 12;
        for (int i = 0; i < wake.length; i++) if (wake[i] != 0) wakeOk = false;
        check("wake is 12 zeros", wakeOk);
        check("status query bytes", bytesEq(PrinterProtocol.statusQuery(), new int[]{0x10, 0xFF, 0x40}));
        check("battery query bytes", bytesEq(PrinterProtocol.batteryQuery(), new int[]{0x10, 0xFF, 0x50, 0xF1}));
        check("model query bytes", bytesEq(PrinterProtocol.modelQuery(), new int[]{0x10, 0xFF, 0x20, 0xF0}));
        check("version query bytes", bytesEq(PrinterProtocol.versionQuery(), new int[]{0x10, 0xFF, 0x20, 0xF1}));
        check("serial query bytes", bytesEq(PrinterProtocol.serialQuery(), new int[]{0x10, 0xFF, 0x20, 0xF2}));
        // endLineDot is the Java int 80 in the vendor code, cast to a byte: 0x50 on the wire
        check("feed bytes", bytesEq(PrinterProtocol.feed(), new int[]{0x1B, 0x4A, 0x50}));
        check("feed clamp", bytesEq(PrinterProtocol.feedDots(300), new int[]{0x1B, 0x4A, 0xFF}));
        check("mark last bytes", bytesEq(PrinterProtocol.markPrintLast(), new int[]{0x1B, 0xBB, 0xBB}));
        check("end bytes", bytesEq(PrinterProtocol.endPrintJob(), new int[]{0x10, 0xFF, 0xF1, 0x45}));

        // Battery parse: value is byte [1]
        check("battery parse", PrinterProtocol.parseBattery(new byte[]{0x00, 0x55}, 2) == 0x55);
        check("battery short", PrinterProtocol.parseBattery(new byte[]{0x00}, 1) == -1);

        // End reply matching
        check("end reply AA", PrinterProtocol.isEndReply(new byte[]{(byte) 0xAA, 0x01}, 2));
        check("end reply OK", PrinterProtocol.isEndReply(new byte[]{'O', 'K'}, 2));
        check("end reply no", !PrinterProtocol.isEndReply(new byte[]{0x10, 0x01}, 2));

        // Print-job byte sequence order for a tiny bitmap (8x1, first pixel black)
        boolean[] tiny = new boolean[]{true, false, false, false, false, false, false, false};
        List<byte[]> segs = PrinterProtocol.printPageSegments(tiny, 8, 1);
        check("job segment count", segs.size() == 6);
        check("job order enable", bytesEq(segs.get(0), new int[]{0x10, 0xFF, 0xF1, 0x03}));
        check("job order wake", segs.get(1).length == 12);
        check("job order raster", bytesEq(segs.get(2),
                new int[]{0x1D, 0x76, 0x30, 0x00, 0x01, 0x00, 0x01, 0x00, 0x80}));
        check("job order feed", bytesEq(segs.get(3), new int[]{0x1B, 0x4A, 0x50}));
        check("job order mark", bytesEq(segs.get(4), new int[]{0x1B, 0xBB, 0xBB}));
        check("job order end", bytesEq(segs.get(5), new int[]{0x10, 0xFF, 0xF1, 0x45}));

        // Floyd-Steinberg: a solid mid-gray field must produce a mix, extremes stay solid
        int[] gray = new int[64];
        for (int i = 0; i < gray.length; i++) gray[i] = 128;
        boolean[] fs = Rasterizer.floydSteinberg(gray, 8, 8);
        int blacks = 0;
        for (int i = 0; i < fs.length; i++) if (fs[i]) blacks++;
        check("fs midgray mixes", blacks > 10 && blacks < 54);
        int[] dark = new int[16];
        boolean[] fsDark = Rasterizer.floydSteinberg(dark, 4, 4);
        boolean allBlack = true;
        for (int i = 0; i < fsDark.length; i++) if (!fsDark[i]) allBlack = false;
        check("fs black stays black", allBlack);
        check("threshold midgray all white", Rasterizer.threshold(gray)[0] == false);

        // ---- Device identity merging (Classic + BLE sightings of one unit) ----
        check("canonical strips _BLE", "PPS1_9F8E".equals(PrinterDevices.canonicalName("PPS1_9F8E_BLE")));
        check("canonical keeps plain", "PPS1_9F8E".equals(PrinterDevices.canonicalName("PPS1_9F8E")));
        check("mac equal ignoring first octet",
                PrinterDevices.macEqualIgnoringFirstOctet("02:0A:1B:2C:3D:4E", "03:0A:1B:2C:3D:4E"));
        check("mac differ after first octet",
                !PrinterDevices.macEqualIgnoringFirstOctet("02:0A:1B:2C:3D:4E", "03:0A:1B:2C:3D:4F"));
        check("sameUnit pair", PrinterDevices.sameUnit(
                "PPS1_9F8E", "02:0A:1B:2C:3D:4E", "PPS1_9F8E_BLE", "03:0A:1B:2C:3D:4E"));
        check("sameUnit different suffix is not a unit", !PrinterDevices.sameUnit(
                "PPS1_9F8E", "02:0A:1B:2C:3D:4E", "PPS1_9F8E_BLE", "03:0A:1B:2C:3D:4F"));
        check("sameUnit different name is not a unit", !PrinterDevices.sameUnit(
                "PPS1_77AA", "02:99:88:77:66:55", "PPS1_9F8E_BLE", "03:0A:1B:2C:3D:4E"));
        check("sameUnit mac-only match is not enough", !PrinterDevices.sameUnit(
                "PPS1_77AA", "02:0A:1B:2C:3D:4E", "PPS1_9F8E", "03:0A:1B:2C:3D:4E"));

        // Sighting flow: bonded Classic first, BLE identity later -> one merged entry
        java.util.List<PrinterDevices.Device> devs = new java.util.ArrayList<PrinterDevices.Device>();
        int idx = PrinterDevices.findMatch(devs, "PPS1_9F8E", "02:0A:1B:2C:3D:4E");
        check("no match in empty list", idx == -1);
        PrinterDevices.Device unit = new PrinterDevices.Device("PPS1_9F8E");
        unit.addSighting("PPS1_9F8E", "02:0A:1B:2C:3D:4E", PrinterDevices.TAG_BONDED);
        unit.addSighting("PPS1_9F8E", "02:0A:1B:2C:3D:4E", PrinterDevices.TAG_CLASSIC);
        devs.add(unit);
        int merged = PrinterDevices.findMatch(devs, "PPS1_9F8E_BLE", "03:0A:1B:2C:3D:4E");
        check("BLE sighting matches classic entry", merged == 0);
        devs.get(merged).addSighting("PPS1_9F8E_BLE", "03:0A:1B:2C:3D:4E", PrinterDevices.TAG_BLE);
        check("merged keeps both addresses",
                "02:0A:1B:2C:3D:4E".equals(unit.classicMac) && "03:0A:1B:2C:3D:4E".equals(unit.bleMac));
        check("merged canonical name", "PPS1_9F8E".equals(unit.name));
        check("merged tags combined", "Bonded · Classic · BLE".equals(unit.viaDisplay()));
        check("merged primary is classic", "02:0A:1B:2C:3D:4E".equals(unit.primaryMac()));
        check("other unit does not match",
                PrinterDevices.findMatch(devs, "PPS1_55CC", "02:66:55:44:33:22") == -1);

        // Transport preference: SPP first when a Classic address exists, else BLE
        check("prefer SPP with both addresses", PrinterDevices.preferSppFirst(true, true));
        check("prefer SPP with classic only", PrinterDevices.preferSppFirst(true, false));
        check("prefer BLE with ble only", !PrinterDevices.preferSppFirst(false, true));
        PrinterDevices.Device bleOnly = new PrinterDevices.Device("PPS1_9F8E_BLE");
        bleOnly.addSighting("PPS1_9F8E_BLE", "03:0A:1B:2C:3D:4E", PrinterDevices.TAG_BLE);
        check("ble-only device prefers BLE", !bleOnly.preferSppFirst()
                && "03:0A:1B:2C:3D:4E".equals(bleOnly.primaryMac()));

        // ---- Text layout maths ----
        check("align left offset 0", TextLayout.lineXOffset(TextLayout.ALIGN_LEFT, 364, 100) == 0);
        check("align center offset", TextLayout.lineXOffset(TextLayout.ALIGN_CENTER, 364, 100) == 132);
        check("align right offset", TextLayout.lineXOffset(TextLayout.ALIGN_RIGHT, 364, 100) == 264);
        check("align wide line clamps to 0", TextLayout.lineXOffset(TextLayout.ALIGN_RIGHT, 364, 400) == 0);
        boolean[] tinyRaster = new boolean[]{true, false, true, false};
        boolean[] flipped = TextLayout.applyInvert(tinyRaster, true);
        check("invert flips bits", !flipped[0] && flipped[1] && !flipped[2] && flipped[3]);
        check("invert leaves input untouched", tinyRaster[0] && !tinyRaster[1]);
        check("no invert returns same raster", TextLayout.applyInvert(tinyRaster, false) == tinyRaster);

        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
