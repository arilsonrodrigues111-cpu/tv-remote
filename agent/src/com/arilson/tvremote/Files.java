package com.arilson.tvremote;

import android.content.Context;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Comparator;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Explorador de arquivos da TV: lista pastas, cria, apaga, baixa para o servidor e recebe do servidor. */
public class Files {

    /** Raiz segura: o armazenamento compartilhado (onde ficam Download, filmes, etc). */
    static File root() { return Environment.getExternalStorageDirectory(); }

    static File resolve(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) return root();
        File f = new File(path);
        if (!f.isAbsolute()) f = new File(root(), path);
        return f;
    }

    static void list(final Context c, final String path) {
        new Thread(new Runnable() { public void run() {
            try {
                File dir = resolve(path);
                File[] files = dir.listFiles();
                if (files == null) files = new File[0];
                Arrays.sort(files, new Comparator<File>() { public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }});
                JSONArray arr = new JSONArray();
                for (File f : files) {
                    if (f.getName().startsWith(".")) continue;
                    arr.put(new JSONObject().put("name", f.getName()).put("path", f.getAbsolutePath())
                            .put("dir", f.isDirectory()).put("size", f.isDirectory() ? 0 : f.length())
                            .put("apk", f.getName().toLowerCase().endsWith(".apk")));
                }
                AgentService s = AgentService.instance;
                if (s != null) s.sendJson(new JSONObject().put("type", "files")
                        .put("path", dir.getAbsolutePath()).put("root", root().getAbsolutePath()).put("list", arr));
            } catch (Exception e) { AgentService.status("error", "Arquivos: " + e.getMessage()); }
        }}).start();
    }

    static void mkdir(Context c, String path) {
        File d = resolve(path);
        if (d.mkdirs() || d.isDirectory()) { AgentService.status("ok", "Pasta criada"); list(c, d.getParent()); }
        else AgentService.status("error", "Não consegui criar a pasta");
    }

    static void delete(Context c, String path) {
        File f = resolve(path);
        String parent = f.getParent();
        if (deleteRec(f)) { AgentService.status("ok", "Apagado: " + f.getName()); list(c, parent); }
        else AgentService.status("error", "Não consegui apagar " + f.getName());
    }
    private static boolean deleteRec(File f) {
        if (f.isDirectory()) { File[] ch = f.listFiles(); if (ch != null) for (File x : ch) deleteRec(x); }
        return f.delete();
    }

    /** Envia um arquivo da TV para o servidor (botão "Baixar" no painel). */
    static void upload(final Context c, final OkHttpClient http, final String path, final String name) {
        new Thread(new Runnable() { public void run() {
            try {
                final File f = resolve(path);
                if (!f.isFile()) { AgentService.status("error", "Arquivo não encontrado"); return; }
                RequestBody body = new RequestBody() {
                    public MediaType contentType() { return MediaType.parse("application/octet-stream"); }
                    public long contentLength() { return f.length(); }
                    public void writeTo(okio.BufferedSink sink) throws java.io.IOException {
                        FileInputStream in = new FileInputStream(f); byte[] buf = new byte[64 * 1024]; int n;
                        try { while ((n = in.read(buf)) > 0) sink.write(buf, 0, n); } finally { in.close(); }
                    }
                };
                String url = Prefs.httpBase(c) + "/api/fromtv?token=" + enc(Prefs.token(c))
                        + "&device=" + enc(Prefs.deviceId(c)) + "&name=" + enc(f.getName());
                Response r = http.newCall(new Request.Builder().url(url).post(body).build()).execute();
                AgentService.status(r.isSuccessful() ? "ok" : "error", r.isSuccessful()
                        ? "Arquivo " + f.getName() + " enviado para o painel" : "Falha ao enviar (HTTP " + r.code() + ")");
                r.close();
            } catch (Exception e) { AgentService.status("error", "Envio falhou: " + e.getMessage()); }
        }}).start();
    }

    /** Recebe um arquivo do servidor e grava numa pasta da TV (sem instalar). */
    static void download(final Context c, final OkHttpClient http, final JSONObject m) {
        new Thread(new Runnable() { public void run() {
            try {
                String name = m.optString("name", "arquivo");
                File dir = resolve(m.optString("dir", Environment.DIRECTORY_DOWNLOADS));
                if (!dir.isDirectory()) dir = new File(root(), Environment.DIRECTORY_DOWNLOADS);
                dir.mkdirs();
                File out = new File(dir, name);
                String url = m.optString("url", "");
                if (url.isEmpty()) url = Prefs.httpBase(c) + m.getString("path") + "?token=" + enc(Prefs.token(c));
                Response r = http.newCall(new Request.Builder().url(url).build()).execute();
                if (!r.isSuccessful()) throw new Exception("HTTP " + r.code());
                InputStream in = r.body().byteStream(); OutputStream os = new FileOutputStream(out);
                byte[] buf = new byte[64 * 1024]; int n; while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.close(); in.close(); r.close();
                AgentService.status("ok", "Salvo na TV: " + out.getAbsolutePath());
                list(c, dir.getAbsolutePath());
            } catch (Exception e) { AgentService.status("error", "Download falhou: " + e.getMessage()); }
        }}).start();
    }

    private static String enc(String s) { try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }
}
