package com.hermes.tvguard;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.FileReader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 无障碍服务：判定目标 App 是否在前台，并按间隔弹出验证界面。
 * 只用 packageName 判断前后台，不读取任何窗口内容。
 *
 * 常驻策略：前台服务常驻通知 + AlarmManager 看门狗（每 60 秒自检）+ 开机自启。
 * 若已通过 adb 授予 WRITE_SECURE_SETTINGS，被关掉的无障碍开关会被自动写回。
 */
public class GuardService extends AccessibilityService {

    public static final String TAG = "TvGuard";
    /** 本服务在系统设置里的组件名，用于自检和自愈。 */
    public static final String COMPONENT =
            "com.hermes.tvguard/com.hermes.tvguard.GuardService";
    private static final String CHANNEL_ID = "guard_foreground";
    private static final int NOTIF_ID = 1001;

    public static volatile GuardService INSTANCE;
    private static final long CHECKPOINT_MS = 15000L;
    private long lastCheckpoint;
    private boolean connected;
    private String bootId = "";
    /** 答题界面当前是否可见（由 ChallengeActivity 维护），避免重复拉起。 */
    public static volatile boolean challengeVisible = false;

    public static final int STATE_MONITORING = 0;
    public static final int STATE_CHALLENGE = 1;
    public static final int STATE_LOCKED = 2;

    private int state = STATE_MONITORING;
    private boolean targetForeground = false;
    /** 累计前台毫秒数：离开时累加保存，答对才清零（防止退出重进绕过倒计时）。 */
    private long accumulatedMs = 0L;
    /** 本次进入前台的起始时刻；0 表示当前不在前台。 */
    private long fgSince = 0L;
    private long lockedUntil = 0L;
    private String targetPkg = Prefs.DEFAULT_TARGET;
    /** 最近一次非忽略的前台包名（自检用）。 */
    private String lastPkg = "";
    /** 检测到“别的 App 在前台”的起始时刻，0 表示没有候选离开。 */
    private long otherSince = 0L;
    /** 上一次拉起答题界面的时刻，用于节流。 */
    private long lastLaunchAt = 0L;
    /** 上次答错时的正确答案，锁定界面展示用。 */
    private String lastReveal = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (INSTANCE != GuardService.this || !Prefs.getEnabled(GuardService.this)) return;
            onTick();
            if (INSTANCE == GuardService.this) handler.postDelayed(this, 500L);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler.removeCallbacks(tick);
        GuardService previous = INSTANCE;
        if (previous != null && previous != this) {
            previous.parkAccumulated();
            previous.persist();
            previous.handler.removeCallbacks(previous.tick);
        }
        INSTANCE = this;
        if (!Prefs.getEnabled(this)) {
            // 用户用密码退出过：不要继续守着
            Log.i(TAG, "service connected but guard disabled by user -> self disable");
            try {
                disableSelf();
            } catch (Exception ignored) {
            }
            INSTANCE = null;
            return;
        }
        if (!connected) {
            bootId = readBootId();
            restore();
            connected = true;
        }
        reload();
        persist();
        startForegroundNotify();
        Watchdog.schedule(this);
        // A bound service may not receive another window event during uninterrupted playback.
        onTick();
        handler.postDelayed(tick, 500L);
        Log.i(TAG, "service connected, target=" + targetPkg);
    }

    /** 常驻通知：把服务提到前台，降低被系统回收的概率。 */
    private void startForegroundNotify() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        "守护答题", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                if (nm != null) {
                    nm.createNotificationChannel(ch);
                }
                b = new Notification.Builder(this, CHANNEL_ID);
            } else {
                b = new Notification.Builder(this);
            }
            b.setContentTitle("守护答题运行中")
                    .setContentText("目标：" + Prefs.getTarget(this))
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_MIN);
            startForeground(NOTIF_ID, b.build());
            Log.i(TAG, "foreground notification started");
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed", e);
        }
    }

    /** 系统设置里本无障碍服务是否处于开启状态。 */
    public static boolean isAccessibilitySettingOn(Context c) {
        try {
            int en = Settings.Secure.getInt(c.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            String v = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return en == 1 && GuardState.containsComponent(v, COMPONENT);
        } catch (Exception e) {
            return false;
        }
    }

    /** 写入无障碍开关；需要 WRITE_SECURE_SETTINGS（adb 授予）。 */
    public static boolean setAccessibilitySetting(Context c, boolean on) {
        try {
            String old = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String updated = GuardState.withComponent(old, COMPONENT, on);
            if (!Settings.Secure.putString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)) return false;
            return Settings.Secure.putInt(c.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, updated.isEmpty() ? 0 : 1);
        } catch (Exception e) {
            Log.w(TAG, "write secure settings failed (need WRITE_SECURE_SETTINGS): " + e);
            return false;
        }
    }

    /** Explicit stop pauses but preserves accrued time/challenge/lock for restart. */
    public static void stopGuard(Context c) {
        Prefs.setEnabled(c, false);
        if (!c.getSharedPreferences("guard", Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", false).commit()) Log.w(TAG, "persist disabled failed");
        Watchdog.cancel(c);
        GuardService s = INSTANCE;
        if (s != null) {
            s.pauseForSettings();
            s.handler.removeCallbacks(s.tick);
            INSTANCE = null;
            try {
                s.stopForeground(true);
            } catch (Exception ignored) {
            }
            try {
                s.disableSelf();
            } catch (Exception e) {
                Log.w(TAG, "disableSelf failed", e);
            }
        }
        setAccessibilitySetting(c, false);
        Log.i(TAG, "guard stopped by user");
    }

    /** 重新启用守护。 */
    public static void startGuard(Context c) {
        Prefs.setEnabled(c, true);
        boolean ok = isAccessibilitySettingOn(c) || setAccessibilitySetting(c, true);
        Watchdog.schedule(c);
        Log.i(TAG, "guard enabled, accessibility=" + ok);
    }

    private void reload() {
        String target = Prefs.getTarget(this);
        if (target.equals(targetPkg)) return;
        targetPkg = target;
        targetForeground = false;
        accumulatedMs = fgSince = lockedUntil = otherSince = lastLaunchAt = 0L;
        state = STATE_MONITORING;
        lastPkg = lastReveal = "";
        persist();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (INSTANCE != this || !Prefs.getEnabled(this) || event == null) {
            return;
        }
        reload();
        // Content events may come from a covered/background app.
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cs = event.getPackageName();
        if (cs == null) {
            return;
        }
        String pkg = cs.toString();
        if (pkg.isEmpty() || pkg.equals(getPackageName())) {
            return;
        }
        if (pkg.equals("com.android.systemui") || pkg.equals("android")) {
            return;
        }
        if (pkg.contains("inputmethod") || pkg.contains("keyboard")) {
            return;
        }
        if (pkg.contains("permissioncontroller") || pkg.contains("packageinstaller")) {
            return;
        }
        lastPkg = pkg;

        if (pkg.equals(targetPkg)) {
            if (!targetForeground) {
                targetForeground = true;
                if (fgSince == 0L) {
                    fgSince = SystemClock.elapsedRealtime();
                }
                Log.i(TAG, "target foreground, accumulated=" + (accumulatedMs / 1000) + "s");
                persist();
            }
            otherSince = 0L;
            if (state != STATE_MONITORING) {
                showChallenge();
            }
        } else if (targetForeground && otherSince == 0L) {
            // 先记录候选离开时刻，连续 3 秒才确认（过滤瞬时弹窗/系统浮层）
            otherSince = SystemClock.elapsedRealtime();
            Log.i(TAG, "candidate left -> " + pkg);
        }
    }

    /** 离开前台时把已看时长累加保存，不清零。 */
    private void parkAccumulated() {
        long now = SystemClock.elapsedRealtime();
        if (fgSince > 0L) {
            accumulatedMs += now - fgSince;
            fgSince = 0L;
        }
    }

    /** Fresh package-only observation recovers a quiet target after rebinding.
     * No text, child nodes or other screen content are inspected.
     */
    private void reconcileActiveTarget() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            CharSequence pkg = root == null ? null : root.getPackageName();
            if (pkg != null && targetPkg.contentEquals(pkg)) {
                targetForeground = true;
                fgSince = SystemClock.elapsedRealtime();
                otherSince = 0L;
                lastPkg = targetPkg;
                persist();
            }
        } catch (Exception e) {
            Log.w(TAG, "active-window package unavailable", e);
        } finally {
            if (root != null) root.recycle();
        }
    }

    private void onTick() {
        if (INSTANCE != this || !Prefs.getEnabled(this)) return;
        reload();
        if (!targetForeground) reconcileActiveTarget();
        long now = SystemClock.elapsedRealtime();

        // 连续 3 秒都是别的 App 才算真正离开
        if (targetForeground && otherSince > 0L && now - otherSince >= 3000L) {
            parkAccumulated();
            targetForeground = false;
            otherSince = 0L;
            persist();
            Log.i(TAG, "target confirmed left");
        }
        if (state == STATE_LOCKED && now >= lockedUntil) {
            state = STATE_CHALLENGE;
            lockedUntil = 0L;
            persist();
        }
        persistIfDue();
        if (!targetForeground) {
            return;
        }

        if (state == STATE_LOCKED) {
            if (now >= lockedUntil) {
                state = STATE_CHALLENGE;
            }
            showChallenge();
            return;
        }
        if (state == STATE_CHALLENGE) {
            showChallenge();
            return;
        }

        long watched = accumulatedMs + (fgSince > 0L ? now - fgSince : 0L);
        if (watched >= Prefs.getIntervalMin(this) * 60000L) {
            state = STATE_CHALLENGE;
            persist();
            Prefs.addChallenge(this);
            Log.i(TAG, "challenge fired after " + (watched / 1000) + "s");
            showChallenge();
        }
    }

    private void showChallenge() {
        if (INSTANCE != this || !Prefs.getEnabled(this)) return;
        // 已经有答题界面在显示，就不要重复拉起（否则会不停替换窗口、点击丢失）
        if (challengeVisible) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        // 节流：两次拉起至少间隔 1200ms，避免反复 startActivity 造成闪烁
        if (now - lastLaunchAt < 1200L) {
            return;
        }
        lastLaunchAt = now;
        Intent i = new Intent(this, ChallengeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "start challenge failed", e);
        }
    }

    public boolean isTargetForeground() {
        return targetForeground;
    }

    /** 已累计的前台观看时长（秒），用于设置页自检。 */
    public long getAccumulatedSec() {
        long now = SystemClock.elapsedRealtime();
        long ms = accumulatedMs + (fgSince > 0L ? now - fgSince : 0L);
        return ms / 1000L;
    }

    /** 最近一次非忽略的前台包名，用于设置页自检。 */
    public String getForegroundPkg() {
        return lastPkg;
    }

    public int getState() {
        return state;
    }

    public long getLockedUntil() {
        return lockedUntil;
    }

    public void onAnsweredCorrect() {
        if (!acceptAnswer()) return;
        state = STATE_MONITORING;
        lockedUntil = 0L;
        accumulatedMs = 0L;
        fgSince = targetForeground ? SystemClock.elapsedRealtime() : 0L;
        lastReveal = "";
        persist();
        Prefs.addCorrect(this);
        Log.i(TAG, "correct, timer reset");
    }

    public void onAnsweredWrong(String reveal) {
        if (!acceptAnswer()) return;
        state = STATE_LOCKED;
        lockedUntil = SystemClock.elapsedRealtime() + Prefs.getLockMin(this) * 60000L;
        this.lastReveal = reveal == null ? "" : reveal;
        persist();
        Prefs.addWrong(this);
        Log.i(TAG, "wrong, locked " + Prefs.getLockMin(this) + " min, answer=" + this.lastReveal);
    }

    /** 上一次答错时的正确答案，供锁定界面展示。 */
    public String getLastReveal() {
        return lastReveal;
    }

    public void unlockNow() {
        if (INSTANCE == this && state == STATE_LOCKED
                && SystemClock.elapsedRealtime() >= lockedUntil) {
            state = STATE_CHALLENGE;
            lockedUntil = 0L;
            persist();
        }
    }

    public void forceChallenge() {
        if (INSTANCE != this || !Prefs.getEnabled(this)) return;
        reload();
        if (state == STATE_LOCKED) return;
        state = STATE_CHALLENGE;
        persist();
        showChallenge();
    }

    /** MainActivity.onResume must call this before any settings/PIN/picker flow. */
    public void pauseForSettings() {
        if (INSTANCE != this) return;
        reload();
        parkAccumulated();
        targetForeground = false;
        otherSince = 0L;
        lastPkg = getPackageName();
        persist();
        // Only a fresh target window event or active-root package observation resumes accounting.
    }

    private boolean acceptAnswer() {
        if (INSTANCE != this || !Prefs.getEnabled(this)) return false;
        reload();
        return state == STATE_CHALLENGE;
    }

    private SharedPreferences statePreferences() {
        return getSharedPreferences("guard_service_state", Context.MODE_PRIVATE);
    }

    private String readBootId() {
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/proc/sys/kernel/random/boot_id"))) {
            String value = reader.readLine();
            if (value != null && !value.isEmpty()) return "kernel:" + value;
        } catch (Exception ignored) { }
        try {
            String count = Settings.Global.getString(getContentResolver(), "boot_count");
            if (count != null && !count.isEmpty()) return "count:" + count;
        } catch (Exception ignored) { }
        // Unknown boot is handled as reboot, never as a trusted elapsed deadline.
        return "";
    }

    private void restore() {
        SharedPreferences p = statePreferences();
        targetPkg = p.getString("target", Prefs.getTarget(this));
        long now = SystemClock.elapsedRealtime();
        long savedAt = p.getLong("savedAt", now);
        boolean same = GuardState.sameBoot(p.getString("boot", ""), bootId, savedAt, now);
        accumulatedMs = GuardState.restoredWatched(p.getLong("watched", 0L),
                p.getBoolean("running", false), savedAt, same, now);
        state = p.getInt("state", STATE_MONITORING);
        if (state < STATE_MONITORING || state > STATE_LOCKED) state = STATE_CHALLENGE;
        lastReveal = p.getString("reveal", "");
        if (state == STATE_LOCKED) {
            long remaining = GuardState.remainingLock(p.getLong("deadline", 0L),
                    p.getLong("remaining", 0L), same, now);
            lockedUntil = now + remaining;
            if (remaining == 0L) state = STATE_CHALLENGE;
        }
        // Foreground itself is never restored: a fresh event/root package must establish it.
        targetForeground = false;
        fgSince = otherSince = 0L;
    }

    private void persistIfDue() {
        if (targetForeground && SystemClock.elapsedRealtime() - lastCheckpoint >= CHECKPOINT_MS) {
            persist();
        }
    }

    private void persist() {
        if (INSTANCE != this) return;
        long now = SystemClock.elapsedRealtime();
        long watched = accumulatedMs + (fgSince > 0L ? Math.max(0L, now - fgSince) : 0L);
        // Commit critical transitions before launching UI or releasing ownership.
        // Active viewing checkpoints are at most once per 15 seconds, not every tick.
        boolean ok = statePreferences().edit().putString("target", targetPkg)
                .putString("boot", bootId).putLong("savedAt", now)
                .putLong("watched", watched).putBoolean("running", fgSince > 0L)
                .putInt("state", state).putLong("deadline", lockedUntil)
                .putLong("remaining", Math.max(0L, lockedUntil - now))
                .putString("reveal", lastReveal).commit();
        if (ok) lastCheckpoint = now;
        else Log.w(TAG, "service state checkpoint failed");
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "service interrupted");
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (INSTANCE == this) {
            parkAccumulated();
            persist();
            INSTANCE = null;
        }
        super.onDestroy();
    }
}
