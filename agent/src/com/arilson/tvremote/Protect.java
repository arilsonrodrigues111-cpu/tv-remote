package com.arilson.tvremote;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Proteção contra desinstalação: guarda quais apps o suporte travou nesta TV.
 * Quem aplica de verdade é a RemoteAccessibility: quando a tela de "Desinstalar?" de um app
 * protegido aparece, ela cancela antes de confirmar. O próprio TechSolutions fica sempre protegido.
 */
final class Protect {
    private Protect() {}

    static boolean on(Context c) { return Prefs.sp(c).getBoolean("protect_on", false); }

    /** Só os apps que o suporte escolheu (sem o próprio TechSolutions, que é implícito). */
    static List<String> chosen(Context c) {
        List<String> out = new ArrayList<>();
        for (String p : Prefs.sp(c).getString("protect_pkgs", "").split(",")) {
            p = p.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    static void set(Context c, boolean on, List<String> pkgs) {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        if (pkgs != null) for (String p : pkgs) {
            if (p == null) continue;
            p = p.trim();
            if (!p.isEmpty() && !p.equals(c.getPackageName())) s.add(p);
        }
        StringBuilder b = new StringBuilder();
        for (String p : s) { if (b.length() > 0) b.append(','); b.append(p); }
        Prefs.sp(c).edit().putBoolean("protect_on", on).putString("protect_pkgs", b.toString()).apply();
    }

    /** Pacotes realmente protegidos: os escolhidos + o próprio app (sempre). */
    static Set<String> effective(Context c) {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        s.add(c.getPackageName());
        s.addAll(chosen(c));
        return s;
    }

    /** Nomes (rótulos) dos apps protegidos, em minúsculas, para casar com o texto da tela de desinstalar. */
    static List<String> labels(Context c) {
        List<String> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String p : effective(c)) {
            try {
                String lab = pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString().trim().toLowerCase(Locale.ROOT);
                if (lab.length() >= 2 && !out.contains(lab)) out.add(lab);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    static void putInfo(Context c, JSONObject info) {
        try {
            info.put("protect", on(c));
            info.put("protectPkgs", new JSONArray(chosen(c)));
        } catch (Throwable ignored) {}
    }
}
