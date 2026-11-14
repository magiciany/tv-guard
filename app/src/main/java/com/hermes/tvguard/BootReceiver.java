package com.hermes.tvguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启：恢复无障碍开关并重新安排看门狗。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        if (!Prefs.getEnabled(context)) {
            Log.i(GuardService.TAG, "boot: guard disabled by user, skip");
            return;
        }
        boolean ok = GuardService.isAccessibilitySettingOn(context)
                || GuardService.setAccessibilitySetting(context, true);
        Watchdog.schedule(context);
        Log.i(GuardService.TAG, "boot: restored " + ok);
    }
}
