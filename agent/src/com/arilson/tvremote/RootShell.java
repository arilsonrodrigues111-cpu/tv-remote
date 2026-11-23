package com.arilson.tvremote;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Root direto no app, sem depender da depuração/ADB: roda comandos pelo "su" da própria TV.
 * Só funciona em caixinhas cujo su libera root para apps (muitas genéricas liberam).
 * Nunca na main thread (faz I/O de processo).
 */
public class RootShell {
    static volatile int rooted = 0; // 0 = não testado, 1 = tem root, -1 = não tem

    public static boolean available() { return rooted == 1; }

    /** Roda um comando como root (su lê do stdin). Devolve a saída, ou null se o su falhar.
     *  Tem watchdog de 7 s: se o su travar pedindo confirmação, mata o processo e desiste. Bloqueante. */
    public static String run(String script) {
        final Process[] pp = new Process[1];
        Thread wd = null;
        try {
            final Process p = Runtime.getRuntime().exec("su");
            pp[0] = p;
            wd = new Thread(new Runnable() { public void run() {
                try { Thread.sleep(7000); } catch (InterruptedException e) { return; }
                try { p.destroy(); } catch (Throwable ignored) {}
            }});
            wd.setDaemon(true);
            wd.start();
            OutputStream os = p.getOutputStream();
            os.write((script + "\nexit\n").getBytes("UTF-8"));
            os.flush();
            os.close();
            String out = read(p.getInputStream());
            String err = read(p.getErrorStream());
            p.waitFor();
            return out + (err.isEmpty() ? "" : "\n" + err);
        } catch (Throwable t) {
            return null;
        } finally {
            if (wd != null) wd.interrupt();
            if (pp[0] != null) try { pp[0].destroy(); } catch (Throwable ignored) {}
        }
    }

    /** Testa se o su dá root e guarda o resultado. Bloqueante. */
    public static boolean check() {
        String o = run("id");
        rooted = (o != null && o.contains("uid=0")) ? 1 : -1;
        return rooted == 1;
    }

    private static String read(InputStream is) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) b.write(buf, 0, n);
        return new String(b.toByteArray(), "UTF-8");
    }
}
