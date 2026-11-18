package com.hermes.tvguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 看门狗：服务被关或被系统杀掉时，把无障碍开关写回去并强制重新绑定。
 * 需要 WRITE_SECURE_SETTINGS（用 adb 授予），没有权限时只记录日志。
 */
public class WatchdogReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.getEnabled(context)) {
            Log.i(GuardService.TAG, "watchdog: guard disabled by user, skip");
            return;
        }
        boolean settingOn = GuardService.isAccessibilitySettingOn(context);
        boolean alive = GuardService.INSTANCE != null;

        if (!settingOn) {
            boolean ok = GuardService.setAccessibilitySetting(context, true);
            Log.i(GuardService.TAG, "watchdog: accessibility was OFF -> restore " + (ok ? "ok" : "no permission"));
            return;
        }
        if (!alive) {
            // Toggle only our component; never clear unrelated services.
            boolean removed = GuardService.setAccessibilitySetting(context, false);
            // Restore even after a partial failed removal (putString may have succeeded).
            boolean restored = GuardService.setAccessibilitySetting(context, true);
            Log.i(GuardService.TAG, "watchdog: service dead -> rebind remove=" + removed
                    + ", restore=" + restored);
        }
    }
}
