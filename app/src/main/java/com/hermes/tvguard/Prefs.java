package com.hermes.tvguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 配置与统计存储（纯 SharedPreferences，无第三方依赖）。 */
public final class Prefs {

    private static final String NAME = "guard";
    public static final String DEFAULT_TARGET = "com.xiaodianshi.tv.yst";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String getTarget(Context c) {
        return sp(c).getString("target", DEFAULT_TARGET);
    }

    public static void setTarget(Context c, String pkg) {
        sp(c).edit().putString("target", pkg).apply();
    }

    public static int getIntervalMin(Context c) {
        return sp(c).getInt("intervalMin", 10);
    }

    public static void setIntervalMin(Context c, int v) {
        sp(c).edit().putInt("intervalMin", v).apply();
    }

    public static int getLockMin(Context c) {
        return sp(c).getInt("lockMin", 5);
    }

    public static void setLockMin(Context c, int v) {
        sp(c).edit().putInt("lockMin", v).apply();
    }

    public static int getDifficulty(Context c) {
        return sp(c).getInt("difficulty", 1);
    }

    public static void setDifficulty(Context c, int v) {
        sp(c).edit().putInt("difficulty", v).apply();
    }

    /** 题目类型：0 混合 / 1 口算 / 2 四级词汇。 */
    public static int getType(Context c) {
        return sp(c).getInt("type", Questions.TYPE_MIXED);
    }

    public static void setType(Context c, int v) {
        sp(c).edit().putInt("type", v).apply();
    }

    /** 守护是否启用（用密码退出后置 false，此时看门狗不再自愈）。 */
    public static boolean getEnabled(Context c) {
        return sp(c).getBoolean("enabled", true);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("enabled", v).apply();
    }

    public static int getChallenges(Context c) {
        return sp(c).getInt("challenges", 0);
    }

    public static int getCorrect(Context c) {
        return sp(c).getInt("correct", 0);
    }

    public static int getWrong(Context c) {
        return sp(c).getInt("wrong", 0);
    }

    private static void bump(Context c, String key) {
        SharedPreferences p = sp(c);
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply();
    }

    public static void addChallenge(Context c) {
        bump(c, "challenges");
        log(c, "弹出验证");
    }

    public static void addCorrect(Context c) {
        bump(c, "correct");
        log(c, "答对放行");
    }

    public static void addWrong(Context c) {
        bump(c, "wrong");
        log(c, "答错锁定");
    }

    public static String getLog(Context c) {
        return sp(c).getString("log", "");
    }

    private static void log(Context c, String what) {
        SharedPreferences p = sp(c);
        String ts = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date());
        String line = ts + "  " + what + "\n";
        String old = p.getString("log", "");
        String merged = line + old;
        if (merged.length() > 4000) {
            merged = merged.substring(0, 4000);
        }
        p.edit().putString("log", merged).apply();
    }

    public static void resetStats(Context c) {
        sp(c).edit().putInt("challenges", 0).putInt("correct", 0).putInt("wrong", 0)
                .putString("log", "").apply();
    }
}
