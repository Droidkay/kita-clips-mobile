// Shared crypto + room-code helpers (used by the web client and the relay tests).
// Must stay byte-compatible with app/src/main/java/com/kita/clips/Crypto.kt

const enc = new TextEncoder();
const dec = new TextDecoder();

const SALT = enc.encode('kita-clips-v1');
const ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'; // 31 chars, no I L O 0 1
export const CODE_LEN = 10;

export function normalizeCode(code) {
  return String(code || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
}

export function formatCode(code) {
  const c = normalizeCode(code);
  return c.length > 5 ? `${c.slice(0, 5)}-${c.slice(5)}` : c;
}

export function generateCode() {
  const out = [];
  while (out.length < CODE_LEN) {
    const bytes = crypto.getRandomValues(new Uint8Array(32));
    for (const x of bytes) {
      if (x < 248 && out.length < CODE_LEN) out.push(ALPHABET[x % 31]); // 248 = 31 * 8, no bias
    }
  }
  return out.join('');
}

function toHex(buf) {
  return Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, '0')).join('');
}

function b64(u8) {
  let s = '';
  for (let i = 0; i < u8.length; i += 0x8000) {
    s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  }
  return btoa(s);
}

function unb64(s) {
  const bin = atob(s);
  const u8 = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
  return u8;
}

/** Opaque room id sent to the relay (the relay never learns the room code). */
export async function roomIdFor(code) {
  const digest = await crypto.subtle.digest('SHA-256', enc.encode('kita-clips-room:' + normalizeCode(code)));
  return toHex(digest);
}

/** AES-GCM-256 key derived from the room code. */
export async function keyFor(code) {
  const base = await crypto.subtle.importKey('raw', enc.encode(normalizeCode(code)), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt: SALT, iterations: 100000, hash: 'SHA-256' },
    base,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

/** blob = base64( iv(12) || ciphertext+tag ) of JSON {t: text, d: deviceName} */
export async function encryptPayload(key, obj, iv = crypto.getRandomValues(new Uint8Array(12))) {
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, enc.encode(JSON.stringify(obj))));
  const out = new Uint8Array(12 + ct.length);
  out.set(iv, 0);
  out.set(ct, 12);
  return b64(out);
}

export async function decryptPayload(key, blob) {
  const raw = unb64(blob);
  const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: raw.slice(0, 12) }, key, raw.slice(12));
  return JSON.parse(dec.decode(pt));
}
