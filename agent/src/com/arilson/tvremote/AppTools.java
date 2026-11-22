package com.arilson.tvremote;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Limpeza" do painel: lista TODOS os apps (inclusive de fábrica, desativados e removidos)
 * e desativa / remove / restaura pelo Modo avançado.
 */
final class AppTools {
    private AppTools() {}

    /** Lista completa com o que o painel precisa para classificar (essencial, opcional, suspeito...). */
    static JSONArray list(Context c) throws Exception {
        PackageManager pm = c.getPackageManager();
        int flags = PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS;
        List<PackageInfo> all = pm.getInstalledPackages(flags);

        Set<String> launch = new HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER})
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), PackageManager.MATCH_DISABLED_COMPONENTS))
                launch.add(r.activityInfo.packageName);
        Set<String> homes = new HashSet<>();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo r : pm.queryIntentActivities(home, 0)) homes.add(r.activityInfo.packageName);
        String homeNow = "";
        try { ResolveInfo r = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY); if (r != null && r.activityInfo != null) homeNow = r.activityInfo.packageName; } catch (Throwable ignored) {}

        Set<String> imes = new HashSet<>();
        try {
            InputMethodManager im = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            for (InputMethodInfo i : im.getInputMethodList()) imes.add(i.getPackageName());
        } catch (Throwable ignored) {}
        String imeNow = pkgOf(Settings.Secure.getString(c.getContentResolver(), "default_input_method"));

        Set<String> accs = new HashSet<>();
        String accStr = Settings.Secure.getString(c.getContentResolver(), "enabled_accessibility_services");
        if (accStr != null) for (String p : accStr.split(":")) { String k = pkgOf(p); if (!k.isEmpty()) accs.add(k); }

        Set<String> admins = new HashSet<>();
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            List<ComponentName> l = dpm.getActiveAdmins();
            if (l != null) for (ComponentName cn : l) admins.add(cn.getPackageName());
        } catch (Throwable ignored) {}

        JSONArray arr = new JSONArray();
        for (PackageInfo pi : all) {
            try {
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null) continue;
                String p = pi.packageName;
                JSONObject o = new JSONObject();
                o.put("pkg", p);
                String label = "";
                try { label = String.valueOf(pm.getApplicationLabel(ai)); } catch (Throwable ignored) {}
                o.put("label", label.isEmpty() ? p : label);
                o.put("v", pi.versionName == null ? "" : pi.versionName);
                o.put("sys", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                o.put("upd", (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
                o.put("pers", (ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0);
                o.put("inst", (ai.flags & ApplicationInfo.FLAG_INSTALLED) != 0);
                int st = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
                try { st = pm.getApplicationEnabledSetting(p); } catch (Throwable ignored) {}
                boolean on = st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ? ai.enabled
                        : st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
                o.put("on", on);
                o.put("launch", launch.contains(p));
                o.put("home", homes.contains(p));
                o.put("homeNow", p.equals(homeNow));
                o.put("ime", imes.contains(p));
                o.put("imeNow", p.equals(imeNow));
                o.put("acc", accs.contains(p));
                o.put("admin", admins.contains(p));
                if (pi.sharedUserId != null) o.put("uid", pi.sharedUserId);
                try { if (ai.sourceDir != null) o.put("size", new File(ai.sourceDir).length()); } catch (Throwable ignored) {}
                try { String ins = pm.getInstallerPackageName(p); if (ins != null) o.put("from", ins); } catch (Throwable ignored) {}
                o.put("t", pi.firstInstallTime);
                if (ai.sourceDir != null) o.put("path", ai.sourceDir.startsWith("/data/") ? "data" : ai.sourceDir.startsWith("/vendor/") || ai.sourceDir.startsWith("/odm/") ? "vendor" : ai.sourceDir.startsWith("/product/") ? "product" : "system");
                arr.put(o);
            } catch (Throwable ignored) {}
        }
        return arr;
    }

    private static String pkgOf(String comp) {
        if (comp == null) return "";
        int i = comp.indexOf('/');
        return (i > 0 ? comp.substring(0, i) : comp).trim();
    }

    private static final Map<String, String> NAMES = new HashMap<>();
    static {
        NAMES.put("disable", "desativado");
        NAMES.put("enable", "ativado de novo");
        NAMES.put("remove", "removido");
        NAMES.put("restore", "restaurado");
        NAMES.put("stop", "parado");
        NAMES.put("unadmin", "sem poder de administrador");
    }

    /** Comando do shell (Modo avançado) para cada ação. null = ação desconhecida. */
    static String shellFor(Context c, String action, String pkg, boolean system) {
        switch (action) {
            case "disable": return "pm disable-user --user 0 " + pkg;
            case "enable": return "pm enable " + pkg;
            // de fábrica: some para o usuário (não apaga da memória do sistema; dá para restaurar)
            case "remove": return system ? "pm uninstall -k --user 0 " + pkg : "pm uninstall " + pkg;
            case "restore": return "cmd package install-existing " + pkg + " 2>&1 || pm install-existing " + pkg;
            case "stop": return "am force-stop " + pkg + " && echo Success";
            case "unadmin": {
                StringBuilder b = new StringBuilder();
                try {
                    DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
                    List<ComponentName> l = dpm.getActiveAdmins();
                    if (l != null) for (ComponentName cn : l) if (cn.getPackageName().equals(pkg))
                        b.append("dpm remove-active-admin --user 0 '").append(cn.flattenToString()).append("' 2>&1; ");
                } catch (Throwable ignored) {}
                return b.length() == 0 ? null : b.toString();
            }
        }
        return null;
    }

    static boolean looksOk(String action, String out) {
        String o = out == null ? "" : out;
        if (o.contains("Failure") || o.contains("Exception") || o.contains("Error") || o.contains("Unknown package") || o.contains("not allowed")) return false;
        switch (action) {
            case "disable": return o.contains("disabled");
            case "enable": return o.contains("enabled");
            case "unadmin": return o.contains("Success") || o.contains("removed");
            case "restore": return o.contains("installed") || o.contains("Success");
            default: return o.contains("Success");
        }
    }

    static String doneText(String action) { String n = NAMES.get(action); return n == null ? action : n; }

    /** Sem Modo avançado: abre a tela do app nas Configurações da TV (para clicar em Desativar/Desinstalar com o controle). */
    static void openDetails(Context c, String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        RemoteAccessibility.startActivityAny(c, i);
    }
}
