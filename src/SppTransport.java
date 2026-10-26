package app.oguri.printer;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Classic Bluetooth SPP transport, mirroring the vendor SDK:
 * standard SPP UUID, secure socket first with an insecure fallback,
 * no createBond call, writes chunked at 16384 bytes with a 10 ms
 * pre-write sleep and a flush per chunk.
 */
public class SppTransport implements PrinterTransport {

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int CHUNK = 16384;

    private BluetoothSocket socket;
    private OutputStream out;
    private volatile boolean connected;
    private Thread readerThread;

    @Override public String name() {
        return "Classic SPP";
    }

    @Override
    public boolean connect(Context context, BluetoothDevice device,
                           final IncomingListener listener) {
        disconnect();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            try { adapter.cancelDiscovery(); } catch (Exception ignored) { }
        }
        sleep(150);
        BluetoothSocket s = null;
        try {
            s = device.createRfcommSocketToServiceRecord(SPP_UUID);
            s.connect();
        } catch (Exception e) {
            closeQuietly(s);
            s = null;
            sleep(150);
            try {
                s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                s.connect();
            } catch (Exception e2) {
                closeQuietly(s);
                return false;
            }
        }
        try {
            socket = s;
            out = s.getOutputStream();
            final InputStream in = s.getInputStream();
            connected = true;
            readerThread = new Thread(new Runnable() {
                @Override public void run() {
                    byte[] buf = new byte[1024];
                    try {
                        int n;
                        while (connected && (n = in.read(buf)) >= 0) {
                            if (n > 0) {
                                byte[] copy = new byte[n];
                                System.arraycopy(buf, 0, copy, 0, n);
                                listener.onBytes(copy, n);
                            }
                        }
                    } catch (Exception e) {
                        if (connected) {
                            connected = false;
                            listener.onDisconnected("Classic connection lost");
                        }
                    }
                }
            }, "spp-reader");
            readerThread.start();
            return true;
        } catch (Exception e) {
            closeQuietly(s);
            socket = null;
            return false;
        }
    }

    @Override
    public synchronized void write(byte[] data) throws Exception {
        if (!connected || out == null) throw new Exception("Not connected");
        sleep(10);
        int off = 0;
        while (off < data.length) {
            int n = Math.min(CHUNK, data.length - off);
            out.write(data, off, n);
            out.flush();
            off += n;
        }
    }

    @Override
    public void disconnect() {
        connected = false;
        closeQuietly(socket);
        socket = null;
        out = null;
    }

    @Override public boolean isConnected() {
        return connected && socket != null;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s != null) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }
}
