package app.oguri.printer;

import android.bluetooth.BluetoothDevice;
import android.content.Context;

import java.util.List;

/**
 * Owns the active transport, serialises commands through the
 * response dispatcher, and runs the vendor print sequence.
 * Every method blocks: call them from one background IO thread only.
 */
public class PrinterConnection {

    public interface Events {
        /** An unsolicited FF <n> error push arrived from the printer. */
        void onUnsolicitedError(String description);
        void onDisconnected(String transportName);
    }

    public static final String TRANSPORT_SPP = "Classic SPP";
    public static final String TRANSPORT_BLE = "BLE";

    private final ResponseDispatcher dispatcher = new ResponseDispatcher();
    private PrinterTransport transport;
    private String transportName = "";
    private String deviceName = "";
    private String deviceMac = "";

    public PrinterConnection(final Events events) {
        dispatcher.setUnsolicitedListener(new ResponseDispatcher.UnsolicitedListener() {
            @Override public void onUnsolicitedError(int code) {
                if (events != null) events.onUnsolicitedError(PrinterStatus.describe(code));
            }
            @Override public void onDisconnected(String reason) {
                if (events != null) events.onDisconnected(transportName);
            }
        });
    }

    public boolean isConnected() {
        return transport != null && transport.isConnected();
    }

    public String transportName() { return transportName; }
    public String deviceName() { return deviceName; }
    public String deviceMac() { return deviceMac; }

    /**
     * Connects trying the preferred transport first, then the other.
     * preferred is TRANSPORT_SPP, TRANSPORT_BLE, or anything else for
     * "no preference" (SPP first: it is the simpler transport).
     */
    public boolean connect(Context context, BluetoothDevice device, String preferred) {
        disconnect();
        String[] order = TRANSPORT_BLE.equals(preferred)
                ? new String[]{TRANSPORT_BLE, TRANSPORT_SPP}
                : new String[]{TRANSPORT_SPP, TRANSPORT_BLE};
        for (int i = 0; i < order.length; i++) {
            PrinterTransport t = TRANSPORT_BLE.equals(order[i])
                    ? (PrinterTransport) new BleTransport()
                    : (PrinterTransport) new SppTransport();
            boolean ok = false;
            try {
                ok = t.connect(context, device, dispatcher);
            } catch (Exception ignored) {
                ok = false;
            }
            if (ok) {
                transport = t;
                transportName = order[i];
                try { deviceName = device.getName(); } catch (Exception ignored) { }
                if (deviceName == null) deviceName = "";
                try { deviceMac = device.getAddress(); } catch (Exception ignored) { deviceMac = ""; }
                return true;
            }
            try { t.disconnect(); } catch (Exception ignored) { }
        }
        transport = null;
        transportName = "";
        return false;
    }

    public void disconnect() {
        if (transport != null) {
            try { transport.disconnect(); } catch (Exception ignored) { }
            transport = null;
        }
        transportName = "";
        deviceName = "";
        deviceMac = "";
    }

    // ---------- queries ----------

    /** Status query, or null when the printer did not reply. */
    public PrinterStatus queryStatus() {
        if (!isConnected()) return null;
        byte[] reply = transact(PrinterProtocol.statusQuery(), 3000,
                new ResponseDispatcher.Matcher() {
                    @Override public boolean matches(byte[] data, int len) { return len >= 1; }
                });
        if (reply == null || reply.length < 1) return null;
        return PrinterStatus.parse(reply[0] & 0xFF);
    }

    /** Battery raw level (reply byte 1), or -1. The firmware scale is unverified: it is NOT a percent. */
    public int queryBattery() {
        if (!isConnected()) return -1;
        byte[] reply = transact(PrinterProtocol.batteryQuery(), 3000,
                new ResponseDispatcher.Matcher() {
                    @Override public boolean matches(byte[] data, int len) { return len >= 2; }
                });
        return PrinterProtocol.parseBattery(reply, reply == null ? 0 : reply.length);
    }

    public String queryModel() { return queryText(PrinterProtocol.modelQuery()); }
    public String queryVersion() { return queryText(PrinterProtocol.versionQuery()); }
    public String querySerial() { return queryText(PrinterProtocol.serialQuery()); }

    private String queryText(byte[] cmd) {
        if (!isConnected()) return null;
        byte[] reply;
        try {
            dispatcher.begin(new ResponseDispatcher.Matcher() {
                @Override public boolean matches(byte[] data, int len) { return false; }
            });
            transport.write(cmd);
            reply = dispatcher.awaitDone(1500);
        } catch (Exception e) {
            return null;
        }
        if (reply == null || reply.length == 0) return null;
        String s;
        try {
            s = new String(reply, "UTF-8");
        } catch (Exception e) {
            s = new String(reply);
        }
        // Trim surrounding whitespace, NULs and other control framing.
        int start = 0, end = s.length();
        while (start < end && s.charAt(start) <= ' ') start++;
        while (end > start && s.charAt(end - 1) <= ' ') end--;
        s = s.substring(start, end);
        return s.length() == 0 ? null : s;
    }

    private byte[] transact(byte[] cmd, long timeoutMs, ResponseDispatcher.Matcher matcher) {
        if (!isConnected()) return null;
        try {
            dispatcher.begin(matcher);
            transport.write(cmd);
            return dispatcher.awaitDone(timeoutMs);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- actions ----------

    /** Wake + feed 80 dots. Returns false when not connected or the write failed. */
    public boolean feed() {
        if (!isConnected()) return false;
        try {
            transport.write(PrinterProtocol.wakeup());
            transport.write(PrinterProtocol.feed());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Prints the bitmap. Returns null on success, or a short error
     * message. Sequence (vendor): status check before printing,
     * density set, then per copy: enable, wake, GS v 0 raster, feed,
     * mark-last, end-of-job waiting for the AA / OK reply.
     */
    public String print(boolean[] black, int width, int height, int density, int copies) {
        if (!isConnected()) return "Not connected";
        PrinterStatus status = queryStatus();
        if (status != null) {
            int block = status.prePrintBlockCode();
            if (block != PrinterStatus.CODE_OK) {
                return PrinterStatus.describe(block);
            }
        }
        try {
            transport.write(PrinterProtocol.setDensity(density));
        } catch (Exception e) {
            return "Could not set density";
        }
        List<byte[]> segments = PrinterProtocol.printPageSegments(black, width, height);
        int nCopies = Math.max(1, Math.min(3, copies));
        for (int copy = 0; copy < nCopies; copy++) {
            try {
                for (int i = 0; i < segments.size() - 1; i++) {
                    transport.write(segments.get(i));
                }
            } catch (Exception e) {
                return "Write failed while printing";
            }
            byte[] reply = transact(segments.get(segments.size() - 1), 70000,
                    new ResponseDispatcher.Matcher() {
                        @Override public boolean matches(byte[] data, int len) {
                            return PrinterProtocol.isEndReply(data, len);
                        }
                    });
            if (reply == null || !PrinterProtocol.isEndReply(reply, reply.length)) {
                return "Printed, but the printer did not confirm it finished";
            }
        }
        return null;
    }
}
