package dev.salih.tvhome;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Safety net (idea from Free My TV's Watchdog): each crash of this app's main process (Home, keys, screensaver) is
 * recorded; on the 3rd within 5 minutes a job in a separate process gives Home and the screensaver back to Google
 * (HomeSwitch.rescue), so a broken build can never leave the TV without a working Home. Undo in Home settings.
 */
final class Watchdog {
    private static final long WINDOW = 5 * 60_000L;
    private static final int LIMIT = 3;
    private static final int JOB_ID = 4062;
    private static boolean installed;

    private Watchdog() { }

    static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        Context app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                if (record(app, System.currentTimeMillis()) >= LIMIT) {
                    JobScheduler js = app.getSystemService(JobScheduler.class);
                    js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(app, Rescue.class))
                            .setOverrideDeadline(0).build());
                }
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
    }

    private static int record(Context c, long now) throws IOException {
        List<Long> list = recent(c, now);
        list.add(now);
        StringBuilder sb = new StringBuilder();
        for (long at : list) sb.append(at).append('\n');
        try (FileOutputStream out = new FileOutputStream(file(c))) { out.write(sb.toString().getBytes(StandardCharsets.UTF_8)); }
        return list.size();
    }

    private static List<Long> recent(Context c, long now) {
        List<Long> list = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return list;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) Math.min(f.length(), 4096)];
            int read = in.read(data);
            if (read <= 0) return list;
            for (String line : new String(data, 0, read, StandardCharsets.UTF_8).split("\n")) {
                try { long at = Long.parseLong(line.trim()); if (at <= now && now - at < WINDOW) list.add(at); }
                catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) { }
        return list;
    }

    private static File file(Context c) { return new File(c.getFilesDir(), "home-crashes.txt"); }

    /** Runs in the ":rescue" process, so the crashing one can't take it down. */
    public static final class Rescue extends JobService {
        @Override public boolean onStartJob(JobParameters params) {
            new Thread(() -> {
                try { HomeSwitch.rescue(this); } catch (IOException ignored) { /* ADB off or prompt never accepted */ }
                jobFinished(params, false);
            }, "rescue").start();
            return true;
        }
        @Override public boolean onStopJob(JobParameters params) { return false; }
    }
}
