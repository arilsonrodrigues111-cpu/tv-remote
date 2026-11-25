package com.arilson.tvremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;

public class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(final Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        final String name = i.getStringExtra("apkname");
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String act = i.getStringExtra("action");
        if (act == null) act = "Instalação";
        boolean streaming = i.getBooleanExtra("streaming", false);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                RemoteAccessibility.autoAccept(60000);
                RemoteAccessibility.startActivityAny(c, confirm);
                AgentService.status("info", act + " de " + name + ": confirmando na TV...");
                if (streaming) AgentService.setStreamNote(RemoteAccessibility.enabled() ? "Confirmando a instalação…" : "Confirme “Instalar” na tela");
            }
        } else if (st == PackageInstaller.STATUS_SUCCESS) {
            AgentService.status("ok", act + " concluída: " + name);
            RemoteAccessibility.autoAccept(8000); // fecha o diálogo "Concluído" se aparecer
            if (AgentService.instance != null) AgentService.instance.sendApps();
            if (streaming) AgentService.streamEnded(name + " instalado ✓");
            // app instalado pelo painel: libera armazenamento e "instalar apps desconhecidos" pra ele
            String pkg = i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
            if (pkg == null) pkg = i.getStringExtra("pkg");
            final String fpkg = pkg;
            if (i.getBooleanExtra("grant", false) && fpkg != null && !fpkg.equals(c.getPackageName())) {
                final Context app = c.getApplicationContext();
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { public void run() {
                    try { Grants.afterInstall(app, fpkg, name); } catch (Throwable e) { Diag.save("permissões após instalar", e); }
                }}, 1500);
            }
        } else {
            AgentService.status("error", act + " falhou (" + name + "): " + msg + " [" + st + "]");
            if (streaming) AgentService.streamEnded("Não deu para instalar: " + (msg == null ? "erro " + st : msg));
        }
    }
}
