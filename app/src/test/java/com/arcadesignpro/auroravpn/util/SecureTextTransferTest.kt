package com.arcadesignpro.auroravpn.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class SecureTextTransferTest {

    private fun <T> ok(r: TransferResult<T>): T =
        (r as? TransferResult.Ok)?.value ?: run { fail("expected Ok, got $r"); throw AssertionError() }

    private fun err(r: TransferResult<*>): TransferError =
        (r as? TransferResult.Err)?.error ?: run { fail("expected Err, got $r"); throw AssertionError() }

    private fun fakeRbk(size: Int, seed: Int = 1): ByteArray {
        val b = Random(seed).nextBytes(size)
        b[0] = 0x50; b[1] = 0x4B; b[2] = 0x03; b[3] = 0x04
        return b
    }

    private val wg = """
        [Interface]
        PrivateKey = TEST_PLACEHOLDER_PRIVATE
        Address = 10.0.0.2/32

        [Peer]
        PublicKey = TEST_PLACEHOLDER_PUBLIC
        Endpoint = vpn.example.org:51820
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    // ---- Base64 ----

    @Test fun base64RoundTripAllLengths() {
        for (n in 0..300) {
            val data = Random(n).nextBytes(n)
            assertArrayEquals("n=$n", data, StrictBase64.decode(StrictBase64.encode(data)))
        }
    }

    @Test fun base64AcceptsUrlSafeUnpaddedAndWhitespace() {
        assertArrayEquals(byteArrayOf(-5, -1), StrictBase64.decode("-_8"))
        assertArrayEquals("hi".toByteArray(), StrictBase64.decode(" aG\nk "))
    }

    @Test fun base64RejectsGarbage() {
        listOf("a", "ab=c", "abc==d", "a===", "ab!c", "ééé", "QQ==QQ==").forEach {
            assertEquals("input=$it", null, StrictBase64.decode(it))
        }
    }

    // ---- Sanitizer ----

    @Test fun sanitizerNormalisesPasteArtefacts() {
        val raw = "\uFEFF[Interface]\r\nA\u00A0= 1\r\u200B\n"
        assertEquals("[Interface]\nA = 1", ok(PasteSanitizer.sanitize(raw, 1000)))
    }

    @Test fun sanitizerRejectsControlCharsAndOversize() {
        assertEquals(TransferError.CONTROL_CHARACTERS, err(PasteSanitizer.sanitize("a\u0000b", 100)))
        assertEquals(TransferError.TOO_LARGE, err(PasteSanitizer.sanitize("x".repeat(101), 100)))
        assertEquals(TransferError.EMPTY, err(PasteSanitizer.sanitize(" \n\t ", 100)))
        assertEquals(TransferError.EMPTY, err(PasteSanitizer.sanitize(null, 100)))
    }

    // ---- Backup armor ----

    @Test fun backupRoundTrip() {
        val rbk = fakeRbk(50_000)
        val text = BackupText.encode(rbk)
        assertTrue(text.startsWith("-----BEGIN AURORAVPN BACKUP-----"))
        assertArrayEquals(rbk, ok(BackupText.decode(text)))
        // survives messengers that add CRLF, indentation and trailing chatter
        val mangled = "here you go:\r\n" + text.replace("\n", "\r\n    ") + "\r\n\r\nbye"
        assertArrayEquals(rbk, ok(BackupText.decode(mangled)))
    }

    @Test fun backupDetectsTruncationAndTampering() {
        val text = BackupText.encode(fakeRbk(3000))
        val lines = text.lines()
        val missingLine = (lines.take(8) + lines.drop(9)).joinToString("\n")
        val r = BackupText.decode(missingLine)
        assertTrue(r is TransferResult.Err)

        val b64Line = lines[6]
        val flipped = (if (b64Line[10] == 'A') 'B' else 'A')
        val tampered = text.replace(b64Line, b64Line.substring(0, 10) + flipped + b64Line.substring(11))
        assertEquals(TransferError.CHECKSUM_MISMATCH, err(BackupText.decode(tampered)))

        assertEquals(TransferError.TRUNCATED, err(BackupText.decode(text.substringBefore("-----END"))))
    }

    @Test fun backupRejectsWrongKindAndNonZip() {
        assertEquals(TransferError.WRONG_KIND, err(BackupText.decode(WgConfigText.encode(wg))))
        val notZip = TextArmor.encode("hello world".toByteArray(), TextArmor.KIND_BACKUP)
        assertEquals(TransferError.NOT_A_BACKUP, err(BackupText.decode(notZip)))
        assertEquals(TransferError.NOT_BASE64, err(BackupText.decode("this is not base64 at all!")))
    }

    @Test fun backupAcceptsBareBase64() {
        val rbk = fakeRbk(1000)
        assertArrayEquals(rbk, ok(BackupText.decode(StrictBase64.encode(rbk, 0))))
    }

    @Test fun backupSizeLimits() {
        val big = fakeRbk(BackupText.MAX_BACKUP_BYTES + 1)
        val r = BackupText.decode(TextArmor.encode(big, TextArmor.KIND_BACKUP))
        assertTrue(r is TransferResult.Err)
        // a declared size above the cap is refused before decoding
        val lie = BackupText.encode(fakeRbk(100)).replace(Regex("Bytes: \\d+"), "Bytes: 999999999")
        assertEquals(TransferError.TOO_LARGE, err(BackupText.decode(lie)))
    }

    // ---- WireGuard ----

    @Test fun wgPlainArmoredAndBase64() {
        assertEquals(wg, ok(WgConfigText.prepare(wg)))
        assertEquals(wg, ok(WgConfigText.prepare(WgConfigText.encode(wg))))
        assertEquals(wg, ok(WgConfigText.prepare(StrictBase64.encode(wg.toByteArray()))))
        assertEquals(wg, ok(WgConfigText.prepare("\uFEFF" + wg.replace("\n", "\r\n") + "\n\n")))
    }

    @Test fun wgRejectsNonConfigs() {
        assertEquals(TransferError.NOT_WIREGUARD, err(WgConfigText.prepare("hello")))
        assertEquals(TransferError.NOT_WIREGUARD, err(WgConfigText.prepare(StrictBase64.encode("hello".toByteArray()))))
        assertEquals(TransferError.TOO_LARGE, err(WgConfigText.prepare("[Interface]\n" + "x".repeat(WgConfigText.MAX_CHARS))))
        assertEquals(TransferError.WRONG_KIND, err(WgConfigText.prepare(BackupText.encode(fakeRbk(64)))))
        val badUtf8 = TextArmor.encode(byteArrayOf(0xC3.toByte(), 0x28), TextArmor.KIND_WIREGUARD)
        assertEquals(TransferError.NOT_WIREGUARD, err(WgConfigText.prepare(badUtf8)))
    }

    // ---- Fuzz: nothing may throw, and Ok must only come from valid input ----

    @Test fun fuzzNeverThrows() {
        val rnd = Random(42)
        val alphabet = "ABCabc+/=-_ \n\r\t[]:-----BEGIN AURORAVPN BACKUP WIREGUARD END\u0000\uFEFF\u00A0é"
        val seeds = listOf(BackupText.encode(fakeRbk(200)), WgConfigText.encode(wg), wg)
        repeat(20_000) { i ->
            val input = when (i % 3) {
                0 -> String(CharArray(rnd.nextInt(0, 400)) { alphabet[rnd.nextInt(alphabet.length)] })
                1 -> { // mutate a valid seed
                    val s = StringBuilder(seeds[rnd.nextInt(seeds.size)])
                    repeat(rnd.nextInt(1, 6)) {
                        if (s.isEmpty()) return@repeat
                        val p = rnd.nextInt(s.length)
                        when (rnd.nextInt(3)) {
                            0 -> s.deleteCharAt(p)
                            1 -> s.insert(p, alphabet[rnd.nextInt(alphabet.length)])
                            else -> s.setCharAt(p, alphabet[rnd.nextInt(alphabet.length)])
                        }
                    }
                    s.toString()
                }
                else -> String(CharArray(rnd.nextInt(0, 200)) { rnd.nextInt(0, 0xFFFF).toChar() })
            }
            try {
                val b = BackupText.decode(input)
                if (b is TransferResult.Ok) assertTrue(b.value.size >= 4 && b.value[0] == 0x50.toByte())
                val w = WgConfigText.prepare(input)
                if (w is TransferResult.Ok) assertTrue(w.value.contains("[interface]", ignoreCase = true))
                StrictBase64.decode(input)
            } catch (t: Throwable) {
                fail("threw on input #$i (${input.take(80)}): $t")
            }
        }
    }
}
