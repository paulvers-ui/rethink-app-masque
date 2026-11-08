/*
 * Copyright 2026 AuroraVPN contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Codec code: Base64 is defined by its bit arithmetic (6/8-bit shifts, 0xff
// masks), and early `return Err(...)` is how a parse-proof decoder rejects
// input without throwing. Splitting these to satisfy style rules would make
// the parser harder to audit, so they are suppressed for this file only.
@file:Suppress(
    "MagicNumber",
    "ReturnCount",
    "CyclomaticComplexMethod",
    "CognitiveComplexMethod",
    "LoopWithTooManyJumpStatements",
)

package com.paulvers.auroravpn.util

import java.security.MessageDigest

/*
 * Copy/paste transfer of backups and WireGuard configs as text.
 *
 * Pure Kotlin on purpose: no android.* imports, so it runs on every API level
 * (java.util.Base64 needs API 26; minSdk is 23) and is unit-tested on the JVM.
 *
 * "Parse-proof" contract, enforced by SecureTextTransferTest:
 *  - never throws on any input; every failure is a typed [TransferError];
 *  - size is checked before any work, so huge pastes cost nothing;
 *  - the armored format carries the byte count and a SHA-256, so a truncated
 *    or altered paste is rejected instead of being half-restored;
 *  - backups must decode to a ZIP (the .rbk container) before they reach the
 *    restore pipeline, which then applies its own zip-slip/zip-bomb checks.
 *
 * The armor is an integrity check, not encryption: anyone holding the text
 * can read it, exactly like the .rbk file. Treat it like the file.
 */

/** Why a paste was rejected. Mapped to user-facing strings in the UI layer. */
enum class TransferError {
    EMPTY,
    TOO_LARGE,
    CONTROL_CHARACTERS,
    NOT_BASE64,
    WRONG_KIND,
    TRUNCATED,
    CHECKSUM_MISMATCH,
    NOT_A_BACKUP,
    NOT_WIREGUARD,
}

sealed class TransferResult<out T> {
    data class Ok<T>(val value: T) : TransferResult<T>()
    data class Err(val error: TransferError) : TransferResult<Nothing>()
}

/** Strict-but-tolerant Base64: accepts the standard and URL-safe alphabets,
 *  ignores whitespace, and allows missing padding. Rejects everything else. */
object StrictBase64 {
    private const val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE = IntArray(128) { -1 }.also { t ->
        STD.forEachIndexed { i, c -> t[c.code] = i }
        t['-'.code] = 62 // URL-safe
        t['_'.code] = 63
    }

    fun encode(data: ByteArray, lineLength: Int = 64): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4 + data.size / 48 + 2)
        var col = 0
        fun put(c: Char) {
            if (lineLength > 0 && col == lineLength) { sb.append('\n'); col = 0 }
            sb.append(c); col++
        }
        var i = 0
        while (i + 3 <= data.size) {
            val n = (data[i].toInt() and 0xff shl 16) or (data[i + 1].toInt() and 0xff shl 8) or
                (data[i + 2].toInt() and 0xff)
            put(STD[n ushr 18 and 63]); put(STD[n ushr 12 and 63]); put(STD[n ushr 6 and 63]); put(STD[n and 63])
            i += 3
        }
        val rem = data.size - i
        if (rem == 1) {
            val n = data[i].toInt() and 0xff shl 16
            put(STD[n ushr 18 and 63]); put(STD[n ushr 12 and 63]); put('='); put('=')
        } else if (rem == 2) {
            val n = (data[i].toInt() and 0xff shl 16) or (data[i + 1].toInt() and 0xff shl 8)
            put(STD[n ushr 18 and 63]); put(STD[n ushr 12 and 63]); put(STD[n ushr 6 and 63]); put('=')
        }
        return sb.toString()
    }

    /** Returns null on any malformed input. Never throws. */
    fun decode(text: CharSequence): ByteArray? {
        // First pass: count significant chars and validate the alphabet.
        var sig = 0
        var pad = 0
        for (c in text) {
            when {
                c == ' ' || c == '\n' || c == '\r' || c == '\t' -> continue
                c == '=' -> pad++
                pad > 0 -> return null // data after padding
                c.code < 128 && DECODE[c.code] >= 0 -> sig++
                else -> return null
            }
        }
        if (pad > 2) return null
        if ((sig + pad) % 4 != 0 && pad != 0) return null // padded but misaligned
        if (sig % 4 == 1) return null // impossible length
        val outLen = sig / 4 * 3 + when (sig % 4) { 2 -> 1; 3 -> 2; else -> 0 }
        val out = ByteArray(outLen)
        var acc = 0
        var bits = 0
        var o = 0
        for (c in text) {
            if (c == '=') break
            val v = if (c.code < 128) DECODE[c.code] else -1
            if (v < 0) continue // whitespace (already validated)
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (o < outLen) out[o++] = (acc ushr bits and 0xff).toByte()
            }
        }
        return if (o == outLen) out else null
    }
}

/** Removes paste artefacts and rejects control characters. */
object PasteSanitizer {
    private val INVISIBLE = setOf('\uFEFF', '\u200B', '\u200C', '\u200D', '\u2060', '\u00AD')

    fun sanitize(raw: CharSequence?, maxChars: Int): TransferResult<String> {
        if (raw == null) return TransferResult.Err(TransferError.EMPTY)
        if (raw.length > maxChars) return TransferResult.Err(TransferError.TOO_LARGE)
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\r' -> { // CRLF and lone CR both become LF
                    sb.append('\n')
                    if (i + 1 < raw.length && raw[i + 1] == '\n') i++
                }
                c in INVISIBLE -> Unit
                c == '\u00A0' || c == '\u2007' || c == '\u202F' -> sb.append(' ')
                c == '\n' || c == '\t' -> sb.append(c)
                c.code < 0x20 || c.code == 0x7F -> return TransferResult.Err(TransferError.CONTROL_CHARACTERS)
                else -> sb.append(c)
            }
            i++
        }
        val out = sb.toString().trim()
        return if (out.isEmpty()) TransferResult.Err(TransferError.EMPTY) else TransferResult.Ok(out)
    }
}

/**
 * PGP-style ASCII armor:
 *
 *     -----BEGIN AURORAVPN BACKUP-----
 *     Version: 1
 *     Bytes: 12345
 *     SHA-256: 3f2a…
 *
 *     UEsDBBQAAAAIA…   (Base64, 64 columns)
 *     -----END AURORAVPN BACKUP-----
 *
 * Decoding also accepts bare Base64 (no armor); then there is no checksum, so
 * callers add a content check (ZIP magic for backups).
 */
object TextArmor {
    const val KIND_BACKUP = "BACKUP"
    const val KIND_WIREGUARD = "WIREGUARD"
    private const val PRODUCT = "AURORAVPN"
    private const val VERSION = "1"

    fun encode(data: ByteArray, kind: String): String {
        val body = StrictBase64.encode(data, 64)
        return buildString(body.length + 200) {
            append("-----BEGIN ").append(PRODUCT).append(' ').append(kind).append("-----\n")
            append("Version: ").append(VERSION).append('\n')
            append("Bytes: ").append(data.size).append('\n')
            append("SHA-256: ").append(sha256Hex(data)).append("\n\n")
            append(body).append('\n')
            append("-----END ").append(PRODUCT).append(' ').append(kind).append("-----")
        }
    }

    /** True if [text] contains an armor header of any kind. */
    fun looksArmored(text: String): Boolean = text.contains("-----BEGIN $PRODUCT ", ignoreCase = true)

    fun decode(text: String, kind: String, maxBytes: Int): TransferResult<ByteArray> {
        val begin = "-----BEGIN $PRODUCT "
        val start = text.indexOf(begin, ignoreCase = true)
        if (start < 0) return decodeBare(text, maxBytes)

        val headerEnd = text.indexOf("-----", start + begin.length)
        if (headerEnd < 0) return TransferResult.Err(TransferError.TRUNCATED)
        val foundKind = text.substring(start + begin.length, headerEnd).trim()
        if (!foundKind.equals(kind, ignoreCase = true)) return TransferResult.Err(TransferError.WRONG_KIND)

        val endMarker = "-----END $PRODUCT $kind-----"
        val bodyStart = headerEnd + 5
        val end = text.indexOf(endMarker, bodyStart, ignoreCase = true)
        if (end < 0) return TransferResult.Err(TransferError.TRUNCATED)

        var declaredBytes = -1L
        var declaredSha: String? = null
        val payload = StringBuilder()
        for (line in text.substring(bodyStart, end).split('\n')) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val colon = t.indexOf(':')
            if (colon > 0 && payload.isEmpty()) { // header lines precede the payload
                val key = t.substring(0, colon).trim().lowercase()
                val value = t.substring(colon + 1).trim()
                when (key) {
                    "bytes" -> declaredBytes = value.toLongOrNull() ?: return TransferResult.Err(TransferError.TRUNCATED)
                    "sha-256", "sha256" -> declaredSha = value.lowercase()
                }
                continue
            }
            payload.append(t)
            if (payload.length > maxEncodedChars(maxBytes)) return TransferResult.Err(TransferError.TOO_LARGE)
        }
        if (declaredBytes > maxBytes) return TransferResult.Err(TransferError.TOO_LARGE)
        val bytes = StrictBase64.decode(payload) ?: return TransferResult.Err(TransferError.NOT_BASE64)
        if (bytes.isEmpty()) return TransferResult.Err(TransferError.EMPTY)
        if (bytes.size > maxBytes) return TransferResult.Err(TransferError.TOO_LARGE)
        if (declaredBytes >= 0 && declaredBytes != bytes.size.toLong()) return TransferResult.Err(TransferError.TRUNCATED)
        if (declaredSha != null && declaredSha != sha256Hex(bytes)) return TransferResult.Err(TransferError.CHECKSUM_MISMATCH)
        return TransferResult.Ok(bytes)
    }

    private fun decodeBare(text: String, maxBytes: Int): TransferResult<ByteArray> {
        if (text.length > maxEncodedChars(maxBytes) + text.length / 32 + 16) {
            return TransferResult.Err(TransferError.TOO_LARGE)
        }
        val bytes = StrictBase64.decode(text) ?: return TransferResult.Err(TransferError.NOT_BASE64)
        if (bytes.isEmpty()) return TransferResult.Err(TransferError.EMPTY)
        if (bytes.size > maxBytes) return TransferResult.Err(TransferError.TOO_LARGE)
        return TransferResult.Ok(bytes)
    }

    private fun maxEncodedChars(maxBytes: Int): Int = ((maxBytes.toLong() + 2) / 3 * 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun sha256Hex(data: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(data)
        val hex = "0123456789abcdef"
        val sb = StringBuilder(d.size * 2)
        for (b in d) { sb.append(hex[b.toInt() ushr 4 and 15]); sb.append(hex[b.toInt() and 15]) }
        return sb.toString()
    }
}

/** Backups (.rbk): a ZIP container, carried as armored text. */
object BackupText {
    /** Android's clipboard rides on a ~1 MiB Binder buffer; stay well below. */
    const val MAX_CLIPBOARD_CHARS = 700_000
    /** Decoded .rbk cap for text restores (the file path has no such limit). */
    const val MAX_BACKUP_BYTES = 512 * 1024
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK\3\4"

    fun encode(rbk: ByteArray): String = TextArmor.encode(rbk, TextArmor.KIND_BACKUP)

    fun decode(pasted: CharSequence?): TransferResult<ByteArray> {
        val clean = when (val s = PasteSanitizer.sanitize(pasted, MAX_CLIPBOARD_CHARS)) {
            is TransferResult.Ok -> s.value
            is TransferResult.Err -> return s
        }
        val bytes = when (val d = TextArmor.decode(clean, TextArmor.KIND_BACKUP, MAX_BACKUP_BYTES)) {
            is TransferResult.Ok -> d.value
            is TransferResult.Err -> return d
        }
        if (bytes.size < ZIP_MAGIC.size || !ZIP_MAGIC.indices.all { bytes[it] == ZIP_MAGIC[it] }) {
            return TransferResult.Err(TransferError.NOT_A_BACKUP)
        }
        return TransferResult.Ok(bytes)
    }
}

/** WireGuard configs pasted as plain wg-quick text, armored text or bare Base64. */
object WgConfigText {
    /** A wg-quick file is a few hundred bytes; 64 KiB leaves room for many peers. */
    const val MAX_CHARS = 64 * 1024

    fun encode(config: String): String =
        TextArmor.encode(config.toByteArray(Charsets.UTF_8), TextArmor.KIND_WIREGUARD)

    fun prepare(pasted: CharSequence?): TransferResult<String> {
        val clean = when (val s = PasteSanitizer.sanitize(pasted, MAX_CHARS)) {
            is TransferResult.Ok -> s.value
            is TransferResult.Err -> return s
        }
        val text = when {
            TextArmor.looksArmored(clean) ->
                when (val d = TextArmor.decode(clean, TextArmor.KIND_WIREGUARD, MAX_CHARS)) {
                    is TransferResult.Ok -> utf8OrNull(d.value) ?: return TransferResult.Err(TransferError.NOT_WIREGUARD)
                    is TransferResult.Err -> return d
                }
            !clean.contains('[') -> { // could be bare Base64 of a config
                val bytes = StrictBase64.decode(clean)
                val decoded = bytes?.let { utf8OrNull(it) }
                if (decoded != null && decoded.contains("[interface]", ignoreCase = true)) decoded else clean
            }
            else -> clean
        }
        // Re-sanitize decoded content: it came from inside the armor.
        val final = when (val s = PasteSanitizer.sanitize(text, MAX_CHARS)) {
            is TransferResult.Ok -> s.value
            is TransferResult.Err -> return s
        }
        if (!final.contains("[interface]", ignoreCase = true)) return TransferResult.Err(TransferError.NOT_WIREGUARD)
        return TransferResult.Ok(final)
    }

    // Invalid UTF-8 is an expected outcome (the caller reports NOT_WIREGUARD).
    @Suppress("SwallowedException")
    private fun utf8OrNull(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            null
        }
    }
}
