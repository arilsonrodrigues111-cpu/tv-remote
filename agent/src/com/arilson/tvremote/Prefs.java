package com.arilson.tvremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

public class Prefs {
    static SharedPreferences sp(Context c) { return c.getSharedPreferences("cfg", Context.MODE_PRIVATE); }
    static String server(Context c) { return sp(c).getString("server", Config.DEFAULT_SERVER); }
    static String token(Context c) { return sp(c).getString("token", Config.DEFAULT_TOKEN); }
    static String name(Context c) {
        String n = sp(c).getString("name", "");
        return n.isEmpty() ? (Build.MANUFACTURER + " " + Build.MODEL) : n;
    }
    static String deviceId(Context c) {
        String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        return id == null ? "unknown" : id;
    }
    static void save(Context c, String server, String token, String name) {
        sp(c).edit().putString("server", server).putString("token", token).putString("name", name).apply();
    }
    /** Número do cliente dado pelo servidor (0 = ainda não recebeu). */
    static int clientNum(Context c) { return sp(c).getInt("clientNum", 0); }
    static String clientName(Context c) { return sp(c).getString("clientName", ""); }
    static void saveClient(Context c, int num, String name) {
        sp(c).edit().putInt("clientNum", num).putString("clientName", name == null ? "" : name).apply();
    }
    static String clientLabel(Context c) {
        int n = clientNum(c);
        return n > 0 ? String.format(java.util.Locale.ROOT, "%04d", n) : "----";
    }
    /** http(s)://host -> ws(s)://host */
    static String httpBase(Context c) {
        String s = server(c).trim();
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.startsWith("http")) s = "https://" + s;
        return s;
    }
}
