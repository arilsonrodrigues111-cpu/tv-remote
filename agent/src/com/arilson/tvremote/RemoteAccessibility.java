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
    private final Handler h = new Handler(Looper.getMainLooper());

    // Textos de botões que podem ser clicados automaticamente (PT/EN/ES)
    private static final String[] ACCEPT = {
            "instalar", "install", "atualizar", "update", "reinstalar",
            "iniciar agora", "começar agora", "start now", "comenzar ahora", "empezar ahora",
            "permitir", "allow", "ok", "aceitar", "accept", "desinstalar", "uninstall",
            "concluído", "concluido", "done", "listo", "continuar", "continue",
            "compartilhar tela", "share screen", "tela inteira", "entire screen"
    };
    private static final String[] CHECKBOX = { "não mostrar novamente", "nao mostrar novamente", "don't show again", "no volver a mostrar" };
    private static final String[] ACCEPT_IDS = { "ok_button", "button1", "allow_button", "done_button", "install_confirm_question" };

    static boolean enabled() { return instance != null; }

    static void autoAccept(long ms) {
        autoAcceptUntil = System.currentTimeMillis() + ms;
        final RemoteAccessibility s = instance;
        if (s != null) s.scheduleScan(0);
    }

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
        if (System.currentTimeMillis() < autoAcceptUntil) scheduleScan(150);
    }

    private boolean scanPending = false;
    private void scheduleScan(long delay) {
        if (scanPending) return;
        scanPending = true;
        h.postDelayed(new Runnable() { public void run() {
            scanPending = false;
            if (System.currentTimeMillis() >= autoAcceptUntil) return;
            boolean clicked = scanAndAccept();
            // continua tentando (botão "Instalar" costuma ficar desabilitado por ~1s)
            scheduleScan(clicked ? 1200 : 700);
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
            if (!(p.contains("packageinstaller") || p.contains("systemui") || p.contains("permissioncontroller")
                    || p.equals("android") || p.contains("mediaprojection"))) continue;
            if (acceptIn(r)) return true;
        }
        return false;
    }

    private boolean acceptIn(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collect(root, all, 0);
        // marca "não mostrar novamente" (Android < 10 lembra o consentimento)
        for (AccessibilityNodeInfo n : all) {
            if (n.isCheckable() && !n.isChecked() && matches(n, CHECKBOX)) n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        for (AccessibilityNodeInfo n : all) {
            if (!n.isEnabled() || !n.isVisibleToUser()) continue;
            boolean byText = matches(n, ACCEPT);
            String vid = n.getViewIdResourceName();
            boolean byId = false;
            if (vid != null) for (String id : ACCEPT_IDS) if (vid.endsWith("/" + id)) byId = true;
            if (!(byText || byId)) continue;
            AccessibilityNodeInfo target = n;
            while (target != null && !target.isClickable()) target = target.getParent();
            if (target == null) continue;
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
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
}
