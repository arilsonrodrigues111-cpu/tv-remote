package com.arilson.tvremote;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Liga/desliga o launcher TechSolutions (HomeActivity) e garante que ele continue sendo a tela inicial,
 * inclusive depois de tirar a TV da tomada.
 *  1) grava como tela inicial padrão pelo Modo avançado (cmd package set-home-activity / papel HOME);
 *  2) opcional: desativa o launcher de fábrica (fica guardado; volta com "pm enable");
 *  3) se o launcher de fábrica aparecer mesmo assim (ROM teimosa), o controle volta para o nosso na hora.
 */
final class Launcher {
    private Launcher() {}

    private static SharedPreferences sp(Context c) { return Prefs.sp(c); }
    static ComponentName comp(Context c) { return new ComponentName(c.getPackageName(), HomeActivity.class.getName()); }
    static boolean on(Context c) { return sp(c).getBoolean("launcher_on", false); }

    /** Apps que não aparecem na fileira (do sistema e que só atrapalham). O painel pode mostrar/esconder. */
    private static final Set<String> DEFAULT_HIDE = new HashSet<>(Arrays.asList(
            "com.google.android.gms", "com.google.android.gsf", "com.google.android.tv.remote.service",
            "com.android.tv.settings", "com.android.settings", "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher", "com.google.android.tvrecommendations", "com.android.documentsui",
            "com.google.android.katniss", "com.google.android.apps.tv.dreamx", "com.android.providers.downloads.ui"));

    static Set<String> hidden(Context c) {
        Set<String> s = new HashSet<>(DEFAULT_HIDE);
        try {
            InputMethodManager im = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            for (InputMethodInfo i : im.getInputMethodList()) s.add(i.getPackageName());
        } catch (Throwable ignored) {}
        s.addAll(csv(sp(c).getString("launcher_hide", "")));
        s.removeAll(csv(sp(c).getString("launcher_show", "")));
        return s;
    }

    static List<String> order(Context c) { return new ArrayList<>(csv(sp(c).getString("launcher_order", ""))); }
    static void saveOrder(Context c, List<String> pkgs) { sp(c).edit().putString("launcher_order", join(pkgs)).apply(); }

    private static LinkedHashSet<String> csv(String s) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (s != null) for (String p : s.split(",")) { p = p.trim(); if (!p.isEmpty()) out.add(p); }
        return out;
    }
    private static String join(Iterable<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) { if (b.length() > 0) b.append(','); b.append(s); }
        return b.toString();
    }

    // ---------- tela inicial aberta / ordem mudou ----------
    private static WeakReference<HomeActivity> shown = new WeakReference<>(null);
    static void homeShown(HomeActivity a) { shown = new WeakReference<>(a); }
    static void orderChanged(Context c) { AgentService.status("info", "O cliente mudou a ordem dos apps na tela inicial"); }
    /** O painel mudou a ordem/escondidos: a tela inicial recarrega. */
    static void refreshHome() {
        final HomeActivity a = shown.get();
        if (a != null) a.runOnUiThread(new Runnable() { public void run() { try { a.reloadFromPanel(); } catch (Throwable ignored) {} } });
    }

    // ---------- quem é a tela inicial ----------
    static Intent homeIntent() { return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME); }

    /** Pacote da tela inicial padrão agora ("android" = o sistema pergunta qual usar). */
    static String currentHome(Context c) {
        try {
            ResolveInfo r = c.getPackageManager().resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY);
            if (r != null && r.activityInfo != null) return r.activityInfo.packageName;
        } catch (Throwable ignored) {}
        return "";
    }

    private static boolean isSettings(String p) { return p.equals("com.android.settings") || p.equals("com.android.tv.settings") || p.equals("android"); }

    /** Launchers de fábrica (ativos) — sem o nosso e sem a tela de emergência das Configurações. */
    static List<ComponentName> stockHomes(Context c) {
        List<ComponentName> out = new ArrayList<>();
        try {
            for (ResolveInfo r : c.getPackageManager().queryIntentActivities(homeIntent(), 0)) {
                if (r.activityInfo == null) continue;
                String p = r.activityInfo.packageName;
                if (p.equals(c.getPackageName()) || isSettings(p)) continue;
                out.add(new ComponentName(p, r.activityInfo.name));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** TV de verdade (com HDMI de entrada ou antena)? Nela o launcher de fábrica não é desativado. */
    static boolean tvSet(Context c) {
        try {
            TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return false;
            for (TvInputInfo i : tim.getTvInputList()) {
                if (i.isPassthroughInput()) return true;
                if (i.getType() == TvInputInfo.TYPE_TUNER) {
                    try {
                        ApplicationInfo ai = c.getPackageManager().getApplicationInfo(i.getServiceInfo().packageName, 0);
                        if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return true;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static List<String> disabledByUs(Context c) { return new ArrayList<>(csv(sp(c).getString("launcher_stock_off", ""))); }

    static void putInfo(Context c, JSONObject info) {
        try {
            info.put("launcher", on(c));
            info.put("home", currentHome(c));
            info.put("tvSet", tvSet(c));
            JSONArray st = new JSONArray();
            for (ComponentName cn : stockHomes(c)) st.put(cn.getPackageName());
            info.put("stockHomes", st);
            info.put("stockOff", new JSONArray(disabledByUs(c)));
        } catch (Throwable ignored) {}
    }

    // ---------- ligar / desligar ----------
    interface Done { void done(boolean ok, String msg); }

    static void setOn(final Context c, boolean wantOn, boolean disableStock, final Done done) {
        final PackageManager pm = c.getPackageManager();
        final String me = comp(c).flattenToShortString();
        if (wantOn) {
            pm.setComponentEnabledSetting(comp(c), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            sp(c).edit().putBoolean("launcher_on", true).apply();
            if (!AdbShell.isReady()) {
                // sem Modo avançado: abre a escolha de tela inicial (o cliente escolhe TechSolutions e "Sempre")
                try { RemoteAccessibility.startActivityAny(c, homeIntent()); } catch (Throwable ignored) {}
                done.done(true, "Launcher ligado. Sem o Modo avançado a TV pode perguntar qual tela inicial usar: escolha TechSolutions e \"Sempre\".");
                return;
            }
            StringBuilder cmd = new StringBuilder();
            cmd.append(setHomeCmd(c, me, c.getPackageName()));
            final List<String> off = new ArrayList<>(disabledByUs(c));
            if (disableStock) for (ComponentName cn : stockHomes(c)) {
                String p = cn.getPackageName();
                cmd.append("pm disable-user --user 0 ").append(p).append(" 2>&1; ");
                if (!off.contains(p)) off.add(p);
            }
            cmd.append("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; echo FIM");
            sh(c, cmd.toString(), new AdbShell.Result() { public void done(String out) {
                if (!off.isEmpty()) sp(c).edit().putString("launcher_stock_off", join(off)).apply();
                boolean ok = comp(c).getPackageName().equals(currentHome(c));
                done.done(ok, ok ? "Launcher TechSolutions é a tela inicial agora" + (off.isEmpty() ? "" : " (launcher de fábrica desativado)")
                        : "Liguei, mas a TV ainda não aceitou como padrão: " + tail(out));
            }});
        } else {
            sp(c).edit().putBoolean("launcher_on", false).apply();
            final List<String> off = disabledByUs(c);
            if (!AdbShell.isReady()) {
                pm.setComponentEnabledSetting(comp(c), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                done.done(off.isEmpty(), off.isEmpty() ? "Voltou ao launcher original"
                        : "Desliguei o nosso, mas para reativar o launcher de fábrica precisa do Modo avançado");
                if (off.isEmpty()) try { RemoteAccessibility.startActivityAny(c, homeIntent()); } catch (Throwable ignored) {}
                return;
            }
            StringBuilder cmd = new StringBuilder();
            for (String p : off) cmd.append("pm enable ").append(p).append(" 2>&1; ");
            sh(c, cmd.length() == 0 ? "echo ok" : cmd.toString(), new AdbShell.Result() { public void done(String out) {
                sp(c).edit().remove("launcher_stock_off").apply();
                pm.setComponentEnabledSetting(comp(c), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                List<ComponentName> st = stockHomes(c);
                String back = st.isEmpty() ? "" : setHomeCmd(c, st.get(0).flattenToShortString(), st.get(0).getPackageName());
                sh(c, back + "am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; echo FIM",
                        new AdbShell.Result() { public void done(String o2) {
                            done.done(true, "Voltou ao launcher original" + (st.isEmpty() ? "" : " (" + st.get(0).getPackageName() + ")"));
                        }});
            }});
        }
    }

    private static String setHomeCmd(Context c, String component, String pkg) {
        String s = "cmd package set-home-activity --user 0 " + component + " 2>&1; ";
        if (android.os.Build.VERSION.SDK_INT >= 29) s += "cmd role add-role-holder --user 0 android.app.role.HOME " + pkg + " 2>&1; ";
        return s;
    }

    /** Roda no shell do Modo avançado; se não der, chama o retorno do mesmo jeito (o painel sempre recebe resposta). */
    private static void sh(Context c, String cmd, AdbShell.Result r) {
        if (!AdbShell.get(c).exec(cmd, r)) r.done("[Modo avançado indisponível agora]");
    }

    private static String tail(String s) { s = s == null ? "" : s.trim(); return s.length() > 300 ? s.substring(s.length() - 300) : s; }

    /** Ativa/desativa só o launcher de fábrica (com o nosso ligado). */
    static void setStock(final Context c, final boolean disable, final Done done) {
        if (!AdbShell.isReady()) { done.done(false, "Precisa do Modo avançado"); return; }
        final List<String> off = disabledByUs(c);
        StringBuilder cmd = new StringBuilder();
        if (disable) {
            for (ComponentName cn : stockHomes(c)) { cmd.append("pm disable-user --user 0 ").append(cn.getPackageName()).append(" 2>&1; "); if (!off.contains(cn.getPackageName())) off.add(cn.getPackageName()); }
        } else {
            for (String p : off) cmd.append("pm enable ").append(p).append(" 2>&1; ");
            off.clear();
        }
        sh(c, cmd.length() == 0 ? "echo ok" : cmd.toString(), new AdbShell.Result() { public void done(String out) {
            sp(c).edit().putString("launcher_stock_off", join(off)).apply();
            if (on(c)) guard(c, "launcher de fábrica mudou", true);
            done.done(true, disable ? "Launcher de fábrica desativado" : "Launcher de fábrica ativado de novo");
        }});
    }

    // ---------- continuar sendo a tela inicial ----------
    private static long lastGuard = 0;
    /** Se a TV voltou para o launcher de fábrica como padrão (alguns box fazem isso no boot), grava o nosso de novo. */
    static void guard(Context c, String why, boolean force) {
        if (!on(c)) return;
        try {
            // o componente pode ter sido desativado por atualização/limpeza: liga de novo
            PackageManager pm = c.getPackageManager();
            if (pm.getComponentEnabledSetting(comp(c)) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                pm.setComponentEnabledSetting(comp(c), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        } catch (Throwable ignored) {}
        if (c.getPackageName().equals(currentHome(c))) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastGuard < 60000) return;
        lastGuard = now;
        Diag.event("tela inicial voltou para " + currentHome(c) + " (" + why + "): gravando a nossa de novo");
        if (AdbShell.isReady()) AdbShell.get(c).run(setHomeCmd(c, comp(c).flattenToShortString(), c.getPackageName()));
    }

    private static Set<String> stockClasses = null;
    private static long lastBounce = 0;
    /**
     * Pelo controle (acessibilidade): se a tela do launcher de fábrica abrir com o nosso ligado
     * (ROM que força o dela no boot ou no botão casinha), volta para o nosso na hora.
     */
    static void onWindow(Context c, AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!on(c)) return;
        CharSequence pk = e.getPackageName(), cl = e.getClassName();
        if (pk == null || cl == null) return;
        String p = pk.toString();
        if (p.equals(c.getPackageName()) || isSettings(p)) return;
        if (stockClasses == null || SystemClock.elapsedRealtime() - lastStockScan > 120000) scanStock(c);
        if (!stockClasses.contains(p + "/" + cl)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastBounce < 1500) return;
        lastBounce = now;
        Diag.event("launcher de fábrica abriu sozinho: voltando para o TechSolutions");
        try {
            Intent i = new Intent(c, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            c.startActivity(i);
        } catch (Throwable ignored) {}
        guard(c, "launcher de fábrica abriu", false);
    }
    private static long lastStockScan = 0;
    private static void scanStock(Context c) {
        Set<String> s = new HashSet<>();
        try {
            for (ResolveInfo r : c.getPackageManager().queryIntentActivities(homeIntent(), PackageManager.MATCH_DISABLED_COMPONENTS)) {
                if (r.activityInfo == null) continue;
                String p = r.activityInfo.packageName;
                if (p.equals(c.getPackageName()) || isSettings(p)) continue;
                s.add(p + "/" + r.activityInfo.name);
            }
        } catch (Throwable ignored) {}
        stockClasses = s; lastStockScan = SystemClock.elapsedRealtime();
    }

    // ---------- painel: lista, ordem e esconder ----------
    static JSONObject appsJson(Context c) throws Exception {
        List<HomeActivity.AppItem> shownApps = HomeActivity.queryAll(c);
        Set<String> hide = hidden(c);
        JSONArray arr = new JSONArray();
        for (HomeActivity.AppItem it : shownApps) {
            arr.put(new JSONObject().put("pkg", it.pkg).put("label", it.label).put("hidden", hide.contains(it.pkg)).put("banner", it.banner != null));
        }
        return new JSONObject().put("type", "launcherApps").put("list", arr).put("on", on(c));
    }

    /** order = pacotes na ordem nova; hide/show = mudar o que aparece. */
    static void applyPanel(Context c, JSONObject m) {
        SharedPreferences.Editor ed = sp(c).edit();
        JSONArray o = m.optJSONArray("order");
        if (o != null) { List<String> l = new ArrayList<>(); for (int i = 0; i < o.length(); i++) l.add(o.optString(i)); ed.putString("launcher_order", join(l)); }
        String hp = m.optString("hide", ""), sh = m.optString("show", "");
        if (!hp.isEmpty() || !sh.isEmpty()) {
            LinkedHashSet<String> H = csv(sp(c).getString("launcher_hide", "")), S = csv(sp(c).getString("launcher_show", ""));
            if (!hp.isEmpty()) { H.add(hp); S.remove(hp); }
            if (!sh.isEmpty()) { S.add(sh); H.remove(sh); }
            ed.putString("launcher_hide", join(H)).putString("launcher_show", join(S));
        }
        ed.apply();
        HomeActivity.clearCache();
        refreshHome();
    }
}
