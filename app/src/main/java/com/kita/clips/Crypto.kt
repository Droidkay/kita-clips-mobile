package com.kita.clips

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Must stay byte-compatible with relay/public/crypto.js.
 * Room code -> (opaque room id for the relay, AES-256-GCM key). The relay never sees the code.
 */
object Crypto {
    private const val SALT = "kita-clips-v1"
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val CODE_LEN = 10

    private val random = SecureRandom()

    fun normalize(code: String): String =
        code.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun format(code: String): String {
        val c = normalize(code)
        return if (c.length > 5) c.substring(0, 5) + "-" + c.substring(5) else c
    }

    fun generateCode(): String {
        val sb = StringBuilder()
        val buf = ByteArray(32)
        while (sb.length < CODE_LEN) {
            random.nextBytes(buf)
            for (b in buf) {
                val x = b.toInt() and 0xFF
                if (x < 248 && sb.length < CODE_LEN) sb.append(ALPHABET[x % 31])
            }
        }
        return sb.toString()
    }

    fun roomId(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("kita-clips-room:" + normalize(code)).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun key(code: String): SecretKeySpec {
        val spec = PBEKeySpec(normalize(code).toCharArray(), SALT.toByteArray(Charsets.UTF_8), 100_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(key: SecretKeySpec, plain: String): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    fun decrypt(key: SecretKeySpec, blob: String): String {
        val raw = Base64.getDecoder().decode(blob)
        require(raw.size > 12 + 16) { "blob too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    /** Accepts "host", "https://host", "http://host:8080", "wss://host" and returns the websocket URL. */
    fun wsUrl(server: String, roomId: String): String {
        var s = server.trim().trimEnd('/')
        s = when {
            s.startsWith("https://") -> "wss://" + s.removePrefix("https://")
            s.startsWith("http://") -> "ws://" + s.removePrefix("http://")
            s.startsWith("wss://") || s.startsWith("ws://") -> s
            else -> "wss://$s"
        }
        return "$s/ws?room=$roomId"
    }
}
