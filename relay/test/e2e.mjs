// End-to-end test: real relay + two simulated devices using the shared crypto module.
import assert from 'node:assert/strict';
import WebSocket from 'ws';
import { start, stop } from '../server.js';
import { generateCode, roomIdFor, keyFor, encryptPayload, decryptPayload, normalizeCode, formatCode } from '../public/crypto.js';

const srv = await start(0);
const port = srv.address().port;

function device(roomId) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?room=${roomId}`);
  const inbox = [];
  const waiters = [];
  ws.on('message', (d) => {
    const m = JSON.parse(d.toString());
    const i = waiters.findIndex((w) => w.pred(m));
    if (i >= 0) waiters.splice(i, 1)[0].resolve(m);
    else inbox.push(m);
  });
  const next = (pred, ms = 3000) => new Promise((resolve, reject) => {
    const j = inbox.findIndex(pred);
    if (j >= 0) return resolve(inbox.splice(j, 1)[0]);
    const w = { pred, resolve };
    waiters.push(w);
    setTimeout(() => reject(new Error('timeout waiting for message')), ms).unref();
  });
  const opened = new Promise((res, rej) => { ws.on('open', res); ws.on('error', rej); });
  return { ws, next, opened };
}

try {
  const code = generateCode();
  assert.equal(code.length, 10);
  assert.equal(normalizeCode(formatCode(code)), code);
  const roomId = await roomIdFor(code);
  const key = await keyFor(code);

  // phone and mac join the same room
  const phone = device(roomId);
  await phone.opened;
  await phone.next((m) => m.type === 'history');
  const mac = device(roomId);
  await mac.opened;
  await mac.next((m) => m.type === 'history');
  const peers = await phone.next((m) => m.type === 'peers' && m.count === 2);
  assert.equal(peers.count, 2);

  // phone -> mac, encrypted
  const blob = await encryptPayload(key, { t: 'hello from phone ✓ 日本語', d: 'Galaxy Z Fold5' });
  assert.ok(!blob.includes('hello'), 'blob must not contain plaintext');
  phone.ws.send(JSON.stringify({ type: 'clip', id: 'c1', ts: Date.now(), blob }));
  const got = await mac.next((m) => m.type === 'clip');
  const payload = await decryptPayload(key, got.clip.blob);
  assert.equal(payload.t, 'hello from phone ✓ 日本語');
  assert.equal(payload.d, 'Galaxy Z Fold5');

  // wrong key cannot read it
  const otherKey = await keyFor(generateCode());
  await assert.rejects(decryptPayload(otherKey, got.clip.blob));

  // late joiner receives history
  const laptop = device(roomId);
  await laptop.opened;
  const hist = await laptop.next((m) => m.type === 'history');
  assert.equal(hist.clips.length, 1);
  assert.equal((await decryptPayload(key, hist.clips[0].blob)).t, 'hello from phone ✓ 日本語');

  // duplicate id ignored
  phone.ws.send(JSON.stringify({ type: 'clip', id: 'c1', ts: Date.now(), blob }));
  // mac -> phone, then delete propagates
  const blob2 = await encryptPayload(key, { t: 'reply from mac', d: 'Mac' });
  mac.ws.send(JSON.stringify({ type: 'clip', id: 'c2', ts: Date.now(), blob: blob2 }));
  const back = await phone.next((m) => m.type === 'clip');
  assert.equal(back.clip.id, 'c2');
  mac.ws.send(JSON.stringify({ type: 'delete', id: 'c2' }));
  assert.equal((await phone.next((m) => m.type === 'delete')).id, 'c2');
  phone.ws.send(JSON.stringify({ type: 'clear' }));
  await mac.next((m) => m.type === 'clear');

  // bad room id is rejected, other rooms are isolated
  const bad = new WebSocket(`ws://127.0.0.1:${port}/ws?room=nope`);
  const closeCode = await new Promise((res) => bad.on('close', (c) => res(c)));
  assert.equal(closeCode, 1008);

  const strangerRoom = await roomIdFor(generateCode());
  const stranger = device(strangerRoom);
  await stranger.opened;
  const sh = await stranger.next((m) => m.type === 'history');
  assert.equal(sh.clips.length, 0);

  // static client is served
  const html = await (await fetch(`http://127.0.0.1:${port}/`)).text();
  assert.ok(html.includes('Kita Clips'));
  for (const p of ['/app.js', '/crypto.js', '/vendor/qrcode.js', '/icon.svg']) {
    assert.equal((await fetch(`http://127.0.0.1:${port}${p}`)).status, 200, p);
  }

  for (const d of [phone, mac, laptop, stranger]) d.ws.close();
  console.log('PASS: relay e2e (join, encrypted sync, history, delete, clear, isolation, static files)');
} catch (e) {
  console.error('FAIL', e);
  process.exitCode = 1;
} finally {
  await stop();
}
