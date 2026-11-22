package com.arilson.tvremote;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;

/** Janela invisível que só pede a permissão de arquivos (a acessibilidade toca em "Permitir"). */
public class PermActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 23 && !AgentService.canFiles(this)) {
            RemoteAccessibility.autoAcceptPerm(15000);
            requestPermissions(new String[]{ android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE }, 5);
        } else finish();
    }
    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        AgentService.sendInfoIfConnected();
        finish();
    }
}
