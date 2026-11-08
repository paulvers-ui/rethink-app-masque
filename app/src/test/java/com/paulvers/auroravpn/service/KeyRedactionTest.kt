package com.paulvers.auroravpn.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The config editors never show the keys, and saving them back keeps the stored keys. */
class KeyRedactionTest {
    private val config = """
        {
          "private_key": "MHcCAQEEIBase64Key+/=",
          "endpoint_v4": "162.159.198.1",
          "endpoint_pub_key": "-----BEGIN PUBLIC KEY-----\nMFkw/abc==\n-----END PUBLIC KEY-----\n",
          "access_token": "token-123",
          "ipv4": "172.16.0.2"
        }
    """.trimIndent()

    private val wg = """
        [Interface]
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.8.0.2/32
        MTU = 1280

        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        PresharedKey = FpCyhws9cxwWoV4xELtfJvjJN+zQVRPISllRWgeopVE=
        Endpoint = vpn.example.org:51820
    """.trimIndent()

    @Test
    fun jsonKeysAreHiddenAndRestored() {
        val shown = KeyRedaction.hideJson(config)
        assertFalse(shown.contains("MHcCAQEEIBase64Key"))
        assertFalse(shown.contains("token-123"))
        assertTrue(shown.contains("162.159.198.1"))
        assertTrue(shown.contains("BEGIN PUBLIC KEY"))

        // The user edits an endpoint and saves with the keys still hidden.
        val edited = shown.replace("162.159.198.1", "162.159.192.9")
        val saved = KeyRedaction.restoreJson(edited, config)!!
        assertEquals(config.replace("162.159.198.1", "162.159.192.9"), saved)
    }

    @Test
    fun aPastedNewKeyIsKept() {
        val pasted = config.replace("MHcCAQEEIBase64Key+/=", "NEWKEY")
        assertEquals(pasted, KeyRedaction.restoreJson(pasted, config))
    }

    @Test
    fun aHiddenKeyWithNothingStoredIsRefused() {
        assertNull(KeyRedaction.restoreJson(KeyRedaction.hideJson(config), null))
    }

    @Test
    fun wgKeysAreHiddenAndRestored() {
        val shown = KeyRedaction.hideWg(wg)
        assertFalse(shown.contains("yAnz5TF"))
        assertFalse(shown.contains("FpCyhws9"))
        assertTrue(shown.contains("xTIBA5rbo")) // the peer's public key is not secret
        assertTrue(shown.contains("Endpoint = vpn.example.org:51820"))

        val edited = shown.replace("MTU = 1280", "MTU = 1200")
        assertEquals(wg.replace("MTU = 1280", "MTU = 1200"), KeyRedaction.restoreWg(edited, wg))
    }

    @Test
    fun olderLibusqueGetsArgsWithoutTheNewerFlags() {
        val args = listOf(
            "socks", "-b", "127.0.0.1", "-p", "40000", "-c", "/x/config.json",
            "-k", "10s", "--always-reconnect", "--idle-timeout", "25s", "--stall-timeout=2s",
            "--watch-network", "--secrets-stdin", "--doh",
        )
        assertEquals(
            listOf("socks", "-b", "127.0.0.1", "-p", "40000", "-c", "/x/config.json", "-k", "10s", "--always-reconnect", "--doh"),
            UsqueOutput.withoutNewerFlags(args),
        )
    }

    @Test
    fun usqueOutputReportsTheLastErrorAndARejectedFlag() {
        val out = UsqueOutput()
        out.add("2026/10/08 Establishing MASQUE connection to 162.159.198.1:443")
        out.add("Error: unknown flag: --stall-timeout")
        out.add("Usage:")
        assertTrue(out.rejectedAFlag())
        assertEquals("Error: unknown flag: --stall-timeout", out.lastError())
        out.clear()
        assertEquals("", out.lastError())
    }
}
