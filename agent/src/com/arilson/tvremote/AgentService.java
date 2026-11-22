package com.arilson.tvremote;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class AgentService extends Service {
    static volatile AgentService instance;
    static volatile String connState = "desconectado";

    private final Handler main = new Handler(Looper.getMainLooper());
    private OkHttpClient http;
    private WebSocket ws;
    private boolean connected = false;
    private int retry = 0;
    private PowerManager.WakeLock wake;

    // captura
    private MediaProjection projection;
    private VirtualDisplay vdisplay;
    private ImageReader reader;
    private HandlerThread capThread;
    private Handler capHandler, treeHandler;
    private volatile boolean streamWanted = false;
    private volatile int maxSize = 960, quality = 60, fps = 10;
    private volatile long lastFrame = 0;
    private int screenW, screenH;

    static void start(Context c) {
        Intent i = new Intent(c, AgentService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) {}
    }

    static void status(String level, String msg) {
        AgentService s = instance;
        if (s == null) return;
        try { s.sendJson(new JSONObject().put("type", "status").put("level", level).put("msg", msg)); } catch (Exception ignored) {}
    }

    /** Chamado quando o ADB local conecta/cai: se auto-libera tudo e avisa o painel. */
    static void adbChanged() {
        AgentService s = instance;
        if (s == null) return;
        if (AdbShell.isReady()) {
            String me = s.getPackageName();
            AdbShell a = AdbShell.get(s);
            a.run("appops set " + me + " PROJECT_MEDIA allow");
            a.run("appops set " + me + " REQUEST_INSTALL_PACKAGES allow");
            a.run("pm grant " + me + " android.permission.READ_EXTERNAL_STORAGE; pm grant " + me + " android.permission.WRITE_EXTERNAL_STORAGE");
            a.run("dumpsys deviceidle whitelist +" + me + " >/dev/null 2>&1");
            // permissão do sistema para o app religar o controle sozinho (vale mesmo depois de desligar a depuração)
            a.run("pm grant " + me + " android.permission.WRITE_SECURE_SETTINGS");
            // só mexe na acessibilidade se o controle NÃO estiver rodando (antes reescrevia sempre e podia derrubar)
            if (!AccGuard.running()) AccGuard.repair(s, "modo avançado", true);
            // launcher TechSolutions ligado: confere se continua sendo a tela inicial
            try { Launcher.guard(s, "modo avançado conectou", false); } catch (Throwable ignored) {}
            status("ok", "Modo avançado ativo: controle total liberado");
        }
        s.sendInfo();
    }

    static void sendInfoIfConnected() { if (instance != null) instance.sendInfo(); }

    /** O controle (acessibilidade) ligou ou caiu: avisa o painel na hora. */
    static void accChanged(boolean on) {
        final AgentService s = instance;
        if (s == null) return;
        s.main.post(new Runnable() { public void run() { s.sendInfo(); } });
        if (!on) status("warn", "O Android desligou o controle da TV. Tentando religar…");
        else status("ok", "Controle da TV ligado");
    }

    /** Um erro foi protegido (o app não fechou): avisa o painel, com no máximo 1 aviso a cada 15 s. */
    private static long lastErrReport = 0;
    static void reportError() {
        AgentService s = instance;
        if (s == null || !s.connected) return;
        long now = System.currentTimeMillis();
        if (now - lastErrReport < 15000) return;
        lastErrReport = now;
        status("warn", "O app da TV teve um erro e se recuperou sozinho (" + Diag.lastError + "). Detalhes em Mais › Diagnóstico.");
        Diag.markReported();
    }

    /** Vigia o controle a cada 8 s: se o Android derrubou, religa sozinho (quando tem permissão/modo avançado). */
    private String lastAccState = "";
    private final Runnable accWatch = new Runnable() { public void run() {
        try {
            String st = AccGuard.state(AgentService.this);
            if (!"on".equals(st)) AccGuard.repair(AgentService.this, "automático", false);
            if (!st.equals(lastAccState)) { lastAccState = st; sendInfo(); }
        } catch (Throwable ignored) {}
        main.postDelayed(this, 8000);
    }};

    private static int pendingCode; private static Intent pendingData;
    static void deliverProjection(Context c, int code, Intent data) {
        if (instance != null) instance.onProjectionGranted(code, data);
        else { pendingCode = code; pendingData = data; start(c); }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        startForegroundNotif();
        http = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvremote:agent");
        wake.setReferenceCounted(false);
        wake.acquire();
        capThread = new HandlerThread("capture"); capThread.start();
        capHandler = new Handler(capThread.getLooper());
        HandlerThread tt = new HandlerThread("tree"); tt.start(); treeHandler = new Handler(tt.getLooper());
        readScreenSize();
        connect();
        AdbShell.get(this).ensure();
        main.postDelayed(accWatch, 6000);
        // depois de ligar a TV: se o launcher TechSolutions estiver ligado, garante que continua sendo a tela inicial
        main.postDelayed(new Runnable() { public void run() { try { Launcher.guard(AgentService.this, "TV ligou", true); } catch (Throwable ignored) {} } }, 12000);
        // vigia a lista de acessibilidade: se a TV tirar o TechSolutions, recoloca na hora (com permissão)
        try {
            getContentResolver().registerContentObserver(android.provider.Settings.Secure.getUriFor(AccGuard.KEY), false,
                    new android.database.ContentObserver(main) { @Override public void onChange(boolean self) {
                        try { AccGuard.onSettingChanged(AgentService.this); } catch (Throwable ignored) {}
                    }});
        } catch (Throwable ignored) {}
        if (pendingData != null) { Intent d = pendingData; pendingData = null; onProjectionGranted(pendingCode, d); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // startForegroundService() exige startForeground() de novo a cada chamada; sem isso alguns Android fecham o app
        try { startForegroundNotif(); } catch (Throwable ignored) {}
        if (intent != null && intent.getBooleanExtra("reconnect", false)) { retry = 0; reconnectNow(); }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        instance = null;
        try { if (ws != null) ws.close(1000, "bye"); } catch (Exception ignored) {}
        stopCapture(true);
        if (wake != null && wake.isHeld()) wake.release();
        capThread.quitSafely();
        super.onDestroy();
    }

    private void updateNotif() { try { startForegroundNotif(); } catch (Exception ignored) {} }
    private void startForegroundNotif() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("agent", "Assistência remota", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "agent");
        } else b = new Notification.Builder(this);
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification n = b.setContentTitle(getString(R.string.app_name)).setContentText("Suporte remoto ativo · Cliente nº " + Prefs.clientLabel(this))
                .setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentIntent(pi).setOngoing(true).build();
        startForeground(1, n);
    }

    // ---------------- WebSocket ----------------
    private void connect() {
        try {
            String base = Prefs.httpBase(this).replaceFirst("^http", "ws");
            String url = base + "/ws?role=agent&id=" + enc(Prefs.deviceId(this)) + "&name=" + enc(Prefs.name(this))
                    + "&token=" + enc(Prefs.token(this));
            connState = "conectando...";
            ws = http.newWebSocket(new Request.Builder().url(url).build(), new Listener());
        } catch (Exception e) {
            connState = "erro: " + e.getMessage();
            scheduleReconnect();
        }
    }
    private void reconnectNow() {
        try { if (ws != null) ws.cancel(); } catch (Exception ignored) {}
        connected = false;
        main.removeCallbacksAndMessages(null);
        main.postDelayed(accWatch, 6000);
        connect();
    }
    private void scheduleReconnect() {
        long delay = Math.min(30000, 1000L * (1 << Math.min(retry++, 5)));
        main.postDelayed(new Runnable() { public void run() { connect(); } }, delay);
    }
    private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }

    /** "Estou viva" a cada 15 s: o servidor marca a TV como offline logo que ela some (tirada da tomada, sem internet). */
    private final Runnable heartbeat = new Runnable() { public void run() {
        if (connected && ws != null) ws.send("{\"type\":\"hb\"}");
        main.postDelayed(this, 15000);
    }};

    private class Listener extends WebSocketListener {
        @Override public void onOpen(WebSocket w, Response r) {
            if (w != ws) return;
            connected = true; retry = 0; connState = "conectado";
            sendInfo();
            main.removeCallbacks(heartbeat); main.postDelayed(heartbeat, 15000);
            if (Diag.unreported) main.postDelayed(new Runnable() { public void run() { lastErrReport = 0; reportError(); } }, 1500);
        }
        @Override public void onMessage(WebSocket w, final String text) {
            main.post(new Runnable() { public void run() {
                try { handle(new JSONObject(text)); } catch (Throwable e) { Diag.event("comando falhou: " + e); status("error", "Comando falhou: " + e); }
            }});
        }
        @Override public void onClosing(WebSocket w, int code, String reason) { w.close(1000, null); }
        @Override public void onClosed(WebSocket w, int code, String reason) { lost(w, code == 4001 ? "token inválido" : "desconectado"); }
        @Override public void onFailure(WebSocket w, Throwable t, Response r) { lost(w, "falha: " + t.getMessage()); }
    }
    private void lost(final WebSocket w, final String why) {
        main.post(new Runnable() { public void run() {
            if (w != ws) return;
            connected = false; connState = why; streamWanted = false;
            releaseDisplay();
            scheduleReconnect();
        }});
    }

    void sendLauncherApps() {
        final android.content.Context c = this;
        new Thread(new Runnable() { public void run() {
            try { sendJson(Launcher.appsJson(c)); } catch (Throwable e) { status("error", "Lista do launcher: " + e); }
        }}, "launcher-apps").start();
    }
    void sendJson(JSONObject o) { if (ws != null && connected) ws.send(o.toString()); }

    void sendInfo() {
        try {
            JSONObject info = new JSONObject()
                    .put("name", Prefs.name(this))
                    .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                    .put("android", Build.VERSION.RELEASE)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("accessibility", RemoteAccessibility.enabled())
                    .put("acc", AccGuard.state(this))
                    .put("selfFix", AccGuard.canWriteSecure(this) || AdbShell.isReady())
                    .put("accRemoved", AccGuard.removedBySystem)
                    .put("capturing", projection != null)
                    .put("canInstall", Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())
                    .put("canFiles", canFiles(this))
                    .put("clientNum", Prefs.clientNum(this))
                    .put("screen", screenW + "x" + screenH)
                    .put("storage", storageInfo())
                    .put("adb", AdbShell.isReady() ? "ativo" : AdbShell.state)
                    .put("agentVersion", BuildInfo.VERSION);
            Launcher.putInfo(this, info);
            sendJson(new JSONObject().put("type", "info").put("info", info));
        } catch (Exception ignored) {}
    }
    private void sendScreenState() {
        try { sendJson(new JSONObject().put("type", "screen").put("w", screenW).put("h", screenH).put("capturing", projection != null)); }
        catch (Exception ignored) {}
    }

    // ---------------- comandos ----------------
    private void handle(JSONObject m) throws Exception {
        String type = m.optString("type");
        RemoteAccessibility acc = RemoteAccessibility.instance;
        switch (type) {
            case "assigned":
                Prefs.saveClient(this, m.optInt("num", 0), m.optString("client", ""));
                updateNotif();
                return;
            case "hello": AdbShell.get(this).ensure(); sendInfo(); sendScreenState(); imeChanged(); forceKeyframe(); if (streamWanted && projection != null) ensureDisplay(); return;
            case "stream":
                streamWanted = m.optBoolean("on");
                if (streamWanted) {
                    if (projection == null) requestProjection(); else ensureDisplay();
                } else releaseDisplay();
                return;
            case "startCapture": if (projection == null) requestProjection(); else { ensureDisplay(); sendScreenState(); } return;
            case "quality":
                maxSize = m.optInt("max", 960); quality = m.optInt("q", 60); fps = Math.max(1, m.optInt("fps", 10));
                if (vdisplay != null) { releaseDisplay(); ensureDisplay(); }
                return;
            case "info": sendInfo(); return;
            case "apps": sendApps(); return;
            case "allApps": sendAllApps(); return;
            case "launcher": {
                final boolean on = m.optBoolean("on");
                Launcher.setOn(this, on, m.optBoolean("stock"), new Launcher.Done() { public void done(boolean ok, String msg) {
                    status(ok ? "ok" : "error", msg);
                    main.post(new Runnable() { public void run() { sendInfo(); sendLauncherApps(); } });
                }});
                return;
            }
            case "launcherStock":
                Launcher.setStock(this, m.optBoolean("disable"), new Launcher.Done() { public void done(boolean ok, String msg) {
                    status(ok ? "ok" : "error", msg);
                    main.post(new Runnable() { public void run() { sendInfo(); } });
                }});
                return;
            case "launcherApps": sendLauncherApps(); return;
            case "launcherSet": Launcher.applyPanel(this, m); sendLauncherApps(); return;
            case "pkgAction": pkgAction(m.getString("action"), m.getString("pkg"), m.optBoolean("sys"), m.optString("label", m.getString("pkg"))); return;
            case "launch": launch(m.getString("pkg")); return;
            case "uninstall": uninstall(m.getString("pkg")); return;
            case "install": install(m); return;
            case "files": Files.list(this, m.optString("path", "")); return;
            case "mkdir": Files.mkdir(this, m.getString("path")); return;
            case "deleteFile": Files.delete(this, m.getString("path")); return;
            case "installLocal": installLocal(m.getString("path"), m.optString("name", "app.apk")); return;
            case "sendFile": Files.upload(this, http, m.getString("path"), m.optString("name", "")); return;
            case "putFile": Files.download(this, http, m); return;
            case "selfUpdate": selfUpdate(); return;
            case "toast": showToast(m.getString("text")); return;
            case "openUrl": openUrl(m.getString("url")); return;
            case "enableInstall": enableInstall(); return;
            case "enableFiles": enableFiles(); return;
            case "enableAll": enableAll(); return;
            case "openSettings": RemoteAccessibility.startActivityAny(this, new Intent(m.getString("action"))); return;
            case "adbConnect": AdbShell.get(this).ensure(); status("info", "Modo avançado: " + AdbShell.state); return;
            case "shell": {
                if (!AdbShell.isReady()) { status("error", "Modo avançado não está ativo"); return; }
                final String cmd = m.getString("cmd");
                boolean ok = AdbShell.get(this).exec(cmd, new AdbShell.Result() { public void done(String out) {
                    try { sendJson(new JSONObject().put("type", "shellOut").put("cmd", cmd).put("out", Diag.tail(out, 60000))); } catch (Exception ignored) {}
                }});
                if (!ok) AdbShell.get(this).run(cmd);
                return;
            }
            case "diag": sendDiag(); return;
            case "fixAcc":
                if (AccGuard.running()) { status("ok", "O controle já está funcionando"); return; }
                if (!AccGuard.repair(this, "pedido do painel", true))
                    status("error", "Não dá para religar sozinho sem o Modo avançado. Peça para desligar e ligar a chave TechSolutions em Acessibilidade.");
                return;
        }
        if ("key".equals(type) && adbKey(m.getString("key"))) return;
        if ("text".equals(type) && acc == null && AdbShell.isReady()) { adbText(m.getString("text")); return; }
        if (acc == null) { status("error", "Acessibilidade desativada na TV: ative em Configurações > Acessibilidade > " + getString(R.string.app_name)); return; }
        readScreenSize();
        switch (type) {
            case "tap": acc.tap((float) m.getDouble("x") * screenW, (float) m.getDouble("y") * screenH, 60); break;
            case "longpress": acc.tap((float) m.getDouble("x") * screenW, (float) m.getDouble("y") * screenH, 900); break;
            case "swipe":
                acc.swipe((float) m.getDouble("x1") * screenW, (float) m.getDouble("y1") * screenH,
                        (float) m.getDouble("x2") * screenW, (float) m.getDouble("y2") * screenH, m.optLong("ms", 300));
                break;
            case "text":
                if (!acc.setText(m.getString("text"))) {
                    if (AdbShell.isReady()) adbText(m.getString("text"));
                    else status("error", "Nenhum campo de texto focado");
                }
                break;
            case "key": key(acc, m.getString("key")); break;
            case "appIcon": { final String ip = m.optString("pkg"); treeHandler.post(new Runnable() { public void run() { sendAppIcon(ip); } }); return; }
            case "tree": treeWantedUntil = System.currentTimeMillis() + 3500; final RemoteAccessibility fa = acc; treeHandler.post(new Runnable() { public void run() { sendTree(fa); } }); break;
        }
    }

    static volatile long treeWantedUntil = 0;
    private long lastTreeAt = 0;
    synchronized void sendTree(RemoteAccessibility acc) {
        if (acc == null) return;
        lastTreeAt = System.currentTimeMillis();
        try {
            readScreenSize();
            sendJson(new JSONObject().put("type", "tree").put("nodes", acc.tree(screenW, screenH))
                    .put("app", appLabel(acc.foregroundPkg())).put("pkg", lastPkg).put("theme", appTheme(lastPkg))
                    .put("w", screenW).put("h", screenH)
                    .put("focus", focusJson(acc.realFocus(screenW, screenH))));
        } catch (Throwable ignored) {}
    }
    /** Chamado pela acessibilidade quando o foco muda: manda o desenho na hora (se o painel estiver olhando). */
    static void focusMoved() {
        final AgentService s = instance;
        if (s == null || System.currentTimeMillis() > treeWantedUntil) return;
        if (System.currentTimeMillis() - s.lastTreeAt < 120) return;
        s.treeHandler.post(new Runnable() { public void run() { s.sendTree(RemoteAccessibility.instance); } });
    }

    private static JSONArray focusJson(int[] f) {
        if (f == null) return null;
        JSONArray a = new JSONArray(); for (int v : f) a.put(v); return a;
    }

    /** Relatório do botão "Diagnóstico" do painel (com log do sistema quando o Modo avançado está ativo). */
    private void sendDiag() {
        status("info", "Montando o diagnóstico da TV…");
        new Thread(new Runnable() { public void run() {
            final String local = Diag.report(AgentService.this);
            if (!AdbShell.isReady()) { sendDiagText(local + "\n(Sem Modo avançado: o log completo do sistema não está disponível.)\n"); return; }
            String cmd = "echo '— QUEM MEXEU NA LISTA DE ACESSIBILIDADE (pkg = app que gravou por último)'; dumpsys settings 2>/dev/null | grep -iE 'enabled_accessibility_services|accessibility_enabled' | head -30; echo; "
                    + "echo '— SERVIÇOS DE ACESSIBILIDADE (sistema)'; dumpsys accessibility 2>&1 | grep -iE 'Bound services|Enabled services|Binding services|Crashed services|tvremote|isEnabled|touchExploration' | head -40; "
                    + "echo; echo '— LOG DO SISTEMA (trechos sobre o app e a acessibilidade)'; "
                    + "logcat -d -v time -t 6000 2>&1 | grep -iE 'tvremote|TechSolutions|accessib|AndroidRuntime|FATAL|lowmemory|am_kill|am_crash|am_anr|died' | tail -220";
            boolean ok = AdbShell.get(AgentService.this).exec(cmd, new AdbShell.Result() { public void done(String out) {
                sendDiagText(local + "\n" + out);
            }});
            if (!ok) sendDiagText(local);
        }}, "diag").start();
    }
    private void sendDiagText(String t) {
        try { sendJson(new JSONObject().put("type", "diag").put("text", Diag.tail(t, 120000))); Diag.markReported(); } catch (Exception ignored) {}
    }

    // ---------- cores do tema do app (para desenhar a tela parecida com o original) ----------
    private String themePkg = null; private JSONObject themeCache = null;
    private JSONObject appTheme(String pkg) {
        if (pkg == null || pkg.length() == 0) return null;
        if (pkg.equals(themePkg)) return themeCache;
        themePkg = pkg; themeCache = null;
        try {
            PackageManager pm = getPackageManager();
            Context pc = createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY);
            int theme = 0;
            Intent li = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (li == null) li = pm.getLaunchIntentForPackage(pkg);
            if (li != null && li.getComponent() != null) {
                try { theme = pm.getActivityInfo(li.getComponent(), 0).getThemeResource(); } catch (Exception ignored) {}
            }
            if (theme == 0) theme = pm.getApplicationInfo(pkg, 0).theme;
            if (theme != 0) pc.setTheme(theme);
            android.content.res.Resources.Theme th = pc.getTheme();
            JSONObject o = new JSONObject();
            putColor(o, "bg", pc, th, android.R.attr.windowBackground, 0);
            if (!o.has("bg")) putColor(o, "bg", pc, th, android.R.attr.colorBackground, 0);
            putColor(o, "fg", pc, th, android.R.attr.textColorPrimary, 0);
            putColor(o, "fg2", pc, th, android.R.attr.textColorSecondary, 0);
            putColor(o, "primary", pc, th, android.R.attr.colorPrimary, pc.getResources().getIdentifier("colorPrimary", "attr", pkg));
            putColor(o, "accent", pc, th, android.R.attr.colorAccent, pc.getResources().getIdentifier("colorAccent", "attr", pkg));
            if (!o.has("accent")) putColor(o, "accent", pc, th, 0, pc.getResources().getIdentifier("colorSecondary", "attr", pkg));
            themeCache = o.length() > 0 ? o : null;
        } catch (Throwable ignored) {}
        return themeCache;
    }
    private static void putColor(JSONObject o, String k, Context pc, android.content.res.Resources.Theme th, int attr, int appAttr) {
        int[] attrs = appAttr != 0 ? new int[]{ appAttr, attr } : new int[]{ attr };
        for (int a : attrs) {
            if (a == 0) continue;
            try {
                android.util.TypedValue tv = new android.util.TypedValue();
                if (!th.resolveAttribute(a, tv, true)) continue;
                Integer c = null;
                if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) c = tv.data;
                else if (tv.resourceId != 0) {
                    try { c = pc.getResources().getColorStateList(tv.resourceId, th).getDefaultColor(); }
                    catch (Exception e) {
                        android.graphics.drawable.Drawable dr = pc.getResources().getDrawable(tv.resourceId, th);
                        if (dr instanceof android.graphics.drawable.ColorDrawable) c = ((android.graphics.drawable.ColorDrawable) dr).getColor();
                    }
                }
                if (c != null) { o.put(k, String.format("#%08X", c)); return; }
            } catch (Throwable ignored) {}
        }
    }
    /** Ícone do app em PNG (base64), para o cabeçalho/logo da tela reconstruída. */
    void sendAppIcon(String pkg) {
        try {
            android.graphics.drawable.Drawable d = getPackageManager().getApplicationIcon(pkg);
            int sz = 128;
            Bitmap b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            d.setBounds(0, 0, sz, sz); d.draw(c);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bo);
            String data = android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
            String banner = null;
            try {
                android.graphics.drawable.Drawable bd = getPackageManager().getApplicationBanner(pkg);
                if (bd != null) {
                    Bitmap bb = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas bc = new android.graphics.Canvas(bb); bd.setBounds(0, 0, 320, 180); bd.draw(bc);
                    ByteArrayOutputStream bo2 = new ByteArrayOutputStream(); bb.compress(Bitmap.CompressFormat.PNG, 100, bo2);
                    banner = android.util.Base64.encodeToString(bo2.toByteArray(), android.util.Base64.NO_WRAP);
                }
            } catch (Throwable ignored) {}
            sendJson(new JSONObject().put("type", "appIcon").put("pkg", pkg).put("icon", data).put("banner", banner == null ? JSONObject.NULL : banner));
        } catch (Throwable ignored) {}
    }

    private String lastPkg = "", lastLabel = "";
    private String appLabel(String pkg) {
        if (pkg.equals(lastPkg)) return lastLabel;
        String l = pkg;
        try { l = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) {}
        lastPkg = pkg; lastLabel = l;
        return l;
    }

    /** Teclas de verdade via ADB local (funcionam dentro de qualquer app). */
    private boolean adbKey(String k) {
        if (!AdbShell.isReady()) { AdbShell.get(this).ensure(); return false; }
        int code;
        switch (k) {
            case "up": code = 19; break;
            case "down": code = 20; break;
            case "left": code = 21; break;
            case "right": code = 22; break;
            case "ok": code = 23; break;
            case "back": code = 4; break;
            case "home": code = 3; break;
            case "recents": code = 187; break;
            case "menu": code = 82; break;
            case "playpause": code = 85; break;
            case "stop": code = 86; break;
            case "next": code = 87; break;
            case "prev": code = 88; break;
            case "rewind": code = 89; break;
            case "ff": code = 90; break;
            case "volup": code = 24; break;
            case "voldown": code = 25; break;
            case "mute": code = 164; break;
            case "del": code = 67; break;
            case "enter": code = 66; break;
            case "settings": code = 176; break;
            case "notifications": code = 83; break;
            case "info": code = 165; break;
            case "chup": code = 166; break;
            case "chdown": code = 167; break;
            default: return false;
        }
        return AdbShell.get(this).key(code);
    }

    private void adbText(String t) {
        StringBuilder b = new StringBuilder();
        for (char c : t.toCharArray()) {
            if (c == ' ') b.append("%s");
            else if (c == '\'') b.append("'\\''");
            else b.append(c);
        }
        AdbShell.get(this).run("input text '" + b + "'");
    }

    private void key(RemoteAccessibility acc, String k) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        switch (k) {
            case "back": acc.global(AccessibilityService.GLOBAL_ACTION_BACK); break;
            case "home": acc.global(AccessibilityService.GLOBAL_ACTION_HOME); break;
            case "recents": acc.global(AccessibilityService.GLOBAL_ACTION_RECENTS); break;
            case "notifications": acc.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); break;
            case "up": acc.dpad(View.FOCUS_UP); break;
            case "down": acc.dpad(View.FOCUS_DOWN); break;
            case "left": acc.dpad(View.FOCUS_LEFT); break;
            case "right": acc.dpad(View.FOCUS_RIGHT); break;
            case "ok": acc.clickFocused(screenW, screenH); break;
            case "volup": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); break;
            case "voldown": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); break;
            case "mute": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); break;
            case "playpause": media(am, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
            case "stop": media(am, android.view.KeyEvent.KEYCODE_MEDIA_STOP); break;
            case "next": media(am, android.view.KeyEvent.KEYCODE_MEDIA_NEXT); break;
            case "prev": media(am, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
            case "rewind": media(am, android.view.KeyEvent.KEYCODE_MEDIA_REWIND); break;
            case "ff": media(am, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD); break;
            case "del": if (!acc.deleteChar()) status("error", "Nenhum campo de texto focado"); break;
            case "enter": if (!acc.imeEnter()) acc.clickFocused(screenW, screenH); break;
            case "menu": case "settings": case "info": case "chup": case "chdown":
                status("error", "Esta tecla precisa do Modo avançado (aba Liberar)"); break;
        }
    }
    /** Teclas de mídia sem ADB: vão para o player que estiver tocando. */
    private static void media(AudioManager am, int code) {
        long t = android.os.SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, code, 0));
    }
    static boolean canFiles(Context c) {
        return Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
    static boolean canInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    void sendApps() {
        new Thread(new Runnable() { public void run() {
            try {
                PackageManager pm = getPackageManager();
                Set<String> pkgs = new HashSet<>();
                Intent i1 = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                Intent i2 = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                for (ResolveInfo r : pm.queryIntentActivities(i1, 0)) pkgs.add(r.activityInfo.packageName);
                for (ResolveInfo r : pm.queryIntentActivities(i2, 0)) pkgs.add(r.activityInfo.packageName);
                JSONArray arr = new JSONArray();
                for (String p : pkgs) {
                    try {
                        PackageInfo pi = pm.getPackageInfo(p, 0);
                        ApplicationInfo ai = pi.applicationInfo;
                        arr.put(new JSONObject().put("pkg", p).put("label", String.valueOf(pm.getApplicationLabel(ai)))
                                .put("version", pi.versionName).put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0));
                    } catch (Exception ignored) {}
                }
                sendJson(new JSONObject().put("type", "apps").put("list", arr));
            } catch (Exception e) { status("error", "Lista de apps: " + e); }
        }}).start();
    }

    /** Limpeza: lista todos os apps (inclusive de fábrica). */
    void sendAllApps() {
        new Thread(new Runnable() { public void run() {
            try {
                sendJson(new JSONObject().put("type", "allApps").put("list", AppTools.list(AgentService.this))
                        .put("me", getPackageName()).put("adb", AdbShell.isReady()));
            } catch (Throwable e) { status("error", "Lista completa de apps: " + e); }
        }}, "allapps").start();
    }

    /** Desativar / remover / restaurar / parar um app (Modo avançado). Sem ele, abre a tela do app na TV. */
    private void pkgAction(final String action, final String pkg, boolean sys, final String label) {
        if (!pkg.matches("[A-Za-z0-9._]+")) { status("error", "Nome de pacote inválido"); return; }
        if (pkg.equals(getPackageName())) { status("error", "Não dá para mexer no próprio app de suporte por aqui"); return; }
        if ("details".equals(action)) { AppTools.openDetails(this, pkg); status("info", "Abri a tela de " + label + " na TV"); return; }
        if (!AdbShell.isReady()) {
            if ("remove".equals(action) && !sys) { uninstall(pkg); return; } // app comum: desinstalador normal
            AppTools.openDetails(this, pkg);
            status("error", "Sem o Modo avançado não dá para " + ("disable".equals(action) ? "desativar" : "mexer em") + " app de fábrica daqui. Abri a tela de " + label + " na TV: use o controle e clique em Desativar.");
            return;
        }
        String cmd = AppTools.shellFor(this, action, pkg, sys);
        if (cmd == null) { status("error", "unadmin".equals(action) ? label + " não é administrador do aparelho" : "Ação desconhecida: " + action); return; }
        final String fcmd = cmd;
        boolean started = AdbShell.get(this).exec(cmd, new AdbShell.Result() { public void done(String out) {
            boolean ok = AppTools.looksOk(action, out);
            String o = out == null ? "" : out.trim();
            if (o.length() > 300) o = o.substring(0, 300);
            Diag.event("app " + pkg + ": " + action + (ok ? " ok" : " falhou: " + o));
            if (ok) status("ok", label + " " + AppTools.doneText(action) + " ✓");
            else if (o.contains("DELETE_FAILED_DEVICE_POLICY_MANAGER") || o.contains("device admin") || o.contains("DevicePolicy"))
                status("error", label + " é administrador do aparelho. Clique em “Tirar admin” e tente de novo.");
            else status("error", "Não deu para " + action + " " + label + ": " + (o.isEmpty() ? "sem resposta" : o));
            try { sendJson(new JSONObject().put("type", "pkgResult").put("pkg", pkg).put("action", action).put("ok", ok).put("out", o).put("cmd", fcmd)); } catch (Exception ignored) {}
            sendAllApps();
        }});
        if (!started) status("error", "Modo avançado caiu, tente de novo");
        else status("info", "Executando em " + label + "…");
    }

    private void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) { status("error", "App sem tela inicial: " + pkg); return; }
        RemoteAccessibility.startActivityAny(this, i);
        status("ok", "Abrindo " + pkg);
    }

    private void uninstall(String pkg) {
        Intent cb = new Intent(this, InstallReceiver.class).putExtra("apkname", pkg).putExtra("action", "Desinstalação");
        PendingIntent pi = PendingIntent.getBroadcast(this, (int) (System.currentTimeMillis() & 0xffff), cb, piFlags());
        RemoteAccessibility.autoAccept(30000);
        getPackageManager().getPackageInstaller().uninstall(pkg, pi.getIntentSender());
        status("info", "Desinstalando " + pkg + "...");
    }

    private static int piFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) f |= 0x02000000; // FLAG_MUTABLE (o instalador preenche extras)
        return f;
    }

    private void install(final JSONObject m) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            status("error", "Falta permissão 'Instalar apps desconhecidos' para o agente. Abrindo a tela na TV...");
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + getPackageName()));
            RemoteAccessibility.startActivityAny(this, i);
            return;
        }
        new Thread(new Runnable() { public void run() {
            String name = m.optString("name", "app.apk");
            File f = new File(getCacheDir(), "dl.apk");
            try {
                String url = m.optString("url", "");
                if (url.isEmpty()) url = Prefs.httpBase(AgentService.this) + m.getString("path") + "?token=" + enc(Prefs.token(AgentService.this));
                status("info", "Baixando " + name + "...");
                Response r = http.newCall(new Request.Builder().url(url).build()).execute();
                if (!r.isSuccessful()) throw new Exception("HTTP " + r.code());
                long total = r.body().contentLength();
                InputStream in = r.body().byteStream();
                OutputStream out = new FileOutputStream(f);
                byte[] buf = new byte[64 * 1024];
                long done = 0; int n, lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n); done += n;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct / 5 != lastPct / 5) { lastPct = pct;
                            sendJson(new JSONObject().put("type", "progress").put("name", name).put("key", "dl-" + name).put("pct", pct)); }
                    }
                }
                out.close(); in.close();
                commitApk(f, name);
            } catch (Exception e) {
                status("error", "Instalação falhou: " + e.getMessage());
            } finally {
                main.postDelayed(new Runnable() { public void run() { new File(getCacheDir(), "dl.apk").delete(); } }, 120000);
            }
        }}).start();
    }

    /** Instala um APK que já está num arquivo local da TV. */
    private void installLocal(final String path, final String name) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) { enableInstall(); return; }
        new Thread(new Runnable() { public void run() {
            try {
                File f = new File(path);
                if (!f.isFile()) { status("error", "Arquivo não encontrado: " + path); return; }
                status("info", "Instalando " + name + "...");
                commitApk(f, name);
            } catch (Exception e) { status("error", "Instalação falhou: " + e.getMessage()); }
        }}).start();
    }

    /** Grava o APK numa sessão do PackageInstaller e confirma (auto-aceita o diálogo). */
    private void commitApk(File f, String name) throws Exception {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setSize(f.length());
        int sid = pi.createSession(sp);
        PackageInstaller.Session s = pi.openSession(sid);
        OutputStream so = s.openWrite("base.apk", 0, f.length());
        InputStream fi = new FileInputStream(f);
        byte[] buf = new byte[64 * 1024]; int n;
        while ((n = fi.read(buf)) > 0) so.write(buf, 0, n);
        s.fsync(so); so.close(); fi.close();
        Intent cb = new Intent(this, InstallReceiver.class).putExtra("apkname", name).putExtra("action", "Instalação");
        PendingIntent p = PendingIntent.getBroadcast(this, sid, cb, piFlags());
        RemoteAccessibility.autoAccept(60000);
        s.commit(p.getIntentSender());
        s.close();
        status("info", "Instalando " + name + "...");
    }

    static void triggerSelfUpdate() { if (instance != null) instance.selfUpdate(); }

    /** Liga remotamente "instalar apps desconhecidos": abre a tela e aceita sozinho. */
    void enableInstall() {
        if (canInstall(this)) { status("ok", "Instalar apps já está liberado"); sendInfo(); return; }
        if (AdbShell.isReady()) { adbChanged(); main.postDelayed(new Runnable() { public void run() { sendInfo(); } }, 2500); return; }
        RemoteAccessibility.autoAcceptPerm(20000);
        Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + getPackageName()));
        RemoteAccessibility.startActivityAny(this, i);
        status("info", "Liberando 'instalar apps' na TV...");
        main.postDelayed(new Runnable() { public void run() { sendInfo(); } }, 4000);
    }

    /** Liga remotamente o acesso a arquivos. */
    /** Libera tudo o que dá: com ADB faz sozinho; sem ADB abre uma tela de cada vez (a acessibilidade aceita). */
    void enableAll() {
        AdbShell.get(this).ensure();
        if (!canInstall(this)) { enableInstall(); main.postDelayed(new Runnable() { public void run() { if (!canFiles(AgentService.this)) enableFiles(); } }, 9000); }
        else if (!canFiles(this)) enableFiles();
        else status("ok", "Instalar apps e arquivos já estão liberados");
    }

    void enableFiles() {
        if (canFiles(this)) { status("ok", "Arquivos já está liberado"); sendInfo(); return; }
        if (AdbShell.isReady()) { adbChanged(); main.postDelayed(new Runnable() { public void run() { sendInfo(); } }, 2500); return; }
        RemoteAccessibility.autoAcceptPerm(20000);
        RemoteAccessibility.startActivityAny(this, new Intent(this, PermActivity.class));
        status("info", "Pedindo a permissão de arquivos na TV (o app aceita sozinho)...");
        main.postDelayed(new Runnable() { public void run() { sendInfo(); } }, 5000);
    }

    /** Baixa e instala o próprio APK mais novo do servidor (botão "Atualizar app"). */
    private void selfUpdate() {
        try {
            JSONObject m = new JSONObject().put("url", Prefs.httpBase(this) + "/agent.apk?token=" + enc(Prefs.token(this)))
                    .put("name", "TechSolutions (atualização)");
            status("info", "Baixando atualização do app...");
            install(m);
        } catch (Exception e) { status("error", "Atualização falhou: " + e.getMessage()); }
    }

    /**
     * Mensagem na tela da TV. Antes era um Toast (aviso pequeno de 3 s que muitas TVs escondem quando vem de app em segundo plano).
     * Agora: faixa grande por cima de qualquer app (com o controle ligado), ou tela com botão OK.
     */
    private void showToast(final String text) {
        final RemoteAccessibility acc = RemoteAccessibility.instance;
        new Thread(new Runnable() { public void run() {
            if (acc != null && acc.showMessage(text)) { status("ok", "Mensagem na tela da TV ✓"); return; }
            MessageActivity.pending = text;
            if (AdbShell.isReady()) {
                AdbShell.get(AgentService.this).run("am start -n " + getPackageName() + "/.MessageActivity");
                return; // a própria tela avisa o painel quando abrir
            }
            main.post(new Runnable() { public void run() {
                try {
                    Intent i = new Intent(AgentService.this, MessageActivity.class).putExtra("text", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable ignored) {}
                try { android.widget.Toast.makeText(AgentService.this, text, android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
                status("info", "Mensagem enviada. Sem o controle ligado algumas TVs não mostram: ligue o controle para ela aparecer sempre.");
            }});
        }}, "msg").start();
    }

    /** O teclado da TV abriu/fechou: o painel liga a digitação pelo teclado do computador. */
    static void imeChanged() {
        AgentService s = instance;
        if (s == null) return;
        try {
            s.sendJson(new JSONObject().put("type", "ime").put("open", RemoteAccessibility.imeOpen)
                    .put("hint", RemoteAccessibility.imeHint).put("text", RemoteAccessibility.imeText));
        } catch (Exception ignored) {}
    }

    private void openUrl(String url) {
        try {
            if (!url.contains("://")) url = "http://" + url;
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            RemoteAccessibility.startActivityAny(this, i);
            status("ok", "Abrindo " + url);
        } catch (Exception e) { status("error", "Não consegui abrir o link: " + e.getMessage()); }
    }

    private String storageInfo() {
        try {
            android.os.StatFs st = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
            long free = st.getAvailableBytes(), total = st.getTotalBytes();
            return (free / 1073741824.0 > 1 ? String.format(Locale.US, "%.1f", free / 1073741824.0) + " GB"
                    : (free / 1048576) + " MB") + " livres de " + String.format(Locale.US, "%.1f", total / 1073741824.0) + " GB";
        } catch (Exception e) { return ""; }
    }

    // ---------------- captura de tela ----------------
    private void readScreenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels; screenH = dm.heightPixels;
    }

    private long lastRequest = 0;
    private void requestProjection() {
        if (System.currentTimeMillis() - lastRequest < 5000) return;
        lastRequest = System.currentTimeMillis();
        status("info", "Pedindo permissão de captura de tela na TV...");
        Intent i = new Intent(this, ProjectionActivity.class).putExtra("auto", true);
        RemoteAccessibility.startActivityAny(this, i);
    }

    static boolean capturing() { AgentService s = instance; return s != null && s.projection != null; }

    void onProjectionGranted(int code, Intent data) {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        // encerra a projeção anterior (se houver) antes de usar a nova
        if (projection != null) { MediaProjection old = projection; projection = null; releaseDisplay(); try { old.stop(); } catch (Exception ignored) {} }
        final MediaProjection mp;
        try { mp = mpm.getMediaProjection(code, data); }
        catch (Exception e) { status("error", "Falha ao obter captura: " + e.getMessage()); return; }
        if (mp == null) { status("error", "Projeção nula"); return; }
        projection = mp;
        mp.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { main.post(new Runnable() { public void run() {
                if (projection != mp) return; // é uma projeção antiga
                releaseDisplay(); projection = null;
                status("error", "A TV encerrou o compartilhamento de tela");
                sendScreenState(); sendInfo();
            } }); }
        }, main);
        status("ok", "Captura de tela autorizada");
        sendInfo();
        if (streamWanted) ensureDisplay();
        sendScreenState();
    }

    private void ensureDisplay() {
        if (projection == null || vdisplay != null) return;
        readScreenSize();
        float scale = Math.min(1f, (float) maxSize / Math.max(screenW, screenH));
        int w = Math.max(2, Math.round(screenW * scale)) & ~1, h = Math.max(2, Math.round(screenH * scale)) & ~1;
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        frameW = w; frameH = h;
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader r) { onFrame(r); }
        }, capHandler);
        try {
            vdisplay = projection.createVirtualDisplay("tvremote", w, h, 160,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, capHandler);
            forceKeyframe();
            capHandler.removeCallbacks(sender);
            capHandler.post(sender);
        } catch (Exception e) {
            status("error", "Falha ao iniciar captura: " + e.getMessage());
            releaseDisplay();
        }
    }

    // Último quadro recebido (copiado do ImageReader); o envio é feito pelo "sender",
    // que respeita o FPS e reenvia a tela a cada 2 s mesmo se nada mudar.
    private final Object frameLock = new Object();
    private Bitmap frameBmp;
    private volatile boolean frameDirty = false;
    private volatile long lastSent = 0;
    private int frameW, frameH;

    void forceKeyframe() { lastSent = 0; frameDirty = true; }

    private void onFrame(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int rowPx = p.getRowStride() / p.getPixelStride();
            int h = img.getHeight();
            synchronized (frameLock) {
                if (frameBmp == null || frameBmp.getWidth() != rowPx || frameBmp.getHeight() != h)
                    frameBmp = Bitmap.createBitmap(rowPx, h, Bitmap.Config.ARGB_8888);
                frameBmp.copyPixelsFromBuffer(buf);
                frameDirty = true;
            }
        } catch (Throwable ignored) {
        } finally { if (img != null) img.close(); }
    }

    private final Runnable sender = new Runnable() { public void run() {
        if (vdisplay == null) return;
        long now = System.currentTimeMillis();
        try {
            WebSocket sock = ws;
            boolean due = frameDirty || now - lastSent > 2000;
            if (streamWanted && connected && sock != null && due && sock.queueSize() < 512 * 1024) {
                byte[] jpg = null;
                synchronized (frameLock) {
                    if (frameBmp != null) {
                        Bitmap out = frameBmp.getWidth() == frameW ? frameBmp : Bitmap.createBitmap(frameBmp, 0, 0, Math.min(frameW, frameBmp.getWidth()), frameBmp.getHeight());
                        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
                        out.compress(Bitmap.CompressFormat.JPEG, quality, bos);
                        if (out != frameBmp) out.recycle();
                        jpg = bos.toByteArray();
                        frameDirty = false;
                    }
                }
                if (jpg != null) { sock.send(ByteString.of(jpg)); lastSent = now; }
            }
        } catch (Throwable ignored) {}
        capHandler.postDelayed(this, Math.max(30, 1000 / Math.max(1, fps)));
    }};

    private void releaseDisplay() {
        if (capHandler != null) capHandler.removeCallbacks(sender);
        if (vdisplay != null) { try { vdisplay.release(); } catch (Exception ignored) {} vdisplay = null; }
        if (reader != null) { try { reader.close(); } catch (Exception ignored) {} reader = null; }
    }

    private void stopCapture(boolean stopProjection) {
        releaseDisplay();
        if (stopProjection && projection != null) try { projection.stop(); } catch (Exception ignored) {}
        projection = null;
    }
}
