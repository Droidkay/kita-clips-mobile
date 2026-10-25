import {
  generateCode, formatCode, normalizeCode, CODE_LEN,
  roomIdFor, keyFor, encryptPayload, decryptPayload,
} from './crypto.js';

const $ = (s) => document.querySelector(s);
const LS = {
  get(k, d = null) { try { return localStorage.getItem('kc.' + k) ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem('kc.' + k, v); } catch { /* ignore */ } },
  del(k) { try { localStorage.removeItem('kc.' + k); } catch { /* ignore */ } },
};

const state = {
  code: null, key: null, roomId: null, ws: null, gen: 0, attempt: 0, reconnectTimer: null,
  clips: new Map(), peers: 0,
  device: LS.get('device') || (/Mac/i.test(navigator.platform) ? 'Mac' : 'Web'),
  autoCopy: LS.get('autoCopy', '1') === '1',
  pending: [],
};

function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.remove('show'), 1800);
}

function setStatus(kind, text) {
  $('#dot').className = 'dot ' + (kind === 'on' ? 'on' : kind === 'connecting' ? 'connecting' : '');
  $('#statusText').textContent = text;
}

function newId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, '0')).join('');
}

function timeAgo(ts) {
  const s = Math.max(0, Math.round((Date.now() - ts) / 1000));
  if (s < 10) return 'just now';
  if (s < 60) return s + 's ago';
  if (s < 3600) return Math.floor(s / 60) + 'm ago';
  if (s < 86400) return Math.floor(s / 3600) + 'h ago';
  return Math.floor(s / 86400) + 'd ago';
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

/* ---------- rendering ---------- */
function render(freshId = null) {
  const list = [...state.clips.values()].sort((a, b) => b.ts - a.ts);
  const grid = $('#clips');
  grid.textContent = '';
  for (const clip of list) {
    const card = document.createElement('div');
    card.className = 'clip' + (clip.id === freshId ? ' fresh' : '');

    const text = document.createElement('div');
    text.className = 'text';
    text.textContent = clip.text;
    text.title = 'Click to copy';
    text.onclick = async () => toast((await copyText(clip.text)) ? 'Copied' : 'Copy failed');

    const meta = document.createElement('div');
    meta.className = 'meta';
    const who = document.createElement('span');
    who.textContent = `${clip.device} · ${timeAgo(clip.ts)}`;
    const spacer = document.createElement('span');
    spacer.className = 'spacer';
    const copy = document.createElement('button');
    copy.className = 'small';
    copy.textContent = 'Copy';
    copy.onclick = async () => toast((await copyText(clip.text)) ? 'Copied' : 'Copy failed');
    const del = document.createElement('button');
    del.className = 'small danger';
    del.textContent = 'Delete';
    del.onclick = () => deleteClip(clip.id);
    meta.append(who, spacer, copy, del);

    card.append(text, meta);
    grid.append(card);
  }
  $('#empty').classList.toggle('hidden', list.length > 0);
}

/* ---------- networking ---------- */
function wsUrl() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${location.host}/ws?room=${state.roomId}`;
}

function connect() {
  clearTimeout(state.reconnectTimer);
  const gen = ++state.gen;
  setStatus('connecting', 'Connecting…');
  const ws = new WebSocket(wsUrl());
  state.ws = ws;

  ws.onopen = () => {
    if (gen !== state.gen) return;
    state.attempt = 0;
    setStatus('on', peerText());
    for (const msg of state.pending.splice(0)) ws.send(JSON.stringify(msg));
  };
  ws.onmessage = (ev) => {
    if (gen !== state.gen) return;
    handleMessage(ev.data);
  };
  const lost = () => {
    if (gen !== state.gen) return;
    state.peers = 0;
    setStatus('off', 'Offline – retrying…');
    const delay = Math.min(30000, 1000 * 2 ** Math.min(state.attempt++, 5));
    state.reconnectTimer = setTimeout(connect, delay);
  };
  ws.onclose = lost;
  ws.onerror = () => { try { ws.close(); } catch { /* ignore */ } };
}

function peerText() {
  const others = Math.max(0, state.peers - 1);
  return others === 0 ? 'Connected · just you' : `Connected · ${others} other device${others === 1 ? '' : 's'}`;
}

async function decodeClip(raw) {
  try {
    const p = await decryptPayload(state.key, raw.blob);
    return { id: raw.id, ts: raw.ts, text: String(p.t), device: String(p.d || '?') };
  } catch {
    return null; // wrong key or corrupt – ignore
  }
}

async function handleMessage(data) {
  let msg;
  try { msg = JSON.parse(data); } catch { return; }
  if (msg.type === 'history') {
    for (const raw of msg.clips || []) {
      const clip = await decodeClip(raw);
      if (clip && !state.clips.has(clip.id)) state.clips.set(clip.id, clip);
    }
    render();
  } else if (msg.type === 'clip') {
    const clip = await decodeClip(msg.clip);
    if (!clip || state.clips.has(clip.id)) return;
    state.clips.set(clip.id, clip);
    render(clip.id);
    if (state.autoCopy) {
      const ok = await copyText(clip.text);
      toast(ok ? `Copied from ${clip.device}` : `New clip from ${clip.device}`);
    } else {
      toast(`New clip from ${clip.device}`);
    }
  } else if (msg.type === 'delete') {
    state.clips.delete(msg.id);
    render();
  } else if (msg.type === 'clear') {
    state.clips.clear();
    render();
  } else if (msg.type === 'peers') {
    state.peers = msg.count;
    if (state.ws && state.ws.readyState === WebSocket.OPEN) setStatus('on', peerText());
  }
}

function sendMsg(msg) {
  if (state.ws && state.ws.readyState === WebSocket.OPEN) state.ws.send(JSON.stringify(msg));
  else state.pending.push(msg);
}

async function sendText(text) {
  if (!text || !text.trim()) return false;
  if (text.length > 100000) { toast('Too long (100,000 characters max)'); return false; }
  for (const c of state.clips.values()) {
    if (c.text === text && Date.now() - c.ts < 5000) return false; // duplicate
  }
  const clip = { id: newId(), ts: Date.now(), text, device: state.device };
  state.clips.set(clip.id, clip);
  render();
  const blob = await encryptPayload(state.key, { t: text, d: state.device });
  sendMsg({ type: 'clip', id: clip.id, ts: clip.ts, blob });
  return true;
}

function deleteClip(id) {
  state.clips.delete(id);
  render();
  sendMsg({ type: 'delete', id });
}

/* ---------- room lifecycle ---------- */
async function enterRoom(code) {
  const norm = normalizeCode(code);
  if (norm.length !== CODE_LEN) throw new Error(`A room code has ${CODE_LEN} characters`);
  if (!crypto.subtle) throw new Error('Encryption needs https:// (or localhost). Open this site over https.');
  state.code = norm;
  state.roomId = await roomIdFor(norm);
  state.key = await keyFor(norm);
  state.clips.clear();
  LS.set('code', norm);

  $('#landing').classList.add('hidden');
  $('#room').classList.remove('hidden');
  $('#leaveBtn').classList.remove('hidden');
  $('#codeText').textContent = formatCode(norm);
  $('#originText').textContent = location.origin;
  drawQr(norm);
  render();
  connect();
}

function drawQr(code) {
  const payload = `kitaclips://join?s=${encodeURIComponent(location.origin)}&r=${code}`;
  const qr = window.qrcode(0, 'M');
  qr.addData(payload);
  qr.make();
  $('#qr').innerHTML = qr.createSvgTag({ cellSize: 5, margin: 0, scalable: true });
  const svg = $('#qr svg');
  if (svg) { svg.style.width = '180px'; svg.style.height = '180px'; }
}

function leaveRoom() {
  state.gen++;
  clearTimeout(state.reconnectTimer);
  try { state.ws && state.ws.close(); } catch { /* ignore */ }
  state.code = null; state.key = null; state.pending = [];
  state.clips.clear();
  LS.del('code');
  $('#room').classList.add('hidden');
  $('#leaveBtn').classList.add('hidden');
  $('#landing').classList.remove('hidden');
  setStatus('off', 'Not connected');
  history.replaceState(null, '', location.pathname);
}

/* ---------- wiring ---------- */
$('#createBtn').onclick = () => enterRoom(generateCode()).catch((e) => toast(e.message));
$('#joinBtn').onclick = async () => {
  const err = $('#joinErr');
  err.classList.add('hidden');
  try {
    await enterRoom($('#joinInput').value);
  } catch (e) {
    err.textContent = e.message;
    err.classList.remove('hidden');
  }
};
$('#joinInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#joinBtn').click(); });
$('#leaveBtn').onclick = leaveRoom;
$('#codeText').onclick = async () => toast((await copyText(formatCode(state.code))) ? 'Room code copied' : 'Copy failed');

$('#sendBtn').onclick = async () => {
  const box = $('#composer');
  if (await sendText(box.value)) box.value = '';
};
$('#composer').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) $('#sendBtn').click();
});
$('#sendClipboardBtn').onclick = async () => {
  try {
    const text = await navigator.clipboard.readText();
    if (!(await sendText(text))) toast('Nothing new to send');
    else toast('Sent');
  } catch {
    toast('Allow clipboard access, or paste into the box');
  }
};
$('#clearBtn').onclick = () => {
  if (!state.clips.size) return;
  if (!confirm('Delete all clips on every device in this room?')) return;
  state.clips.clear();
  render();
  sendMsg({ type: 'clear' });
};
$('#autoCopy').checked = state.autoCopy;
$('#autoCopy').onchange = (e) => {
  state.autoCopy = e.target.checked;
  LS.set('autoCopy', state.autoCopy ? '1' : '0');
};

setInterval(() => { if (state.code) render(); }, 30000);

// Auto-join from a saved code or a #/join/CODE link.
const hashMatch = location.hash.match(/^#\/join\/([A-Za-z0-9-]+)/);
const startCode = hashMatch ? hashMatch[1] : LS.get('code');
if (startCode) enterRoom(startCode).catch(() => { LS.del('code'); });
