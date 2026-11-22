package com.arilson.tvremote;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
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
 * Tela do cliente, pensada para quem nunca usou:
 *  - Número do cliente bem grande no topo (o técnico identifica a TV por ele).
 *  - Prioridade: só 2 passos (ligar o controle e autorizar ver a tela).
 *  - Embaixo, opcional: "Liberar tudo" e cada liberação extra, com passo a passo do modo avançado.
 */
public class MainActivity extends Activity {
    private TextView clientNum, clientName, conn, chip1, chip2, title, subtitle, foot;
    private Button action, fixAll, update;
    private Extra exInstall, exFiles, exAdb;
    private final Handler h = new Handler();
    private int lastState = -1;
    private boolean fixChain = false;
    private int chainInstallTries = 0, chainFilesTries = 0;

    private final Runnable tick = new Runnable() { public void run() { refresh(); h.postDelayed(this, 1000); } };

    /** Uma linha de liberação extra: nome, situação e botão. */
    private static class Extra { TextView status; Button btn; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        AgentService.start(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.NAVY);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 44), Ui.dp(this, 26), Ui.dp(this, 44), Ui.dp(this, 30));
        sv.addView(col);

        // ---------- topo: marca + número do cliente ----------
        LinearLayout top = Ui.row(this);
        LinearLayout brandCol = new LinearLayout(this);
        brandCol.setOrientation(LinearLayout.VERTICAL);
        brandCol.addView(Ui.brand(this, 30));
        conn = Ui.text(this, "", 16, Ui.MUTED, false);
        conn.setPadding(0, Ui.dp(this, 6), 0, 0);
        brandCol.addView(conn);
        top.addView(brandCol, Ui.weight(1));

        LinearLayout badge = new LinearLayout(this);
        badge.setOrientation(LinearLayout.VERTICAL);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.round(this, Ui.ORANGE, 18));
        badge.setPadding(Ui.dp(this, 26), Ui.dp(this, 10), Ui.dp(this, 26), Ui.dp(this, 12));
        TextView lbl = Ui.text(this, "CLIENTE Nº", 15, Color.WHITE, true);
        lbl.setGravity(Gravity.CENTER);
        lbl.setLetterSpacing(0.1f);
        badge.addView(lbl);
        clientNum = Ui.text(this, "----", 46, Color.WHITE, true);
        clientNum.setGravity(Gravity.CENTER);
        clientNum.setLetterSpacing(0.06f);
        badge.addView(clientNum);
        clientName = Ui.text(this, "", 15, Color.WHITE, false);
        clientName.setGravity(Gravity.CENTER);
        badge.addView(clientName);
        top.addView(badge, Ui.wrap());
        col.addView(top, Ui.match());

        // ---------- passos principais ----------
        LinearLayout main = Ui.card(this);
        LinearLayout chips = Ui.row(this);
        chip1 = chip(); chip2 = chip();
        chips.addView(chip1, Ui.margins(Ui.wrap(), this, 0, 0, 10, 0));
        chips.addView(chip2, Ui.wrap());
        main.addView(chips);
        title = Ui.text(this, "", 30, Color.WHITE, true);
        title.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 6));
        main.addView(title);
        subtitle = Ui.text(this, "", 18, Ui.MUTED, false);
        subtitle.setLineSpacing(Ui.dp(this, 3), 1f);
        subtitle.setPadding(0, 0, 0, Ui.dp(this, 18));
        main.addView(subtitle);
        action = Ui.button(this, "", Ui.ORANGE);
        action.setTextSize(22);
        main.addView(action, Ui.wrap());
        col.addView(main, Ui.margins(Ui.match(), this, 0, 20, 0, 0));

        // ---------- liberações extras (opcional) ----------
        TextView exTitle = Ui.text(this, "Liberações extras (opcional)", 20, Color.WHITE, true);
        exTitle.setPadding(0, Ui.dp(this, 26), 0, Ui.dp(this, 2));
        col.addView(exTitle);
        TextView exHint = Ui.text(this, "O técnico pode fazer isso pelo acesso remoto. Se preferir, faça você mesmo:", 15, Ui.MUTED, false);
        exHint.setPadding(0, 0, 0, Ui.dp(this, 10));
        col.addView(exHint);

        LinearLayout extras = Ui.card(this);
        fixAll = Ui.button(this, "⚡  Liberar tudo de uma vez", Color.rgb(22, 140, 70));
        fixAll.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            fixChain = true; chainInstallTries = 0; chainFilesTries = 0; runChain();
        }});
        extras.addView(fixAll, Ui.margins(Ui.wrap(), this, 0, 0, 0, 12));
        exInstall = extraRow(extras, "Instalar aplicativos", "Para o técnico instalar apps na TV.", "Liberar", new Runnable() { public void run() { openInstall(); } });
        exFiles = extraRow(extras, "Acessar arquivos", "Para enviar e receber arquivos.", "Liberar", new Runnable() { public void run() { openFiles(); } });
        exAdb = extraRow(extras, "Modo avançado (controle total)", "Controle funciona dentro de qualquer app.", "Passo a passo", new Runnable() { public void run() {
            startActivity(new Intent(MainActivity.this, GuideActivity.class));
        }});
        col.addView(extras, Ui.match());

        // ---------- rodapé ----------
        LinearLayout bottom = Ui.row(this);
        bottom.setPadding(0, Ui.dp(this, 22), 0, 0);
        update = Ui.button(this, "⬇  Atualizar o aplicativo", Ui.CARD2);
        update.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            AgentService.start(MainActivity.this);
            new Thread(new Runnable() { public void run() { try { AgentService.triggerSelfUpdate(); } catch (Exception ignored) {} } }).start();
            toast("Baixando a versão mais nova…");
        }});
        bottom.addView(update, Ui.wrap());
        foot = Ui.text(this, "", 14, Ui.MUTED, false);
        foot.setGravity(Gravity.END);
        bottom.addView(foot, Ui.weight(1));
        col.addView(bottom, Ui.match());

        setContentView(sv);
    }

    private TextView chip() {
        TextView t = Ui.text(this, "", 15, Color.WHITE, true);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        return t;
    }
    private void setChip(TextView t, String s, boolean done, boolean current) {
        t.setText((done ? "✓  " : "") + s);
        t.setBackground(Ui.round(this, done ? Color.rgb(22, 120, 64) : current ? Ui.ORANGE : Ui.CARD2, 20));
    }

    private Extra extraRow(LinearLayout parent, String name, String desc, String btn, final Runnable onClick) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        Extra e = new Extra();
        e.status = Ui.text(this, "○", 24, Ui.MUTED, true);
        e.status.setGravity(Gravity.CENTER);
        r.addView(e.status, Ui.margins(Ui.lp(Ui.dp(this, 36), -2), this, 0, 0, 12, 0));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(Ui.text(this, name, 19, Color.WHITE, true));
        tx.addView(Ui.text(this, desc, 15, Ui.MUTED, false));
        r.addView(tx, Ui.weight(1));
        e.btn = Ui.button(this, btn, Ui.CARD2);
        e.btn.setTextSize(17);
        e.btn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onClick.run(); } });
        r.addView(e.btn, Ui.wrap());
        parent.addView(r, Ui.match());
        return e;
    }
    private void setExtra(Extra e, boolean ok, String okText, String pendText) {
        e.status.setText(ok ? "✓" : "○");
        e.status.setTextColor(ok ? Ui.GREEN : Ui.MUTED);
        e.btn.setText(ok ? okText : pendText);
    }

    // ------- estado -------
    private boolean acc() { return RemoteAccessibility.enabled(); }
    private boolean capturing() { return AgentService.capturing(); }

    private void refresh() {
        boolean a = acc(), cap = capturing();
        clientNum.setText(Prefs.clientLabel(this));
        String cn = Prefs.clientName(this);
        clientName.setText(cn);
        clientName.setVisibility(cn.isEmpty() ? View.GONE : View.VISIBLE);
        boolean online = "conectado".equalsIgnoreCase(AgentService.connState);
        conn.setText((online ? "● Conectado ao suporte" : "● " + AgentService.connState));
        conn.setTextColor(online ? Ui.GREEN : Ui.MUTED);

        setChip(chip1, "1  Controle", a, !a);
        setChip(chip2, "2  Ver a tela", cap, a && !cap);

        int state = !a ? 0 : !cap ? 1 : 2;
        if (state == 0) {
            show("Ligue o controle remoto", "Toque no botão abaixo. Na lista que abrir, procure “TechSolutions”, entre e ligue a chave. Depois aperte Voltar no controle.",
                    "▶  LIGAR O CONTROLE", Ui.ORANGE, new Runnable() { public void run() { open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } });
        } else if (state == 1) {
            show("Autorize ver a tela", "Toque no botão abaixo e confirme “Iniciar agora” na janela que aparecer. É a última etapa.",
                    "▶  AUTORIZAR VER A TELA", Ui.ORANGE, new Runnable() { public void run() {
                        startActivity(new Intent(MainActivity.this, ProjectionActivity.class).putExtra("auto", false));
                    }});
        } else {
            show("Tudo pronto! ✓", "A TV já está conectada ao suporte. Pode fechar este aplicativo, não precisa fazer mais nada.",
                    "FECHAR", Color.rgb(22, 140, 70), new Runnable() { public void run() { finishAffinity(); } });
        }
        title.setTextColor(state == 2 ? Ui.GREEN : Color.WHITE);
        // só puxa o foco quando a etapa muda (antes puxava a cada segundo e não dava para ir aos outros botões)
        if (state != lastState) { lastState = state; action.requestFocus(); }

        boolean inst = AgentService.canInstall(this), files = AgentService.canFiles(this), adb = AdbShell.isReady();
        setExtra(exInstall, inst, "Liberado", "Liberar");
        setExtra(exFiles, files, "Liberado", "Liberar");
        setExtra(exAdb, adb, "Ativo", "Passo a passo");
        fixAll.setVisibility(inst && files && adb ? View.GONE : View.VISIBLE);

        foot.setText("versão " + BuildInfo.VERSION);
    }

    private void show(String t, String sub, String btn, int color, final Runnable onClick) {
        title.setText(t);
        subtitle.setText(sub);
        action.setText(btn);
        Ui.setColor(this, action, color);
        action.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onClick.run(); } });
    }

    // ------- liberações -------
    private void openInstall() {
        if (Build.VERSION.SDK_INT < 26 || AgentService.canInstall(this)) { toast("Instalar apps já está liberado"); return; }
        RemoteAccessibility.autoAcceptPerm(20000);
        open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
    }
    private void openFiles() {
        if (AgentService.canFiles(this)) { toast("Arquivos já está liberado"); return; }
        startActivity(new Intent(this, PermActivity.class));
    }
    /** "Liberar tudo": uma liberação por vez; ao voltar para o app continua na próxima. */
    private void runChain() {
        if (!fixChain) return;
        AdbShell.get(this).ensure();
        if (!AgentService.canInstall(this) && chainInstallTries++ < 1) { openInstall(); return; }
        if (!AgentService.canFiles(this) && chainFilesTries++ < 1) { openFiles(); return; }
        fixChain = false;
        if (!AdbShell.isReady()) {
            toast("Falta só o Modo avançado: siga o passo a passo");
            startActivity(new Intent(this, GuideActivity.class));
        } else toast("Tudo liberado ✓");
    }

    private void open(Intent i) {
        try { startActivity(i); } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
        }
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override protected void onResume() {
        super.onResume();
        lastState = -1;
        h.post(tick);
        if (fixChain) h.postDelayed(new Runnable() { public void run() { runChain(); } }, 900);
    }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }
}
