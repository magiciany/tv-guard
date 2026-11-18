package com.hermes.tvguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/** Salted SHA-256 PIN storage, with durable, fail-closed retry throttling. */
public final class PinStore {
    private static final String FILE = "guard_pin";
    public static final int MAX_FAILS = PinRetryPolicy.MAX_FAILS;
    public static final long COOLDOWN_MS = PinRetryPolicy.COOLDOWN_MS;

    private PinStore() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean hasPin(Context c) {
        String h = sp(c).getString("hash", "");
        return h != null && !h.isEmpty();
    }

    private static boolean validPin(String pin) {
        return pin != null && pin.matches("[0-9]{4}");
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest(pin.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >>> 4) & 15, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // Never substitute an empty digest: a provider failure must not authenticate.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** First setup only. An existing PIN can never be overwritten by this API. */
    public static synchronized boolean setPin(Context c, String pin) {
        return !hasPin(c) && validPin(pin) && writePin(c, pin);
    }

    /** Change requires the old PIN again at the storage boundary, not just a UI flag. */
    public static synchronized boolean changePin(Context c, String oldPin, String newPin) {
        return validPin(newPin) && check(c, oldPin) && writePin(c, newPin);
    }

    private static boolean writePin(Context c, String pin) {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder salt = new StringBuilder(32);
        for (byte b : bytes) {
            salt.append(Character.forDigit((b >>> 4) & 15, 16));
            salt.append(Character.forDigit(b & 15, 16));
        }
        return sp(c).edit().putString("salt", salt.toString())
                .putString("hash", hash(salt.toString(), pin))
                .remove("fails").remove("cooldownUntil").commit();
    }

    private static int bootCount(Context c) {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT);
            } catch (Exception ignored) { }
        }
        return -1;
    }

    /** Same-boot elapsed time ignores wall-clock edits; reboot restarts a full cooldown. */
    public static synchronized long cooldownRemainingMs(Context c) {
        SharedPreferences p = sp(c);
        long until = p.getLong("cooldownUntil", 0L);
        if (until == 0L) return 0L;
        long now = SystemClock.elapsedRealtime();
        int boot = bootCount(c);
        int savedBoot = p.getInt("cooldownBoot", -1);
        long epoch = System.currentTimeMillis() - now;
        long started = p.getLong("cooldownStarted", 0L);
        long savedEpoch = p.getLong("cooldownEpoch", 0L);
        if (PinRetryPolicy.mustRestartCooldown(started, savedBoot, savedEpoch, now, boot, epoch)) {
            startCooldown(c);
            return COOLDOWN_MS;
        }
        long left = PinRetryPolicy.remainingMs(until, started, savedBoot, savedEpoch,
                now, boot, epoch);
        if (left <= 0L) {
            p.edit().remove("cooldownUntil").remove("fails").commit();
            return 0L;
        }
        return left;
    }

    private static void startCooldown(Context c) {
        long now = SystemClock.elapsedRealtime();
        sp(c).edit().putInt("fails", MAX_FAILS).putLong("cooldownUntil", now + COOLDOWN_MS)
                .putLong("cooldownStarted", now).putInt("cooldownBoot", bootCount(c))
                .putLong("cooldownEpoch", System.currentTimeMillis() - now).commit();
    }

    public static synchronized int attemptsRemaining(Context c) {
        long left = cooldownRemainingMs(c);
        return PinRetryPolicy.attemptsRemaining(sp(c).getInt("fails", 0), left);
    }

    /** Every incorrect check is persisted before returning, across all entry points. */
    public static synchronized boolean check(Context c, String pin) {
        if (cooldownRemainingMs(c) > 0L || !hasPin(c)) return false;
        SharedPreferences p = sp(c);
        String salt = p.getString("salt", "");
        String expected = p.getString("hash", "");
        boolean ok = validPin(pin) && salt != null && !salt.isEmpty()
                && expected != null && expected.length() == 64
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        hash(salt, pin).getBytes(StandardCharsets.UTF_8));
        if (ok) {
            p.edit().remove("fails").remove("cooldownUntil").commit();
        } else {
            int failures = p.getInt("fails", 0) + 1;
            if (failures >= MAX_FAILS) startCooldown(c);
            else p.edit().putInt("fails", failures).commit();
        }
        return ok;
    }
}
