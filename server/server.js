// TV Remote relay: ponte entre os agentes (TV Box) e o painel web.
// Agentes e painel conectam SAINDO para este servidor, então só ele precisa estar acessível.
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = parseInt(process.env.PORT || '8080', 10);
// Token opcional dos agentes (embutido no APK). Sem ele, qualquer agente pode se registrar,
// mas só o painel (com senha) consegue ver ou controlar qualquer coisa.
const AGENT_TOKEN = process.env.AGENT_TOKEN || '';
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const APK_DIR = path.join(DATA_DIR, 'apks');
const MAX_UPLOAD = parseInt(process.env.MAX_UPLOAD_MB || '500', 10) * 1024 * 1024;
const APK_TTL_H = parseInt(process.env.APK_TTL_HOURS || '72', 10);
fs.mkdirSync(APK_DIR, { recursive: true });

// Senha do painel: variável TOKEN, ou criada no primeiro acesso ao painel (salva como hash em DATA_DIR/admin.json)
const ADMIN_FILE = path.join(DATA_DIR, 'admin.json');
const sha = (s) => crypto.createHash('sha256').update(String(s)).digest('hex');
let adminHash = process.env.TOKEN ? sha(process.env.TOKEN) : (fs.existsSync(ADMIN_FILE) ? JSON.parse(fs.readFileSync(ADMIN_FILE, 'utf8')).hash : null);
if (!adminHash) console.warn('[!] Senha do painel ainda não definida: abra o painel para criar.');

const safeEq = (a, b) => {
  const x = Buffer.from(String(a || '')), y = Buffer.from(String(b || ''));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
};
const tokOf = (req, url) => url.searchParams.get('token') || req.headers['x-token'];
const authOk = (req, url) => !!adminHash && !!tokOf(req, url) && safeEq(sha(tokOf(req, url)), adminHash);
const agentOk = (req, url) => !AGENT_TOKEN || safeEq(tokOf(req, url), AGENT_TOKEN) || authOk(req, url);
const log = (...a) => console.log(new Date().toISOString(), ...a);

// ---------- estado ----------
/** id -> { ws, info, watchers:Set<ws> } */
const agents = new Map();
const panels = new Set();

function deviceList() {
  return [...agents.entries()].map(([id, a]) => ({ id, ...a.info, watchers: a.watchers.size }));
}
function broadcastDevices() {
  const msg = JSON.stringify({ type: 'devices', list: deviceList() });
  for (const p of panels) if (p.readyState === 1) p.send(msg);
}
function sendJson(ws, obj) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); }
function updateStream(id) {
  const a = agents.get(id);
  if (a) sendJson(a.ws, { type: 'stream', on: a.watchers.size > 0 });
}

// ---------- HTTP ----------
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml', '.apk': 'application/vnd.android.package-archive' };

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;

  // Upload de APK (corpo bruto). Painel envia com header X-Token.
  if (p === '/api/upload' && req.method === 'POST') {
    if (!authOk(req, url)) { res.writeHead(401); return res.end('unauthorized'); }
    const orig = (url.searchParams.get('name') || 'app.apk').replace(/[^\w.\-]+/g, '_').slice(0, 100);
    const file = `${Date.now()}-${crypto.randomBytes(16).toString('hex')}-${orig.endsWith('.apk') ? orig : orig + '.apk'}`;
    const dest = path.join(APK_DIR, file);
    const out = fs.createWriteStream(dest);
    let size = 0, aborted = false;
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_UPLOAD && !aborted) { aborted = true; req.destroy(); out.destroy(); fs.rm(dest, () => {}); }
    });
    req.pipe(out);
    out.on('finish', () => {
      if (aborted) return;
      log('upload', file, size);
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ path: `/apk/${file}`, size, name: orig }));
    });
    out.on('error', () => { if (!res.headersSent) { res.writeHead(500); res.end('erro'); } });
    return;
  }

  // Download do APK pelo agente (token na query)
  if (p.startsWith('/apk/')) {
    if (!agentOk(req, url)) { res.writeHead(401); return res.end('unauthorized'); }
    const f = path.join(APK_DIR, path.basename(p));
    return fs.stat(f, (err, st) => {
      if (err) { res.writeHead(404); return res.end('not found'); }
      res.writeHead(200, { 'content-type': MIME['.apk'], 'content-length': st.size });
      fs.createReadStream(f).pipe(res);
    });
  }

  if (p === '/api/setup') {
    if (req.method === 'GET') { res.writeHead(200, { 'content-type': 'application/json' }); return res.end(JSON.stringify({ needsSetup: !adminHash })); }
    if (req.method === 'POST') {
      if (adminHash) { res.writeHead(403); return res.end('já configurado'); }
      let body = '';
      req.on('data', (c) => { body += c; if (body.length > 1000) req.destroy(); });
      req.on('end', () => {
        let pw = ''; try { pw = JSON.parse(body).password || ''; } catch {}
        if (pw.length < 8) { res.writeHead(400); return res.end('senha curta (mín. 8)'); }
        adminHash = sha(pw);
        fs.writeFileSync(ADMIN_FILE, JSON.stringify({ hash: adminHash, created: new Date().toISOString() }));
        log('senha do painel criada');
        res.writeHead(200, { 'content-type': 'application/json' }); res.end('{"ok":true}');
      });
      return;
    }
  }

  if (p === '/api/devices') {
    if (!authOk(req, url)) { res.writeHead(401); return res.end('unauthorized'); }
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify(deviceList()));
  }

  // Arquivos estáticos do painel
  const rel = p === '/' ? 'index.html' : p.slice(1);
  const f = path.join(__dirname, 'public', path.normalize(rel).replace(/^(\.\.[/\\])+/, ''));
  fs.readFile(f, (err, buf) => {
    if (err) { res.writeHead(404); return res.end('not found'); }
    // sem cache (Cloudflare guardava o agent.apk antigo)
    res.writeHead(200, { 'content-type': MIME[path.extname(f)] || 'application/octet-stream', 'cache-control': 'no-store, max-age=0' });
    res.end(buf);
  });
});

// ---------- WebSocket ----------
const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 8 * 1024 * 1024 });

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://x');
  const role = url.searchParams.get('role');
  if (!(role === 'agent' ? agentOk(req, url) : authOk(req, url))) { ws.close(4001, 'unauthorized'); return; }
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  if (role === 'agent') {
    const id = (url.searchParams.get('id') || '').slice(0, 64);
    if (!id) return ws.close(4002, 'id requerido');
    const old = agents.get(id);
    const watchers = old ? old.watchers : new Set();
    if (old && old.ws !== ws) try { old.ws.terminate(); } catch {}
    const a = { ws, info: { name: url.searchParams.get('name') || id, online: true, since: Date.now(), ip: req.headers['x-forwarded-for'] || req.socket.remoteAddress }, watchers };
    agents.set(id, a);
    log('agent on', id, a.info.name);
    broadcastDevices();
    updateStream(id);

    ws.on('message', (data, isBinary) => {
      if (isBinary) {
        // Frame JPEG -> todos os painéis assistindo (com backpressure simples)
        for (const p of a.watchers) if (p.readyState === 1 && p.bufferedAmount < 2 * 1024 * 1024) p.send(data, { binary: true });
        return;
      }
      let m; try { m = JSON.parse(data); } catch { return; }
      if (m.type === 'info') { Object.assign(a.info, m.info || {}); broadcastDevices(); return; }
      // status, apps, screen, etc. -> painéis que assistem (ou todos se for status)
      const out = JSON.stringify({ ...m, device: id });
      const targets = m.type === 'status' ? panels : a.watchers;
      for (const p of targets) if (p.readyState === 1) p.send(out);
    });
    ws.on('close', () => {
      if (agents.get(id) === a) { agents.delete(id); log('agent off', id); broadcastDevices(); }
    });
    return;
  }

  // painel
  panels.add(ws);
  ws.watching = null;
  sendJson(ws, { type: 'devices', list: deviceList() });
  ws.on('message', (data) => {
    let m; try { m = JSON.parse(data); } catch { return; }
    if (m.type === 'watch') {
      if (ws.watching && agents.get(ws.watching)) { agents.get(ws.watching).watchers.delete(ws); updateStream(ws.watching); }
      ws.watching = m.id || null;
      const a = ws.watching && agents.get(ws.watching);
      if (a) { a.watchers.add(ws); updateStream(ws.watching); sendJson(a.ws, { type: 'hello' }); }
      broadcastDevices();
    } else if (m.type === 'cmd') {
      const a = agents.get(m.id || ws.watching);
      if (!a) return sendJson(ws, { type: 'status', level: 'error', msg: 'Dispositivo offline' });
      sendJson(a.ws, m.cmd);
    }
  });
  ws.on('close', () => {
    panels.delete(ws);
    const a = ws.watching && agents.get(ws.watching);
    if (a) { a.watchers.delete(ws); updateStream(ws.watching); broadcastDevices(); }
  });
});

// keepalive (derruba conexões mortas atrás de NAT)
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false; try { ws.ping(); } catch {}
  }
}, 25000);

// limpeza de APKs antigos
setInterval(() => {
  fs.readdir(APK_DIR, (e, files) => {
    if (e) return;
    for (const f of files) {
      const fp = path.join(APK_DIR, f);
      fs.stat(fp, (er, st) => { if (!er && Date.now() - st.mtimeMs > APK_TTL_H * 3600e3) fs.rm(fp, () => {}); });
    }
  });
}, 3600e3);

server.listen(PORT, () => log(`TV Remote relay em http://0.0.0.0:${PORT}`));
