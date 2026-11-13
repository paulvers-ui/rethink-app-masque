package com.paulvers.auroravpn.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are real libusque.so output:
 *  - chain_unknown_flag_old_binary.txt: what the phone's libusque.so printed on Oct 25
 *    when started with --always-reconnect (from chain_debug.txt), 45 lines.
 *  - chain_help_with_always_reconnect.txt: `chain --help` of usque with --always-reconnect.
 */
class UsqueFlagsTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/usque/$name")) { "missing fixture $name" }.readText()

    private val mustHave = listOf("bind", "port", "config", "wg", "exit-config")

    // The command the phone ran (ChainManager log, Oct 25 23:08 UTC), binary path left out.
    private val phoneArgs = (
        "chain -b 127.0.0.1 -p 40001 -c - --wg - --exit-config - -s google.com -m 1280 -i 1350 -P 443 " +
            "-k 10s -r 1s --idle-timeout 25s --always-reconnect --idle-timeout 25s --stall-timeout 2s " +
            "--doh --dnssec validate --doh-url https://1.1.1.1/dns-query --doh-url https://1.0.0.1/dns-query " +
            "--wg-mtu 0 --wg-keepalive 25 --exit-sni amazon.com --exit-transport auto --exit-mtu 1280 " +
            "--exit-connect-port 443 --exit-stall-timeout 8s --secrets-stdin"
        ).split(' ')

    @Test
    fun oldBinaryHelpIsParsed() {
        val flags = requireNotNull(UsqueFlags.parseHelp(fixture("chain_unknown_flag_old_binary.txt"), mustHave))
        assertFalse("always-reconnect" in flags)
        for (f in listOf("doh", "dnssec", "doh-url", "http2-fallback-after", "secrets-stdin", "config", "ipv6")) {
            assertTrue(f, f in flags)
        }
    }

    @Test
    fun unknownFlagIsDroppedAndEverythingElseKept() {
        val flags = UsqueFlags.parseHelp(fixture("chain_unknown_flag_old_binary.txt"), mustHave)!!
        val result = flags.supportedOnly(phoneArgs)
        assertEquals(listOf("--always-reconnect"), result.dropped)
        assertEquals(phoneArgs - "--always-reconnect", result.kept)
    }

    @Test
    fun newBinaryKeepsTheWholeCommand() {
        val flags = UsqueFlags.parseHelp(fixture("chain_help_with_always_reconnect.txt"), mustHave)!!
        assertTrue("always-reconnect" in flags)
        val result = flags.supportedOnly(phoneArgs)
        assertEquals(emptyList<String>(), result.dropped)
        assertEquals(phoneArgs, result.kept)
    }

    @Test
    fun valuesStayWithTheirFlags() {
        val flags = UsqueFlags.parseHelp(fixture("chain_unknown_flag_old_binary.txt"), mustHave)!!
        // "-c -": the "-" is -c's value, not a flag; "--wg-mtu -1" keeps a value that looks like a flag;
        // an unknown valued flag takes its value with it; "=" and glued shorthand values stay attached.
        val args = listOf("chain", "-c", "-", "--wg-mtu", "-1", "--bogus", "7", "--dnssec=strict", "-k10s", "--nope=1")
        val result = flags.supportedOnly(args)
        assertEquals(listOf("chain", "-c", "-", "--wg-mtu", "-1", "--dnssec=strict", "-k10s"), result.kept)
        assertEquals(listOf("--bogus", "7", "--nope=1"), result.dropped)
    }

    @Test
    fun helpThatParsedShortIsNotTrusted() {
        assertNull(UsqueFlags.parseHelp("Usage:\n  usque chain [flags]\n", mustHave))
        assertNull(UsqueFlags.parseHelp("      --doh   Resolve names\n", mustHave))
    }

    // ── UsqueOutput: the 45-line dump used to push the real error out of its 40-line buffer ──

    private fun outputOf(name: String) = UsqueOutput().apply { fixture(name).lines().forEach { add(it) } }

    @Test
    fun refusedFlagSurvivesTheUsageDump() {
        val out = outputOf("chain_unknown_flag_old_binary.txt")
        assertTrue(out.rejectedAFlag())
        assertEquals("--always-reconnect", out.rejectedFlag())
    }

    @Test
    fun reasonIsTheRealErrorNotAUsageLine() {
        // Was: "--http2-fallback-after int  WARP1: switch from QUIC ... after this many failed connects".
        assertEquals("Error: unknown flag: --always-reconnect", outputOf("chain_unknown_flag_old_binary.txt").lastError())
    }

    @Test
    fun runtimeErrorsStillReported() {
        val out = UsqueOutput()
        out.add("2026/10/27 19:54:31 UTC Establishing MASQUE connection to 162.159.198.2:443")
        out.add("2026/10/27 19:54:31 UTC Failed to connect tunnel: network is unreachable")
        assertFalse(out.rejectedAFlag())
        assertEquals("2026/10/27 19:54:31 UTC Failed to connect tunnel: network is unreachable", out.lastError())
        out.clear()
        assertEquals("", out.lastError())
    }

    @Test
    fun shorthandRefusalIsRecognised() {
        val out = UsqueOutput()
        out.add("Error: unknown shorthand flag: 'x' in -x")
        assertEquals("-x", out.rejectedFlag())
    }

    @Test
    fun withoutDropsAFlagAndItsValue() {
        val args = listOf("chain", "-c", "-", "--always-reconnect", "--doh-url", "u1", "--bogus", "7", "--stall-timeout", "2s")
        assertEquals(
            listOf("chain", "-c", "-", "--doh-url", "u1", "--stall-timeout", "2s"),
            UsqueOutput.without(args, listOf("--always-reconnect", "--bogus")),
        )
    }
}
