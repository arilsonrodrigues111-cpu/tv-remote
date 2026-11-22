package com.arilson.tvremote;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Passo a passo do Modo avançado (depuração/ADB da própria TV).
 * Pode ser feito pelo técnico (pelo acesso remoto) ou pela própria pessoa, com o controle.
 */
public class GuideActivity extends Activity {
    private TextView state;
    private Button test;
    private final Handler h = new Handler();
    private final Runnable tick = new Runnable() { public void run() { refresh(); h.postDelayed(this, 1000); } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.NAVY);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 44), Ui.dp(this, 26), Ui.dp(this, 44), Ui.dp(this, 30));
        sv.addView(col);

        LinearLayout top = Ui.row(this);
        top.addView(Ui.brand(this, 22), Ui.weight(1));
        TextView num = Ui.text(this, "Cliente nº " + Prefs.clientLabel(this), 20, Color.WHITE, true);
        num.setBackground(Ui.round(this, Ui.ORANGE, 14));
        num.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
        top.addView(num, Ui.wrap());
        col.addView(top, Ui.match());

        TextView t = Ui.text(this, "Modo avançado: passo a passo", 30, Color.WHITE, true);
        t.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 4));
        col.addView(t);
        TextView s = Ui.text(this, "Deixa o controle funcionar dentro de qualquer aplicativo e libera tudo sozinho, até depois de reiniciar a TV. "
                + "O técnico pode fazer pelo acesso remoto, ou você mesmo com o controle.", 17, Ui.MUTED, false);
        s.setLineSpacing(Ui.dp(this, 3), 1f);
        s.setPadding(0, 0, 0, Ui.dp(this, 14));
        col.addView(s);

        step(col, "1", "Abra “Sobre” e clique 7 vezes em “Build” (ou “Versão do sistema”)",
                "Fica em Configurações › Sistema › Sobre. Clique até aparecer “Você agora é um desenvolvedor”.",
                "Abrir “Sobre”", new Runnable() { public void run() { open(Settings.ACTION_DEVICE_INFO_SETTINGS); } });
        step(col, "2", "Ligue a “Depuração USB”",
                "Em Opções do desenvolvedor, ligue “Depuração USB”. Se existir, ligue também “Depuração de rede” ou “ADB pela rede”.",
                "Abrir Opções do desenvolvedor", new Runnable() { public void run() { open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS); } });
        test = step(col, "3", "Volte aqui e toque em “Testar”",
                "Se aparecer “Permitir depuração?”, o app marca “Sempre permitir” e confirma sozinho. Se não conseguir, confirme você.",
                "▶  Testar agora", new Runnable() { public void run() {
                    AdbShell.get(GuideActivity.this).ensure();
                    Toast.makeText(GuideActivity.this, "Testando…", Toast.LENGTH_SHORT).show();
                }});

        state = Ui.text(this, "", 20, Color.WHITE, true);
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 10));
        col.addView(state, Ui.match());

        Button back = Ui.button(this, "◀  Voltar", Ui.CARD2);
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        col.addView(back, Ui.wrap());

        setContentView(sv);
    }

    private Button step(LinearLayout col, String n, String title, String desc, String btn, final Runnable onClick) {
        LinearLayout card = Ui.card(this);
        LinearLayout r = Ui.row(this);
        TextView num = Ui.text(this, n, 26, Color.WHITE, true);
        num.setGravity(Gravity.CENTER);
        num.setBackground(Ui.round(this, Ui.ORANGE, 24));
        r.addView(num, Ui.margins(Ui.lp(Ui.dp(this, 48), Ui.dp(this, 48)), this, 0, 0, 18, 0));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(Ui.text(this, title, 20, Color.WHITE, true));
        TextView d = Ui.text(this, desc, 16, Ui.MUTED, false);
        d.setPadding(0, Ui.dp(this, 4), 0, 0);
        tx.addView(d);
        r.addView(tx, Ui.weight(1));
        Button b = Ui.button(this, btn, Ui.CARD2);
        b.setTextSize(17);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onClick.run(); } });
        r.addView(b, Ui.margins(Ui.wrap(), this, 16, 0, 0, 0));
        card.addView(r, Ui.match());
        col.addView(card, Ui.margins(Ui.match(), this, 0, 0, 0, 12));
        return b;
    }

    private void refresh() {
        boolean ok = AdbShell.isReady();
        state.setText(ok ? "✓ Modo avançado ativo! Pode voltar." : "Situação: " + AdbShell.state);
        state.setTextColor(ok ? Ui.GREEN : Ui.GOLD);
    }

    private void open(String action) {
        try { startActivity(new Intent(action)); } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
        }
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); AdbShell.get(this).ensure(); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }
}
