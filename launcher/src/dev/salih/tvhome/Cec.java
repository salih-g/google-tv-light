package dev.salih.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which HDMI-CEC devices are plugged in and whether they are on. Android only gives this to system apps
 * (HdmiControlManager), and the public input state can't tell: this TCL reports an empty port and a device in
 * standby alike (CONNECTED_STANDBY) and a CEC device as CONNECTED even while it sleeps. The TV's own CEC service
 * knows, so the device list is read from `dumpsys hdmi_control` through the TV's own ADB (Adb, already allowed by
 * the user for Home settings), read-only, when the Inputs menu opens. Without ADB the menu shows "Connected" without on/off.
 */
final class Cec {
    static final class Device {
        final String name; final int power, port;
        Device(String name, int power, int port) { this.name = name; this.power = power; this.port = port; }
        /** CEC power status: 0 on, 1 standby, 2 turning on, 3 turning off. */
        boolean on() { return power == 0 || power == 2; }
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // "CEC: logical_address: 0x04 device_type: 4 vendor_id: 524358 display_name: PlayStation 5 power_status: 1
    //  physical_address: 0x1000 port_id: 1"
    private static final Pattern LINE = Pattern.compile(
            "CEC: logical_address: 0x([0-9a-fA-F]+) device_type: (\\d+) .*?display_name: (.*?) power_status: (-?\\d+) .*?port_id: (-?\\d+)");
    private static volatile Map<String, Device> devices = new HashMap<>();   // by display name, last known

    private Cec() { }

    static Device byName(String name) { return name == null ? null : devices.get(name.trim()); }

    /** The one known device, when there is exactly one (to pair it with the one CEC input whatever its name). */
    static Device only() {
        Map<String, Device> d = devices;
        return d.size() == 1 ? d.values().iterator().next() : null;
    }

    /** Re-reads the device list in the background, then runs `done` on the main thread if anything changed. */
    static void refresh(Context context, Runnable done) {
        Context c = context.getApplicationContext();
        if (!Adb.trusted(c)) return;   // never raise the debugging prompt from a menu
        IO.execute(() -> {
            Map<String, Device> found = new HashMap<>();
            try (Adb adb = Adb.connect(c, 2000, null)) {
                String out = adb.shell("dumpsys hdmi_control | grep 'CEC: logical_address'");
                Matcher m = LINE.matcher(out);
                while (m.find()) {
                    if (Integer.parseInt(m.group(2)) == 0) continue;   // device type 0 is the TV itself
                    found.put(m.group(3).trim(), new Device(m.group(3).trim(), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5))));
                }
            } catch (Exception e) {
                return;   // ADB not allowed or off: keep the last known list
            }
            boolean changed = !sameAs(found);
            devices = found;
            if (changed && done != null) MAIN.post(done);
        });
    }

    private static boolean sameAs(Map<String, Device> found) {
        Map<String, Device> old = devices;
        if (old.size() != found.size()) return false;
        for (Device d : found.values()) {
            Device o = old.get(d.name);
            if (o == null || o.power != d.power || o.port != d.port) return false;
        }
        return true;
    }
}
