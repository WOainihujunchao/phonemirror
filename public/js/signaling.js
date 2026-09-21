// 信令客户端：连接 WebSocket，加入房间，转发 WebRTC 信令
function pmGetCode() {
  try {
    const q = new URLSearchParams(location.search).get('code');
    if (q) { localStorage.setItem('pm_code', q); return q; }
    return localStorage.getItem('pm_code') || '';
  } catch (e) { return ''; }
}

function connectSignaling(room, role, handlers) {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}`);

  ws.onopen = () => {
    ws.send(JSON.stringify({ type: 'join', room, role, code: pmGetCode() }));
    handlers.onOpen && handlers.onOpen();
  };
  ws.onmessage = (e) => {
    let msg;
    try { msg = JSON.parse(e.data); } catch { return; }
    if (msg.type === 'auth-fail') {
      const c = window.prompt('访问口令不正确，请重新输入：');
      if (c && c.trim()) {
        try { localStorage.setItem('pm_code', c.trim()); } catch (err) {}
        location.reload();
      }
      return;
    }
    handlers.onMessage && handlers.onMessage(msg);
  };
  ws.onclose = () => handlers.onClose && handlers.onClose();
  ws.onerror = () => handlers.onError && handlers.onError();

  return {
    send: (m) => { if (ws.readyState === 1) ws.send(JSON.stringify(m)); },
    close: () => ws.close()
  };
}
