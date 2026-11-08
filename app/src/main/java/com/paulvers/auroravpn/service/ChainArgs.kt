package com.paulvers.auroravpn.service

import android.content.Context

/**
 * Builds the `libusque.so chain` command line from the chain screen's per-hop
 * settings. The command is a fixed core plus one editable flag string per hop:
 *
 *   core   chain -b 127.0.0.1 -p 40001 -c {config} --wg {wg} --exit-config {exit_config}
 *   WARP1  -s {sni} -m 1280 -i 1350 -P 443 -k 10s -r 1s --always-reconnect --idle-timeout 25s
 *          --stall-timeout 2s --doh --dnssec validate --doh-url https://1.1.1.1/dns-query
 *          --doh-url https://1.0.0.1/dns-query
 *   wg0    --wg-mtu 0 --wg-keepalive 25
 *   WARP2  --exit-sni {exit_sni} --exit-transport auto --exit-mtu 1280 --exit-connect-port 443
 *          --exit-stall-timeout 8s
 *
 * The core is not editable: the VPN tunnel is routed to that SOCKS port (see
 * [ChainRouting]) and the three files are the ones the screen edits. The hop
 * defaults spell out the values usque uses anyway (only -s and -i are the app's
 * own choice, same as simple WARP), so the screen shows what really runs.
 *
 * Placeholders: {config} {wg} {exit_config} are the three key files, {sni} and
 * {exit_sni} the per-hop SNIs. A flag whose placeholder is blank is dropped with
 * it, so a blank SNI falls back to usque's own default instead of breaking argv.
 * The keys reach usque through stdin (ChainManager adds --secrets-stdin), so the
 * file placeholders render as "-"; only an old libusque.so gets real paths
 * ([buildWithFiles]).
 */
// Small single-purpose helpers (defaults, validate, render, MTU math) on purpose.
@Suppress("TooManyFunctions")
object ChainArgs {
    enum class Hop { WARP1, WG, WARP2 }

    /** Result of [writeHopArgs]; anything but SAVED / CLEARED was not stored. */
    enum class ArgsCheck { SAVED, CLEARED, CORE_FLAG, WRONG_HOP_FLAG, SUBCOMMAND }

    const val CORE_TEMPLATE =
        "chain -b ${ChainManager.SOCKS_HOST} -p ${ChainManager.SOCKS_PORT} " +
            "-c {config} --wg {wg} --exit-config {exit_config}"

    // WARP1 is the only hop on the host network (the ISP sees its SNI). QUIC with
    // 1350-byte packets like simple WARP. -k / -r are the MASQUE keepalive and
    // reconnect delay, shared with WARP2. --always-reconnect keeps WARP1 and WARP2
    // persistent: a lost hop is rebuilt at once, not when traffic next arrives
    // (needs a libusque.so whose chain takes the flag; an older one has it left out,
    // see UsqueFlags). WARP1 is also rebuilt when the network changes (usque's
    // default), or when packets go unanswered for 2s and a probe through it fails;
    // QUIC drops a silent connection after 25s.
    //
    // The proxied apps' names resolve over DNS-over-HTTPS to Cloudflare by IP with
    // DNSSEC checked, same as simple WARP (UsqueManager.DEFAULT_SOCKS_DNS_ARGS).
    //
    // --http2-fallback-after is not set here: add it to this hop by hand when wanted
    // (0 = never). Left out, usque's own default applies.
    const val DEFAULT_WARP1_ARGS =
        "-s {sni} -m 1280 -i 1350 -P 443 -k 10s -r 1s --always-reconnect --idle-timeout 25s --stall-timeout 2s " +
            "--doh --dnssec validate --doh-url https://1.1.1.1/dns-query --doh-url https://1.0.0.1/dns-query"

    // --wg-mtu 0 = use wg0.conf's MTU; usque always caps it to what fits inside
    // WARP1 (WARP1 MTU - 60, or - 80 for an IPv6 endpoint).
    const val DEFAULT_WG_ARGS = "--wg-mtu 0 --wg-keepalive 25"

    // WARP2 rides inside wg0, where QUIC does not fit, so auto picks HTTP/2. Its own
    // stall check waits longer and only runs while WARP1 is healthy, so an outage of
    // WARP1 is not doubled by WARP2 rebuilding itself on top.
    const val DEFAULT_WARP2_ARGS =
        "--exit-sni {exit_sni} --exit-transport auto --exit-mtu 1280 --exit-connect-port 443 --exit-stall-timeout 8s"

    // usque's own exit SNI (internal.ConnectSNI). Inside wg0, so the ISP never sees it.
    const val DEFAULT_WARP2_SNI = "consumer-masque.cloudflareclient.com"

    // usque's defaults, used by the MTU summary when a hop string omits the flag.
    const val DEFAULT_TUNNEL_MTU = 1280
    private const val WG_OVERHEAD_V4 = 60 // WireGuard 32 + UDP 8 + IPv4 20
    private const val WG_OVERHEAD_V6 = 80 // WireGuard 32 + UDP 8 + IPv6 40
    // usque chain.ExitTransportFits: QUIC needs wg0 MTU - 28 >= 1452.
    private const val QUIC_EXIT_MIN_WG_MTU = 1480

    private const val MAX_SNI_LEN = 253
    private val SNI_CHARS = Regex("^[A-Za-z0-9.-]+$")
    private val WHITESPACE = Regex("\\s+")
    private const val SUBCOMMAND = "chain"
    // Owned by the core: SOCKS address / auth (the tunnel connects without
    // credentials) and the three config files.
    private val CORE_FLAGS = setOf(
        "-b", "--bind", "-p", "--port", "-u", "--username", "-w", "--password",
        "-c", "--config", "--wg", "--exit-config",
    )
    private const val WG_PREFIX = "--wg-"
    private const val EXIT_PREFIX = "--exit-"

    private val PLACEHOLDERS = mapOf(
        "{config}" to ChainManager.WARP1_CONFIG,
        "{wg}" to ChainManager.WG_CONFIG,
        "{exit_config}" to ChainManager.EXIT_CONFIG,
    )
    // What a key file placeholder renders to when the keys come through stdin.
    private const val KEYS_FROM_STDIN = "-"

    fun defaultArgs(hop: Hop): String = when (hop) {
        Hop.WARP1 -> DEFAULT_WARP1_ARGS
        Hop.WG -> DEFAULT_WG_ARGS
        Hop.WARP2 -> DEFAULT_WARP2_ARGS
    }

    /** What a hop's editor shows: the saved override, or the full default. */
    fun hopArgs(ps: PersistentState?, hop: Hop): String =
        ps?.let { saved(it, hop) }.orEmpty().ifEmpty { defaultArgs(hop) }

    /** True when the hop runs with its default flags (no override saved). */
    fun isDefault(ps: PersistentState, hop: Hop): Boolean = saved(ps, hop).isEmpty()

    /**
     * Validates and stores a hop's flags. Whitespace (newlines too) collapses to
     * single spaces; blank or the default text clears the override. A hop only
     * takes its own flags (wg0 only --wg-*, WARP2 only --exit-*, WARP1 neither)
     * so an edit cannot land on the wrong hop, and none may touch the core.
     */
    fun writeHopArgs(ps: PersistentState, hop: Hop, text: String): ArgsCheck {
        val normalized = text.replace(WHITESPACE, " ").trim()
        if (normalized.isEmpty() || normalized == defaultArgs(hop)) {
            store(ps, hop, "")
            return ArgsCheck.CLEARED
        }
        val check = validate(hop, normalized.split(' '))
        if (check == ArgsCheck.SAVED) store(ps, hop, normalized)
        return check
    }

    private fun validate(hop: Hop, tokens: List<String>): ArgsCheck {
        // Flags may be written --flag=value; compare the flag part only.
        val flags = tokens.filter { it.startsWith("-") }.map { it.substringBefore('=') }
        val foreign = when (hop) {
            Hop.WARP1 -> flags.any { it.startsWith(WG_PREFIX) || it.startsWith(EXIT_PREFIX) }
            Hop.WG -> flags.any { !it.startsWith(WG_PREFIX) }
            Hop.WARP2 -> flags.any { !it.startsWith(EXIT_PREFIX) }
        }
        return when {
            SUBCOMMAND in tokens -> ArgsCheck.SUBCOMMAND
            flags.any { it in CORE_FLAGS } -> ArgsCheck.CORE_FLAG
            foreign -> ArgsCheck.WRONG_HOP_FLAG
            else -> ArgsCheck.SAVED
        }
    }

    /**
     * argv after the binary path: core, then WARP1, wg0 and WARP2 flags, rendered, with
     * the key files as "-" (ChainManager adds --secrets-stdin and pipes the keys in).
     */
    fun build(ps: PersistentState?): List<String> =
        render(fullTemplate(ps), values(ps, null))

    /** [build] for an old libusque.so: the key files come from [paths] (file name -> path). */
    fun buildWithFiles(ps: PersistentState?, paths: Map<String, String>): List<String> =
        render(fullTemplate(ps), values(ps, paths))

    /** The command the next start will run, for the screen's "effective" line. */
    fun effectiveForDisplay(ps: PersistentState?): String =
        (build(ps) + ChainManager.SECRETS_STDIN_FLAG).joinToString(" ")

    /** One hop's flags as they will be passed (placeholders filled in). */
    fun effectiveHopForDisplay(ps: PersistentState?, hop: Hop): String =
        render(hopArgs(ps, hop), values(ps, null)).joinToString(" ")

    private fun fullTemplate(ps: PersistentState?): String =
        listOf(CORE_TEMPLATE, hopArgs(ps, Hop.WARP1), hopArgs(ps, Hop.WG), hopArgs(ps, Hop.WARP2))
            .joinToString(" ")

    private fun saved(ps: PersistentState, hop: Hop): String = when (hop) {
        Hop.WARP1 -> ps.chainWarp1Args
        Hop.WG -> ps.chainWgArgs
        Hop.WARP2 -> ps.chainWarp2Args
    }.trim()

    private fun store(ps: PersistentState, hop: Hop, value: String) {
        when (hop) {
            Hop.WARP1 -> ps.chainWarp1Args = value
            Hop.WG -> ps.chainWgArgs = value
            Hop.WARP2 -> ps.chainWarp2Args = value
        }
    }

    // paths null: keys through stdin, every key file placeholder renders as "-".
    private fun values(ps: PersistentState?, paths: Map<String, String>?): Map<String, String> =
        PLACEHOLDERS.mapValues { (_, file) -> paths?.get(file) ?: KEYS_FROM_STDIN } + mapOf(
            "{sni}" to ps?.chainWarp1Sni?.trim().orEmpty(),
            "{exit_sni}" to ps?.chainWarp2Sni?.trim().orEmpty(),
        )

    // A token that is exactly a placeholder with a blank value is dropped together
    // with the flag before it ("-s {sni}" with no SNI renders to nothing).
    private fun render(template: String, values: Map<String, String>): List<String> {
        val out = mutableListOf<String>()
        for (token in template.split(WHITESPACE).filter { it.isNotEmpty() }) {
            if (values[token]?.isEmpty() == true) {
                if (out.lastOrNull()?.startsWith("-") == true) out.removeAt(out.lastIndex)
                continue
            }
            out += values.entries.fold(token) { acc, (key, value) -> acc.replace(key, value) }
        }
        return out
    }

    /** An SNI is a host name (letters, digits, dots, hyphens), or blank for usque's default. */
    fun isValidSni(value: String): Boolean =
        value.isEmpty() || (value.length <= MAX_SNI_LEN && SNI_CHARS.matches(value))

    // ── MTU summary ───────────────────────────────────────────────────────────
    /** The three tunnel MTUs the next start will use, worked out like usque does. */
    data class MtuPlan(val warp1: Int, val wg: Int?, val warp2: Int, val warp2OverQuic: Boolean)

    fun mtuPlan(ctx: Context, ps: PersistentState?): MtuPlan {
        val warp1 = intFlag(hopArgs(ps, Hop.WARP1), "-m", "--mtu") ?: DEFAULT_TUNNEL_MTU
        val wgOverride = intFlag(hopArgs(ps, Hop.WG), "--wg-mtu") ?: 0
        val warp2 = intFlag(hopArgs(ps, Hop.WARP2), "--exit-mtu") ?: DEFAULT_TUNNEL_MTU
        val wg = effectiveWgMtu(ctx, warp1, wgOverride)
        val transport = stringFlag(hopArgs(ps, Hop.WARP2), "--exit-transport") ?: "auto"
        val quic = transport == "h3" || (transport == "auto" && wg != null && wg >= QUIC_EXIT_MIN_WG_MTU)
        return MtuPlan(warp1, wg, warp2, quic)
    }

    // usque chain.WGMTU: the override (else wg0.conf's MTU) if it is smaller than
    // what fits inside WARP1, else what fits. Null without a wg0.conf. A hostname
    // endpoint counts as IPv4, which usque prefers.
    private fun effectiveWgMtu(ctx: Context, warp1Mtu: Int, override: Int): Int? {
        val conf = ChainManager.readFile(ctx, ChainManager.WG_CONFIG)
        if (conf.isBlank()) return null
        val host = confValue(conf, "Endpoint")?.substringBeforeLast(':')?.trim('[', ']').orEmpty()
        val fit = warp1Mtu - if (':' in host) WG_OVERHEAD_V6 else WG_OVERHEAD_V4
        val want = override.takeIf { it > 0 } ?: confValue(conf, "MTU")?.toIntOrNull() ?: 0
        return if (want in 1 until fit) want else fit
    }

    private fun stringFlag(args: String, vararg names: String): String? {
        val tokens = args.split(WHITESPACE)
        tokens.forEachIndexed { i, t ->
            val name = t.substringBefore('=')
            if (name in names) return if ('=' in t) t.substringAfter('=') else tokens.getOrNull(i + 1)
        }
        return null
    }

    private fun intFlag(args: String, vararg names: String): Int? = stringFlag(args, *names)?.toIntOrNull()

    private fun confValue(conf: String, key: String): String? =
        conf.lineSequence()
            .map { it.substringBefore('#').trim() }
            .firstOrNull { '=' in it && it.substringBefore('=').trim().equals(key, ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
}
