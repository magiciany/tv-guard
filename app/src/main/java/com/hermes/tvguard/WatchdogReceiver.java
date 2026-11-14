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
            // 设置是开的但服务没活：清空再写回，逼系统重新绑定
            GuardService.setAccessibilitySetting(context, false);
            GuardService.setAccessibilitySetting(context, true);
            Log.i(GuardService.TAG, "watchdog: service dead -> force rebind");
        }
    }
}
