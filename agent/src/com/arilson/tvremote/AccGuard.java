package com.arilson.tvremote;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.LinkedHashSet;

/**
 * Cuida do "controle" (serviço de acessibilidade):
 *  - sabe se está ligado nas Configurações, rodando, ou ligado-mas-travado (o Android desligou por dentro);
 *  - religa sozinho (desliga e liga a chave) quando tem a permissão do sistema ou o Modo avançado;
 *  - nunca mexe na configuração quando o controle já está rodando.
 */
final class AccGuard {
    static final String KEY = "enabled_accessibility_services";
    static volatile long lastLost = 0;       // quando o Android desligou o serviço por último
    static volatile int repairs = 0;
    private static volatile long missingSince = 0;
    private static volatile long lastRepair = 0;
    private static volatile int attempts = 0;

    static String component(Context c) { return c.getPackageName() + "/" + RemoteAccessibility.class.getName(); }

    private static boolean isOurs(Context c, String part) {
        ComponentName cn = ComponentName.unflattenFromString(part.trim());
        return cn != null && cn.getPackageName().equals(c.getPackageName())
                && cn.getClassName().equals(RemoteAccessibility.class.getName());
    }

    static boolean inSettings(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), KEY);
            if (s == null) return false;
            for (String p : s.split(":")) if (isOurs(c, p)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** Os outros serviços de acessibilidade da TV (sem o nosso, sem repetidos). */
    static String others(Context c) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), KEY);
            if (s != null) for (String p : s.split(":")) {
                p = p.trim();
                if (p.isEmpty() || "null".equals(p) || isOurs(c, p) || ComponentName.unflattenFromString(p) == null) continue;
                set.add(p);
            }
        } catch (Throwable ignored) {}
        StringBuilder b = new StringBuilder();
        for (String p : set) { if (b.length() > 0) b.append(':'); b.append(p); }
        return b.toString();
    }

    static boolean canWriteSecure(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    static boolean running() { return RemoteAccessibility.instance != null; }

    /** Ligado nas Configurações há alguns segundos, mas o Android não está rodando o serviço. */
    static boolean stuck(Context c) {
        if (running()) { missingSince = 0; return false; }
        if (!inSettings(c)) { missingSince = 0; return false; }
        long now = SystemClock.elapsedRealtime();
        if (missingSince == 0) missingSince = now;
        return now - missingSince > 4000;
    }

    static String describe(Context c) {
        if (running()) return "funcionando";
        if (stuck(c)) return "TRAVADO (ligado nas Configurações, mas o Android não está rodando)";
        if (inSettings(c)) return "iniciando…";
        return "desligado";
    }

    /** Estado curto para o painel: on / travado / off. */
    static String state(Context c) { return running() ? "on" : stuck(c) ? "travado" : "off"; }

    static volatile int removedBySystem = 0;
    private static volatile long lastFast = 0;

    /**
     * A lista de acessibilidade mudou. Se tiraram o TechSolutions (alguns box com sistema modificado fazem isso sozinhos
     * segundos depois de ligar), coloca de volta na hora quando tem permissão.
     */
    static void onSettingChanged(final Context c) {
        if (inSettings(c)) return;
        if (!running() && lastLost == 0 && removedBySystem == 0 && repairs == 0) return; // nunca esteve ligado: nada a fazer
        removedBySystem++;
        Diag.event("a TV tirou o TechSolutions da lista de acessibilidade");
        if (!canWriteSecure(c) && !AdbShell.isReady()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastFast < 3000) return; // evita ficar num vai-e-volta sem fim
        lastFast = now;
        apply(c, false, "a TV desligou o controle");
    }

    static void connected() { attempts = 0; missingSince = 0; Diag.event("controle ligado"); }
    static void lost(String why) { lastLost = System.currentTimeMillis(); Diag.event("controle desligado (" + why + ")"); }

    /**
     * Liga (ou religa) o controle sozinho, se der. Retorna true se tentou.
     * force=true ignora o intervalo de espera (usado quando o Modo avançado acabou de conectar).
     */
    static synchronized boolean repair(final Context c, final String why, boolean force) {
        if (running()) return false;
        final boolean secure = canWriteSecure(c), adb = AdbShell.isReady();
        if (!secure && !adb) return false;
        boolean wasIn = inSettings(c);
        if (wasIn && !stuck(c) && !force) return false; // acabou de ligar: dá tempo do Android iniciar
        long now = SystemClock.elapsedRealtime();
        long wait = attempts == 0 ? 0 : Math.min(300000, 20000L << Math.min(attempts - 1, 4)); // 0, 20s, 40s, 80s... até 5 min
        if (!force && now - lastRepair < wait) return false;
        lastRepair = now; attempts++;
        apply(c, wasIn, why);
        return true;
    }

    private static void apply(final Context c, final boolean wasIn, String why) {
        final boolean secure = canWriteSecure(c);
        repairs++;
        final String others = others(c);
        final String on = others.isEmpty() ? component(c) : others + ":" + component(c);
        Diag.event("religando o controle (" + why + (secure ? ", permissão" : ", adb") + ")");
        AgentService.status("info", "Religando o controle da TV sozinho…");
        if (secure) {
            final boolean toggle = wasIn;
            new Thread(new Runnable() { public void run() {
                try {
                    ContentResolver cr = c.getContentResolver();
                    if (toggle) { Settings.Secure.putString(cr, KEY, others); Thread.sleep(1200); }
                    Settings.Secure.putString(cr, KEY, on);
                    Settings.Secure.putInt(cr, "accessibility_enabled", 1);
                } catch (Throwable e) {
                    Diag.event("religar falhou: " + e);
                    if (AdbShell.isReady()) AdbShell.get(c).run(shellCmd(toggle, others, on));
                }
            }}, "acc-fix").start();
        } else {
            AdbShell.get(c).run(shellCmd(wasIn, others, on));
        }
    }

    private static String shellCmd(boolean toggle, String others, String on) {
        String off = others.isEmpty() ? "settings put secure " + KEY + " ''" : "settings put secure " + KEY + " '" + others + "'";
        return (toggle ? off + "; sleep 1; " : "") + "settings put secure " + KEY + " '" + on + "'; settings put secure accessibility_enabled 1";
    }
}
