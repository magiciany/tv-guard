package com.hermes.tvguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;

/** 设置主界面（遥控器可操作）。 */
public class MainActivity extends Activity {

    private static final int[] INTERVALS = {1, 5, 10, 15, 20, 30};
    private static final int[] LOCKS = {3, 5, 10, 15, 30};
    private static final String[] DIFF_LABEL = {"简单（两位数加法）", "中等（乘加混合）", "较难（带括号）"};
    private static final String[] TYPE_LABEL = {"混合（口算 + 四级词汇）", "只考口算", "只考四级词汇"};

    private Button btnTarget;
    private Button btnType;
    private Button btnInterval;
    private Button btnLock;
    private Button btnDiff;
    private Button btnAccess;
    private Button btnOverlay;
    private Button btnStats;
    private Button btnPin;
    private Button btnPower;
    private Button btnTest;
    private android.widget.TextView tvDiag;

    private static final int REQ_ACTION = 11;
    private static final int REQ_PIN = 12;
    private enum Action { TYPE, INTERVAL, LOCK, DIFFICULTY, ACCESSIBILITY, OVERLAY, POWER, CLEAR_STATS }
    // Deliberately not saved: process recreation/relaunch must never restore authorization.
    private Action pendingAction;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable diagTick = new Runnable() {
        @Override
        public void run() {
            updateDiag();
            ui.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnTarget = findViewById(R.id.btn_target);
        btnType = findViewById(R.id.btn_type);
        btnInterval = findViewById(R.id.btn_interval);
        btnLock = findViewById(R.id.btn_lock);
        btnDiff = findViewById(R.id.btn_diff);
        btnAccess = findViewById(R.id.btn_access);
        btnOverlay = findViewById(R.id.btn_overlay);
        btnStats = findViewById(R.id.btn_stats);
        btnPin = findViewById(R.id.btn_pin);
        btnPower = findViewById(R.id.btn_power);
        btnTest = findViewById(R.id.btn_test);
        tvDiag = findViewById(R.id.tv_diag);

        // The picker owns its own short-lived authentication; no transferable auth extra.
        btnTarget.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, AppPickerActivity.class)));
        btnType.setOnClickListener(v -> requireParent(Action.TYPE));
        btnInterval.setOnClickListener(v -> requireParent(Action.INTERVAL));
        btnLock.setOnClickListener(v -> requireParent(Action.LOCK));
        btnDiff.setOnClickListener(v -> requireParent(Action.DIFFICULTY));
        btnAccess.setOnClickListener(v -> requireParent(Action.ACCESSIBILITY));
        btnOverlay.setOnClickListener(v -> requireParent(Action.OVERLAY));
        btnStats.setOnClickListener(v -> showStats());
        btnTest.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, TestChallengeActivity.class)));
        btnPin.setOnClickListener(v -> {
            int mode = PinStore.hasPin(this) ? PinActivity.MODE_CHANGE : PinActivity.MODE_SET;
            startActivityForResult(new Intent(this, PinActivity.class)
                    .putExtra(PinActivity.EXTRA_MODE, mode), REQ_PIN);
        });
        btnPower.setOnClickListener(v -> {
            if (Prefs.getEnabled(this) && !PinStore.hasPin(this)) {
                Toast.makeText(this, "请先设置密码，再关闭守护", Toast.LENGTH_LONG).show();
                return;
            }
            requireParent(Action.POWER);
        });

        btnTarget.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        GuardService service = GuardService.INSTANCE;
        if (service != null) service.pauseForSettings();
        refresh();
        ui.removeCallbacks(diagTick);
        ui.post(diagTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(diagTick);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ACTION) {
            Action action = pendingAction;
            pendingAction = null; // One result authorizes one action, never a settings session.
            if (res == RESULT_OK && action != null) perform(action);
        }
        refresh();
    }

    private void requireParent(Action action) {
        if (pendingAction != null) return;
        if (!PinStore.hasPin(this)) {
            perform(action); // Preserve initial setup before a parent PIN exists.
            return;
        }
        pendingAction = action;
        startActivityForResult(new Intent(this, PinActivity.class)
                .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY), REQ_ACTION);
    }

    private void perform(Action action) {
        switch (action) {
            case TYPE:
                Prefs.setType(this, (Prefs.getType(this) + 1) % 3);
                break;
            case INTERVAL:
                Prefs.setIntervalMin(this, next(INTERVALS, Prefs.getIntervalMin(this)));
                break;
            case LOCK:
                Prefs.setLockMin(this, next(LOCKS, Prefs.getLockMin(this)));
                break;
            case DIFFICULTY:
                Prefs.setDifficulty(this, Prefs.getDifficulty(this) % 3 + 1);
                break;
            case ACCESSIBILITY:
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception ignored) { }
                break;
            case OVERLAY:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                }
                break;
            case POWER:
                if (Prefs.getEnabled(this)) {
                    GuardService.stopGuard(this);
                    Toast.makeText(this, "已退出守护（无障碍已关闭）", Toast.LENGTH_LONG).show();
                } else {
                    GuardService.startGuard(this);
                    Toast.makeText(this, "已重新启用守护", Toast.LENGTH_SHORT).show();
                }
                break;
            case CLEAR_STATS:
                Prefs.resetStats(this);
                break;
        }
        refresh();
    }

    private void updateDiag() {
        if (tvDiag == null) {
            return;
        }
        if (!Prefs.getEnabled(this)) {
            tvDiag.setText("自检：守护已停止（点上面的「守护状态」可重新启用）");
            return;
        }
        GuardService s = GuardService.INSTANCE;
        if (s == null) {
            tvDiag.setText("自检：无障碍服务未连接（未开启，或被系统停止）");
            return;
        }
        String st;
        switch (s.getState()) {
            case GuardService.STATE_CHALLENGE:
                st = "待答题";
                break;
            case GuardService.STATE_LOCKED:
                st = "锁定中";
                break;
            default:
                st = "监控中";
                break;
        }
        tvDiag.setText("自检：已连接 | 前台包名 " + s.getForegroundPkg()
                + " | 目标在前台=" + s.isTargetForeground()
                + " | 已累计 " + s.getAccumulatedSec() + " 秒 / 阈值 "
                + (Prefs.getIntervalMin(this) * 60) + " 秒 | 状态 " + st);
    }

    private int next(int[] arr, int cur) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == cur) {
                return arr[(i + 1) % arr.length];
            }
        }
        return arr[0];
    }

    private String appLabel(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private boolean isAccessibilityEnabled() {
        ComponentName cn = new ComponentName(this, GuardService.class);
        String flat = cn.flattenToString();
        String enabled;
        try {
            enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (Exception e) {
            return false;
        }
        if (enabled == null) {
            return false;
        }
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(flat)) {
                return true;
            }
        }
        return false;
    }

    private boolean canOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void refresh() {
        String target = Prefs.getTarget(this);
        btnTarget.setText(getString(R.string.label_target) + "：" + appLabel(target)
                + "  [" + target + "]");
        btnType.setText(getString(R.string.label_type) + "："
                + TYPE_LABEL[Math.max(0, Math.min(2, Prefs.getType(this)))]);
        btnInterval.setText(getString(R.string.label_interval)
                + "：每 " + Prefs.getIntervalMin(this) + " 分钟");
        btnLock.setText(getString(R.string.label_lock)
                + "：" + Prefs.getLockMin(this) + " 分钟");
        btnDiff.setText(getString(R.string.label_diff)
                + "：" + DIFF_LABEL[Math.max(0, Math.min(2, Prefs.getDifficulty(this) - 1))]);
        btnAccess.setText(getString(R.string.label_access) + "："
                + (isAccessibilityEnabled() ? getString(R.string.perm_on)
                : getString(R.string.perm_off)));
        btnOverlay.setText(getString(R.string.label_overlay) + "："
                + (canOverlay() ? getString(R.string.perm_on)
                : getString(R.string.perm_off)));
        btnStats.setText(getString(R.string.label_stats)
                + "：弹出 " + Prefs.getChallenges(this)
                + " 次 / 答对 " + Prefs.getCorrect(this)
                + " / 答错 " + Prefs.getWrong(this));
        boolean enabled = Prefs.getEnabled(this);
        btnPower.setText(getString(R.string.label_power) + "："
                + (enabled ? "运行中（关闭需密码）" : "已停止（点击重新启用）"));
        btnPin.setText(getString(R.string.label_pin) + "："
                + (PinStore.hasPin(this) ? "已设置（点击修改）" : "未设置（点击设置）"));
    }

    private void showStats() {
        String log = Prefs.getLog(this);
        if (log == null || log.trim().isEmpty()) {
            log = "暂无记录";
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.label_stats)
                .setMessage("累计：弹出 " + Prefs.getChallenges(this)
                        + " 次，答对 " + Prefs.getCorrect(this)
                        + " 次，答错 " + Prefs.getWrong(this) + " 次\n\n" + log)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清零", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        requireParent(Action.CLEAR_STATS);
                    }
                })
                .show();
    }
}
