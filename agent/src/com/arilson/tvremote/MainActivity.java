package com.arilson.tvremote;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Tela única, em passo a passo, pensada para quem nunca usou.
 * - Passo 1 (obrigatório pelo Android): ligar a Acessibilidade.
 * - Depois disso, o próprio app liga as outras permissões sozinho.
 * - Tela final verde "Tudo pronto".
 */
public class MainActivity extends Activity {
    private static final int NAVY = Color.rgb(10, 27, 51);
    private static final int ORANGE = Color.rgb(244, 88, 60);
    private static final int GREEN = Color.rgb(34, 197, 94);
    private static final int CARD = Color.rgb(24, 31, 46);

    private TextView stepLabel, title, subtitle, foot;
    private Button action, secondary;
    private View dots;
    private final Handler h = new Handler();
    private boolean autoFixing = false;

    private final Runnable tick = new Runnable() { public void run() { refresh(); h.postDelayed(this, 1000); } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        AgentService.start(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(NAVY);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(48), dp(40), dp(48), dp(40));
        sv.addView(root);

        // marca
        TextView brand = new TextView(this);
        brand.setText("TECHSOLUTIONS");
        brand.setTextColor(Color.WHITE);
        brand.setTextSize(26);
        brand.setLetterSpacing(0.08f);
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(0, 0, 0, dp(6));
        // "SOLUTIONS" em laranja via spannable simples
        android.text.SpannableString ss = new android.text.SpannableString("TECHSOLUTIONS");
        ss.setSpan(new android.text.style.ForegroundColorSpan(ORANGE), 4, 13, 0);
        brand.setText(ss);
        root.addView(brand);

        stepLabel = new TextView(this);
        stepLabel.setTextColor(ORANGE);
        stepLabel.setTextSize(15);
        stepLabel.setGravity(Gravity.CENTER);
        stepLabel.setPadding(0, dp(18), 0, dp(10));
        root.addView(stepLabel);

        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(14));
        root.addView(title);

        subtitle = new TextView(this);
        subtitle.setTextColor(Color.rgb(170, 180, 200));
        subtitle.setTextSize(18);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(dp(4), 1f);
        subtitle.setPadding(0, 0, 0, dp(30));
        root.addView(subtitle);

        action = bigButton(ORANGE);
        root.addView(action);

        secondary = bigButton(CARD);
        LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) secondary.getLayoutParams();
        slp.topMargin = dp(14);
        secondary.setLayoutParams(slp);
        root.addView(secondary);

        foot = new TextView(this);
        foot.setTextColor(Color.rgb(120, 130, 150));
        foot.setTextSize(13);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(28), 0, 0);
        root.addView(foot);

        setContentView(sv);
    }

    private Button bigButton(final int bg) {
        final Button bt = new Button(this);
        bt.setAllCaps(false);
        bt.setTextSize(20);
        bt.setTextColor(Color.WHITE);
        bt.setPadding(dp(28), dp(22), dp(28), dp(22));
        bt.setMinWidth(dp(520));
        bt.setStateListAnimator(null);
        applyBg(bt, bg, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bt.setLayoutParams(lp);
        bt.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) {
                applyBg(bt, bg, f);
                v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(120).start();
            }
        });
        return bt;
    }

    /** Fundo arredondado; quando focado, borda branca grossa bem visível no controle. */
    private void applyBg(Button bt, int bg, boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(16));
        if (focused) g.setStroke(dp(5), Color.WHITE);
        else g.setStroke(dp(2), Color.argb(60, 255, 255, 255));
        bt.setBackground(g);
    }

    // ------- estado -------
    private boolean acc() { return RemoteAccessibility.enabled(); }
    private boolean canInstall() { return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls(); }
    private boolean canFiles() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    private boolean capturing() { return AgentService.capturing(); }

    private void refresh() {
        // O cliente só precisa autorizar 2 coisas: o controle (Acessibilidade) e o ver a tela.
        // Instalar apps e arquivos são ligados depois, remotamente, pelo painel.
        boolean a = acc(), cap = capturing();
        int done = (a ? 1 : 0) + (cap ? 1 : 0);
        stepLabel.setText("Passo " + Math.min(done + 1, 2) + " de 2");

        if (!a) {
            show("1) Ligue o controle remoto", "Toque no botão abaixo. Na lista que abrir, procure “TechSolutions”, abra e ligue a chave. Depois aperte Voltar no controle.",
                    "▶  LIGAR CONTROLE", new Runnable() { public void run() {
                        open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }}, false);
            setSecondaryAdvanced();
            return;
        }

        if (!cap) {
            show("2) Autorize ver a tela", "Toque no botão abaixo e confirme “Iniciar agora” na janela que aparecer. É a última etapa.",
                    "▶  AUTORIZAR VER A TELA", new Runnable() { public void run() {
                        startActivity(new Intent(MainActivity.this, ProjectionActivity.class).putExtra("auto", false));
                    }}, false);
            setSecondaryAdvanced();
            return;
        }

        // Tudo pronto
        show("Tudo pronto! ✓", "Pode fechar este aplicativo. A TV já está conectada ao suporte. Não precisa fazer mais nada.",
                "FECHAR", new Runnable() { public void run() { finishAffinity(); } }, true);
        setSecondaryAdvanced();
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) { autoFixing = false; }

    private void setSecondaryAdvanced() {
        secondary.setVisibility(View.VISIBLE);
        secondary.setText("Atualizar o aplicativo");
        secondary.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            AgentService.start(MainActivity.this);
            new Thread(new Runnable() { public void run() {
                try { AgentService.triggerSelfUpdate(); } catch (Exception ignored) {}
            }}).start();
            toast("Baixando atualização…");
        }});
    }

    private void show(String t, String sub, String btn, final Runnable onClick, boolean green) {
        title.setText(t);
        title.setTextColor(green ? GREEN : Color.WHITE);
        subtitle.setText(sub);
        action.setText(btn);
        applyBg(action, green ? GREEN : ORANGE, action.isFocused());
        action.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onClick.run(); } });
        action.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) { applyBg(action, green ? GREEN : ORANGE, f); v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(120).start(); }
        });
        if (!action.isFocused()) action.requestFocus();
        foot.setText("Servidor: " + AgentService.connState + "   •   versão " + BuildInfo.VERSION);
    }

    private void open(Intent i) {
        try { startActivity(i); } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
        }
    }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    @Override protected void onResume() { super.onResume(); autoFixing = false; h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }
}
