package app.oguri.printer;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * BLE transport, mirroring the vendor SDK. The GATT layout is found
 * by UUID prefix, not hardcoded: service 0000ff00..., the write
 * characteristic is the one in that service with
 * PROPERTY_WRITE_NO_RESPONSE (in practice ff02), responses notify on
 * ff01, and ff03 carries a credit flow-control channel:
 *   [01 n]       adds n credits
 *   [02 lo hi]   printer packet size = lo | (hi<<8); the client then
 *                requests MTU packet+3, capped so a packet is <= 512
 * Writes are credit-gated and serialised. The connection is only
 * "ready" after notifications are subscribed on ff01 and ff03 and
 * the first credit has arrived.
 */
public class BleTransport implements PrinterTransport {

    private static final UUID CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int DEFAULT_PACKET = 20;

    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic writeChar;
    private volatile BluetoothGattCharacteristic ff01Char;
    private volatile BluetoothGattCharacteristic ff03Char;
    private volatile boolean connected;
    private volatile boolean ready;
    private volatile boolean failed;
    private volatile IncomingListener listener;

    private CountDownLatch readyLatch;
    private volatile CountDownLatch writeLatch;
    private volatile int subStep; // 0 none, 1 = ff01 subscribing, 2 = ff03 subscribing, 3 = subscribed

    private final Object creditLock = new Object();
    private int credits;
    private volatile int packetSize = DEFAULT_PACKET;
    private final Object writeLock = new Object();

    @Override public String name() {
        return "BLE";
    }

    @Override
    public boolean connect(Context context, BluetoothDevice device, IncomingListener l) {
        disconnect();
        listener = l;
        connected = false;
        ready = false;
        failed = false;
        subStep = 0;
        packetSize = DEFAULT_PACKET;
        synchronized (creditLock) { credits = 0; }
        readyLatch = new CountDownLatch(1);
        BluetoothGatt g;
        try {
            g = device.connectGatt(context, false, callback);
        } catch (Exception e) {
            return false;
        }
        if (g == null) return false;
        gatt = g;
        try {
            // Vendor: 12 s connect latch, 10 s per subscription step,
            // then it still waits for the first credit. 35 s overall.
            readyLatch.await(35, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!ready) {
            disconnect();
            return false;
        }
        return true;
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                try { g.discoverServices(); } catch (Exception e) { fail(); }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean was = connected;
                connected = false;
                ready = false;
                if (readyLatch != null) readyLatch.countDown();
                CountDownLatch wl = writeLatch;
                if (wl != null) wl.countDown();
                if (was && listener != null) listener.onDisconnected("BLE connection lost");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { fail(); return; }
            BluetoothGattService service = null;
            List<BluetoothGattService> services = g.getServices();
            for (int i = 0; i < services.size(); i++) {
                if (uuidStartsWith(services.get(i).getUuid(), "0000ff00")) {
                    service = services.get(i);
                    break;
                }
            }
            if (service == null) { fail(); return; }
            BluetoothGattCharacteristic w = null, n1 = null, n3 = null;
            List<BluetoothGattCharacteristic> chars = service.getCharacteristics();
            for (int i = 0; i < chars.size(); i++) {
                BluetoothGattCharacteristic c = chars.get(i);
                if (w == null
                        && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    w = c;
                }
                if (uuidStartsWith(c.getUuid(), "0000ff01")) n1 = c;
                if (uuidStartsWith(c.getUuid(), "0000ff03")) n3 = c;
            }
            if (w == null || n1 == null || n3 == null) { fail(); return; }
            writeChar = w;
            ff01Char = n1;
            ff03Char = n3;
            subStep = 1;
            if (!enableNotification(g, n1)) { fail(); }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { fail(); return; }
            BluetoothGattCharacteristic c = descriptor.getCharacteristic();
            if (c == null) return;
            if (uuidStartsWith(c.getUuid(), "0000ff01") && subStep == 1) {
                subStep = 2;
                if (!enableNotification(g, ff03Char)) { fail(); }
            } else if (uuidStartsWith(c.getUuid(), "0000ff03") && subStep == 2) {
                subStep = 3;
                maybeReady();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            // Pre-API 33 callback shape.
            byte[] v = c.getValue();
            if (v != null) handleNotify(c, v);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            // API 33+ callback shape.
            if (value != null) handleNotify(c, value);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            CountDownLatch wl = writeLatch;
            if (wl != null) wl.countDown();
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 3) {
                packetSize = Math.min(mtu - 3, 512);
            }
        }
    };

    private boolean enableNotification(BluetoothGatt g, BluetoothGattCharacteristic c) {
        try {
            if (!g.setCharacteristicNotification(c, true)) return false;
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d == null) return false;
            if (Build.VERSION.SDK_INT >= 33) {
                return g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        == BluetoothStatusCodes.SUCCESS;
            }
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            return g.writeDescriptor(d);
        } catch (Exception e) {
            return false;
        }
    }

    private void handleNotify(BluetoothGattCharacteristic c, byte[] v) {
        if (ff03Char != null && c.getUuid().equals(ff03Char.getUuid())) {
            if (v.length >= 2 && (v[0] & 0xFF) == 0x01) {
                synchronized (creditLock) {
                    credits += v[1] & 0xFF;
                    creditLock.notifyAll();
                }
                maybeReady();
            } else if (v.length >= 3 && (v[0] & 0xFF) == 0x02) {
                int packet = (v[1] & 0xFF) | ((v[2] & 0xFF) << 8);
                if (packet > 0) {
                    BluetoothGatt g = gatt;
                    if (g != null) {
                        try { g.requestMtu(Math.min(packet + 3, 515)); } catch (Exception ignored) { }
                    }
                }
            }
        } else if (ff01Char != null && c.getUuid().equals(ff01Char.getUuid())) {
            IncomingListener l = listener;
            if (l != null) l.onBytes(v, v.length);
        }
    }

    private void maybeReady() {
        if (subStep == 3) {
            synchronized (creditLock) {
                if (credits > 0 && !ready) {
                    ready = true;
                    if (readyLatch != null) readyLatch.countDown();
                }
            }
        }
    }

    private void fail() {
        failed = true;
        if (readyLatch != null) readyLatch.countDown();
    }

    @Override
    public void write(byte[] data) throws Exception {
        if (!ready || gatt == null || writeChar == null) throw new Exception("Not connected");
        synchronized (writeLock) {
            sleep(10);
            int offset = 0;
            while (offset < data.length) {
                int avail = awaitCredits();
                int packetsLeft = (data.length - offset + packetSize - 1) / packetSize;
                int batch = Math.min(avail, packetsLeft);
                for (int i = 0; i < batch; i++) {
                    int n = Math.min(packetSize, data.length - offset);
                    byte[] pkt = new byte[n];
                    System.arraycopy(data, offset, pkt, 0, n);
                    writeLatch = new CountDownLatch(1);
                    writeOnePacket(pkt);
                    boolean ok;
                    try {
                        ok = writeLatch.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new Exception("Interrupted while writing");
                    }
                    if (!ok) throw new Exception("BLE write timed out");
                    synchronized (creditLock) { credits--; }
                    offset += n;
                }
            }
        }
    }

    /** Computes how many packets may be sent now, waiting up to 30 s for a credit. */
    private int awaitCredits() throws Exception {
        long deadline = System.currentTimeMillis() + 30000;
        synchronized (creditLock) {
            while (credits <= 0) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) throw new Exception("Timed out waiting for printer credits");
                try {
                    creditLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Exception("Interrupted while waiting for credits");
                }
            }
            // Vendor behaviour: with 3 or more credits, one is held back.
            return credits >= 3 ? credits - 1 : credits;
        }
    }

    private void writeOnePacket(byte[] pkt) throws Exception {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic c = writeChar;
        if (g == null || c == null) throw new Exception("Not connected");
        if (Build.VERSION.SDK_INT >= 33) {
            int rc = g.writeCharacteristic(c, pkt,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            if (rc != BluetoothStatusCodes.SUCCESS) throw new Exception("BLE write rejected");
        } else {
            c.setValue(pkt);
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            if (!g.writeCharacteristic(c)) throw new Exception("BLE write rejected");
        }
    }

    @Override
    public void disconnect() {
        ready = false;
        connected = false;
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try { g.disconnect(); } catch (Exception ignored) { }
            try { g.close(); } catch (Exception ignored) { }
        }
        writeChar = null;
        ff01Char = null;
        ff03Char = null;
        if (readyLatch != null) readyLatch.countDown();
        CountDownLatch wl = writeLatch;
        if (wl != null) wl.countDown();
        synchronized (creditLock) {
            credits = 0;
            creditLock.notifyAll();
        }
    }

    @Override public boolean isConnected() {
        return ready;
    }

    private static boolean uuidStartsWith(UUID uuid, String prefix) {
        return uuid != null && uuid.toString().startsWith(prefix);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
