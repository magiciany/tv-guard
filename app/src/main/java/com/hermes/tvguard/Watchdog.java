package com.hermes.tvguard;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * 看门狗闹钟：每 60 秒唤醒一次，让 WatchdogReceiver 检查服务是否还活着。
 * 被系统杀进程后闹钟仍在，可以自己把自己救回来。
 */
public final class Watchdog {

    /** 检查周期（毫秒）。 */
    private static final long PERIOD = 60000L;

    private Watchdog() {
    }

    private static PendingIntent pi(Context c) {
        Intent i = new Intent(c, WatchdogReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, 7, i, flags);
    }

    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        try {
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + PERIOD, PERIOD, pi(c));
        } catch (Exception e) {
            android.util.Log.w(GuardService.TAG, "watchdog schedule failed", e);
        }
    }

    public static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        try {
            am.cancel(pi(c));
        } catch (Exception ignored) {
        }
    }
}
