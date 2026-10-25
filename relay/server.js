// Kita Clips relay
// - Rooms are identified by an opaque id (SHA-256 of the room code, computed by clients).
// - Clip payloads are AES-GCM encrypted by clients; this server only ever sees opaque blobs.
// - Everything is in memory. Restarting the server clears history.

import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { WebSocketServer } from 'ws';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.join(__dirname, 'public');

const PORT = Number(process.env.PORT || 8080);
const MAX_CLIPS_PER_ROOM = 100;
const MAX_MESSAGE_BYTES = 256 * 1024;
const MAX_BLOB_CHARS = 200_000;
const MAX_CLIENTS_PER_ROOM = 12;
const MAX_MESSAGES_PER_SECOND = 30;
const EMPTY_ROOM_TTL_MS = 6 * 60 * 60 * 1000;
const ROOM_ID_RE = /^[a-f0-9]{64}$/;

/** @type {Map<string, {clients:Set<import('ws').WebSocket>, clips:Array<{id:string,ts:number,blob:string}>, lastActive:number}>} */
const rooms = new Map();

function getRoom(id) {
  let room = rooms.get(id);
  if (!room) {
    room = { clients: new Set(), clips: [], lastActive: Date.now() };
    rooms.set(id, room);
  }
  return room;
}

function send(ws, obj) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
}

function broadcast(room, obj, except = null) {
  const data = JSON.stringify(obj);
  for (const c of room.clients) {
    if (c !== except && c.readyState === c.OPEN) c.send(data);
  }
}

const STATIC_FILES = {
  '/': ['index.html', 'text/html; charset=utf-8'],
  '/index.html': ['index.html', 'text/html; charset=utf-8'],
  '/app.js': ['app.js', 'text/javascript; charset=utf-8'],
  '/crypto.js': ['crypto.js', 'text/javascript; charset=utf-8'],
  '/vendor/qrcode.js': ['vendor/qrcode.js', 'text/javascript; charset=utf-8'],
  '/icon.svg': ['icon.svg', 'image/svg+xml'],
};

const server = http.createServer(async (req, res) => {
  const { pathname } = new URL(req.url, 'http://localhost');
  if (pathname === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, rooms: rooms.size }));
    return;
  }
  const entry = STATIC_FILES[pathname];
  if (!entry) {
    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('Not found');
    return;
  }
  try {
    const body = await readFile(path.join(PUBLIC_DIR, entry[0]));
    res.writeHead(200, {
      'content-type': entry[1],
      'cache-control': 'no-cache',
      'x-content-type-options': 'nosniff',
      'referrer-policy': 'no-referrer',
    });
    res.end(body);
  } catch {
    res.writeHead(500, { 'content-type': 'text/plain' });
    res.end('Server error');
  }
});

const wss = new WebSocketServer({ server, path: '/ws', maxPayload: MAX_MESSAGE_BYTES });

wss.on('connection', (ws, req) => {
  const roomId = new URL(req.url, 'http://localhost').searchParams.get('room') || '';
  if (!ROOM_ID_RE.test(roomId)) {
    ws.close(1008, 'bad room');
    return;
  }
  const room = getRoom(roomId);
  if (room.clients.size >= MAX_CLIENTS_PER_ROOM) {
    ws.close(1008, 'room full');
    return;
  }

  room.clients.add(ws);
  room.lastActive = Date.now();
  ws.isAlive = true;
  let windowStart = Date.now();
  let windowCount = 0;

  ws.on('pong', () => {
    ws.isAlive = true;
  });

  send(ws, { type: 'history', clips: room.clips });
  broadcast(room, { type: 'peers', count: room.clients.size });

  ws.on('message', (raw) => {
    const now = Date.now();
    if (now - windowStart > 1000) {
      windowStart = now;
      windowCount = 0;
    }
    if (++windowCount > MAX_MESSAGES_PER_SECOND) return;

    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return;
    }
    if (!msg || typeof msg.type !== 'string') return;
    room.lastActive = now;

    if (msg.type === 'clip') {
      const { id, ts, blob } = msg;
      if (typeof id !== 'string' || id.length === 0 || id.length > 64) return;
      if (typeof blob !== 'string' || blob.length === 0 || blob.length > MAX_BLOB_CHARS) return;
      if (typeof ts !== 'number' || !Number.isFinite(ts)) return;
      if (room.clips.some((c) => c.id === id)) return;
      const clip = { id, ts, blob };
      room.clips.unshift(clip);
      if (room.clips.length > MAX_CLIPS_PER_ROOM) room.clips.length = MAX_CLIPS_PER_ROOM;
      broadcast(room, { type: 'clip', clip }, ws);
    } else if (msg.type === 'delete') {
      if (typeof msg.id !== 'string') return;
      room.clips = room.clips.filter((c) => c.id !== msg.id);
      broadcast(room, { type: 'delete', id: msg.id }, ws);
    } else if (msg.type === 'clear') {
      room.clips = [];
      broadcast(room, { type: 'clear' }, ws);
    }
  });

  ws.on('close', () => {
    room.clients.delete(ws);
    room.lastActive = Date.now();
    broadcast(room, { type: 'peers', count: room.clients.size });
  });

  ws.on('error', () => {});
});

// Keep connections alive and drop dead ones.
setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 30_000).unref();

// Forget rooms nobody has used for a while.
setInterval(() => {
  const now = Date.now();
  for (const [id, room] of rooms) {
    if (room.clients.size === 0 && now - room.lastActive > EMPTY_ROOM_TTL_MS) rooms.delete(id);
  }
}, 60_000).unref();

export function start(port = PORT) {
  return new Promise((resolve) => {
    server.listen(port, () => resolve(server));
  });
}

export function stop() {
  for (const ws of wss.clients) ws.terminate();
  return new Promise((resolve) => server.close(() => resolve()));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  start().then(() => console.log(`Kita Clips relay listening on :${PORT}`));
}
