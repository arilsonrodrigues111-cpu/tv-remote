package com.arilson.tvremote;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Mensagem do suporte na tela da TV.
 * Com o controle ligado aparece como faixa por cima de qualquer app (sem atrapalhar);
 * sem ele, abre esta tela com botão OK.
 */
public class MessageActivity extends Activity {
    static volatile String pending = "";

    /** Cartão da mensagem (usado na faixa por cima dos apps e nesta tela). */
    static LinearLayout card(Context c, String text) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(c, Ui.NAVY, 18));
        int p = Ui.dp(c, 22);
        box.setPadding(p, Ui.dp(c, 14), p, Ui.dp(c, 18));
        LinearLayout top = Ui.row(c);
        View bar = new View(c);
        bar.setBackground(Ui.round(c, Ui.ORANGE, 3));
        top.addView(bar, Ui.margins(Ui.lp(Ui.dp(c, 6), Ui.dp(c, 22)), c, 0, 0, 10, 0));
        top.addView(Ui.text(c, "Mensagem do suporte · Cliente nº " + Prefs.clientLabel(c), 15, Ui.MUTED, true));
        box.addView(top);
        TextView t = Ui.text(c, text, 26, Color.WHITE, true);
        t.setPadding(0, Ui.dp(c, 10), 0, 0);
        t.setLineSpacing(Ui.dp(c, 3), 1f);
        box.addView(t);
        return box;
    }

    static long duration(String text) { return Math.max(8000, Math.min(30000, 4000 + text.length() * 90L)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // só mostra o que veio do painel (a tela pode ser aberta pelo Modo avançado, por isso fica "exportada")
        String text = pending;
        pending = "";
        if (text == null || text.isEmpty()) { finish(); return; }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.argb(150, 0, 0, 0));
        LinearLayout c = card(this, text);
        Button ok = Ui.button(this, "OK", Ui.ORANGE);
        ok.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        c.addView(ok, Ui.margins(Ui.wrap(), this, 0, 16, 0, 0));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 620), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(c, lp);
        setContentView(root);
        ok.requestFocus();
        new Handler().postDelayed(new Runnable() { public void run() { if (!isFinishing()) finish(); } }, duration(text) + 7000);
        AgentService.status("ok", "Mensagem aberta na tela da TV");
    }
}
