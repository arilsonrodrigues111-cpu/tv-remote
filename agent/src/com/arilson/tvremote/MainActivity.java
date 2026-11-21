package com.arilson.tvremote;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Tela de configuração, pensada para controle remoto (D-pad). */
public class MainActivity extends Activity {
    private TextView status;
    private Button bAcc, bInst, bCap;
    private LinearLayout adv;
    private final Handler h = new Handler();
    private final Runnable tick = new Runnable() { public void run() { refresh(); h.postDelayed(this, 1500); } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        AgentService.start(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.rgb(15, 17, 21));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(60, 40, 60, 40);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextSize(28); title.setTextColor(Color.WHITE);
        root.addView(title);

        TextView idv = new TextView(this);
        idv.setText("ID: " + Prefs.deviceId(this) + "   ·   " + Prefs.name(this));
        idv.setTextColor(Color.GRAY); idv.setTextSize(14);
        root.addView(idv);

        status = new TextView(this);
        status.setTextSize(18); status.setTextColor(Color.WHITE);
        status.setPadding(0, 24, 0, 24);
        root.addView(status);

        bAcc = btn(root, "1. Ativar controle remoto (Acessibilidade)", new View.OnClickListener() { public void onClick(View v) {
            openSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }});
        bInst = btn(root, "2. Permitir instalar apps", new View.OnClickListener() { public void onClick(View v) {
            if (Build.VERSION.SDK_INT >= 26)
                openSettings(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            else openSettings(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        }});
        bCap = btn(root, "3. Permitir ver a tela", new View.OnClickListener() { public void onClick(View v) {
            startActivity(new Intent(MainActivity.this, ProjectionActivity.class));
        }});

        btn(root, "Avançado (servidor)", new View.OnClickListener() { public void onClick(View v) {
            adv.setVisibility(adv.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        }});

        adv = new LinearLayout(this);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setVisibility(View.GONE);
        root.addView(adv);
        final EditText eServer = edit(adv, "Servidor (https://...)", Prefs.server(this), false);
        final EditText eToken = edit(adv, "Token", Prefs.token(this), true);
        final EditText eName = edit(adv, "Nome deste aparelho", Prefs.sp(this).getString("name", ""), false);
        btn(adv, "Salvar e reconectar", new View.OnClickListener() { public void onClick(View v) {
            Prefs.save(MainActivity.this, eServer.getText().toString().trim(), eToken.getText().toString().trim(), eName.getText().toString().trim());
            startService(new Intent(MainActivity.this, AgentService.class).putExtra("reconnect", true));
        }});

        setContentView(sv);
        bAcc.requestFocus();
    }

    private void openSettings(Intent i) {
        try { startActivity(i); } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
        }
    }

    private Button btn(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false); b.setTextSize(18);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 12;
        parent.addView(b, lp);
        return b;
    }

    private EditText edit(LinearLayout parent, String hint, String val, boolean pass) {
        TextView t = new TextView(this); t.setText(hint); t.setTextColor(Color.GRAY); t.setPadding(0, 16, 0, 0);
        parent.addView(t);
        EditText e = new EditText(this);
        e.setText(val); e.setSingleLine(true); e.setTextColor(Color.WHITE);
        if (pass) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(e);
        return e;
    }

    private void refresh() {
        boolean acc = RemoteAccessibility.enabled();
        boolean inst = Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
        bAcc.setText((acc ? "✔ " : "1. ") + "Controle remoto (Acessibilidade)" + (acc ? " – ativo" : " – toque para ativar"));
        bInst.setText((inst ? "✔ " : "2. ") + "Instalar apps" + (inst ? " – permitido" : " – toque para permitir"));
        bCap.setText("3. Ver a tela – toque para permitir (o suporte também pode pedir)");
        status.setText("Servidor: " + AgentService.connState + "\n" +
                (acc && inst ? "Tudo pronto. Pode fechar este app." : "Conclua os passos abaixo."));
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }
}
