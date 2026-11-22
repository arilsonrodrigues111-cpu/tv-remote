package com.arilson.tvremote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;

/**
 * Janela invisível que só pede o consentimento de captura de tela.
 * - Pedido vindo do painel (extra "auto"): o agente tenta clicar "Iniciar agora" sozinho, com atraso.
 * - Se o diálogo fechar sozinho em menos de 3 s, tenta de novo SEM clique automático,
 *   para a pessoa conseguir tocar em "Permitir".
 */
public class ProjectionActivity extends Activity {
    static final int REQ = 77;
    private boolean auto;
    private int attempt;
    private long askedAt;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        auto = getIntent().getBooleanExtra("auto", false);
        attempt = getIntent().getIntExtra("attempt", 0);
        AgentService.start(this);
        ask();
    }

    private void ask() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            askedAt = SystemClock.elapsedRealtime();
            if (auto) RemoteAccessibility.autoAcceptProjection(15000, 1500);
            else RemoteAccessibility.cancelAutoAccept();
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
        } catch (Exception e) {
            AgentService.status("error", "Esta TV não tem a tela de permissão de captura (" + Build.MANUFACTURER + " " + Build.MODEL
                    + ", Android " + Build.VERSION.RELEASE + "): " + e.getMessage());
            finish();
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ) { finish(); return; }
        RemoteAccessibility.cancelAutoAccept();
        long took = SystemClock.elapsedRealtime() - askedAt;
        if (res == RESULT_OK && data != null) {
            AgentService.deliverProjection(this, res, data);
            Toast.makeText(this, "Compartilhamento de tela autorizado", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (took < 3000 && attempt == 0) {
            // fechou "sozinho": repete sem o clique automático para a pessoa tocar
            AgentService.status("info", "Diálogo de captura fechou em " + took + " ms; reabrindo para toque manual na TV...");
            Intent again = new Intent(this, ProjectionActivity.class).putExtra("auto", false).putExtra("attempt", 1)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            finish();
            startActivity(again);
            return;
        }
        AgentService.status("error", "Captura de tela não autorizada na TV (código " + res + ", " + took + " ms, "
                + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + ")");
        finish();
    }
}
