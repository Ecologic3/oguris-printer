package app.oguri.printer;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, Android-free device-identity logic for the picker.
 *
 * One physical PPS1 unit advertises two Bluetooth identities: a
 * Classic one (name "PPS1_9F8E", MAC 02:0A:1B:2C:3D:4E) and a BLE one
 * (name "PPS1_9F8E_BLE", MAC 03:0A:1B:2C:3D:4E). The two are the same
 * unit when their names match after stripping a trailing "_BLE" AND
 * their MACs are equal ignoring the first octet. Sightings of either
 * identity merge into one Device that keeps both addresses: SPP
 * connects to the Classic address, BLE to the BLE address.
 */
public final class PrinterDevices {

    public static final String TAG_BONDED = "Bonded";
    public static final String TAG_CLASSIC = "Classic";
    public static final String TAG_BLE = "BLE";
    public static final String TAG_LAST_USED = "Last used";

    private PrinterDevices() { }

    /** "PPS1_9F8E_BLE" -> "PPS1_9F8E"; any other name is unchanged. */
    public static String canonicalName(String name) {
        if (name == null) return null;
        if (name.endsWith("_BLE")) return name.substring(0, name.length() - 4);
        return name;
    }

    public static boolean isBleName(String name) {
        return name != null && name.endsWith("_BLE");
    }

    /** MAC equality ignoring the first octet (everything after "XX:"). */
    public static boolean macEqualIgnoringFirstOctet(String a, String b) {
        if (a == null || b == null) return false;
        int ia = a.indexOf(':');
        int ib = b.indexOf(':');
        if (ia < 0 || ib < 0) return a.equalsIgnoreCase(b);
        return a.substring(ia).equalsIgnoreCase(b.substring(ib));
    }

    /** The merge predicate: same canonical name AND MACs equal ignoring the first octet. */
    public static boolean sameUnit(String nameA, String macA, String nameB, String macB) {
        if (nameA == null || nameB == null || macA == null || macB == null) return false;
        String ca = canonicalName(nameA);
        String cb = canonicalName(nameB);
        if (ca == null || !ca.equals(cb)) return false;
        return macEqualIgnoringFirstOctet(macA, macB);
    }

    /** SPP first whenever a Classic address exists, otherwise BLE first. */
    public static boolean preferSppFirst(boolean hasClassicAddress, boolean hasBleAddress) {
        return hasClassicAddress;
    }

    /** One picker entry: a physical unit with up to two addresses. */
    public static final class Device {
        public String name;          // canonical, never carries the _BLE suffix
        public String classicMac;    // address SPP connects to, or null
        public String bleMac;        // address BLE connects to, or null
        public final List<String> tags = new ArrayList<String>();

        public Device(String name) {
            this.name = canonicalName(name);
        }

        public boolean hasTag(String tag) {
            return tags.contains(tag);
        }

        /** Records a sighting of this unit under one of its identities. */
        public void addSighting(String sightName, String mac, String tag) {
            if (mac == null) return;
            String canon = canonicalName(sightName);
            if (canon != null && canon.length() > 0) name = canon;
            if (isBleName(sightName) || TAG_BLE.equals(tag)) {
                bleMac = mac;
            } else {
                classicMac = mac;
            }
            if (tag != null && tag.length() > 0 && !tags.contains(tag)) tags.add(tag);
        }

        /** True when this sighting belongs to this unit (exact MAC hit or the merge rule). */
        public boolean matches(String sightName, String mac) {
            if (mac == null) return false;
            if (mac.equalsIgnoreCase(classicMac == null ? "" : classicMac)
                    || mac.equalsIgnoreCase(bleMac == null ? "" : bleMac)) {
                return true;
            }
            if (classicMac != null && sameUnit(sightName, mac, name, classicMac)) return true;
            if (bleMac != null && sameUnit(sightName, mac, name, bleMac)) return true;
            return false;
        }

        /** The address shown in the list and stored as the primary last-used key. */
        public String primaryMac() {
            return classicMac != null ? classicMac : bleMac;
        }

        public boolean preferSppFirst() {
            return PrinterDevices.preferSppFirst(classicMac != null, bleMac != null);
        }

        /** Found-via tags for display, e.g. "Bonded · Classic · BLE". */
        public String viaDisplay() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tags.size(); i++) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(tags.get(i));
            }
            return sb.toString();
        }
    }

    /** Index of the device this sighting belongs to, or -1. */
    public static int findMatch(List<Device> devices, String name, String mac) {
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).matches(name, mac)) return i;
        }
        return -1;
    }
}
