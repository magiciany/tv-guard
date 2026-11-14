package com.hermes.tvguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 密码存储：只保存 SHA-256(盐 + 密码)，不保存明文。
 */
public final class PinStore {

    private static final String FILE = "guard_pin";

    private PinStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean hasPin(Context c) {
        String h = sp(c).getString("hash", "");
        return h != null && h.length() > 0;
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes("UTF-8"));
            byte[] d = md.digest(pin.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static void setPin(Context c, String pin) {
        String salt = Long.toHexString(new SecureRandom().nextLong());
        sp(c).edit().putString("salt", salt).putString("hash", hash(salt, pin)).apply();
    }

    public static boolean check(Context c, String pin) {
        String salt = sp(c).getString("salt", "");
        String h = sp(c).getString("hash", "");
        if (h == null || h.length() == 0) {
            return false;
        }
        return h.equals(hash(salt, pin));
    }

    public static void clear(Context c) {
        sp(c).edit().clear().apply();
    }
}
