package com.arilson.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tela inicial (launcher) TechSolutions: fundo preto, aba Apps, engrenagem e horário.
 * Uma fileira só de cartões (banner de cada app, no estilo Google TV); os que não cabem ficam à direita.
 * Segurar OK (ou apertar Menu) num app → Mover: setas levam o app, OK solta. A ordem fica guardada.
 * Sem nada rodando por trás: só existe enquanto está na tela.
 */
public class HomeActivity extends Activity {
    private static final int BG = Color.BLACK;
    private float k = 1f; // escala: o desenho foi feito para 1920 px de largura
    private LinearLayout row;
    private HorizontalScrollView scroll;
    private TextView clock, hint;
    private View gear;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int moving = -1;        // índice do app sendo movido (-1 = nenhum)
    private String lastFocusPkg = null;
    private boolean startFresh = true; // TV ligou / voltou de um app / casinha: seletor no 1º app

    static final class AppItem {
        String pkg, label; Drawable banner, icon; Intent launch;
    }
    private final List<AppItem> items = new ArrayList<>();
    private static List<AppItem> cache = null; // volta para a tela inicial na hora (sem recarregar)

    private int px(float designPx) { return Math.round(designPx * k); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(BG));
        w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        if (k <= 0) k = 1f;
        setContentView(build());
        if (cache != null) { items.clear(); items.addAll(cache); fillRow(); }
        load();
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED); pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED); pf.addDataScheme("package");
        try { registerReceiver(pkgReceiver, pf); } catch (Throwable ignored) {}
        Launcher.homeShown(this);
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override protected void onStart() {
        super.onStart();
        // onStart = a tela inicial voltou a aparecer de verdade (ligou a TV, saiu de um app,
        // acordou do descanso). Janelinhas por cima (mensagem, permissão) não passam por aqui.
        startFresh = true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (startFresh) {
            startFresh = false;
            lastFocusPkg = null;
            if (scroll != null) scroll.scrollTo(0, 0);
        }
        tick();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED); tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        try { registerReceiver(timeReceiver, tf); } catch (Throwable ignored) {}
        if (moving >= 0) endMove();
        focusLast();
    }

    @Override protected void onPause() {
        try { unregisterReceiver(timeReceiver); } catch (Throwable ignored) {}
        super.onPause();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (hint != null && moving < 0) hint.setText(baseHint());
        // apertou a casinha estando aqui: volta para o começo da fileira
        if (moving >= 0) endMove();
        lastFocusPkg = null;
        if (scroll != null) scroll.smoothScrollTo(0, 0);
        focusLast();
    }

    private boolean preview() { return getIntent() != null && getIntent().getBooleanExtra("preview", false) && !Launcher.on(this); }

    @Override public void onBackPressed() {
        if (moving >= 0) { endMove(); return; }
        if (preview()) finish(); // prévia: Voltar sai. Como tela inicial de verdade, Voltar não sai.
    }

    // ---------------- montagem da tela ----------------
    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setClipChildren(false);

        // topo: "Apps" à esquerda; engrenagem e horário à direita
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView apps = txt("Apps", 30, Color.WHITE, false);
        apps.setSingleLine(true);
        tab.addView(apps, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        GradientDrawable ld = new GradientDrawable(); ld.setColor(Color.WHITE); ld.setCornerRadius(px(2));
        line.setBackground(ld);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(px(36), px(4)); llp.topMargin = px(8);
        tab.addView(line, llp);
        top.addView(tab, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        gear = new GearView(this);
        gear.setId(View.generateViewId());
        gear.setFocusable(true);
        gear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openSettings(); } });
        gear.setOnFocusChangeListener(new View.OnFocusChangeListener() { public void onFocusChange(View v, boolean f) { v.invalidate(); } });
        top.addView(gear, new LinearLayout.LayoutParams(px(60), px(60)));
        clock = txt("", 40, Color.WHITE, false);
        clock.setSingleLine(true);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = px(36);
        top.addView(clock, clp);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(80), Gravity.TOP);
        tlp.leftMargin = px(110); tlp.rightMargin = px(108); tlp.topMargin = px(44);
        root.addView(top, tlp);

        // fileira de apps (rola para a direita)
        scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setFillViewport(false);
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(110), px(20), px(110), px(20));
        scroll.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(108 + 40), Gravity.TOP);
        slp.topMargin = px(464 - 20);
        root.addView(scroll, slp);

        hint = txt(baseHint(), 22, Color.rgb(120, 120, 120), false);
        hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        hlp.bottomMargin = px(64);
        root.addView(hint, hlp);
        return root;
    }

    private String baseHint() {
        return preview() ? "Prévia da tela inicial TechSolutions  ·  Voltar para sair  ·  Segure OK em um app para mudar de lugar"
                         : "Segure OK em um app para mudar de lugar";
    }

    private TextView txt(String s, float designPx, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(designPx));
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    private void tick() {
        if (clock == null) return;
        String f = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        clock.setText(DateFormat.format(f, new Date()));
    }

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { tick(); }
    };
    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { h.removeCallbacks(reload); h.postDelayed(reload, 800); }
    };
    private final Runnable reload = new Runnable() { public void run() { load(); } };

    // ---------------- lista de apps ----------------
    private void load() {
        final Context app = getApplicationContext();
        new Thread(new Runnable() { public void run() {
            final List<AppItem> list;
            try { list = query(app); } catch (Throwable e) { Diag.save("launcher: lista", e); return; }
            h.post(new Runnable() { public void run() {
                if (isFinishing() || moving >= 0) return;
                cache = list;
                items.clear(); items.addAll(list);
                fillRow();
                focusLast();
            }});
        }}, "home-load").start();
    }

    /** Todos os apps que abrem na TV, na ordem guardada; apps novos vão para o fim. */
    static List<AppItem> query(Context c) { return query(c, false); }
    /** Para o painel: inclui os escondidos. */
    static List<AppItem> queryAll(Context c) { return query(c, true); }
    static void clearCache() { cache = null; }
    void reloadFromPanel() { if (moving >= 0) return; load(); }

    private static List<AppItem> query(Context c, boolean all) {
        PackageManager pm = c.getPackageManager();
        Map<String, ResolveInfo> found = new HashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> l = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
            for (ResolveInfo r : l) {
                if (r.activityInfo == null) continue;
                String p = r.activityInfo.packageName;
                if (!found.containsKey(p)) found.put(p, r); // a de TV (leanback) vem primeiro e tem preferência
            }
        }
        java.util.Set<String> hide = Launcher.hidden(c);
        List<String> order = Launcher.order(c);
        final String pin = Launcher.pinned(c);
        List<AppItem> out = new ArrayList<>();
        List<AppItem> fresh = new ArrayList<>();
        Map<String, AppItem> byPkg = new HashMap<>();
        for (Map.Entry<String, ResolveInfo> e : found.entrySet()) {
            String p = e.getKey();
            if (!all && hide.contains(p) && !p.equals(pin)) continue;
            ResolveInfo r = e.getValue();
            ActivityInfo ai = r.activityInfo;
            AppItem it = new AppItem();
            it.pkg = p;
            try { it.label = String.valueOf(r.loadLabel(pm)); } catch (Throwable t) { it.label = p; }
            try { it.banner = ai.loadBanner(pm); } catch (Throwable ignored) {}
            if (it.banner == null) try { it.banner = ai.applicationInfo.loadBanner(pm); } catch (Throwable ignored) {}
            if (it.banner == null) try { it.icon = r.loadIcon(pm); } catch (Throwable ignored) {}
            Intent li = null;
            try { li = pm.getLeanbackLaunchIntentForPackage(p); } catch (Throwable ignored) {}
            if (li == null) try { li = pm.getLaunchIntentForPackage(p); } catch (Throwable ignored) {}
            if (li == null) li = new Intent(Intent.ACTION_MAIN).setClassName(p, ai.name);
            it.launch = li;
            byPkg.put(p, it);
        }
        for (String p : order) { AppItem it = byPkg.remove(p); if (it != null) out.add(it); }
        // novos: em ordem alfabética, no fim da fileira
        fresh.addAll(byPkg.values());
        final Context cx = c;
        java.util.Collections.sort(fresh, new java.util.Comparator<AppItem>() {
            public int compare(AppItem a, AppItem b) {
                int ra = rank(cx, a.pkg), rb = rank(cx, b.pkg);
                return ra != rb ? ra - rb : a.label.compareToIgnoreCase(b.label);
            }
        });
        out.addAll(fresh);
        // app fixado pelo suporte: sempre o 1º da fileira
        if (!pin.isEmpty()) for (int i = 0; i < out.size(); i++) {
            if (out.get(i).pkg.equals(pin)) { if (i > 0) out.add(0, out.remove(i)); break; }
        }
        if (!fresh.isEmpty() && !all) Launcher.saveOrder(c, pkgs(out));
        return out;
    }

    private static final String[] STREAMING = {"youtube", "netflix", "amazon", "primevideo", "disney", "globo", "hbo", "max", "spotify",
            "paramount", "star", "pluto", "twitch", "deezer", "crunchyroll", "vix", "kodi", "smarters", "iptv", "xciptv", "stb", "tivimate"};
    /** Ordem inicial: o que o cliente instalou, depois streaming conhecido, depois loja, ferramentas e o TechSolutions. */
    static int rank(Context c, String pkg) {
        if (pkg.equals(c.getPackageName())) return 4;
        if (pkg.equals("com.android.vending")) return 2;
        boolean sys = false;
        try { sys = (c.getPackageManager().getApplicationInfo(pkg, 0).flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0; } catch (Throwable ignored) {}
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        for (String k : STREAMING) if (p.contains(k)) return sys ? 1 : 0;
        return sys ? 3 : 0;
    }

    static List<String> pkgs(List<AppItem> l) { List<String> s = new ArrayList<>(); for (AppItem i : l) s.add(i.pkg); return s; }

    private void fillRow() {
        row.removeAllViews();
        for (int i = 0; i < items.size(); i++) row.addView(tile(items.get(i)));
        if (items.isEmpty()) {
            TextView t = txt("Carregando os apps…", 26, Color.GRAY, false);
            row.addView(t);
        }
        if (gear != null && row.getChildCount() > 0) gear.setNextFocusDownId(View.NO_ID);
    }

    private View tile(final AppItem it) {
        final FrameLayout f = new FrameLayout(this);
        f.setFocusable(true);
        f.setClickable(true);
        f.setLongClickable(true);
        f.setTag(it.pkg);
        final float r = px(10);
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        f.setClipToOutline(true);
        if (it.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(it.banner);
            f.setBackgroundColor(Color.rgb(40, 40, 40));
            f.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            // app sem banner de TV: cartão com ícone + nome (como o Google TV faz)
            f.setBackgroundColor(Color.rgb(233, 233, 233));
            LinearLayout in = new LinearLayout(this);
            in.setOrientation(LinearLayout.HORIZONTAL);
            in.setGravity(Gravity.CENTER);
            in.setPadding(px(10), 0, px(10), 0);
            if (it.icon != null) {
                ImageView ic = new ImageView(this);
                ic.setImageDrawable(it.icon);
                ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(px(40), px(40));
                ip.rightMargin = px(8);
                in.addView(ic, ip);
            }
            TextView name = txt(it.label, 19, Color.rgb(29, 29, 29), true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            in.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            f.addView(in, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        f.setForeground(dim());
        f.setContentDescription(it.label);
        f.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (moving >= 0) { endMove(); return; }
            open(it);
        }});
        f.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) {
            if (moving < 0) menu(it, v);
            return true;
        }});
        f.setOnKeyListener(new View.OnKeyListener() { public boolean onKey(View v, int code, KeyEvent e) {
            if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && moving < 0) { menu(it, v); return true; }
            return false;
        }});
        f.setOnFocusChangeListener(new View.OnFocusChangeListener() { public void onFocusChange(View v, boolean has) {
            if (has) lastFocusPkg = it.pkg;
            style(v, has);
        }});
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(192), px(108));
        lp.rightMargin = px(24);
        f.setLayoutParams(lp);
        return f;
    }

    private Drawable dim() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.argb(105, 0, 0, 0));
        d.setCornerRadius(px(10));
        return d;
    }
    private Drawable ring(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setStroke(px(3), color);
        d.setCornerRadius(px(10));
        return d;
    }

    private void style(View v, boolean focused) {
        boolean mv = moving >= 0 && focused;
        ((FrameLayout) v).setForeground(focused ? ring(mv ? Ui.ORANGE : Color.WHITE) : dim());
        float s = focused ? 1.1f : 1f;
        v.animate().scaleX(s).scaleY(s).setDuration(140).start();
        v.setTranslationZ(focused ? px(12) : 0);
    }

    private void focusLast() {
        if (row == null || row.getChildCount() == 0) return;
        View target = null;
        if (lastFocusPkg != null) for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (lastFocusPkg.equals(c.getTag())) { target = c; break; }
        }
        if (target == null) target = row.getChildAt(0);
        if (!target.isFocusable()) return;
        final View t = target;
        h.post(new Runnable() { public void run() { t.requestFocus(); } });
    }

    private void open(AppItem it) {
        try {
            Intent i = new Intent(it.launch);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Throwable e) {
            android.widget.Toast.makeText(this, "Não consegui abrir " + it.label, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettings() {
        try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Throwable e) { android.widget.Toast.makeText(this, "Configurações indisponíveis", android.widget.Toast.LENGTH_SHORT).show(); }
    }

    // ---------------- mudar a ordem ----------------
    private boolean isPinned(AppItem it) { return it != null && it.pkg.equals(Launcher.pinned(this)); }

    private void menu(final AppItem it, final View v) {
        final boolean fixo = isPinned(it);
        final String[] opts = fixo ? new String[]{"Abrir", "Cancelar"} : new String[]{"Mover", "Abrir", "Cancelar"};
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(fixo ? it.label + "  (fixado em 1º pelo suporte)" : it.label)
                .setItems(opts, new DialogInterface.OnClickListener() { public void onClick(DialogInterface di, int w) {
                    if (fixo) { if (w == 0) open(it); return; }
                    if (w == 0) startMove(it);
                    else if (w == 1) open(it);
                }}).create();
        d.show();
    }

    private void startMove(AppItem it) {
        if (isPinned(it)) return;
        moving = items.indexOf(it);
        if (moving < 0) return;
        hint.setText("Movendo " + it.label + "  ·  use as setas para levar até onde quiser  ·  OK para soltar");
        hint.setTextColor(Color.WHITE);
        View v = row.getChildAt(moving);
        v.requestFocus();
        style(v, true);
    }

    private void endMove() {
        if (moving < 0) return;
        moving = -1;
        Launcher.saveOrder(this, pkgs(items));
        cache = new ArrayList<>(items);
        hint.setText(baseHint());
        hint.setTextColor(Color.rgb(120, 120, 120));
        View f = getCurrentFocus();
        if (f != null && f.getParent() == row) style(f, true);
        Launcher.orderChanged(this);
    }

    private void moveBy(int d) {
        int to = moving + d;
        if (to < 0 || to >= items.size()) return;
        if (to == 0 && !items.isEmpty() && isPinned(items.get(0))) return; // o 1º lugar é do app fixado
        AppItem it = items.remove(moving);
        items.add(to, it);
        View v = row.getChildAt(moving);
        row.removeViewAt(moving);
        row.addView(v, to);
        moving = to;
        v.requestFocus();
        style(v, true);
        final View vv = v;
        h.post(new Runnable() { public void run() {
            // mantém o app movido à vista
            int left = vv.getLeft() - px(110), right = vv.getRight() + px(110);
            int sx = scroll.getScrollX(), w = scroll.getWidth();
            if (left < sx) scroll.smoothScrollTo(left, 0);
            else if (right > sx + w) scroll.smoothScrollTo(right - w, 0);
        }});
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (moving >= 0) {
            int c = e.getKeyCode();
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (c == KeyEvent.KEYCODE_DPAD_LEFT) { moveBy(-1); return true; }
                if (c == KeyEvent.KEYCODE_DPAD_RIGHT) { moveBy(1); return true; }
                if (c == KeyEvent.KEYCODE_DPAD_UP || c == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            }
            if (c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_BACK
                    || c == KeyEvent.KEYCODE_NUMPAD_ENTER || c == KeyEvent.KEYCODE_BUTTON_A) {
                if (e.getAction() == KeyEvent.ACTION_UP) endMove();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** Engrenagem desenhada (sem depender de imagem): branca; com foco, círculo branco e engrenagem preta. */
    static final class GearView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        GearView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), hh = getHeight(), cx = w / 2f, cy = hh / 2f, s = Math.min(w, hh);
            boolean f = isFocused();
            if (f) { p.setStyle(Paint.Style.FILL); p.setColor(Color.WHITE); cv.drawCircle(cx, cy, s / 2f, p); }
            int col = f ? Color.BLACK : Color.WHITE;
            float ro = s * 0.30f, ri = s * 0.115f, tooth = s * 0.085f, tw = s * 0.11f;
            p.setColor(col); p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 8; i++) {
                cv.save(); cv.rotate(i * 45f, cx, cy);
                cv.drawRoundRect(cx - tw / 2f, cy - ro - tooth, cx + tw / 2f, cy - ro + tooth, tw * 0.25f, tw * 0.25f, p);
                cv.restore();
            }
            Path ring = new Path();
            ring.setFillType(Path.FillType.EVEN_ODD);
            ring.addCircle(cx, cy, ro, Path.Direction.CW);
            ring.addCircle(cx, cy, ri, Path.Direction.CW);
            cv.drawPath(ring, p);
        }
    }
}
