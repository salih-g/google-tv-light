package dev.salih.tvhome;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * How often each app was opened, for the "most used first" dock order. Android's usage history counts every app,
 * however it was opened (dock, remote button, Assistant, another app), which launches counted by Home would miss.
 * Reading it needs the GET_USAGE_STATS app-op, set once over the TV's own ADB when the option is turned on.
 */
final class Usage {
    static final String PREF = "dock_by_use";
    private static final long WINDOW = 30L * 24 * 60 * 60_000;

    private Usage() { }

    static boolean allowed(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        return ops != null && ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    /** Sets the app-op through the TV's ADB. Blocking: call off the main thread. */
    static void allow(Context c, HomeSwitch.Waiting w) throws IOException {
        try (Adb adb = Adb.connect(c, 60_000, w == null ? null : w::waiting)) {
            adb.shell("appops set " + c.getPackageName() + " GET_USAGE_STATS allow");
        }
        if (!allowed(c)) throw new IOException("usage access wasn't granted");
    }

    /**
     * Opens per package over the last 30 days: an app coming to the front after a different one counts once, so
     * moving between an app's own screens doesn't. Home itself isn't counted. Empty without access.
     */
    static Map<String, Integer> opens(Context c) {
        Map<String, Integer> count = new HashMap<>();
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        if (usm == null || !allowed(c)) return count;
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - WINDOW, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String last = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED) continue;
            String pkg = e.getPackageName();
            if (pkg.equals(last)) continue;
            last = pkg;
            if (!pkg.equals(c.getPackageName())) count.merge(pkg, 1, Integer::sum);
        }
        return count;
    }
}
