package app.oguri.printer;

import android.bluetooth.BluetoothDevice;
import android.content.Context;

import java.io.ByteArrayOutputStream;

/**
 * One physical transport to the printer (Classic SPP or BLE).
 * Implementations are used from a single IO thread: connect() and
 * write() block. Incoming bytes are delivered to the IncomingListener
 * from the transport's own reader/callback threads.
 */
public interface PrinterTransport {

    interface IncomingListener {
        void onBytes(byte[] data, int len);
        void onDisconnected(String reason);
    }

    /** Short display name, e.g. "Classic SPP" or "BLE". */
    String name();

    /** Blocks until connected and ready, or fails. Returns true on success. */
    boolean connect(Context context, BluetoothDevice device, IncomingListener listener);

    void disconnect();

    boolean isConnected();

    /** Blocks until the payload has been handed to the printer (transport chunking inside). */
    void write(byte[] data) throws Exception;
}

/**
 * Response plumbing shared by both transports: routes incoming bytes
 * either to an unsolicited-error handler (2-byte FF <n> pushes) or to
 * the one currently waiting command response.
 */
final class ResponseDispatcher implements PrinterTransport.IncomingListener {

    interface UnsolicitedListener {
        void onUnsolicitedError(int code);
        void onDisconnected(String reason);
    }

    interface Matcher {
        boolean matches(byte[] data, int len);
    }

    private final Object lock = new Object();
    private ByteArrayOutputStream buffer;
    private Matcher matcher;
    private UnsolicitedListener unsolicitedListener;

    void setUnsolicitedListener(UnsolicitedListener l) {
        unsolicitedListener = l;
    }

    @Override
    public void onBytes(byte[] data, int len) {
        // Unsolicited error pushes are filtered first, like the vendor SDK.
        if (PrinterStatus.isUnsolicitedError(data, len)) {
            UnsolicitedListener l = unsolicitedListener;
            if (l != null) l.onUnsolicitedError(PrinterStatus.unsolicitedCode(data[1]));
            return;
        }
        synchronized (lock) {
            if (buffer != null) {
                buffer.write(data, 0, len);
                lock.notifyAll();
            }
        }
    }

    @Override
    public void onDisconnected(String reason) {
        synchronized (lock) {
            lock.notifyAll();
        }
        UnsolicitedListener l = unsolicitedListener;
        if (l != null) l.onDisconnected(reason);
    }

    /** Starts capturing a reply. Call before sending the command, so no reply byte is lost. */
    void begin(Matcher m) {
        synchronized (lock) {
            buffer = new ByteArrayOutputStream();
            matcher = m;
        }
    }

    /**
     * Waits until the matcher set in begin() accepts the accumulated
     * reply or the timeout passes. Returns the accumulated bytes, or
     * null on timeout when nothing at all arrived.
     */
    byte[] awaitDone(long timeoutMs) {
        synchronized (lock) {
            if (buffer == null) return null;
            long deadline = System.currentTimeMillis() + timeoutMs;
            try {
                while (true) {
                    byte[] cur = buffer.toByteArray();
                    if (cur.length > 0 && matcher != null && matcher.matches(cur, cur.length)) {
                        return cur;
                    }
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        return cur.length > 0 ? cur : null;
                    }
                    try {
                        lock.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return buffer.size() > 0 ? buffer.toByteArray() : null;
                    }
                }
            } finally {
                buffer = null;
                matcher = null;
            }
        }
    }

    /** Collects whatever arrives during a fixed window (for text replies with no terminator). */
    byte[] collect(long windowMs) {
        begin(new Matcher() {
            @Override public boolean matches(byte[] data, int len) { return false; }
        });
        return awaitDone(windowMs);
    }
}
