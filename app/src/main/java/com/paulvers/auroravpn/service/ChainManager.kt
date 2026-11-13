package com.paulvers.auroravpn.service

import Logger
import android.content.Context
import android.util.Log
import java.io.File
import java.io.StringWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Drives the nested WARP1 -> wg0 -> WARP2 chain exposed by `libusque.so chain`.
 *
 *     apps -> SOCKS :CHAIN_SOCKS_PORT -> WARP2 (exit, CF IP)
 *          -> wg0 (new location) -> WARP1 (hides ISP) -> ISP
 *
 * Deliberately a sibling of [UsqueManager], not a modification of it: the plain
 * single-hop WARP path keeps working untouched, and the chain is opt-in. It
 * reuses the exact same binary (`libusque.so`), the same GODEBUG workaround and
 * the same verbose-log-to-file approach, but with its own files so the two
 * modes never clobber each other's state:
 *
 *   config.json        WARP1 identity (shared with UsqueManager; same registration)
 *   config_exit.json   WARP2 identity (second registration, chain-only)
 *   wg0.conf           middle WireGuard hop (chain-only)
 *   chain_debug.txt    verbose log, mirrors warp_debug.txt
 *
 * The three key files are sealed by [KeyVault] and reach usque through stdin
 * (--secrets-stdin), never as plain files; the editors see the keys as
 * [KeyRedaction.HIDDEN].
 *
 * Everything is modular on purpose: register WARP1, load wg0.conf, register
 * WARP2 and start the chain are independent steps the UI can run and test one
 * at a time.
 */
// One flat object per step on purpose (see above), and every catch sits on a
// process / file / socket boundary where any failure must degrade to "false"
// instead of crashing the app — same trade-off UsqueManager makes.
@Suppress("TooManyFunctions", "TooGenericExceptionCaught", "ReturnCount")
object ChainManager {
    private const val TAG = "CHAIN_DEBUG"

    const val SOCKS_HOST = "127.0.0.1"

    // Distinct from UsqueManager.SOCKS_PORT (40000) so the chain and a stray
    // single-hop usque can never fight over the same port during testing.
    const val SOCKS_PORT = 40001

    private const val SUBCOMMAND = "chain"
    // A chain-capable libusque.so lists all of these in `chain --help`; a help that did
    // not is not trusted to filter flags (see UsqueFlags.parseHelp).
    private val CHAIN_CORE_FLAGS = listOf("bind", "port", "config", "wg", "exit-config")
    private const val SECRETS_STDIN_NAME = "secrets-stdin"
    // Starts with a flag left out each time the binary still refuses one (only when it
    // could not be asked which flags it takes).
    private const val MAX_FLAG_RETRIES = 3
    const val WARP1_CONFIG = "config.json"
    const val EXIT_CONFIG = "config_exit.json"
    const val WG_CONFIG = "wg0.conf"
    // The one field every usque config.json needs (the WARP identity's key).
    private const val WARP_KEY_FIELD = "private_key"

    // Timeouts (ms). The chain brings up three tunnels in series, so it gets a
    // longer start window than the single-hop path.
    private const val CHAIN_START_TIMEOUT_MS = 15_000L
    private const val PORT_RELEASE_TIMEOUT_MS = 2_000L
    private const val PORT_RELEASE_POLL_MS = 100L
    private const val PORT_PROBE_POLL_MS = 250L
    private const val PORT_CONNECT_TIMEOUT_MS = 300
    private const val REGISTER_OUTPUT_JOIN_MS = 3_000L
    private const val OUTPUT_DRAIN_JOIN_MS = 2_000L
    private const val LIVENESS_CONNECT_TIMEOUT_MS = 3_000
    private const val LIVENESS_READ_TIMEOUT_MS = 8_000
    // The SOCKS port opens before the hops carry traffic, so the end-to-end
    // probe is retried while WARP1, wg0 and WARP2 come up in series.
    const val LIVENESS_WAIT_MS = 30_000L
    // How the key bundle reaches usque, see startChainLocked.
    const val SECRETS_STDIN_FLAG = "--secrets-stdin"
    private const val LIVENESS_RETRY_MS = 2_000L

    // SOCKS5 liveness probe: CONNECT to 1.1.1.1:80.
    private const val SOCKS5_VERSION: Byte = 5
    private const val PROBE_PORT_LOW_BYTE: Byte = 80
    private const val SOCKS5_REPLY_LEN = 10

    // SOCKS5 reply framing for the exit-IP check: VER REP RSV ATYP, then
    // BND.ADDR (length depends on ATYP) and BND.PORT. Read exactly, so the
    // stream is left at the first byte of the HTTP response.
    private const val SOCKS5_REPLY_HEAD_LEN = 4
    private const val SOCKS5_ATYP_INDEX = 3
    private const val SOCKS5_ATYP_IPV4 = 1
    private const val SOCKS5_ATYP_DOMAIN = 3
    private const val SOCKS5_ATYP_IPV6 = 4
    private const val IPV4_ADDR_LEN = 4
    private const val IPV6_ADDR_LEN = 16
    private const val PORT_LEN = 2
    private const val BYTE_MASK = 0xFF
    // First printable ASCII character; anything below is escaped in JSON strings.
    private const val SPACE = 0x20

    // Exit-IP check: Cloudflare's /cdn-cgi/trace over plain HTTP through a local
    // SOCKS5. The CONNECT target stays 1.1.1.1:80 (same as the liveness probe, no
    // DNS), but Host must be cp.cloudflare.com: 1.1.1.1 now 301-redirects a
    // plain-HTTP trace for its own Host to https, while Cloudflare's captive-portal
    // host is still served on 1.1.1.1:80 (checked Oct 2026).
    private const val TRACE_READ_TIMEOUT_MS = 10_000
    private const val TRACE_MAX_BYTES = 16 * 1024
    private const val TRACE_CHUNK_BYTES = 2_048
    private const val TRACE_REQUEST =
        "GET /cdn-cgi/trace HTTP/1.1\r\n" +
            "Host: cp.cloudflare.com\r\n" +
            "User-Agent: AuroraVPN-chain-check\r\n" +
            "Accept: text/plain\r\n" +
            "Connection: close\r\n\r\n"
    private const val TRACE_KEY_IP = "ip"
    private const val TRACE_KEY_LOC = "loc"
    private const val TRACE_KEY_COLO = "colo"
    private const val TRACE_KEY_WARP = "warp"
    private val TRACE_KEYS = setOf(TRACE_KEY_IP, TRACE_KEY_LOC, TRACE_KEY_COLO, TRACE_KEY_WARP)
    // "CF-RAY: a44866674eab06f3-BOG": the suffix is the serving colo, present
    // even on a 301/403, so it still identifies the exit PoP if the trace fails.
    private const val CF_RAY_HEADER = "cf-ray:"

    /** Cloudflare's view of one request sent through a local SOCKS5 port. */
    sealed interface ExitTrace {
        /**
         * [ip] = egress IP Cloudflare saw, [loc] = its country, [colo] = IATA code
         * of the Cloudflare data center that served it, [warp] = on / plus / off.
         */
        data class Ok(val ip: String, val loc: String, val colo: String, val warp: String) : ExitTrace

        data class Failed(val reason: String) : ExitTrace
    }

    @Volatile private var process: Process? = null
    private val startLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var isStarting = false
    @Volatile private var portConfirmedAlive = false

    @Volatile private var deathCallback: (() -> Unit)? = null
    fun setDeathCallback(cb: (() -> Unit)?) { deathCallback = cb }

    // usque's recent output (UsqueOutput), and whether this libusque.so turned out to
    // be too old for --secrets-stdin and the reconnect flags.
    private val output = UsqueOutput()
    @Volatile private var legacyBinary = false

    /** Why the last start failed (usque's last error line), or "" when it did not say. */
    fun lastStartError(): String = output.lastError()

    // ── verbose log file (mirrors UsqueManager.warp_debug.txt) ────────────────
    private const val DEBUG_LOG_MAX_BYTES = 2L * 1024 * 1024
    const val DEBUG_LOG_NAME = "chain_debug.txt"

    @Synchronized
    private fun dlog(ctx: Context, msg: String) {
        Log.d(TAG, msg)
        try {
            val f = File(ctx.filesDir, DEBUG_LOG_NAME)
            if (f.length() > DEBUG_LOG_MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.substring(text.length / 2))
            }
            f.appendText("${System.currentTimeMillis()} $msg\n")
        } catch (_: Exception) {}
    }

    fun getDebugLogFile(ctx: Context): File = File(ctx.filesDir, DEBUG_LOG_NAME)

    fun readDebugLog(ctx: Context): String = try {
        val f = File(ctx.filesDir, DEBUG_LOG_NAME)
        if (f.exists()) f.readText() else "log file not found"
    } catch (e: Exception) { "error reading log: ${e.message}" }

    fun clearDebugLog(ctx: Context) {
        try { File(ctx.filesDir, DEBUG_LOG_NAME).delete() } catch (_: Exception) {}
    }

    // ── state inspection, per component ───────────────────────────────────────
    fun warp1Registered(ctx: Context): Boolean = KeyVault.exists(ctx, WARP1_CONFIG)
    fun warp2Registered(ctx: Context): Boolean = KeyVault.exists(ctx, EXIT_CONFIG)
    fun wgLoaded(ctx: Context): Boolean = KeyVault.exists(ctx, WG_CONFIG)

    fun chainReady(ctx: Context): Boolean =
        warp1Registered(ctx) && warp2Registered(ctx) && wgLoaded(ctx)

    // The installed libusque.so if it is the one this build pinned (UsqueBinary.check), else
    // null with the reason in [output], which lastStartError() shows.
    private fun verifiedBinary(ctx: Context): File? {
        val check = UsqueBinary.check(ctx)
        dlog(ctx, "getBinary: ${check.describe()}")
        if (check is UsqueBinary.Check.Ok) return check.file
        output.clear()
        output.add("error: ${check.reason()}")
        Logger.e(Logger.LOG_TAG_PROXY, "chain: ${check.reason()}")
        return null
    }

    // ── config readers / writers (UI editors use these) ───────────────────────
    /** A key file's text for the editors, keys shown as [KeyRedaction.HIDDEN]; "" if none. */
    fun readFile(ctx: Context, name: String): String {
        val data = KeyVault.read(ctx, name) ?: return ""
        return try {
            val text = String(data, Charsets.UTF_8)
            if (name == WG_CONFIG) KeyRedaction.hideWg(text) else KeyRedaction.hideJson(text)
        } finally {
            data.fill(0)
        }
    }

    private fun stored(ctx: Context, name: String): String? {
        val data = KeyVault.read(ctx, name) ?: return null
        return try { String(data, Charsets.UTF_8) } finally { data.fill(0) }
    }

    /**
     * Stores a WARP identity (config.json / config_exit.json) after checking it
     * parses as a JSON object with the private_key usque needs, so a truncated
     * paste is refused here instead of breaking the next start. Keys still
     * reading [KeyRedaction.HIDDEN] keep their stored values.
     */
    fun writeConfigJson(ctx: Context, name: String, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) { dlog(ctx, "writeConfigJson($name): empty"); return false }
        val restored = KeyRedaction.restoreJson(trimmed, stored(ctx, name))
        if (restored == null) { dlog(ctx, "writeConfigJson($name): hidden key with nothing stored"); return false }
        val parsed = try { org.json.JSONObject(restored) } catch (_: org.json.JSONException) { null }
        if (parsed == null) { dlog(ctx, "writeConfigJson($name): not a JSON object"); return false }
        if (parsed.optString(WARP_KEY_FIELD).isBlank()) {
            dlog(ctx, "writeConfigJson($name): no $WARP_KEY_FIELD")
            return false
        }
        return seal(ctx, name, restored)
    }

    /**
     * Stores wg0.conf after a minimal structural check (has [Interface], a
     * PrivateKey, a [Peer] and an Endpoint). The binary does the real parse and
     * rejects AmneziaWG; this only catches obvious paste mistakes early.
     */
    fun writeWgConfig(ctx: Context, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) { dlog(ctx, "writeWgConfig: empty"); return false }
        val restored = KeyRedaction.restoreWg(trimmed, stored(ctx, WG_CONFIG))
        if (restored == null) { dlog(ctx, "writeWgConfig: hidden key with nothing stored"); return false }
        val lower = restored.lowercase()
        val ok = lower.contains("[interface]") && lower.contains("privatekey") &&
            lower.contains("[peer]") && lower.contains("endpoint")
        if (!ok) { dlog(ctx, "writeWgConfig: missing [Interface]/PrivateKey/[Peer]/Endpoint"); return false }
        return seal(ctx, WG_CONFIG, restored)
    }

    private fun seal(ctx: Context, name: String, text: String): Boolean {
        val ok = KeyVault.write(ctx, name, text.toByteArray(Charsets.UTF_8))
        dlog(ctx, "seal($name): saved=$ok (${text.length} chars)")
        return ok
    }

    // ── step 1 / 3: register a WARP identity into [configName] ────────────────
    /**
     * Runs `libusque.so register` and writes the result to [configName].
     * Used for both WARP1 (config.json) and WARP2 (config_exit.json) — the only
     * difference is the target file, which is why it is modular.
     */
    suspend fun registerWarp(ctx: Context, configName: String): Boolean = withContext(Dispatchers.IO) {
        dlog(ctx, "registerWarp($configName): >>>ENTRY<<<")
        // usque writes the new identity here; it is sealed and wiped right after. The
        // current identity stays until the new one exists, so a failed (rate-limited)
        // re-registration does not leave the hop without keys.
        val configFile = KeyVault.scratchFile(ctx, configName)
        try {
            val bin = verifiedBinary(ctx) ?: return@withContext false


            val cmd = listOf(bin.absolutePath, "register", "--accept-tos", "-c", configFile.absolutePath)
            dlog(ctx, "cmd=${cmd.joinToString(" ")}")
            val pb = ProcessBuilder(cmd).redirectErrorStream(false)
            pb.environment()["GODEBUG"] = "vgetrandom=off"
            val proc = pb.start()

            val out = StringWriter(); val errw = StringWriter()
            val tout = Thread {
                try { out.write(proc.inputStream.bufferedReader().readText()) } catch (_: Exception) {}
            }.also { it.start() }
            val terr = Thread {
                try { errw.write(proc.errorStream.bufferedReader().readText()) } catch (_: Exception) {}
            }.also { it.start() }
            val exit = proc.waitFor(); tout.join(REGISTER_OUTPUT_JOIN_MS); terr.join(REGISTER_OUTPUT_JOIN_MS)

            dlog(ctx, "register exit=$exit")
            dlog(ctx, "register stdout=$out")
            dlog(ctx, "register stderr=$errw")
            val written = configFile.length()
            val ok = exit == 0 && written > 0L && KeyVault.adopt(ctx, configFile, configName)
            dlog(ctx, "registerWarp($configName) result=$ok size=$written")
            // A second registration from the same IP may be rate-limited by
            // Cloudflare; the caller should surface that and allow a retry.
            ok
        } catch (e: Exception) {
            dlog(ctx, "registerWarp EXCEPTION ${e.message}\n${e.stackTraceToString()}")
            Logger.e(Logger.LOG_TAG_PROXY, "registerWarp($configName) exception", e)
            false
        } finally {
            KeyVault.wipe(configFile)
        }
    }

    // ── step 4: start the whole chain ─────────────────────────────────────────
    suspend fun startChain(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        startLock.withLock {
            isStarting = true
            try { startChainLocked(ctx) } finally { isStarting = false }
        }
    }

    // A linear start/attach/verify sequence kept in one place so the log reads
    // top to bottom; mirrors UsqueManager.startSocksProxyLocked.
    @Suppress(
        "LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod",
        "LoopWithTooManyJumpStatements", "NestedBlockDepth",
    )
    private fun startChainLocked(ctx: Context): Boolean {
        dlog(ctx, "startChain: >>>ENTRY<<<")
        val existing = process
        if (existing != null && existing.isAlive && isPortAlive()) {
            dlog(ctx, "startChain: already running and healthy — skip")
            portConfirmedAlive = true
            return true
        }
        if (process != null) {
            stopChain()
            waitForPortRelease(ctx, PORT_RELEASE_TIMEOUT_MS)
        }
        if (isPortAlive()) {
            dlog(ctx, "startChain: port alive, no proc ref — reattach to orphan")
            portConfirmedAlive = true
            return true
        }
        portConfirmedAlive = false

        if (!chainReady(ctx)) {
            dlog(ctx, "startChain: not ready (warp1=${warp1Registered(ctx)} wg=${wgLoaded(ctx)} warp2=${warp2Registered(ctx)})")
            return false
        }

        return try {
            val bin = verifiedBinary(ctx) ?: return false
            // Fixed core + the per-hop flags from the chain screen (see ChainArgs).
            val ps = runCatching {
                org.koin.java.KoinJavaComponent.get<PersistentState>(PersistentState::class.java)
            }.getOrNull()
            val keys = readKeys(ctx)
            if (keys == null) {
                output.clear()
                output.add("error: chain keys missing or unreadable, register / load them again")
                dlog(ctx, "startChain: keys missing or unreadable")
                return false
            }
            try {
                // Which flags this libusque.so takes; null when it could not be asked.
                val flags = UsqueBinary.flags(bin, SUBCOMMAND, CHAIN_CORE_FLAGS)
                if (flags == null) dlog(ctx, "startChain: could not read `chain --help`, passing the flags unchecked")
                if (flags != null && SECRETS_STDIN_NAME !in flags) legacyBinary = true
                if (!legacyBinary) {
                    val dropped = mutableListOf<String>()
                    var refused: String? = null
                    for (attempt in 1..MAX_FLAG_RETRIES) {
                        val args = supported(ctx, UsqueOutput.without(ChainArgs.build(ps) + SECRETS_STDIN_FLAG, dropped), flags)
                        val bundle = keyBundle(keys)
                        val started = try { spawnChain(ctx, bin, args, bundle) } finally { bundle.fill(0) }
                        refused = output.rejectedFlag()
                        if (started || refused == null) return started
                        if (refused == SECRETS_STDIN_FLAG) break
                        // A flag the binary refused although it was not filtered out: leave it
                        // out and start again, still with the keys on stdin.
                        dropped += refused
                        dlog(ctx, "startChain: libusque.so refused $refused (try $attempt) — starting again without it")
                    }
                    if (refused != SECRETS_STDIN_FLAG) return false
                    legacyBinary = true
                    dlog(ctx, "startChain: libusque.so has no --secrets-stdin — retrying the old way")
                }
                startLegacy(ctx, bin, ps, keys, flags)
            } finally {
                keys.values.forEach { it.fill(0) }
            }
        } catch (e: Exception) {
            dlog(ctx, "startChain: EXCEPTION ${e.message}\n${e.stackTraceToString()}")
            Logger.e(Logger.LOG_TAG_PROXY, "startChain exception", e)
            false
        }
    }

    // The three key files, unsealed; null if one is missing or unreadable.
    private fun readKeys(ctx: Context): Map<String, ByteArray>? {
        val out = LinkedHashMap<String, ByteArray>()
        for (name in listOf(WARP1_CONFIG, EXIT_CONFIG, WG_CONFIG)) {
            val data = KeyVault.read(ctx, name)
            if (data == null) {
                out.values.forEach { it.fill(0) }
                return null
            }
            out[name] = data
        }
        return out
    }

    // {"config": <WARP1>, "exit_config": <WARP2>, "wg": "<wg0.conf>"} for --secrets-stdin,
    // assembled from bytes so the keys are not copied into Strings.
    private fun keyBundle(keys: Map<String, ByteArray>): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        fun put(s: String) = buf.write(s.toByteArray(Charsets.UTF_8))
        put("{\"config\":"); buf.write(keys.getValue(WARP1_CONFIG))
        put(",\"exit_config\":"); buf.write(keys.getValue(EXIT_CONFIG))
        put(",\"wg\":"); buf.write(jsonString(keys.getValue(WG_CONFIG)))
        put("}")
        return buf.toByteArray()
    }

    // [text] (UTF-8) as a JSON string literal, escaped byte by byte.
    private fun jsonString(text: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.size + 2)
        out.write('"'.code)
        for (b in text) {
            val c = b.toInt() and BYTE_MASK
            when {
                c == '"'.code || c == '\\'.code -> { out.write('\\'.code); out.write(c) }
                c == '\n'.code -> { out.write('\\'.code); out.write('n'.code) }
                c == '\r'.code -> { out.write('\\'.code); out.write('r'.code) }
                c == '\t'.code -> { out.write('\\'.code); out.write('t'.code) }
                c < SPACE -> out.write(String.format(java.util.Locale.ROOT, "\\u%04x", c).toByteArray(Charsets.US_ASCII))
                else -> out.write(c)
            }
        }
        out.write('"'.code)
        return out.toByteArray()
    }

    /**
     * For a libusque.so without --secrets-stdin or the reconnect flags: the keys go in
     * private scratch files that are wiped as soon as usque has read them (it reads them
     * once, at start, before the SOCKS port opens).
     */
    private fun startLegacy(
        ctx: Context,
        bin: File,
        ps: PersistentState?,
        keys: Map<String, ByteArray>,
        flags: UsqueFlags?,
    ): Boolean {
        val files = keys.mapValues { (name, data) -> KeyVault.scratchFile(ctx, name).also { it.writeBytes(data) } }
        return try {
            val all = ChainArgs.buildWithFiles(ps, files.mapValues { it.value.absolutePath })
            // Known flags: keep exactly what the binary takes. Unknown: the old guess.
            val args = if (flags != null) supported(ctx, all, flags) else UsqueOutput.withoutNewerFlags(all)
            spawnChain(ctx, bin, args, null)
        } finally {
            files.values.forEach { KeyVault.wipe(it) }
        }
    }

    /**
     * [args] without the flags this libusque.so does not take ([flags] null: unchanged). A
     * left-out flag is logged: the app (or a hop edit) is newer than the binary.
     */
    private fun supported(ctx: Context, args: List<String>, flags: UsqueFlags?): List<String> {
        if (flags == null) return args
        val result = flags.supportedOnly(args)
        if (result.dropped.isNotEmpty()) {
            val msg = "this libusque.so does not take ${result.dropped.joinToString(" ")} — left out (update libusque.so)"
            dlog(ctx, "startChain: $msg")
            Logger.w(Logger.LOG_TAG_PROXY, "chain: $msg")
        }
        return result.kept
    }

    /** Spawns the chain with [args] and [stdin] (the key bundle, or nothing), then waits for its port. */
    private fun spawnChain(ctx: Context, bin: File, args: List<String>, stdin: ByteArray?): Boolean {
        val cmd = listOf(bin.absolutePath) + args
        dlog(ctx, "startChain: cmd=${cmd.joinToString(" ")}")
        output.clear()
        val pb = ProcessBuilder(cmd).redirectErrorStream(false)
        pb.environment()["GODEBUG"] = "vgetrandom=off"
        val proc = pb.start()
        process = proc
        UsqueManager.writeStdin(proc, stdin)

        val outThread = pumpOutput(ctx, proc.inputStream, "stdout")
        val errThread = pumpOutput(ctx, proc.errorStream, "stderr")

        // The chain brings up three tunnels in series (WARP1, then wg0, then
        // the HTTP/2 exit), so give the port longer to appear than the
        // single-hop path does.
        val portReady = probePort(ctx, CHAIN_START_TIMEOUT_MS, proc)
        val procAlive = proc.isAlive
        dlog(ctx, "startChain: proc.isAlive=$procAlive portReady=$portReady")

        if (portReady && procAlive) {
            portConfirmedAlive = true
            val captured = proc
            Thread {
                try {
                    captured.waitFor()
                    if (process === captured && portConfirmedAlive) {
                        portConfirmedAlive = false
                        Log.w(TAG, "chain process died unexpectedly — firing restart callback")
                        deathCallback?.invoke()
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "chain-death-watcher" }.start()
        } else {
            portConfirmedAlive = false
            // Port never came up but the process may still be alive (e.g. a slow
            // wg0 hostname lookup): kill it, or it would bind :40001 later as an
            // orphan that stopChain() can no longer reach.
            if (proc.isAlive) proc.destroyForcibly()
            // Wait for the kill to land so the log shows the real exit code (not -1).
            proc.waitFor(STOP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            outThread.join(OUTPUT_DRAIN_JOIN_MS); errThread.join(OUTPUT_DRAIN_JOIN_MS)
            val code = try { proc.exitValue() } catch (_: Exception) { -1 }
            dlog(ctx, "startChain: exit=$code reason=${output.lastError()}")
            process = null
        }
        return portReady && procAlive
    }

    private fun pumpOutput(ctx: Context, stream: java.io.InputStream, label: String): Thread =
        Thread {
            try {
                stream.bufferedReader().forEachLine { line ->
                    output.add(line)
                    dlog(ctx, "chain $label: $line")
                    Logger.i(Logger.LOG_TAG_PROXY, "chain: $line")
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "chain-$label"; start() }

    private const val STOP_GRACE_MS = 800L

    fun stopChain() {
        val p = process
        Log.d(TAG, "stopChain: isAlive=${p?.isAlive}")
        portConfirmedAlive = false
        process = null
        if (p == null) return
        try {
            p.destroy()
            if (!p.waitFor(STOP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "stopChain: SIGTERM ignored — SIGKILL")
                p.destroyForcibly(); p.waitFor(STOP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopChain: kill failed ${e.message}")
            try { p.destroyForcibly() } catch (_: Exception) {}
        }
    }

    /** True while startChain() is bringing the hops up (the service must not restart it then). */
    fun isChainStarting(): Boolean = isStarting

    fun isRunning(): Boolean {
        if (isStarting) return true
        val p = process
        if (p != null) {
            if (p.isAlive) return true
            portConfirmedAlive = false
            return false
        }
        if (!portConfirmedAlive) return false
        val alive = isPortAlive()
        if (!alive) portConfirmedAlive = false
        return alive
    }

    fun isPortAlive(): Boolean = try {
        android.net.TrafficStats.setThreadStatsTag(android.os.Process.myTid())
        try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(SOCKS_HOST, SOCKS_PORT), PORT_CONNECT_TIMEOUT_MS); true
            }
        } finally { android.net.TrafficStats.clearThreadStatsTag() }
    } catch (_: Exception) { false }

    /**
     * End-to-end liveness: a real SOCKS5 CONNECT to 1.1.1.1:80 through the chain,
     * so this only succeeds if WARP1, wg0 AND WARP2 are all carrying traffic.
     */
    suspend fun probeChainLiveness(): Boolean = withContext(Dispatchers.IO) {
        try {
            android.net.TrafficStats.setThreadStatsTag(android.os.Process.myTid())
            try {
                java.net.Socket().use { s ->
                    s.soTimeout = LIVENESS_READ_TIMEOUT_MS
                    s.connect(java.net.InetSocketAddress(SOCKS_HOST, SOCKS_PORT), LIVENESS_CONNECT_TIMEOUT_MS)
                    val out = s.getOutputStream(); val inp = s.getInputStream()
                    // greeting: VER, 1 method, NO AUTH
                    out.write(byteArrayOf(SOCKS5_VERSION, 1, 0))
                    val greet = ByteArray(2)
                    if (inp.read(greet) != 2 || greet[0] != SOCKS5_VERSION || greet[1] == 0xFF.toByte()) return@withContext false
                    // CONNECT, RSV, ATYP=IPv4, 1.1.1.1, port 0x0050 (80)
                    out.write(byteArrayOf(SOCKS5_VERSION, 1, 0, 1, 1, 1, 1, 1, 0, PROBE_PORT_LOW_BYTE))
                    val rep = ByteArray(SOCKS5_REPLY_LEN)
                    val n = inp.read(rep)
                    n >= 2 && rep[0] == SOCKS5_VERSION && rep[1] == 0x00.toByte()
                }
            } finally { android.net.TrafficStats.clearThreadStatsTag() }
        } catch (_: Exception) { false }
    }

    /**
     * The SOCKS port opens before the hops carry traffic: WARP1 connects, then
     * wg0 must handshake inside it, then WARP2 connects inside wg0. Retry the
     * end-to-end probe until it passes, the process dies, or timeoutMs elapses.
     */
    suspend fun awaitChainLiveness(ctx: Context, timeoutMs: Long = LIVENESS_WAIT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            if (probeChainLiveness()) {
                dlog(ctx, "awaitChainLiveness: OK after $attempt attempts")
                return true
            }
            if (process?.isAlive != true) {
                dlog(ctx, "awaitChainLiveness: process died after $attempt attempts")
                return false
            }
            delay(LIVENESS_RETRY_MS)
        }
        dlog(ctx, "awaitChainLiveness: no traffic through the chain after ${timeoutMs}ms / $attempt attempts")
        return false
    }

    // ── exit-IP check (Cloudflare trace through a local SOCKS5) ───────────────
    /**
     * GETs /cdn-cgi/trace through the SOCKS5 on 127.0.0.1:[port] and returns what
     * Cloudflare saw (ip=, loc=, colo=, warp=). On [SOCKS_PORT] the request leaves
     * via WARP2: the chain's SOCKS server dials only through the WARP2 stack, and
     * WARP2 reaches Cloudflare from the wg0 server. On UsqueManager.SOCKS_PORT it
     * leaves via simple WARP, which uses WARP1's identity (config.json) from the
     * phone, so it is the WARP1-side baseline to compare against. Read-only.
     */
    suspend fun fetchExitTrace(ctx: Context, port: Int = SOCKS_PORT): ExitTrace = withContext(Dispatchers.IO) {
        val result = try {
            android.net.TrafficStats.setThreadStatsTag(android.os.Process.myTid())
            try { traceViaSocks5(port) } finally { android.net.TrafficStats.clearThreadStatsTag() }
        } catch (e: Exception) {
            ExitTrace.Failed(e.message ?: e.javaClass.simpleName)
        }
        dlog(ctx, "exitTrace(:$port): $result")
        result
    }

    private fun traceViaSocks5(port: Int): ExitTrace = java.net.Socket().use { s ->
        s.soTimeout = TRACE_READ_TIMEOUT_MS
        s.connect(java.net.InetSocketAddress(SOCKS_HOST, port), LIVENESS_CONNECT_TIMEOUT_MS)
        val inp = java.io.DataInputStream(s.getInputStream())
        val out = s.getOutputStream()
        socks5ConnectCloudflare(inp, out)
        out.write(TRACE_REQUEST.toByteArray(Charsets.US_ASCII))
        out.flush()
        parseTrace(readTraceResponse(inp))
    }

    /** No-auth greeting + CONNECT 1.1.1.1:80; throws IOException with a readable reason. */
    private fun socks5ConnectCloudflare(inp: java.io.DataInputStream, out: java.io.OutputStream) {
        out.write(byteArrayOf(SOCKS5_VERSION, 1, 0))
        out.flush()
        val greet = ByteArray(2)
        inp.readFully(greet)
        if (greet[0] != SOCKS5_VERSION || greet[1] != 0.toByte()) {
            socksFail("SOCKS5 greeting rejected (method=${greet[1].toInt() and BYTE_MASK})")
        }
        out.write(byteArrayOf(SOCKS5_VERSION, 1, 0, 1, 1, 1, 1, 1, 0, PROBE_PORT_LOW_BYTE))
        out.flush()
        val head = ByteArray(SOCKS5_REPLY_HEAD_LEN)
        inp.readFully(head)
        val rep = head[1].toInt() and BYTE_MASK
        if (head[0] != SOCKS5_VERSION || rep != 0) socksFail("SOCKS5 CONNECT 1.1.1.1:80 failed (rep=$rep)")
        val addrLen = when (head[SOCKS5_ATYP_INDEX].toInt()) {
            SOCKS5_ATYP_IPV4 -> IPV4_ADDR_LEN
            SOCKS5_ATYP_IPV6 -> IPV6_ADDR_LEN
            SOCKS5_ATYP_DOMAIN -> inp.readUnsignedByte()
            else -> socksFail("SOCKS5 reply has unknown ATYP ${head[SOCKS5_ATYP_INDEX]}")
        }
        inp.readFully(ByteArray(addrLen + PORT_LEN))
    }

    private fun socksFail(reason: String): Nothing = throw java.io.IOException(reason)

    /**
     * Reads until EOF (the request says Connection: close), [TRACE_MAX_BYTES], a
     * read timeout, or until every key in [TRACE_KEYS] has arrived on a complete line.
     */
    private fun readTraceResponse(inp: java.io.InputStream): String {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(TRACE_CHUNK_BYTES)
        var done = false
        while (!done && buf.size() < TRACE_MAX_BYTES) {
            // A timeout after partial data still leaves something worth parsing.
            val n = try { inp.read(chunk) } catch (_: java.net.SocketTimeoutException) { -1 }
            if (n < 0) {
                done = true
            } else {
                buf.write(chunk, 0, n)
                val completeLines = String(buf.toByteArray(), Charsets.UTF_8).substringBeforeLast('\n', "")
                done = traceFields(completeLines).keys.containsAll(TRACE_KEYS)
            }
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }

    private fun parseTrace(response: String): ExitTrace {
        val fields = traceFields(response)
        val colo = fields[TRACE_KEY_COLO] ?: cfRayColo(response)
        val ip = fields[TRACE_KEY_IP]
        if (ip.isNullOrEmpty()) {
            val status = response.substringBefore('\n').trim().ifEmpty { "empty response" }
            val coloHint = if (colo.isEmpty()) "" else ", colo $colo from CF-RAY"
            return ExitTrace.Failed("no ip= in Cloudflare trace ($status$coloHint)")
        }
        return ExitTrace.Ok(
            ip = ip,
            loc = fields[TRACE_KEY_LOC].orEmpty(),
            colo = colo,
            warp = fields[TRACE_KEY_WARP].orEmpty(),
        )
    }

    /** key=value lines of the trace body; no HTTP header yields one of [TRACE_KEYS]. */
    private fun traceFields(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { '=' in it }
            .map { it.substringBefore('=') to it.substringAfter('=') }
            .filter { it.first in TRACE_KEYS }
            .toMap()

    private fun cfRayColo(response: String): String =
        response.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(CF_RAY_HEADER, ignoreCase = true) }
            ?.substringAfterLast('-', "")
            .orEmpty()

    fun reattachIfPortAlive(ctx: Context): Boolean {
        val alive = isPortAlive()
        portConfirmedAlive = alive
        dlog(ctx, "reattachIfPortAlive: portAlive=$alive")
        return alive
    }

    private fun waitForPortRelease(ctx: Context, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isPortAlive()) return true
            Thread.sleep(PORT_RELEASE_POLL_MS)
        }
        dlog(ctx, "waitForPortRelease: still bound after ${timeoutMs}ms")
        return false
    }

    // Gives up as soon as [proc] has exited: a refused flag ends usque in milliseconds, and
    // waiting out the whole timeout for a dead process held every start up for 15s.
    private fun probePort(ctx: Context, timeoutMs: Long, proc: Process): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            if (!proc.isAlive) {
                dlog(ctx, "probePort: process exited after $attempt attempts")
                return false
            }
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(SOCKS_HOST, SOCKS_PORT), PORT_CONNECT_TIMEOUT_MS)
                    dlog(ctx, "probePort: ready after $attempt attempts"); return true
                }
            } catch (_: Exception) {}
            Thread.sleep(PORT_PROBE_POLL_MS)
        }
        dlog(ctx, "probePort: NOT ready after ${timeoutMs}ms / $attempt attempts")
        return false
    }
}
