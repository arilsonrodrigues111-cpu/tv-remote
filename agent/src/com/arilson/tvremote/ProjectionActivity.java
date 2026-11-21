package com.arilson.tvremote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/** Janela invisível que só pede o consentimento de captura de tela. */
public class ProjectionActivity extends Activity {
    static final int REQ = 77;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        RemoteAccessibility.autoAccept(20000);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try { startActivityForResult(mpm.createScreenCaptureIntent(), REQ); }
        catch (Exception e) { AgentService.status("error", "Captura de tela não suportada: " + e); finish(); }
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ) {
            if (res == RESULT_OK && data != null && AgentService.instance != null) {
                AgentService.instance.onProjectionGranted(res, data);
            } else {
                AgentService.status("error", "Captura de tela recusada na TV");
            }
        }
        finish();
    }
}
