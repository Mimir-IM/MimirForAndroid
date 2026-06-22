package com.revertron.mimir.net

/**
 * Encode / decode group-call invite links.
 *
 * Wire shape: `mimir://gc/<pubkey-hex>/<session-id-base32>#<session-key-base64url>`
 *
 * - `<pubkey-hex>`: 64 lowercase hex chars, the call-mediator's Ed25519 pubkey.
 * - `<session-id-base32>`: 16 raw bytes encoded as Crockford-ish base32 (no
 *   padding, uppercase on output but case-insensitive on input). 26 chars.
 * - `<session-key-base64url>`: 32 raw bytes, base64url no-padding. 43 chars.
 *   Lives in the URL fragment so servers and link-logging middleware never
 *   see it — this is the entire end-to-end encryption story for audio.
 *
 * MCU mode invites (planned, not in v1) will omit the fragment; parsing
 * returns `sessionKey = null` for those.
 */
object InviteLinkCodec {

    const val SESSION_ID_LEN = 16
    const val SESSION_KEY_LEN = 32
    const val PUBKEY_HEX_LEN = 64

    data class Invite(
        val callMediatorPubkey: ByteArray,
        val sessionId: ByteArray,
        /** Null when the creator picked MCU mode; present for SFU invites. */
        val sessionKey: ByteArray?,
    )

    fun encode(mediatorPubkey: ByteArray, sessionId: ByteArray, sessionKey: ByteArray?): String {
        require(mediatorPubkey.size == 32) { "pubkey must be 32 bytes" }
        require(sessionId.size == SESSION_ID_LEN) { "session_id must be $SESSION_ID_LEN bytes" }
        sessionKey?.let { require(it.size == SESSION_KEY_LEN) { "session_key must be $SESSION_KEY_LEN bytes" } }

        val pkHex = mediatorPubkey.toHex()
        val sidB32 = base32Encode(sessionId)
        val base = "mimir://gc/$pkHex/$sidB32"
        return if (sessionKey != null) "$base#${base64UrlEncode(sessionKey)}" else base
    }

    /** Parse a `mimir://gc/...` URI. Returns null if it isn't a group-call invite. */
    fun parse(uri: android.net.Uri): Invite? {
        if (uri.scheme != "mimir") return null
        // `mimir://gc/<pk>/<sid>` is parsed by Android as authority="gc",
        // path="/<pk>/<sid>". The `gc` discriminator lives in the authority.
        if (uri.host != "gc") return null
        val path = uri.path?.trim('/') ?: return null
        val parts = path.split("/")
        if (parts.size < 2) return null

        val pkHex = parts[0]
        val sidB32 = parts[1]

        if (pkHex.length != PUBKEY_HEX_LEN) return null
        val mediatorPubkey = pkHex.hexToBytesOrNull() ?: return null
        val sessionId = base32Decode(sidB32) ?: return null
        if (sessionId.size != SESSION_ID_LEN) return null

        val sessionKey = uri.fragment?.takeIf { it.isNotEmpty() }?.let { frag ->
            base64UrlDecode(frag)?.takeIf { it.size == SESSION_KEY_LEN }
        }

        return Invite(mediatorPubkey, sessionId, sessionKey)
    }

    // ── Hex ───────────────────────────────────────────────────────────────

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(HEX_CHARS[(b.toInt() ushr 4) and 0xF])
            sb.append(HEX_CHARS[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            val hi = hexDigit(this[i * 2]) ?: return null
            val lo = hexDigit(this[i * 2 + 1]) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    private const val HEX_CHARS = "0123456789abcdef"

    // ── Base32 (RFC 4648, no padding) ─────────────────────────────────────
    //
    // 16 bytes → 26 chars. Uppercase on encode, case-insensitive on decode.

    private const val B32_ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun base32Encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var bits = 0
        var bitCount = 0
        for (b in bytes) {
            bits = (bits shl 8) or (b.toInt() and 0xFF)
            bitCount += 8
            while (bitCount >= 5) {
                bitCount -= 5
                val idx = (bits ushr bitCount) and 0x1F
                out.append(B32_ALPHA[idx])
            }
        }
        if (bitCount > 0) {
            val idx = (bits shl (5 - bitCount)) and 0x1F
            out.append(B32_ALPHA[idx])
        }
        return out.toString()
    }

    private fun base32Decode(s: String): ByteArray? {
        val upper = s.uppercase()
        val estimated = (upper.length * 5) / 8
        val out = ByteArray(estimated)
        var bits = 0
        var bitCount = 0
        var pos = 0
        for (c in upper) {
            val idx = B32_ALPHA.indexOf(c)
            if (idx < 0) return null
            bits = (bits shl 5) or idx
            bitCount += 5
            if (bitCount >= 8) {
                bitCount -= 8
                out[pos++] = ((bits ushr bitCount) and 0xFF).toByte()
            }
        }
        return if (pos == out.size) out else out.copyOf(pos)
    }

    // ── Base64url (no padding) ────────────────────────────────────────────

    private fun base64UrlEncode(bytes: ByteArray): String {
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )
    }

    private fun base64UrlDecode(s: String): ByteArray? {
        return try {
            android.util.Base64.decode(
                s,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
