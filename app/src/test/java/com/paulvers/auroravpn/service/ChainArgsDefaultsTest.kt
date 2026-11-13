package com.paulvers.auroravpn.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chain's default command, and that the libusque.so it is meant for takes all of it. */
class ChainArgsDefaultsTest {
    private val args = ChainArgs.build(null) + ChainManager.SECRETS_STDIN_FLAG

    @Test
    fun warp1AndWarp2StayConnected() {
        assertTrue(args.contains("--always-reconnect"))
    }

    @Test
    fun namesResolveOverDohWithDnssec() {
        val joined = args.joinToString(" ")
        assertTrue(joined.contains("--doh --dnssec validate"))
        assertEquals(
            listOf("https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query"),
            args.withIndex().filter { (i, _) -> i > 0 && args[i - 1] == "--doh-url" }.map { it.value },
        )
    }

    @Test
    fun http2FallbackIsOnlyAddedByHand() {
        assertFalse(args.any { it.startsWith("--http2-fallback-after") })
    }

    @Test
    fun newLibusqueTakesTheWholeDefaultCommand() {
        val help = requireNotNull(javaClass.getResource("/usque/chain_help_with_always_reconnect.txt")).readText()
        val flags = requireNotNull(UsqueFlags.parseHelp(help, listOf("bind", "port", "config", "wg", "exit-config")))
        assertEquals(emptyList<String>(), flags.supportedOnly(args).dropped)
    }

    @Test
    fun oldLibusqueOnlyLosesAlwaysReconnect() {
        val help = requireNotNull(javaClass.getResource("/usque/chain_unknown_flag_old_binary.txt")).readText()
        val flags = requireNotNull(UsqueFlags.parseHelp(help, listOf("bind", "port", "config", "wg", "exit-config")))
        assertEquals(listOf("--always-reconnect"), flags.supportedOnly(args).dropped)
    }
}
