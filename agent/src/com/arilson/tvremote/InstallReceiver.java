package com.arilson.tvremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

public class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        String name = i.getStringExtra("apkname");
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String act = i.getStringExtra("action");
        if (act == null) act = "Instalação";
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                RemoteAccessibility.autoAccept(60000);
                RemoteAccessibility.startActivityAny(c, confirm);
                AgentService.status("info", act + " de " + name + ": confirmando na TV...");
            }
        } else if (st == PackageInstaller.STATUS_SUCCESS) {
            AgentService.status("ok", act + " concluída: " + name);
            RemoteAccessibility.autoAccept(8000); // fecha o diálogo "Concluído" se aparecer
            if (AgentService.instance != null) AgentService.instance.sendApps();
        } else {
            AgentService.status("error", act + " falhou (" + name + "): " + msg + " [" + st + "]");
        }
    }
}
