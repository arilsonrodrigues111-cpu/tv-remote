package com.arilson.tvremote;

import android.content.Context;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;

/**
 * Cliente mínimo do protocolo ADB, conectando no adbd da própria TV (127.0.0.1:5555).
 * Com ele o app manda teclas de verdade (input keyevent), que funcionam em qualquer app,
 * e consegue se auto-liberar (tela sem pergunta, instalar, arquivos, acessibilidade).
 */
public class AdbShell {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAXDATA = 4096;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    public static volatile String state = "desligado";
    private static AdbShell inst;

    private final Context ctx;
    private Socket sock;
    private InputStream in;
    private OutputStream out;
    private volatile boolean ready = false;
    private volatile int remoteId = 0;
    private final Object okLock = new Object();
    private volatile boolean okPending = false;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private Thread worker;
    private volatile boolean stop = false;
    private volatile boolean rootTried = false;
    private long lastTry = 0;
    private long lastBounce = 0;
    private int bounces = 0; // no máximo 2 por boot, pra não ficar desligando a depuração à toa

    /** Comandos com resposta (diagnóstico): cada um abre um canal próprio no adbd. */
    public interface Result { void done(String out); }
    private static class Exec { volatile int remote; final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); Result cb; }
    private final java.util.concurrent.ConcurrentHashMap<Integer, Exec> execs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger nextId = new java.util.concurrent.atomic.AtomicInteger(100);
    private final android.os.Handler timer = new android.os.Handler(android.os.Looper.getMainLooper());
    // o Android não deixa usar a rede na thread principal: envios de exec/close vão por esta fila
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();

    private AdbShell(Context c) { ctx = c.getApplicationContext(); }

    public static synchronized AdbShell get(Context c) { if (inst == null) inst = new AdbShell(c); return inst; }
    public static boolean isReady() { return inst != null && inst.ready; }

    /** Se a TV tiver root, liga a depuração por TCP e autoriza a chave do app — assim o ADB conecta
     *  sozinho, sem PC, sem cabo e sem a janela "Permitir depuração?". Bloqueante; chamar fora da main thread. */
    public boolean bootstrapViaRoot() {
        try {
            if (!RootShell.check()) return false;
            String pub;
            try { pub = pubKey((RSAPublicKey) loadKey().getPublic()); } catch (Exception e) { return false; }
            String script =
                "mkdir -p /data/misc/adb\n" +
                "if ! grep -q techsolutions /data/misc/adb/adb_keys 2>/dev/null; then echo \"" + pub + " techsolutions\" >> /data/misc/adb/adb_keys; fi\n" +
                "chmod 640 /data/misc/adb/adb_keys 2>/dev/null\n" +
                "chown system:shell /data/misc/adb/adb_keys 2>/dev/null\n" +
                "restorecon /data/misc/adb/adb_keys 2>/dev/null\n" +
                "pm grant " + ctx.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS 2>/dev/null\n" +
                "settings put global adb_enabled 1\n" +
                "setprop service.adb.tcp.port 5555\n" +
                "setprop persist.adb.tcp.port 5555\n" +
                "stop adbd\n" +
                "start adbd\n" +
                "echo BOOT_OK";
            String out = RootShell.run(script);
            return out != null && out.contains("BOOT_OK");
        } catch (Throwable t) { return false; }
    }

    /** Executa um comando no shell da TV (assíncrono). Conecta se preciso. */
    public void run(String cmd) {
        queue.offer(cmd);
        ensure();
    }

    /** Tecla real. Retorna false se o ADB não estiver pronto (aí o chamador usa a Acessibilidade). */
    public boolean key(int code) {
        if (!ready) { ensure(); return false; }
        queue.offer("input keyevent " + code);
        return true;
    }

    /** Roda um comando e devolve a saída (máx. ~25 s). Retorna false se o Modo avançado não estiver pronto. */
    public boolean exec(String cmd, Result cb) {
        if (!ready) { ensure(); return false; }
        final int id = nextId.incrementAndGet();
        Exec e = new Exec(); e.cb = cb;
        execs.put(id, e);
        final String fcmd = cmd;
        io.execute(new Runnable() { public void run() {
            try { send(OPEN, id, 0, ("shell:" + fcmd + "\0").getBytes("UTF-8")); }
            catch (Exception ex) { finishExec(id, "[não consegui enviar: " + ex + "]", false); }
        }});
        timer.postDelayed(new Runnable() { public void run() { finishExec(id, "\n[tempo esgotado]\n", true); } }, 25000);
        return true;
    }

    private void finishExec(int id, String tail, boolean closeIt) {
        Exec e = execs.remove(id);
        if (e == null) return;
        if (closeIt && e.remote != 0) {
            final int rid = e.remote;
            io.execute(new Runnable() { public void run() { try { send(CLSE, id, rid, null); } catch (Exception ignored) {} } });
        }
        String out;
        try { out = new String(e.out.toByteArray(), "UTF-8"); } catch (Exception x) { out = ""; }
        if (tail != null) out += tail;
        try { e.cb.done(out); } catch (Throwable ignored) {}
    }

    public synchronized void ensure() {
        if (worker != null && worker.isAlive()) return;
        if (System.currentTimeMillis() - lastTry < 4000) return;
        lastTry = System.currentTimeMillis();
        stop = false;
        worker = new Thread(new Runnable() { public void run() { loop(); } }, "adb");
        worker.start();
    }

    public void close() {
        stop = true; ready = false;
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
    }

    /** Conecta no adbd (TCP 5555). Se a porta estiver fechada e a TV tiver root, liga a depuração pelo root e tenta de novo. */
    private Socket openSocket() throws Exception {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 2500);
            return s;
        } catch (Exception first) {
            if (!rootTried) {
                rootTried = true;
                state = "ligando pelo root…";
                if (bootstrapViaRoot()) {
                    try { Thread.sleep(1800); } catch (InterruptedException ignored) {}
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress("127.0.0.1", 5555), 2500);
                    return s;
                }
            }
            // Sem root: em alguns boxes o adbd sobe no boot ANTES do Android carregar o
            // persist.adb.tcp.port gravado pelo programa do PC, e fica só na USB. Com
            // WRITE_SECURE_SETTINGS dá pra desligar/ligar a depuração: o adbd reinicia,
            // lê a porta gravada e abre a 5555 de novo — Modo avançado volta sem cabo.
            if (bounceAllowed()) {
                lastBounce = System.currentTimeMillis();
                bounces++;
                state = "religando a depuração…";
                if (bounceAdb()) {
                    for (int i = 0; i < 12; i++) {
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                        try {
                            Socket s = new Socket();
                            s.connect(new InetSocketAddress("127.0.0.1", 5555), 1500);
                            return s;
                        } catch (Exception ignored) {}
                    }
                }
            }
            throw first;
        }
    }

    /** Só religa a depuração se: tem a permissão, a depuração está ligada, a porta foi gravada
     *  (persist.adb.tcp.port) e não fizemos isso nos últimos 5 minutos. */
    private boolean bounceAllowed() {
        try {
            if (bounces >= 2 || System.currentTimeMillis() - lastBounce < 5 * 60000L) return false;
            if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
            if (android.provider.Settings.Global.getInt(ctx.getContentResolver(), "adb_enabled", 0) != 1) return false;
            String p = sysProp("persist.adb.tcp.port");
            return p != null && p.matches("[1-9][0-9]{0,4}");
        } catch (Throwable t) { return false; }
    }

    /** Desliga e liga a depuração (adb_enabled 0 -> 1). Sempre tenta religar no fim. */
    private boolean bounceAdb() {
        android.content.ContentResolver cr = ctx.getContentResolver();
        boolean off = false;
        try {
            off = android.provider.Settings.Global.putInt(cr, "adb_enabled", 0);
            Thread.sleep(2000);
        } catch (Throwable ignored) {
        } finally {
            if (off) {
                for (int i = 0; i < 3; i++) {
                    try { if (android.provider.Settings.Global.putInt(cr, "adb_enabled", 1)) break; } catch (Throwable ignored) {}
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            }
        }
        if (!off) return false;
        try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
        return true;
    }

    private static String sysProp(String k) {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, k);
            if (v != null) return ((String) v).trim();
        } catch (Throwable ignored) {}
        try {
            Process pr = Runtime.getRuntime().exec(new String[]{"getprop", k});
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream()));
            String line = r.readLine();
            pr.waitFor();
            return line == null ? "" : line.trim();
        } catch (Throwable t) { return null; }
    }

    private void loop() {
        try {
            state = "conectando";
            sock = openSocket();
            sock.setTcpNoDelay(true);
            in = sock.getInputStream();
            out = sock.getOutputStream();
            KeyPair kp = loadKey();
            send(CNXN, VERSION, MAXDATA, "host::\0".getBytes("UTF-8"));
            boolean sentSig = false, sentPub = false;
            while (true) {
                Msg m = read(sentPub ? 60000 : 8000);
                if (m.cmd == CNXN) break;
                if (m.cmd == AUTH && m.a0 == 1) {
                    if (!sentSig) { send(AUTH, 2, 0, sign((RSAPrivateKey) kp.getPrivate(), m.data)); sentSig = true; }
                    else if (!sentPub) {
                        state = "aguardando autorização na TV";
                        RemoteAccessibility.autoAcceptDebug(60000);
                        send(AUTH, 3, 0, (pubKey((RSAPublicKey) kp.getPublic()) + " techsolutions\0").getBytes("UTF-8"));
                        sentPub = true;
                    }
                }
            }
            // abre um shell interativo que fica aberto
            send(OPEN, 1, 0, "shell:\0".getBytes("UTF-8"));
            while (true) {
                Msg m = read(8000);
                if (m.cmd == OKAY) { remoteId = m.a0; break; }
                if (m.cmd == CLSE) throw new Exception("shell recusado");
            }
            ready = true;
            state = "ativo";
            AgentService.adbChanged();
            Thread reader = new Thread(new Runnable() { public void run() { readLoop(); } }, "adb-r");
            reader.start();
            while (!stop && ready) {
                String c = queue.poll(30, TimeUnit.SECONDS);
                if (c == null) continue;
                write((c + "\n").getBytes("UTF-8"));
            }
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            state = (e instanceof java.net.ConnectException || msg.contains("ECONNREFUSED") || msg.contains("refused"))
                    ? "indisponível (depuração desligada)" : "erro: " + msg;
        } finally {
            boolean was = ready;
            ready = false;
            try { if (sock != null) sock.close(); } catch (Exception ignored) {}
            if (was) AgentService.adbChanged(); else AgentService.sendInfoIfConnected();
        }
    }

    private void readLoop() {
        try {
            while (ready) {
                Msg m = read(0);
                if (m.a1 != 1) { // canal de um comando com resposta
                    Exec e = execs.get(m.a1);
                    if (m.cmd == OKAY) { if (e != null) e.remote = m.a0; }
                    else if (m.cmd == WRTE) {
                        send(OKAY, m.a1, m.a0, null);
                        if (e != null && e.out.size() < 300000) e.out.write(m.data, 0, m.data.length);
                    } else if (m.cmd == CLSE) {
                        if (e != null && e.remote != 0) { try { send(CLSE, m.a1, m.a0, null); } catch (Exception ignored) {} }
                        finishExec(m.a1, null, false);
                    }
                    continue;
                }
                if (m.cmd == OKAY) { synchronized (okLock) { okPending = false; okLock.notifyAll(); } }
                else if (m.cmd == WRTE) send(OKAY, 1, m.a0, null);
                else if (m.cmd == CLSE) { ready = false; queue.offer("exit"); break; }
            }
        } catch (Exception e) {
            ready = false; queue.offer("exit");
        }
        for (Integer id : execs.keySet()) finishExec(id, "\n[modo avançado caiu]\n", false);
    }

    private void write(byte[] data) throws Exception {
        synchronized (okLock) {
            long until = System.currentTimeMillis() + 5000;
            while (okPending && System.currentTimeMillis() < until) okLock.wait(200);
            okPending = true;
        }
        send(WRTE, 1, remoteId, data);
    }

    // ---------------- protocolo ----------------
    private static class Msg { int cmd, a0, a1; byte[] data; }

    private synchronized void send(int cmd, int a0, int a1, byte[] data) throws Exception {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        if (data != null) for (byte b : data) sum += (b & 0xff);
        ByteBuffer bb = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(cmd ^ 0xffffffff);
        if (data != null) bb.put(data);
        out.write(bb.array());
        out.flush();
    }

    private Msg read(int timeout) throws Exception {
        sock.setSoTimeout(timeout);
        DataInputStream d = new DataInputStream(in);
        byte[] h = new byte[24];
        d.readFully(h);
        ByteBuffer bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = bb.getInt(); m.a0 = bb.getInt(); m.a1 = bb.getInt();
        int len = bb.getInt();
        m.data = new byte[len];
        if (len > 0) d.readFully(m.data);
        return m;
    }

    // ---------------- chaves ----------------
    private KeyPair loadKey() throws Exception {
        File pri = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (pri.exists() && pub.exists()) {
            try {
                return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(pri))));
            } catch (Exception ignored) {}
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        writeAll(pri, kp.getPrivate().getEncoded());
        writeAll(pub, kp.getPublic().getEncoded());
        return kp;
    }

    static byte[] sign(RSAPrivateKey k, byte[] token) throws Exception {
        // PKCS#1 v1.5 com DigestInfo SHA-1, onde o "hash" é o próprio token (é o que o adbd verifica)
        int klen = (k.getModulus().bitLength() + 7) / 8;
        byte[] em = new byte[klen];
        int tlen = SHA1_PREFIX.length + token.length;
        em[0] = 0; em[1] = 1;
        for (int i = 2; i < klen - tlen - 1; i++) em[i] = (byte) 0xff;
        em[klen - tlen - 1] = 0;
        System.arraycopy(SHA1_PREFIX, 0, em, klen - tlen, SHA1_PREFIX.length);
        System.arraycopy(token, 0, em, klen - token.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, k);
        byte[] s = c.doFinal(em);
        if (s.length == klen) return s;
        byte[] r = new byte[klen];
        System.arraycopy(s, Math.max(0, s.length - klen), r, Math.max(0, klen - s.length), Math.min(s.length, klen));
        return r;
    }

    /** Formato de chave pública do Android (struct RSAPublicKey em little-endian, base64). */
    static String pubKey(RSAPublicKey k) {
        BigInteger n = k.getModulus();
        int words = 2048 / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(words);
        bb.putInt(n0inv.intValue());
        bb.put(le(n, 256));
        bb.put(le(rr, 256));
        bb.putInt(k.getPublicExponent().intValue());
        return Base64.encodeToString(bb.array(), Base64.NO_WRAP);
    }

    private static byte[] le(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] r = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) r[i] = be[be.length - 1 - i];
        return r;
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream fi = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int o = 0, n;
        while (o < b.length && (n = fi.read(b, o, b.length - o)) > 0) o += n;
        fi.close();
        return b;
    }
    private static void writeAll(File f, byte[] b) throws Exception {
        FileOutputStream fo = new FileOutputStream(f); fo.write(b); fo.close();
    }
}
