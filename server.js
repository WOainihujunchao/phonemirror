const fs = require('fs');
const path = require('path');
const https = require('https');
const os = require('os');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');
const selfsigned = require('selfsigned');
const QRCode = require('qrcode');
const { exec } = require('child_process');

const PORT = 3443;
const certDir = path.join(__dirname, 'cert');
const keyPath = path.join(certDir, 'key.pem');
const certPath = path.join(certDir, 'cert.pem');

function ensureCert() {
  if (fs.existsSync(keyPath) && fs.existsSync(certPath)) {
    console.log('* 使用已有证书 cert/cert.pem');
    return;
  }
  if (!fs.existsSync(certDir)) fs.mkdirSync(certDir, { recursive: true });
  console.log('* 首次运行，正在生成自签名证书（用于手机 HTTPS 录屏）...');
  const attrs = [{ name: 'commonName', value: 'phone-mirror.local' }];
  const pems = selfsigned.generate(attrs, { days: 3650, keySize: 2048 });
  fs.writeFileSync(keyPath, pems.private);
  fs.writeFileSync(certPath, pems.cert);
  console.log('* 证书已生成。');
}

ensureCert();

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon'
};

// 构建 ICE 服务器列表（STUN 必备；TURN 可选，用于跨严格 NAT / 蜂窝网络穿透）
function buildIceServers() {
  const list = [{ urls: 'stun:stun.l.google.com:19302' }];
  try {
    const tp = path.join(__dirname, 'turn.json');
    if (fs.existsSync(tp)) {
      const t = JSON.parse(fs.readFileSync(tp, 'utf8'));
      if (t && t.urls) {
        let urls = t.urls;
        if (typeof urls === 'string' && urls.includes(',')) urls = urls.split(',').map((s) => s.trim());
        const entry = { urls };
        if (t.username) entry.username = t.username;
        if (t.credential) entry.credential = t.credential;
        list.push(entry);
        console.log('* 已加载 TURN 中继配置: ' + (Array.isArray(urls) ? urls.join(', ') : urls));
      }
    }
  } catch (e) {
    console.log('* 读取 turn.json 失败，仅用 STUN: ' + e.message);
  }
  return list;
}
const ICE_SERVERS = buildIceServers();

// 访问口令保护：auth.json 里配 {"code":"你的口令"}，未配置则不启用
let ACCESS_CODE = '';
try {
  const ap = path.join(__dirname, 'auth.json');
  if (fs.existsSync(ap)) {
    const a = JSON.parse(fs.readFileSync(ap, 'utf8'));
    if (a && a.code) {
      ACCESS_CODE = String(a.code);
      console.log('* 已启用访问口令保护（未带正确口令的连接会被拒绝）');
    }
  }
} catch (e) {
  console.log('* 读取 auth.json 失败，未启用口令: ' + e.message);
}
if (process.env.PM_CODE) ACCESS_CODE = process.env.PM_CODE;

const server = https.createServer(
  { key: fs.readFileSync(keyPath), cert: fs.readFileSync(certPath) },
  async (req, res) => {
    let urlPath = decodeURIComponent(req.url.split('?')[0]);

    // 信令/配置接口：返回 ICE 服务器（含可选 TURN），供网页端与原生 App 拉取
    if (urlPath === '/api/config') {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
      res.end(JSON.stringify({ iceServers: ICE_SERVERS, base: global.__lanBase || ('https://localhost:' + PORT) }));
      return;
    }

    // 二维码生成：手机扫码即可进入对应页面（免手输地址/房间号）
    if (urlPath === '/qr') {
      const u = new URL(req.url, 'https://localhost');
      const text = u.searchParams.get('text');
      const size = Math.min(2000, Math.max(80, parseInt(u.searchParams.get('size') || '320', 10)));
      if (!text) { res.writeHead(400); res.end('missing text'); return; }
      try {
        const png = await QRCode.toBuffer(text, { width: size, margin: 1 });
        res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'no-store' });
        res.end(png);
      } catch (e) {
        res.writeHead(500); res.end('qr error');
      }
      return;
    }

    if (urlPath === '/') urlPath = '/index.html';
    const filePath = path.join(__dirname, 'public', urlPath);
    if (!filePath.startsWith(path.join(__dirname, 'public'))) {
      res.writeHead(403); res.end('Forbidden'); return;
    }
    fs.readFile(filePath, (err, data) => {
      if (err) { res.writeHead(404); res.end('Not Found'); return; }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(filePath)] || 'application/octet-stream' });
      res.end(data);
    });
  }
);

const wss = new WebSocketServer({ server });
const rooms = new Map(); // roomId -> { sender, viewers: Map<id, ws> }

// 心跳保活：服务器每 25s 发 ping，客户端（浏览器/原生）自动 pong；
// 防止公网隧道（如 Cloudflare Tunnel）因空闲断开 WebSocket。
const heartbeat = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) { try { ws.terminate(); } catch (e) {} return; }
    ws.isAlive = false;
    try { ws.ping(); } catch (e) {}
  });
}, 25000);
wss.on('close', () => clearInterval(heartbeat));

function getRoom(id) {
  if (!rooms.has(id)) rooms.set(id, { sender: null, viewers: new Map() });
  return rooms.get(id);
}

wss.on('connection', (ws) => {
  ws.id = crypto.randomBytes(6).toString('hex');
  ws.role = null;
  ws.room = null;
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return; }

    if (ACCESS_CODE && ws.authed !== true) {
      if (String(msg.code || '') !== ACCESS_CODE) {
        try { ws.send(JSON.stringify({ type: 'auth-fail' })); } catch (e) {}
        console.log(`[auth] 口令错误，拒绝连接 (${ws.id})`);
        try { ws.close(); } catch (e) {}
        return;
      }
      ws.authed = true;
    }

    if (msg.type === 'join') {
      const room = getRoom(msg.room);
      ws.room = msg.room;
      ws.role = msg.role;
      if (msg.role === 'sender') {
        room.sender = ws;
        for (const v of room.viewers.values()) v.send(JSON.stringify({ type: 'sender-ready' }));
        console.log(`[room ${msg.room}] 发送端加入 (${ws.id})`);
      } else if (msg.role === 'viewer') {
        room.viewers.set(ws.id, ws);
        if (room.sender) ws.send(JSON.stringify({ type: 'sender-ready' }));
        console.log(`[room ${msg.room}] 查看端加入 (${ws.id})`);
      }
      return;
    }

    const room = ws.room ? rooms.get(ws.room) : null;
    if (!room) return;

    if (ws.role === 'sender') {
      // 发送端 -> 指定查看端
      if (msg.type === 'answer' || msg.type === 'ice') {
        const target = msg.viewerId ? room.viewers.get(msg.viewerId) : null;
        if (target) target.send(JSON.stringify(msg));
      }
    } else if (ws.role === 'viewer') {
      // 查看端 -> 发送端（带上 viewerId 便于发送端匹配连接）
      if (msg.type === 'offer' || msg.type === 'ice') {
        if (room.sender) room.sender.send(JSON.stringify({ ...msg, viewerId: ws.id }));
      }
    }
  });

  ws.on('close', () => {
    const room = ws.room ? rooms.get(ws.room) : null;
    if (!room) return;
    if (ws.role === 'sender') {
      for (const v of room.viewers.values()) v.send(JSON.stringify({ type: 'sender-left' }));
      room.sender = null;
    } else if (ws.role === 'viewer') {
      room.viewers.delete(ws.id);
      if (room.sender) room.sender.send(JSON.stringify({ type: 'viewer-left', viewerId: ws.id }));
    }
  });
});

server.listen(PORT, '0.0.0.0', () => {
  const ips = [];
  for (const name of Object.keys(os.networkInterfaces())) {
    for (const ni of os.networkInterfaces()[name]) {
      if (ni.family === 'IPv4' && !ni.internal) ips.push(ni.address);
    }
  }
  console.log('================================================');
  console.log('  手机同屏镜像服务已启动');
  console.log(`  本机访问 : https://localhost:${PORT}`);
  for (const ip of ips) console.log(`  手机访问 : https://${ip}:${PORT}`);
  global.__lanBase = 'https://' + (ips[0] || 'localhost') + ':' + PORT;
  console.log('------------------------------------------------');
  console.log('  被查看的手机 -> 打开 /sender.html');
  console.log('  你的手机/电脑 -> 打开 /viewer.html');
  console.log('  两端输入相同「房间号」即可实时同屏。');
  console.log('================================================');
  // 自动用默认浏览器打开首页（首页即二维码面板，手机扫码即用）
  try { exec('cmd /c start "" "https://localhost:' + PORT + '/"'); } catch (e) {}
});
