package com.arilson.tvremote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Injeta toques/gestos, navegação D-pad e confirma diálogos (instalar, captura de tela). */
public class RemoteAccessibility extends AccessibilityService {
    static volatile RemoteAccessibility instance;
    static volatile long autoAcceptUntil = 0;
    static volatile long autoAcceptNotBefore = 0;
    static volatile boolean projectionMode = false;
    static volatile boolean debugMode = false;
    private final Handler h = new Handler(Looper.getMainLooper());

    // Botões do instalador de pacotes (instalar/desinstalar/concluir)
    private static final String[] INSTALL_WORDS = {
            "instalar", "install", "atualizar", "update", "reinstalar", "desinstalar", "uninstall",
            "ok", "concluído", "concluido", "done", "listo", "aceptar"
    };
    // Botão positivo do diálogo de captura de tela (nunca o seletor "tela inteira/um app")
    private static final String[] PROJECTION_WORDS = {
            "iniciar agora", "começar agora", "comecar agora", "start now", "comenzar ahora", "empezar ahora",
            "iniciar", "começar", "start", "compartilhar", "share", "permitir", "allow"
    };
    // Diálogo "Permitir depuração USB?" (ADB local)
    private static final String[] DEBUG_WORDS = { "permitir", "allow", "ok", "sim", "yes", "aceptar", "permitir sempre" };
    private static final String[] DEBUG_CHECK = { "sempre permitir", "always allow", "permitir siempre" };
    private static final String[] CHECKBOX = { "não mostrar novamente", "nao mostrar novamente", "don't show again", "no volver a mostrar" };

    static boolean enabled() { return instance != null; }

    /** Instalação/desinstalação: aceita os botões do instalador. */
    static void autoAccept(long ms) {
        projectionMode = false; debugMode = false;
        autoAcceptNotBefore = System.currentTimeMillis() + 600;
        autoAcceptUntil = System.currentTimeMillis() + ms;
        final RemoteAccessibility s = instance;
        if (s != null) s.scheduleScan(700);
    }

    /** Captura de tela: espera o diálogo estabilizar e clica só no botão positivo. */
    static void autoAcceptProjection(long ms, long delayMs) {
        projectionMode = true; debugMode = false;
        autoAcceptNotBefore = System.currentTimeMillis() + delayMs;
        autoAcceptUntil = System.currentTimeMillis() + ms;
        final RemoteAccessibility s = instance;
        if (s != null) s.scheduleScan(delayMs + 100);
    }

    /** Diálogo de autorização do ADB: marca "sempre permitir" e confirma. */
    static void autoAcceptDebug(long ms) {
        projectionMode = false; debugMode = true;
        autoAcceptNotBefore = System.currentTimeMillis() + 800;
        autoAcceptUntil = System.currentTimeMillis() + ms;
        final RemoteAccessibility s = instance;
        if (s != null) s.scheduleScan(900);
    }

    static void cancelAutoAccept() { autoAcceptUntil = 0; projectionMode = false; debugMode = false; }

    /** Abre uma Activity usando o contexto da acessibilidade (isento do bloqueio de background do Android 10+). */
    static void startActivityAny(Context fallback, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (instance != null) instance.startActivity(i); else fallback.startActivity(i);
        } catch (Exception e) {
            try { fallback.startActivity(i); } catch (Exception e2) { AgentService.status("error", "Não consegui abrir: " + e2.getMessage()); }
        }
    }

    @Override protected void onServiceConnected() {
        instance = this;
        AgentService.start(this);
        AgentService.sendInfoIfConnected();
    }

    @Override public void onDestroy() { instance = null; super.onDestroy(); }
    @Override public boolean onUnbind(Intent intent) { instance = null; return super.onUnbind(intent); }
    @Override public void onInterrupt() {}

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (System.currentTimeMillis() < autoAcceptUntil) scheduleScan(300);
        int t = e.getEventType();
        if (t == AccessibilityEvent.TYPE_VIEW_FOCUSED || t == AccessibilityEvent.TYPE_VIEW_SELECTED
                || t == AccessibilityEvent.TYPE_VIEW_SCROLLED || t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            h.removeCallbacks(focusPush); h.postDelayed(focusPush, 140);
        }
    }

    private final Runnable focusPush = new Runnable() { public void run() { AgentService.focusMoved(); } };

    private boolean scanPending = false;
    private void scheduleScan(long delay) {
        if (scanPending) return;
        scanPending = true;
        h.postDelayed(new Runnable() { public void run() {
            scanPending = false;
            long now = System.currentTimeMillis();
            if (now >= autoAcceptUntil) return;
            if (now < autoAcceptNotBefore) { scheduleScan(autoAcceptNotBefore - now + 50); return; }
            boolean clicked = scanAndAccept();
            if (clicked && (projectionMode || debugMode)) { cancelAutoAccept(); return; } // um clique só no diálogo de captura
            scheduleScan(clicked ? 1500 : 700);
        }}, delay);
    }

    private boolean scanAndAccept() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) { AccessibilityNodeInfo r = w.getRoot(); if (r != null) roots.add(r); }
        } catch (Exception ignored) {}
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) roots.add(active);
        for (AccessibilityNodeInfo r : roots) {
            CharSequence pkg = r.getPackageName();
            if (pkg == null) continue;
            String p = pkg.toString();
            if (p.equals(getPackageName())) continue;
            boolean installer = p.contains("packageinstaller") || p.contains("permissioncontroller");
            boolean system = p.contains("systemui") || p.equals("android") || p.contains("mediaprojection");
            if (debugMode) {
                if (!(system || p.contains("settings"))) continue;
                if (acceptIn(r, DEBUG_WORDS)) return true;
                continue;
            }
            if (projectionMode ? !system : !installer) continue;
            if (acceptIn(r, projectionMode ? PROJECTION_WORDS : INSTALL_WORDS)) return true;
        }
        return false;
    }

    private boolean acceptIn(AccessibilityNodeInfo root, String[] words) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collect(root, all, 0);
        if (debugMode) {
            boolean isDebugDialog = false;
            for (AccessibilityNodeInfo n : all) {
                CharSequence t = n.getText();
                if (t == null) continue;
                String x = t.toString().toLowerCase(Locale.ROOT);
                if (x.contains("depura") || x.contains("debug") || x.contains("rsa")) { isDebugDialog = true; break; }
            }
            if (!isDebugDialog) return false;
            for (AccessibilityNodeInfo n : all) {
                if (!n.isCheckable() || n.isChecked()) continue;
                CharSequence t = n.getText();
                String x = t == null ? "" : t.toString().toLowerCase(Locale.ROOT);
                for (String w : DEBUG_CHECK) if (x.contains(w)) { n.performAction(AccessibilityNodeInfo.ACTION_CLICK); break; }
            }
        }
        if (projectionMode) {
            // marca "não mostrar novamente" (Android < 10 lembra o consentimento)
            for (AccessibilityNodeInfo n : all)
                if (n.isCheckable() && !n.isChecked() && matches(n, CHECKBOX)) n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        // 1º: botão positivo padrão (android:id/button1) com texto conhecido; 2º: qualquer botão com texto conhecido
        for (int pass = 0; pass < 2; pass++) {
            for (AccessibilityNodeInfo n : all) {
                if (!n.isEnabled() || !n.isVisibleToUser() || n.isCheckable()) continue;
                String vid = n.getViewIdResourceName();
                boolean positiveId = vid != null && (vid.endsWith("/button1") || vid.endsWith("/ok_button") || vid.endsWith("/done_button"));
                if (pass == 0 && !positiveId) continue;
                if (!matches(n, words)) continue;
                String cls = n.getClassName() == null ? "" : n.getClassName().toString();
                if (cls.contains("Spinner")) continue;
                AccessibilityNodeInfo target = n;
                for (int up = 0; up < 2 && target != null && !target.isClickable(); up++) target = target.getParent();
                if (target == null || !target.isClickable()) continue;
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
        }
        return false;
    }

    private static boolean matches(AccessibilityNodeInfo n, String[] words) {
        CharSequence t = n.getText();
        if (t == null) t = n.getContentDescription();
        if (t == null) return false;
        String s = t.toString().trim().toLowerCase(Locale.ROOT);
        for (String w : words) if (s.equals(w)) return true;
        return false;
    }

    private static void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
    }

    // ---------------- comandos ----------------
    void tap(float x, float y, long ms) {
        Path p = new Path(); p.moveTo(x, y);
        dispatch(p, ms);
    }
    void swipe(float x1, float y1, float x2, float y2, long ms) {
        Path p = new Path(); p.moveTo(x1, y1); p.lineTo(x2, y2);
        dispatch(p, ms);
    }
    private void dispatch(Path p, long ms) {
        try {
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, ms))).build();
            dispatchGesture(g, null, null);
        } catch (Exception e) { AgentService.status("error", "Gesto falhou: " + e.getMessage()); }
    }
    boolean global(int action) { return performGlobalAction(action); }

    boolean setText(String text) {
        AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null || !f.isEditable()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            f = firstEditable(root, 0);
        }
        if (f == null) return false;
        CharSequence cur = f.getText();
        String hint = f.getHintText() != null ? f.getHintText().toString() : null;
        String base = (cur == null || cur.toString().equals(hint)) ? "" : cur.toString();
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, base + text);
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }
    private AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo n, int d) {
        if (n == null || d > 40) return null;
        if (n.isEditable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) { AccessibilityNodeInfo r = firstEditable(n.getChild(i), d + 1); if (r != null) return r; }
        return null;
    }

    /** OK/centro: clica no elemento focado; se não houver, toca no centro da tela. */
    void clickFocused(int sw, int sh) {
        AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) f = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (f != null) {
            AccessibilityNodeInfo t = f;
            while (t != null && !t.isClickable()) t = t.getParent();
            if (t != null && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            Rect r = new Rect(); f.getBoundsInScreen(r);
            if (!r.isEmpty()) { tap(r.exactCenterX(), r.exactCenterY(), 50); return; }
        }
        tap(sw / 2f, sh / 2f, 50);
    }

    /** Navegação D-pad: focusSearch nativo, com fallback geométrico. */
    void dpad(int dir) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        List<AccessibilityNodeInfo> cand = new ArrayList<>();
        collectFocusable(root, cand, 0);
        if (f == null) {
            if (!cand.isEmpty()) cand.get(0).performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return;
        }
        AccessibilityNodeInfo next = null;
        try { next = f.focusSearch(dir); } catch (Exception ignored) {}
        if (next != null && !next.equals(f) && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return;

        Rect cur = new Rect(); f.getBoundsInScreen(cur);
        AccessibilityNodeInfo best = null; double bestScore = Double.MAX_VALUE;
        for (AccessibilityNodeInfo n : cand) {
            if (n.equals(f)) continue;
            Rect r = new Rect(); n.getBoundsInScreen(r);
            double dx = r.exactCenterX() - cur.exactCenterX(), dy = r.exactCenterY() - cur.exactCenterY();
            double main, cross;
            switch (dir) {
                case View.FOCUS_UP: main = -dy; cross = Math.abs(dx); break;
                case View.FOCUS_DOWN: main = dy; cross = Math.abs(dx); break;
                case View.FOCUS_LEFT: main = -dx; cross = Math.abs(dy); break;
                default: main = dx; cross = Math.abs(dy); break;
            }
            if (main <= 1) continue;
            double score = main + cross * 2.5;
            if (score < bestScore) { bestScore = score; best = n; }
        }
        if (best != null) {
            best.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        } else {
            // sem alvo: rola o container na direção
            AccessibilityNodeInfo t = f;
            int act = (dir == View.FOCUS_UP || dir == View.FOCUS_LEFT)
                    ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            while (t != null && !t.isScrollable()) t = t.getParent();
            if (t != null) t.performAction(act);
        }
    }
    private void collectFocusable(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int d) {
        if (n == null || d > 40) return;
        if (n.isFocusable() && n.isVisibleToUser() && n.isEnabled()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collectFocusable(n.getChild(i), out, d + 1);
    }

    /** App em primeiro plano (pacote) visto pela acessibilidade. */
    String foregroundPkg() {
        AccessibilityNodeInfo a = getRootInActiveWindow();
        return a != null && a.getPackageName() != null ? a.getPackageName().toString() : "";
    }

    /**
     * Lê o layout de todas as janelas (funciona mesmo em apps que bloqueiam a imagem).
     * Cada item: [x, y, w, h, texto, flags, tipo, valor] com coordenadas em milésimos da tela.
     * flags: 1 focado, 2 clicável/focável, 4 editável, 8 marcado, 16 selecionado, 32 texto é dica, 64 marcável, 128 desativado, 256 rolável
     * tipo: 0 container, 1 texto, 2 botão, 3 imagem, 4 campo, 5 checkbox, 6 chave, 7 progresso, 8 lista, 9 barra deslizante, 10 rádio
     */
    org.json.JSONArray tree(int sw, int sh) {
        org.json.JSONArray out = new org.json.JSONArray();
        List<AccessibilityWindowInfo> wins = new ArrayList<>();
        try { wins.addAll(getWindows()); } catch (Exception ignored) {}
        java.util.Collections.sort(wins, new java.util.Comparator<AccessibilityWindowInfo>() {
            public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) { return a.getLayer() - b.getLayer(); }
        });
        boolean any = false;
        extraBudget = 120; // no máximo 120 perguntas novas ao app por atualização (o resto vem do cache)
        for (AccessibilityWindowInfo w : wins) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null) continue;
            CharSequence pk = r.getPackageName();
            if (pk != null && pk.toString().equals(getPackageName())) continue;
            walkTree(r, out, sw, sh, 0); any = true;
        }
        if (!any) { AccessibilityNodeInfo a = getRootInActiveWindow(); if (a != null) walkTree(a, out, sw, sh, 0); }
        return out;
    }

    /**
     * Onde o seletor está de verdade: pergunta ao sistema (sem cache) o foco de entrada atual.
     * Se o foco estiver num container grande (lista), usa o filho selecionado/destacado dentro dele.
     */
    int[] realFocus(int sw, int sh) {
        AccessibilityNodeInfo f = null;
        try { f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT); } catch (Exception ignored) {}
        if (f != null) { try { f.refresh(); } catch (Exception ignored) {} }
        if (f == null || !f.isVisibleToUser()) {
            try { f = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY); } catch (Exception ignored) {}
        }
        if (f == null) return null;
        Rect r = new Rect(); f.getBoundsInScreen(r);
        long area = (long) r.width() * r.height();
        if (f.getChildCount() > 0 && area > (long) sw * sh / 6) {
            AccessibilityNodeInfo c = findSelected(f, 0);
            if (c != null) { Rect cr = new Rect(); c.getBoundsInScreen(cr); if (!cr.isEmpty()) r = cr; }
        }
        if (!r.intersect(0, 0, sw, sh) || r.width() < 2 || r.height() < 2 || sw <= 0 || sh <= 0) return null;
        return new int[]{ Math.round(r.left * 1000f / sw), Math.round(r.top * 1000f / sh),
                Math.round(r.width() * 1000f / sw), Math.round(r.height() * 1000f / sh) };
    }
    private AccessibilityNodeInfo findSelected(AccessibilityNodeInfo n, int d) {
        if (n == null || d > 30) return null;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            try { c.refresh(); } catch (Exception ignored) {}
            if (c.isVisibleToUser() && (c.isFocused() || c.isSelected() || c.isAccessibilityFocused())) {
                AccessibilityNodeInfo deeper = c.getChildCount() > 0 ? findSelected(c, d + 1) : null;
                Rect a = new Rect(); c.getBoundsInScreen(a);
                if (deeper != null) { Rect b = new Rect(); deeper.getBoundsInScreen(b); if ((long) b.width() * b.height() * 3 >= (long) a.width() * a.height()) return deeper; }
                return c;
            }
            AccessibilityNodeInfo r = findSelected(c, d + 1);
            if (r != null) return r;
        }
        return null;
    }

    private static int typeOf(AccessibilityNodeInfo n) {
        String c = n.getClassName() == null ? "" : n.getClassName().toString();
        String sc = c.substring(c.lastIndexOf('.') + 1);
        if (n.isEditable() || sc.contains("EditText")) return 4;
        if (sc.contains("Switch") || sc.contains("Toggle")) return 6;
        if (sc.contains("RadioButton")) return 10;
        if (sc.contains("CheckBox") || sc.contains("CheckedTextView")) return 5;
        if (sc.contains("SeekBar") || sc.contains("Slider")) return 9;
        if (sc.contains("ProgressBar") || sc.contains("RatingBar")) return 7;
        if (sc.contains("Image")) return sc.contains("Button") ? 2 : 3;
        if (sc.contains("Button")) return 2;
        if (sc.contains("RecyclerView") || sc.contains("ListView") || sc.contains("GridView") || sc.contains("ScrollView") || sc.contains("Pager")) return 8;
        if (sc.contains("TextView") || sc.equals("Text")) return 1;
        return 0;
    }

    // cache das linhas de texto (posição exata de cada linha), para não perguntar de novo ao app a cada segundo
    private final java.util.HashMap<String, org.json.JSONArray> lineCache = new java.util.HashMap<>();
    private int extraBudget = 0;

    /**
     * Posição real de cada linha do texto na tela (Android 8+), e tamanho real da fonte (Android 11+).
     * Retorna [textSizePx, [[x,y,w,h,ini,fim], ...]] em pixels da tela, ou null se o app não informar.
     */
    private org.json.JSONArray textLayout(AccessibilityNodeInfo n, String text, Rect bounds) {
        if (android.os.Build.VERSION.SDK_INT < 26 || text.length() == 0) return null;
        String key = n.getViewIdResourceName() + "|" + bounds.toShortString() + "|" + text;
        if (lineCache.containsKey(key)) return lineCache.get(key);
        if (extraBudget-- <= 0) return null;
        org.json.JSONArray res = null;
        try {
            List<String> avail = n.getAvailableExtraData();
            float ts = -1;
            if (android.os.Build.VERSION.SDK_INT >= 30 && avail != null && avail.contains(AccessibilityNodeInfo.EXTRA_DATA_RENDERING_INFO_KEY)) {
                n.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_RENDERING_INFO_KEY, new Bundle());
                android.view.accessibility.AccessibilityNodeInfo.ExtraRenderingInfo ri = n.getExtraRenderingInfo();
                if (ri != null) ts = ri.getTextSizeInPx();
            }
            org.json.JSONArray lines = new org.json.JSONArray();
            if (avail != null && avail.contains(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)) {
                int len = Math.min(text.length(), 400);
                Bundle args = new Bundle();
                args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0);
                args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, len);
                n.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args);
                android.os.Parcelable[] arr = n.getExtras().getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY);
                if (arr != null) {
                    float lt = 0, ll = 0, lr = 0, lb = 0; int ls = -1, le = -1;
                    for (int i = 0; i < arr.length; i++) {
                        if (!(arr[i] instanceof android.graphics.RectF)) continue; // letra escondida (cortada com "...")
                        android.graphics.RectF c = (android.graphics.RectF) arr[i];
                        if (c.width() <= 0 && text.charAt(Math.min(i, text.length() - 1)) != ' ') continue;
                        boolean newLine = ls < 0 || Math.abs(c.top - lt) > (lb - lt) * 0.5f || c.left < ll - 2;
                        if (newLine) {
                            if (ls >= 0) lines.put(line(ll, lt, lr, lb, ls, le));
                            lt = c.top; lb = c.bottom; ll = c.left; lr = c.right; ls = i; le = i + 1;
                        } else {
                            lr = Math.max(lr, c.right); lb = Math.max(lb, c.bottom); lt = Math.min(lt, c.top); le = i + 1;
                        }
                    }
                    if (ls >= 0) lines.put(line(ll, lt, lr, lb, ls, le));
                }
            }
            if (ts > 0 || lines.length() > 0) res = new org.json.JSONArray().put(Math.round(ts * 10) / 10.0).put(lines);
        } catch (Exception ignored) {}
        if (lineCache.size() > 800) lineCache.clear();
        lineCache.put(key, res);
        return res;
    }
    private static org.json.JSONArray line(float l, float t, float r, float b, int s, int e) throws org.json.JSONException {
        return new org.json.JSONArray().put(Math.round(l)).put(Math.round(t)).put(Math.round(r - l)).put(Math.round(b - t)).put(s).put(e);
    }

    private void walkTree(AccessibilityNodeInfo n, org.json.JSONArray out, int sw, int sh, int d) {
        if (n == null || d > 60 || out.length() > 900) return;
        // filhos podem estar visíveis mesmo quando o pai diz que não (alguns apps): sempre desce na árvore
        if (n.isVisibleToUser()) emitNode(n, out, sw, sh);
        for (int i = 0; i < n.getChildCount(); i++) walkTree(n.getChild(i), out, sw, sh, d + 1);
    }

    private void emitNode(AccessibilityNodeInfo n, org.json.JSONArray out, int sw, int sh) {
        CharSequence real = n.getText();
        CharSequence t = real;
        boolean hint = false;
        if ((t == null || t.length() == 0) && android.os.Build.VERSION.SDK_INT >= 26 && n.getHintText() != null) { t = n.getHintText(); hint = true; }
        boolean desc = false;
        if (t == null || t.length() == 0) { t = n.getContentDescription(); desc = t != null && t.length() > 0; }
        String txt = t == null ? "" : t.toString();
        if (txt.length() > 400) txt = txt.substring(0, 400);
        int type = typeOf(n);
        boolean click = n.isClickable() || n.isFocusable() || n.isLongClickable();
        if (type == 0 && click && txt.trim().length() > 0 && n.getChildCount() == 0) type = 2;
        int flags = (click ? 2 : 0) | (n.isEditable() ? 4 : 0) | (n.isChecked() ? 8 : 0)
                | (n.isSelected() ? 16 : 0) | (hint ? 32 : 0) | (n.isCheckable() ? 64 : 0) | (n.isEnabled() ? 0 : 128)
                | (n.isScrollable() ? 256 : 0) | (desc ? 512 : 0);
        if (n.isCheckable() && type < 5) type = 5;
        int value = -1;
        AccessibilityNodeInfo.RangeInfo ri = n.getRangeInfo();
        if (ri != null && ri.getMax() > ri.getMin()) {
            value = Math.round((ri.getCurrent() - ri.getMin()) * 1000f / (ri.getMax() - ri.getMin()));
            if (type != 9) type = 7;
        }
        boolean keep = txt.trim().length() > 0 || click || type >= 2 || n.isSelected();
        if (!keep) return;
        Rect r = new Rect(); n.getBoundsInScreen(r);
        Rect full = new Rect(r);
        if (!r.intersect(0, 0, sw, sh)) r.setEmpty();
        if (r.width() <= 2 || r.height() <= 2 || sw <= 0 || sh <= 0) return;
        String vid = n.getViewIdResourceName();
        if (vid != null) vid = vid.substring(vid.indexOf('/') + 1); else vid = "";
        org.json.JSONArray layout = (real != null && real.length() > 0 && !hint) ? textLayout(n, txt, full) : null;
        try {
            out.put(new org.json.JSONArray()
                    .put(Math.round(r.left * 1000f / sw)).put(Math.round(r.top * 1000f / sh))
                    .put(Math.round(r.width() * 1000f / sw)).put(Math.round(r.height() * 1000f / sh))
                    .put(txt).put(flags).put(type).put(value)
                    .put(layout == null ? org.json.JSONObject.NULL : layout).put(vid));
        } catch (Exception ignored) {}
    }
}
