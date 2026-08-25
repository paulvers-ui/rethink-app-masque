/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.creatore.rethinkfork.util

import Logger
import Logger.LOG_TAG_VPN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * DnsSecGuard — DNSSEC enforcement and DNS-poisoning heuristics.
 *
 * This guard is intentionally conservative: it never drops a response
 * on its own, but it annotates [CheckResult] so callers can decide the
 * appropriate action (log, warn, block).
 *
 * DNSSEC enforcement strategy
 * ────────────────────────────
 * The Go-side firestack already sets DO (DNSSEC-OK) on outgoing queries
 * and reports the AD (Authenticated Data) flag from the upstream resolver.
 * The Kotlin layer receives two booleans:
 *   • dnssecOk    — we requested DNSSEC validation (DO bit was set)
 *   • dnssecValid — the upstream resolver confirmed the response is
 *                   cryptographically authentic (AD bit was set)
 *
 * A response where dnssecOk=true AND dnssecValid=false means:
 *   • The resolver received our DNSSEC request but the reply was NOT
 *     authenticated — indicative of a spoofed/poisoned answer, a
 *     misconfigured zone, or a non-DNSSEC-validating intermediary.
 *
 * DNS-poisoning heuristics
 * ────────────────────────
 * Bogon / martian IPs in A/AAAA answers are a classic poison indicator:
 *   • RFC 1918  — 10/8, 172.16/12, 192.168/16
 *   • RFC 5737  — 192.0.2/24, 198.51.100/24, 203.0.113/24 (TEST-NET)
 *   • RFC 3927  — 169.254/16 (link-local)
 *   • RFC 6598  — 100.64/10 (shared address space / CGNAT)
 *   • Loopback  — 127/8, ::1
 *   • Unspecified — 0.0.0.0, ::
 *   • Documentation — 2001:db8::/32
 *   • 6to4/Teredo — 2002::/16, 2001::/32
 *   • Multicast — 224.0.0.0/4, ff00::/8
 */
object DnsSecGuard {

    private const val TAG = "DnsSecGuard"

    // ── Result type ─────────────────────────────────────────────────────────

    sealed class CheckResult {
        /** Response passed all checks. */
        object Clean : CheckResult()

        /**
         * DNSSEC was requested (DO=true) but the upstream did not authenticate
         * the response (AD=false).  The answer may be spoofed or from a
         * non-validating intermediary.
         */
        data class DnssecMissing(val qName: String) : CheckResult()

        /**
         * One or more of the resolved IP addresses is a bogon/martian —
         * commonly injected by censors or poisoning attacks.
         */
        data class BogonIp(val qName: String, val bogonAddresses: List<String>) : CheckResult()

        /**
         * Both DNSSEC is missing AND bogon IPs are present.
         * Highest suspicion level.
         */
        data class PoisonSuspect(
            val qName: String,
            val bogonAddresses: List<String>
        ) : CheckResult()
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Validate a completed DNS transaction.
     *
     * @param qName       The queried hostname (for logging).
     * @param dnssecOk    True if the DO bit was set on the outgoing query.
     * @param dnssecValid True if the upstream set the AD bit on the response.
     * @param responseIps Comma-separated IP addresses from the answer section.
     *                    May be empty for non-A/AAAA records or blocked queries.
     * @return A [CheckResult] describing the outcome.
     */
    fun validate(
        qName: String,
        dnssecOk: Boolean,
        dnssecValid: Boolean,
        responseIps: String
    ): CheckResult {
        val bogons = findBogons(responseIps)
        val dnssecMissing = dnssecOk && !dnssecValid

        return when {
            dnssecMissing && bogons.isNotEmpty() -> {
                Logger.w(
                    LOG_TAG_VPN,
                    "$TAG POISON_SUSPECT: $qName — DNSSEC unvalidated + bogon IPs $bogons"
                )
                CheckResult.PoisonSuspect(qName, bogons)
            }
            bogons.isNotEmpty() -> {
                Logger.w(LOG_TAG_VPN, "$TAG BOGON_IP: $qName — suspicious IPs $bogons")
                CheckResult.BogonIp(qName, bogons)
            }
            dnssecMissing -> {
                Logger.d(
                    LOG_TAG_VPN,
                    "$TAG DNSSEC_MISSING: $qName — DO=true but AD=false (non-validating upstream or spoofed)"
                )
                CheckResult.DnssecMissing(qName)
            }
            else -> CheckResult.Clean
        }
    }

    /** Returns true if the result indicates any degree of suspicion. */
    fun CheckResult.isSuspect(): Boolean = this !is CheckResult.Clean

    /** Returns true only for the highest-confidence poison signal. */
    fun CheckResult.isPoisonSuspect(): Boolean = this is CheckResult.PoisonSuspect

    // ── Bogon detection ──────────────────────────────────────────────────────

    /**
     * Parse [csv] (comma-separated IP strings) and return those that are
     * bogon/martian addresses.  Entries that cannot be parsed are silently
     * ignored — we do not want logging noise from SVCB / HTTPS records.
     */
    private fun findBogons(csv: String): List<String> {
        if (csv.isBlank() || csv == "--") return emptyList()
        return csv.split(",")
            .mapNotNull { it.trim().ifEmpty { null } }
            .filter { raw ->
                val addr = parseIp(raw) ?: return@filter false
                isBogon(addr)
            }
    }

    private fun parseIp(raw: String): InetAddress? {
        return try {
            // strip port suffix if present  (e.g. "1.2.3.4:53")
            val host = if (raw.contains(':') && !raw.startsWith('[') && raw.count { it == ':' } == 1) {
                raw.substringBefore(':')
            } else {
                raw.trimStart('[').substringBefore(']')
            }
            InetAddress.getByName(host)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns true if [addr] is a non-routable / special-use address that has
     * no business appearing in a public-domain DNS answer.
     *
     * Covers all IANA Special-Purpose registries relevant to poisoning:
     *   IPv4: loopback, private (RFC 1918), link-local (RFC 3927),
     *         shared (RFC 6598), TEST-NET (RFC 5737), multicast, unspecified
     *   IPv6: loopback, link-local, ULA (fc00::/7), documentation (2001:db8::),
     *         6to4 (2002::), Teredo (2001::/32), multicast, unspecified
     */
    fun isBogon(addr: InetAddress): Boolean {
        return when (addr) {
            is Inet4Address -> isBogon4(addr)
            is Inet6Address -> isBogon6(addr)
            else            -> false
        }
    }

    private fun isBogon4(a: Inet4Address): Boolean {
        val b = a.address.map { it.toInt() and 0xFF }
        val (b0, b1, b2, _) = b + listOf(0, 0, 0, 0) // pad to avoid IOOBE
        return when {
            b0 == 0                           -> true  // 0.0.0.0/8      — "This" network
            b0 == 10                          -> true  // 10/8            — RFC 1918
            b0 == 127                         -> true  // 127/8           — loopback
            b0 == 169 && b1 == 254            -> true  // 169.254/16      — link-local
            b0 == 172 && b1 in 16..31         -> true  // 172.16–31/12    — RFC 1918
            b0 == 192 && b1 == 0 && b2 == 2   -> true  // 192.0.2/24      — TEST-NET-1
            b0 == 192 && b1 == 168            -> true  // 192.168/16      — RFC 1918
            b0 == 198 && b1 == 18             -> true  // 198.18/15       — benchmarking
            b0 == 198 && b1 == 51 && b2 == 100 -> true // 198.51.100/24   — TEST-NET-2
            b0 == 203 && b1 == 0 && b2 == 113 -> true  // 203.0.113/24    — TEST-NET-3
            b0 == 100 && b1 in 64..127        -> true  // 100.64/10       — CGNAT
            b0 >= 224                         -> true  // 224+            — multicast / reserved
            else                              -> false
        }
    }

    private fun isBogon6(a: Inet6Address): Boolean {
        val b = a.address.map { it.toInt() and 0xFF }
        // ::1 loopback
        if (b.all { it == 0 }.let { allZero ->
                allZero || (b.dropLast(1).all { it == 0 } && b.last() == 1)
            }) return true
        val hi16 = (b[0] shl 8) or b[1]
        return when {
            b[0] == 0xfe && (b[1] and 0xc0) == 0x80 -> true // fe80::/10 link-local
            b[0] and 0xfe == 0xfc                    -> true // fc00::/7  ULA (fc/fd)
            hi16 == 0x2001 && b[2] == 0x0d && b[3] == 0xb8 -> true // 2001:db8:: documentation
            hi16 == 0x2002                           -> true // 2002::/16 6to4
            hi16 == 0x2001 && b[2] == 0 && b[3] == 0 -> true // 2001::/32 Teredo
            b[0] == 0xff                             -> true // ff00::/8  multicast
            b.all { it == 0 }                        -> true // :: unspecified
            else                                     -> false
        }
    }
}
