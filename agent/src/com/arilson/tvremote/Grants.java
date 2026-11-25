package com.arilson.tvremote;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Depois de instalar um app pelo painel: deixa ele com todas as permissões de armazenamento
 * e com "instalar apps de fontes desconhecidas".
 *  - Com o Modo avançado (ADB): pm grant / appops, na hora e sem aparecer nada na TV.
 *  - Sem ADB, com o controle ligado: abre cada tela de permissão e a acessibilidade liga a chave sozinha.
 */
final class Grants {
    private Grants() {}

    private static final String[] STORAGE = {
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.ACCESS_MEDIA_LOCATION"
    };
    private static final String MANAGE = "android.permission.MANAGE_EXTERNAL_STORAGE";
    private static final String INSTALL = "android.permission.REQUEST_INSTALL_PACKAGES";

    private static final Handler h = new Handler(Looper.getMainLooper());

    static Set<String> requested(Context c, String pkg) {
        Set<String> out = new HashSet<>();
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions != null) out.addAll(Arrays.asList(pi.requestedPermissions));
        } catch (Exception ignored) {}
        return out;
    }

    private static boolean sdkHas(String perm) {
        int sdk = Build.VERSION.SDK_INT;
        if (perm.contains("READ_MEDIA_VISUAL_USER_SELECTED")) return sdk >= 34;
        if (perm.contains("READ_MEDIA_")) return sdk >= 33;
        if (perm.contains("ACCESS_MEDIA_LOCATION")) return sdk >= 29;
        return true;
    }

    /** Permissões de armazenamento "comuns" (runtime) que o app pede e este Android tem. */
    private static List<String> storageWanted(Set<String> req) {
        List<String> l = new ArrayList<>();
        for (String p : STORAGE) if (req.contains(p) && sdkHas(p)) l.add(p);
        return l;
    }

    static boolean storageGranted(Context c, String pkg, List<String> wanted) {
        PackageManager pm = c.getPackageManager();
        for (String p : wanted) {
            // READ é a que vale pra tudo; as outras podem não existir/ser ignoradas em Android novo
            if (p.endsWith("READ_EXTERNAL_STORAGE") && Build.VERSION.SDK_INT < 33 && pm.checkPermission(p, pkg) != PackageManager.PERMISSION_GRANTED) return false;
            if (p.contains("READ_MEDIA_") && !p.contains("VISUAL") && pm.checkPermission(p, pkg) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    /** Chamado quando a instalação termina (InstallReceiver). */
    static void afterInstall(final Context c, final String pkg, final String label) {
        if (pkg == null || pkg.equals(c.getPackageName())) return;
        final Set<String> req = requested(c, pkg);
        final List<String> st = storageWanted(req);
        final boolean wantManage = Build.VERSION.SDK_INT >= 30 && req.contains(MANAGE);
        final boolean wantInstall = Build.VERSION.SDK_INT >= 26 && req.contains(INSTALL);
        if (st.isEmpty() && !wantManage && !wantInstall) { Diag.event("permissões " + pkg + ": app não pede armazenamento/instalar"); return; }

        if (AdbShell.isReady()) { viaAdb(c, pkg, label, st, wantManage, wantInstall); return; }
        if (!RemoteAccessibility.enabled()) {
            AgentService.status("error", label + " instalado, mas sem o controle ligado não dá para liberar as permissões dele daqui.");
            return;
        }
        viaScreens(c, pkg, label, st, wantManage, wantInstall);
    }

    private static void viaAdb(final Context c, final String pkg, final String label, final List<String> st, boolean manage, final boolean install) {
        StringBuilder cmd = new StringBuilder();
        for (String p : st) cmd.append("pm grant ").append(pkg).append(' ').append(p).append(" 2>&1; ");
        if (manage) cmd.append("appops set ").append(pkg).append(" MANAGE_EXTERNAL_STORAGE allow 2>&1; ");
        if (Build.VERSION.SDK_INT == 29 && !st.isEmpty()) cmd.append("appops set ").append(pkg).append(" LEGACY_STORAGE allow >/dev/null 2>&1; ");
        if (install) cmd.append("appops set ").append(pkg).append(" REQUEST_INSTALL_PACKAGES allow 2>&1; echo ==INST; appops get ").append(pkg).append(" REQUEST_INSTALL_PACKAGES 2>&1; ");
        cmd.append("echo ==FIM");
        final String fcmd = cmd.toString();
        boolean started = AdbShell.get(c).exec(fcmd, new AdbShell.Result() { public void done(String out) {
            String o = out == null ? "" : out;
            boolean stOk = st.isEmpty() || storageGranted(c, pkg, st);
            boolean instOk = !install || o.substring(Math.max(0, o.indexOf("==INST"))).toLowerCase(java.util.Locale.ROOT).contains("allow");
            Diag.event("permissões " + pkg + " (adb): " + Diag.tail(o, 300));
            report(label, st, stOk, install, instOk);
        }});
        if (!started) viaScreens(c, pkg, label, st, manage, install);
    }

    private static void viaScreens(final Context c, final String pkg, final String label, final List<String> st, final boolean manage, final boolean install) {
        AgentService.status("info", "Liberando as permissões de " + label + " na TV…");
        final List<Runnable> steps = new ArrayList<>();
        final Runnable[] next = new Runnable[1];
        next[0] = new Runnable() { public void run() {
            if (steps.isEmpty()) return;
            steps.remove(0).run();
        }};
        if (install) steps.add(new Runnable() { public void run() {
            RemoteAccessibility.cancelAutoAccept();
            RemoteAccessibility.autoAcceptPerm(9000);
            open(c, new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + pkg)), pkg);
            h.postDelayed(next[0], 6500);
        }});
        if (manage) steps.add(new Runnable() { public void run() {
            RemoteAccessibility.cancelAutoAccept();
            RemoteAccessibility.autoAcceptPerm(9000);
            open(c, new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", Uri.parse("package:" + pkg)), pkg);
            h.postDelayed(next[0], 6500);
        }});
        steps.add(new Runnable() { public void run() {
            if (st.isEmpty() || storageGranted(c, pkg, st)) { RemoteAccessibility.leaveSettings(); h.postDelayed(next[0], 1500); return; }
            RemoteAccessibility.cancelAutoAccept();
            RemoteAccessibility.walk(STORAGE_WALK, 26000, new RemoteAccessibility.WalkDone() { public void done(boolean ok) {
                h.postDelayed(next[0], 1200);
            }});
            open(c, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)), pkg);
        }});
        steps.add(new Runnable() { public void run() {
            RemoteAccessibility.leaveSettings();
            boolean stOk = st.isEmpty() || storageGranted(c, pkg, st);
            // sem ADB não dá para conferir a chave de outro app: conta como feita
            report(label, st, stOk, install, true);
        }});
        h.postDelayed(next[0], 2500); // espera o "Concluído" do instalador sumir
    }

    /** Caminho na tela de informações do app: Permissões → Armazenamento → Permitir. */
    static final String[][] STORAGE_WALK = {
            { "permissões", "permissoes", "permissions", "permisos", "permissão", "permissao", "permissões do app", "permissões de apps", "app permissions" },
            { "armazenamento", "storage", "arquivos e mídia", "arquivos e mídias", "arquivos e midia", "files and media", "arquivos", "files",
              "fotos e vídeos", "photos and videos", "música e áudio", "music and audio", "almacenamiento" },
            { "permitir o gerenciamento de todos os arquivos", "allow management of all files", "permitir", "allow",
              "permitir acesso apenas a mídia", "allow access to media only", "permitir sempre", "allow all the time" }
    };

    private static void open(Context c, Intent i, String pkg) {
        try { RemoteAccessibility.startActivityAny(c, i); }
        catch (Throwable e) { RemoteAccessibility.startActivityAny(c, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))); }
    }

    private static void report(String label, List<String> st, boolean stOk, boolean install, boolean instOk) {
        StringBuilder b = new StringBuilder(label).append(": ");
        if (!st.isEmpty()) b.append("armazenamento ").append(stOk ? "✓" : "✗");
        if (install) b.append(st.isEmpty() ? "" : " · ").append("instalar apps ").append(instOk ? "✓" : "✗");
        boolean ok = stOk && instOk;
        AgentService.status(ok ? "ok" : "error", "Permissões de " + b + (ok ? "" : " (abra o app na TV e aceite, ou use o Modo avançado)"));
        if (AgentService.instance != null) AgentService.instance.sendApps();
    }
}
