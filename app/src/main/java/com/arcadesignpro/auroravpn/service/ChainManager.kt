package com.arcadesignpro.auroravpn.service

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

    private const val BINARY_NAME = "libusque.so"
    const val WARP1_CONFIG = "config.json"
    const val EXIT_CONFIG = "config_exit.json"
    const val WG_CONFIG = "wg0.conf"

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
    private const val LIVENESS_RETRY_MS = 2_000L

    // SOCKS5 liveness probe: CONNECT to 1.1.1.1:80.
    private const val SOCKS5_VERSION: Byte = 5
    private const val PROBE_PORT_LOW_BYTE: Byte = 80
    private const val SOCKS5_REPLY_LEN = 10

    @Volatile private var process: Process? = null
    private val startLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var isStarting = false
    @Volatile private var portConfirmedAlive = false

    @Volatile private var deathCallback: (() -> Unit)? = null
    fun setDeathCallback(cb: (() -> Unit)?) { deathCallback = cb }

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
    fun warp1Registered(ctx: Context): Boolean = fileNonEmpty(ctx, WARP1_CONFIG)
    fun warp2Registered(ctx: Context): Boolean = fileNonEmpty(ctx, EXIT_CONFIG)
    fun wgLoaded(ctx: Context): Boolean = fileNonEmpty(ctx, WG_CONFIG)

    fun chainReady(ctx: Context): Boolean =
        warp1Registered(ctx) && warp2Registered(ctx) && wgLoaded(ctx)

    private fun fileNonEmpty(ctx: Context, name: String): Boolean {
        val f = File(ctx.filesDir, name)
        return f.exists() && f.length() > 0L
    }

    private fun getBinary(ctx: Context): File {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, BINARY_NAME)
        dlog(ctx, "getBinary: path=${bin.absolutePath} exists=${bin.exists()} canExec=${bin.canExecute()} size=${bin.length()}")
        return bin
    }

    // ── config readers / writers (UI editors use these) ───────────────────────
    fun readFile(ctx: Context, name: String): String = try {
        val f = File(ctx.filesDir, name)
        if (f.exists()) f.readText() else ""
    } catch (e: Exception) {
        Logger.e(Logger.LOG_TAG_PROXY, "ChainManager.readFile($name): ${e.message}", e)
        ""
    }

    /** Atomically writes a config file after a light validity check. */
    fun writeConfigJson(ctx: Context, name: String, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) { dlog(ctx, "writeConfigJson($name): empty"); return false }
        val looksJson = trimmed.startsWith("{") && trimmed.endsWith("}")
        if (!looksJson) { dlog(ctx, "writeConfigJson($name): not JSON"); return false }
        return atomicWrite(ctx, name, trimmed)
    }

    /**
     * Writes wg0.conf after a minimal structural check (has [Interface], a
     * PrivateKey, a [Peer] and an Endpoint). The binary does the real parse and
     * rejects AmneziaWG; this only catches obvious paste mistakes early.
     */
    fun writeWgConfig(ctx: Context, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) { dlog(ctx, "writeWgConfig: empty"); return false }
        val lower = trimmed.lowercase()
        val ok = lower.contains("[interface]") && lower.contains("privatekey") &&
            lower.contains("[peer]") && lower.contains("endpoint")
        if (!ok) { dlog(ctx, "writeWgConfig: missing [Interface]/PrivateKey/[Peer]/Endpoint"); return false }
        return atomicWrite(ctx, WG_CONFIG, trimmed)
    }

    private fun atomicWrite(ctx: Context, name: String, text: String): Boolean = try {
        val target = File(ctx.filesDir, name)
        val tmp = File(ctx.filesDir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.writeText(text); tmp.delete() }
        // Config files hold key material; keep them owner-only.
        try { target.setReadable(false, false); target.setReadable(true, true) } catch (_: Exception) {}
        dlog(ctx, "atomicWrite($name): ${target.length()} bytes")
        true
    } catch (e: Exception) {
        Logger.e(Logger.LOG_TAG_PROXY, "ChainManager.atomicWrite($name): ${e.message}", e)
        dlog(ctx, "atomicWrite($name) EXCEPTION ${e.message}")
        false
    }

    // ── step 1 / 3: register a WARP identity into [configName] ────────────────
    /**
     * Runs `libusque.so register` and writes the result to [configName].
     * Used for both WARP1 (config.json) and WARP2 (config_exit.json) — the only
     * difference is the target file, which is why it is modular.
     */
    suspend fun registerWarp(ctx: Context, configName: String): Boolean = withContext(Dispatchers.IO) {
        dlog(ctx, "registerWarp($configName): >>>ENTRY<<<")
        try {
            val bin = getBinary(ctx)
            if (!bin.exists()) { dlog(ctx, "BINARY NOT FOUND in jniLibs/arm64-v8a/"); return@withContext false }
            if (!bin.canExecute()) { dlog(ctx, "BINARY NOT EXECUTABLE — W^X?"); return@withContext false }

            val configFile = File(ctx.filesDir, configName)
            if (configFile.exists()) { configFile.delete(); dlog(ctx, "deleted old $configName") }

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
            val ok = exit == 0 && configFile.exists() && configFile.length() > 0L
            dlog(ctx, "registerWarp($configName) result=$ok size=${configFile.length()}")
            // A second registration from the same IP may be rate-limited by
            // Cloudflare; the caller should surface that and allow a retry.
            ok
        } catch (e: Exception) {
            dlog(ctx, "registerWarp EXCEPTION ${e.message}\n${e.stackTraceToString()}")
            Logger.e(Logger.LOG_TAG_PROXY, "registerWarp($configName) exception", e)
            false
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
    @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
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
            val bin = getBinary(ctx)
            if (!bin.exists() || !bin.canExecute()) {
                dlog(ctx, "startChain: binary not ready"); return false
            }
            val warp1 = File(ctx.filesDir, WARP1_CONFIG).absolutePath
            val wg = File(ctx.filesDir, WG_CONFIG).absolutePath
            val exit = File(ctx.filesDir, EXIT_CONFIG).absolutePath
            val sni = runCatching {
                org.koin.java.KoinJavaComponent.get<PersistentState>(PersistentState::class.java).warpSpoofedSni
            }.getOrDefault(UsqueManager.DEFAULT_WARP_SNI).trim().ifEmpty { UsqueManager.DEFAULT_WARP_SNI }

            val cmd = mutableListOf(
                bin.absolutePath, "chain",
                "-b", SOCKS_HOST, "-p", SOCKS_PORT.toString(),
                "-c", warp1,
                "--wg", wg,
                "--exit-config", exit,
                "--exit-transport", "auto", // QUIC does not fit through wg0; auto picks HTTP/2
                "-s", sni,                  // WARP1 SNI — the only hop the ISP sees
                "-i", "1350",               // Cloudflare-sized QUIC packets for WARP1
            )
            dlog(ctx, "startChain: cmd=${cmd.joinToString(" ")}")
            val pb = ProcessBuilder(cmd).redirectErrorStream(false)
            pb.environment()["GODEBUG"] = "vgetrandom=off"
            val proc = pb.start()
            process = proc

            val outThread = pumpOutput(ctx, proc.inputStream, "stdout")
            val errThread = pumpOutput(ctx, proc.errorStream, "stderr")

            // The chain brings up three tunnels in series (WARP1, then wg0, then
            // the HTTP/2 exit), so give the port longer to appear than the
            // single-hop path does.
            val portReady = probePort(ctx, CHAIN_START_TIMEOUT_MS)
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
                outThread.join(OUTPUT_DRAIN_JOIN_MS); errThread.join(OUTPUT_DRAIN_JOIN_MS)
                val code = try { proc.exitValue() } catch (_: Exception) { -1 }
                dlog(ctx, "startChain: exit=$code")
                process = null
            }
            portReady && procAlive
        } catch (e: Exception) {
            dlog(ctx, "startChain: EXCEPTION ${e.message}\n${e.stackTraceToString()}")
            Logger.e(Logger.LOG_TAG_PROXY, "startChain exception", e)
            false
        }
    }

    private fun pumpOutput(ctx: Context, stream: java.io.InputStream, label: String): Thread =
        Thread {
            try {
                stream.bufferedReader().forEachLine { line ->
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

    private fun probePort(ctx: Context, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
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
