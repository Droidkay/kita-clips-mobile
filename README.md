# Kita Clips Mobile

Clipboard history on Android that syncs with your Mac.

```
Android app  <──wss──>  relay (relay/)  <──wss──>  Mac (web client at the same address)
```

- **End-to-end encrypted.** Clips are encrypted on the device with a key derived from the room code
  (PBKDF2-SHA256 → AES-256-GCM). The relay only sees opaque blobs and a hashed room id.
- **Relay is in-memory only.** Last 100 clips per room, wiped on restart. Empty rooms expire after 6 hours.
- Password-manager clips (flagged "sensitive" by Android) are never synced.

## 1. Deploy the relay (once)

It needs a public `https://` address so your phone and Mac can both reach it (and so the browser allows encryption).
Any Node host works. Example for your own domain, e.g. `clips.kitatool.com`:

```bash
cd relay
npm install
npm start            # listens on :8080 (PORT env var to change)
```

Docker: `docker build -t kita-clips-relay relay && docker run -p 8080:8080 kita-clips-relay`.
Put it behind HTTPS (Render, Fly.io, Railway, or Caddy/nginx on a VPS) and point a DNS record at it.

Test locally first: `cd relay && npm test` (spins up the relay and two simulated devices).

## 2. Mac

Open the relay address in Safari/Chrome (e.g. `https://clips.kitatool.com`):

1. **Create a room** — a QR code and a room code appear.
2. Leave the tab open (or install it as a Dock/web app). Incoming clips auto-copy to your Mac clipboard; clicking a card copies it.
3. **Send my clipboard** pushes whatever you just copied on the Mac to the phone.

## 3. Android app

The Android SDK isn't available where this was written, so the APK is built by GitHub Actions:

1. Push this folder to a GitHub repo (branch `main`).
2. Open the **Actions** tab → **Build APK** → download the `KitaClipsMobile-debug-apk` artifact.
3. Unzip, install `app-debug.apk` on your phone (allow "install unknown apps").

Or open the folder in Android Studio and press Run.

First launch: tap **Scan QR code** and scan the QR on the Mac page. Done.

### Getting clips from the phone to the Mac

Android 10+ only lets an app read the clipboard while it has focus, so there are four ways, all one tap:

- **Quick Settings tile** — edit your tiles, add **Send clipboard**. Copy something, pull down the shade, tap it.
- **Notification action** — the persistent notification has **Send clipboard**.
- **Share menu** — Share → **Kita Clips** sends any shared text.
- **In the app** — **Send my clipboard**, or type in the box.

The app also listens for clipboard changes in the background; this works on some phones and not on others
(Android decides), so treat the one-tap methods as the reliable path.

Mac → phone is automatic: new clips are copied to the phone clipboard as they arrive (toggle in Settings).

### Keep it alive on Samsung

Settings → Apps → Kita Clips → Battery → **Unrestricted**, and in Device care → Battery → Background usage limits,
make sure Kita Clips is not in "Sleeping apps".

## Protocol (for adding sync to the native Kita Clips Mac app later)

WebSocket `wss://<host>/ws?room=<roomId>`, JSON messages.

- `roomId = hex(SHA-256("kita-clips-room:" + CODE))`, `CODE` = 10 chars from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`, upper-case, no dashes.
- `key = PBKDF2-HMAC-SHA256(CODE, salt="kita-clips-v1", 100000 iterations, 32 bytes)` used as AES-256-GCM.
- `blob = base64( iv[12] ‖ ciphertext‖tag )` of the JSON `{"t": "<text>", "d": "<device name>"}`.
- Client → server: `{"type":"clip","id","ts","blob"}`, `{"type":"delete","id"}`, `{"type":"clear"}`.
- Server → client: `{"type":"history","clips":[{id,ts,blob}…]}` on connect, then `clip` (`{type,clip:{id,ts,blob}}`), `delete`, `clear`, `peers` (`{count}`).

Reference implementations: `relay/public/crypto.js` and `app/src/main/java/com/kita/clips/Crypto.kt`.

## Layout

```
relay/                         Node relay + web client for the Mac
app/src/main/java/com/kita/clips/
  Hub.kt                       settings, history, relay connection (single source of truth)
  Crypto.kt                    room code → room id + AES key
  ClipService.kt               foreground service, clipboard listener, notification
  ClipCaptureActivity.kt       invisible activity that reads the clipboard / handles Share
  CaptureTileService.kt        Quick Settings tile
  MainActivity.kt              Compose UI (pairing, history cards, settings)
.github/workflows/build-apk.yml
```
