package com.arilson.tvremote;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Diagnóstico e proteção:
 *  - um erro inesperado no app NÃO derruba mais o processo (se derrubasse, o Android desliga o controle/acessibilidade);
 *  - o erro fica guardado e vai para o painel;
 *  - monta o relatório do botão "Diagnóstico" do painel.
 */
final class Diag {
    private static File file;
    private static Thread.UncaughtExceptionHandler original;
    static volatile boolean unreported = false;
    static volatile String lastError = "";

    static void install(Context c) {
        file = new File(c.getFilesDir(), "erros.txt");
        unreported = file.exists() && file.length() > 0 && new File(c.getFilesDir(), "erros.novo").exists();
        original = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                save("thread " + t.getName(), e);
                // a thread principal só chega aqui se o erro escapou antes da proteção abaixo: aí deixa o Android tratar
                if (t == Looper.getMainLooper().getThread() && original != null) original.uncaughtException(t, e);
                // nas outras threads: a thread termina, mas o app (e o controle) continuam vivos
            }
        });
        // mantém a thread principal rodando mesmo se algum comando der erro
        new Handler(Looper.getMainLooper()).post(new Runnable() { public void run() {
            while (true) {
                try { Looper.loop(); }
                catch (Throwable e) { save("principal", e); }
            }
        }});
    }

    static synchronized void save(String where, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String st = sw.toString();
            if (st.length() > 6000) st = st.substring(0, 6000);
            lastError = e.toString();
            String entry = "=== " + now() + " · v" + BuildInfo.VERSION + " · " + where + "\n" + st + "\n";
            String old = read(file);
            String all = old + entry;
            if (all.length() > 40000) all = all.substring(all.length() - 40000);
            write(file, all);
            write(new File(file.getParentFile(), "erros.novo"), "1");
            unreported = true;
            android.util.Log.e("TechSolutions", "erro protegido em " + where, e);
        } catch (Throwable ignored) {}
        try { AgentService.reportError(); } catch (Throwable ignored) {}
    }

    static void markReported() {
        unreported = false;
        try { if (file != null) new File(file.getParentFile(), "erros.novo").delete(); } catch (Throwable ignored) {}
    }

    static String errors() { return read(file); }

    /** Relatório local (não precisa do Modo avançado). */
    static String report(Context c) {
        StringBuilder b = new StringBuilder();
        b.append("TechSolutions v").append(BuildInfo.VERSION).append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        b.append("Agora: ").append(now()).append('\n');
        b.append("\n— CONTROLE (acessibilidade)\n");
        b.append("Rodando agora: ").append(RemoteAccessibility.instance != null ? "SIM" : "NÃO").append('\n');
        b.append("Ligado nas Configurações: ").append(AccGuard.inSettings(c) ? "SIM" : "NÃO").append('\n');
        b.append("Situação: ").append(AccGuard.describe(c)).append('\n');
        b.append("Pode religar sozinho: ").append(AccGuard.canWriteSecure(c) ? "SIM (permissão do sistema)" : AdbShell.isReady() ? "SIM (modo avançado)" : "NÃO").append('\n');
        b.append("Última vez que o Android desligou: ").append(AccGuard.lastLost == 0 ? "—" : fmt(AccGuard.lastLost)).append('\n');
        b.append("Religamentos automáticos: ").append(AccGuard.repairs).append('\n');
        b.append("Eventos:\n").append(eventsText());
        b.append("enabled_accessibility_services = ").append(Settings.Secure.getString(c.getContentResolver(), "enabled_accessibility_services")).append('\n');
        b.append("\n— OUTROS\n");
        b.append("Ver a tela: ").append(AgentService.capturing() ? "ligado" : "desligado").append('\n');
        b.append("Modo avançado: ").append(AdbShell.isReady() ? "ativo" : AdbShell.state).append('\n');
        b.append("Servidor: ").append(AgentService.connState).append('\n');
        b.append("Processo iniciado há: ").append((android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 1000).append(" s\n");
        b.append("TV ligada há: ").append(android.os.SystemClock.elapsedRealtime() / 60000).append(" min\n");
        if (Build.VERSION.SDK_INT >= 30) {
            b.append("\n— POR QUE O APP FECHOU ANTES (Android)\n");
            try {
                ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 6);
                if (l.isEmpty()) b.append("(nenhum registro)\n");
                for (android.app.ApplicationExitInfo x : l)
                    b.append(fmt(x.getTimestamp())).append(" · ").append(exitReason(x.getReason())).append(" · ")
                            .append(x.getDescription()).append('\n');
            } catch (Throwable t) { b.append("(indisponível: ").append(t).append(")\n"); }
        }
        b.append("\n— ERROS GUARDADOS NO APP\n");
        String er = errors();
        b.append(er.isEmpty() ? "(nenhum)\n" : tail(er, 15000));
        b.append("\n— LOG DO APP (últimas linhas)\n");
        b.append(ownLog(250));
        return b.toString();
    }

    /** Log do próprio app (o Android deixa o app ler o próprio log sem permissão especial). */
    static String ownLog(int lines) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time", "-t", String.valueOf(lines)});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder b = new StringBuilder(); String s;
            long until = System.currentTimeMillis() + 4000;
            while ((s = r.readLine()) != null && System.currentTimeMillis() < until) b.append(s).append('\n');
            r.close(); p.destroy();
            return b.length() == 0 ? "(vazio)\n" : tail(b.toString(), 25000);
        } catch (Throwable t) { return "(não consegui ler: " + t + ")\n"; }
    }

    private static String exitReason(int r) {
        switch (r) {
            case 4: return "TRAVOU (erro)";
            case 5: return "TRAVOU (erro nativo)";
            case 6: return "NÃO RESPONDIA (ANR)";
            case 3: return "pouca memória";
            case 2: return "sinal";
            case 1: return "saiu sozinho";
            case 10: return "usuário/sistema fechou";
            case 11: return "usuário parou";
            case 12: return "dependência morreu";
            case 13: return "outro";
            case 9: return "uso excessivo (bateria/memória)";
            case 7: return "inicialização falhou";
            case 8: return "permissão mudou";
            case 14: return "congelado";
            default: return "motivo " + r;
        }
    }

    static String tail(String s, int max) { return s.length() <= max ? s : "…" + s.substring(s.length() - max); }
    private static String now() { return fmt(System.currentTimeMillis()); }
    static String fmt(long t) { return new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(new Date(t)); }

    private static String read(File f) {
        try {
            if (f == null || !f.exists()) return "";
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()]; int o = 0, n;
            while (o < b.length && (n = in.read(b, o, b.length - o)) > 0) o += n;
            in.close();
            return new String(b, 0, o, "UTF-8");
        } catch (Throwable e) { return ""; }
    }
    private static void write(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f); o.write(s.getBytes("UTF-8")); o.close();
    }

    /** Lista simples para guardar eventos curtos (ligou/desligou o controle). */
    static final List<String> events = new ArrayList<>();
    static synchronized void event(String s) {
        events.add(fmt(System.currentTimeMillis()) + " " + s);
        while (events.size() > 40) events.remove(0);
    }
    static synchronized String eventsText() {
        StringBuilder b = new StringBuilder();
        for (String s : events) b.append(s).append('\n');
        return b.length() == 0 ? "(nenhum)\n" : b.toString();
    }
}
