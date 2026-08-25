package com.creatore.rethinkfork.service

import Logger
import android.content.Context
import android.util.Log
import java.io.File
import java.io.StringWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object UsqueManager {
    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 40000
    private const val BINARY_NAME = "libusque.so"
    @Volatile private var process: Process? = null
    // Prevents concurrent startSocksProxy calls from killing each other (restart storm).
    private val startLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var isStarting = false

    // ── Immediate death detection ─────────────────────────────────────────────
    // Registered by BraveVPNService; fired as soon as the child process exits
    // so the VPN service can restart usque instantly instead of waiting up to
    // 20 s for the next watchdog tick.
    @Volatile private var deathCallback: (() -> Unit)? = null

    /** Register (or clear) the callback that is invoked the moment usque dies. */
    fun setDeathCallback(cb: (() -> Unit)?) {
        deathCallback = cb
    }

    // ── debug log file ────────────────────────────────────────────────────────
    private fun dlog(ctx: Context, msg: String) {
        Log.d("WARP_DEBUG", msg)
        try {
            File(ctx.filesDir, "warp_debug.txt").appendText("${System.currentTimeMillis()} $msg\n")
        } catch (_: Exception) {}
    }

    fun getDebugLogFile(ctx: Context): File = File(ctx.filesDir, "warp_debug.txt")

    fun readDebugLog(ctx: Context): String {
        return try {
            val f = File(ctx.filesDir, "warp_debug.txt")
            if (f.exists()) f.readText() else "log file not found"
        } catch (e: Exception) {
            "error reading log: ${e.message}"
        }
    }

    fun clearDebugLog(ctx: Context) {
        try { File(ctx.filesDir, "warp_debug.txt").delete() } catch (_: Exception) {}
    }
    // ─────────────────────────────────────────────────────────────────────────

    // ── libusque.so arguments (user-editable) ────────────────────────────────
    // The Proxy settings screen exposes the exact argument string passed to
    // libusque.so and lets advanced users edit it. Two placeholders are
    // substituted at process-start time:
    //   {config} → absolute path of the on-disk config.json
    //   {sni}    → current warpSpoofedSni value (may be empty)
    // The default template mirrors the historical hard-coded arg list so
    // existing installs behave identically until the user opts in.
    const val DEFAULT_SOCKS_ARGS_TEMPLATE =
        "socks -b $SOCKS_HOST -p $SOCKS_PORT -c {config}"

    /** Returns the default arg string (with {sni} appended when SNI is set). */
    fun defaultSocksArgsTemplate(sni: String): String {
        val base = DEFAULT_SOCKS_ARGS_TEMPLATE
        return if (sni.isNotBlank()) "$base -s {sni}" else base
    }

    /** Returns the args string currently shown in the UI editor: the user
     *  override if one is saved, otherwise the default template rendered
     *  against the current SNI value. Placeholders are preserved. */
    fun currentSocksArgsForEditor(): String {
        val ps = try {
            org.koin.java.KoinJavaComponent
                .get<PersistentState>(PersistentState::class.java)
        } catch (_: Throwable) { null }
        val override = ps?.warpUsqueArgs?.trim().orEmpty()
        if (override.isNotEmpty()) return override
        val sni = ps?.warpSpoofedSni?.trim().orEmpty()
        return defaultSocksArgsTemplate(sni)
    }

    /** Resolves the final argv (excluding the binary path) that will be
     *  handed to ProcessBuilder. Handles {config}/{sni} substitution and
     *  splits on whitespace. Falls back to the default template if the
     *  saved override is blank or parses to an empty list. */
    fun buildSocksArgs(ctx: Context, configPath: String): List<String> {
        val ps = try {
            org.koin.java.KoinJavaComponent
                .get<PersistentState>(PersistentState::class.java)
        } catch (t: Throwable) {
            dlog(ctx, "buildSocksArgs: PersistentState lookup failed: ${t.message}")
            null
        }
        val sni = ps?.warpSpoofedSni?.trim().orEmpty()
        val override = ps?.warpUsqueArgs?.trim().orEmpty()
        val template = if (override.isNotEmpty()) override
                       else defaultSocksArgsTemplate(sni)
        val rendered = template
            .replace("{config}", configPath)
            .replace("{sni}", sni)
        // Whitespace split — usque args do not contain spaces in practice.
        val parts = rendered.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            dlog(ctx, "buildSocksArgs: override parsed to empty — using default")
            return defaultSocksArgsTemplate(sni)
                .replace("{config}", configPath)
                .replace("{sni}", sni)
                .split(Regex("\\s+")).filter { it.isNotEmpty() }
        }
        return parts
    }

    /** Validates and saves the user override string. Empty/blank clears
     *  the override so the default template takes over again. Returns
     *  true on success.
     *
     *  Input is normalized: all whitespace runs (including newlines) are
     *  collapsed to a single space. This prevents the "second line"
     *  footgun where a user types a revised command on a new line and
     *  the two lines get concatenated into one nonsensical argv with
     *  duplicated `-b/-p/-c` flags — libusque silently honors the first
     *  set and drops everything after (e.g. a trailing `--ipv6`).
     *
     *  The override must contain exactly one `socks` subcommand token
     *  and must include the `{config}` placeholder.
     */
    fun writeSocksArgs(ctx: Context, text: String): Boolean {
        val ps = try {
            org.koin.java.KoinJavaComponent
                .get<PersistentState>(PersistentState::class.java)
        } catch (t: Throwable) {
            dlog(ctx, "writeSocksArgs: PersistentState lookup failed: ${t.message}")
            return false
        }
        // Collapse ALL whitespace (spaces, tabs, newlines) into single spaces
        // so multi-line pasted input becomes a single well-formed argv.
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) {
            ps.warpUsqueArgs = ""
            dlog(ctx, "writeSocksArgs: cleared override (default will be used)")
            return true
        }
        // Must contain {config} so the config.json path always reaches usque.
        if (!normalized.contains("{config}")) {
            dlog(ctx, "writeSocksArgs: refused — missing {config} placeholder")
            return false
        }
        val parts = normalized.split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            dlog(ctx, "writeSocksArgs: refused — no argument tokens")
            return false
        }
        // Exactly one subcommand. Multiple `socks` tokens means the user
        // pasted two command lines; reject rather than silently truncate.
        val socksCount = parts.count { it == "socks" }
        if (socksCount != 1) {
            dlog(ctx, "writeSocksArgs: refused — expected exactly one `socks` subcommand, found $socksCount")
            return false
        }
        if (parts.first() != "socks") {
            dlog(ctx, "writeSocksArgs: refused — first token must be `socks`")
            return false
        }
        ps.warpUsqueArgs = normalized
        dlog(ctx, "writeSocksArgs: saved override (${normalized.length} chars)")
        return true
    }

    /** Renders the fully-substituted argv string that will be handed to
     *  libusque.so on next start. Used by the settings UI to show the
     *  advanced user the *effective* command line (with {config} and
     *  {sni} already resolved) so they can verify their edits landed. */
    fun effectiveSocksArgsForDisplay(ctx: Context): String {
        val ps = try {
            org.koin.java.KoinJavaComponent
                .get<PersistentState>(PersistentState::class.java)
        } catch (_: Throwable) { null }
        val sni = ps?.warpSpoofedSni?.trim().orEmpty()
        val override = ps?.warpUsqueArgs?.trim().orEmpty()
        val template = if (override.isNotEmpty()) override
                       else defaultSocksArgsTemplate(sni)
        val configPath = File(ctx.filesDir, "config.json").absolutePath
        return template
            .replace("{config}", configPath)
            .replace("{sni}", sni)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun getBinary(ctx: Context): File {
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val bin = File(nativeDir, BINARY_NAME)
        dlog(ctx, "getBinary: path=${bin.absolutePath} exists=${bin.exists()} canExec=${bin.canExecute()} size=${bin.length()}")
        return bin
    }

    fun isRegistered(ctx: Context): Boolean {
        val f = File(ctx.filesDir, "config.json")
        Log.d("WARP_DEBUG", "isRegistered: path=${f.absolutePath} exists=${f.exists()} size=${f.length()}")
        return f.exists() && f.length() > 0L
    }

    /**
     * Returns the raw text of the WARP config.json (empty string if it does
     * not exist). Used by the Proxy settings UI to let advanced users edit
     * the tunnel configuration produced by `usque register`.
     */
    fun readConfig(ctx: Context): String {
        return try {
            val f = File(ctx.filesDir, "config.json")
            if (f.exists()) f.readText() else ""
        } catch (e: Exception) {
            Logger.e(Logger.LOG_TAG_PROXY, "readConfig error: ${e.message}", e)
            ""
        }
    }

    /**
     * Atomically overwrites config.json with [text]. Validates that [text] is
     * well-formed JSON before touching the on-disk file so a bad paste cannot
     * corrupt the tunnel state. Returns true on success.
     */
    fun writeConfig(ctx: Context, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            dlog(ctx, "writeConfig: refused empty payload")
            return false
        }
        // Lightweight JSON sanity check — a real parse would pull in a
        // dependency for no gain; usque itself will reject truly broken files.
        val looksJson = (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
        if (!looksJson) {
            dlog(ctx, "writeConfig: refused non-JSON payload")
            return false
        }
        return try {
            val target = File(ctx.filesDir, "config.json")
            val tmp = File(ctx.filesDir, "config.json.tmp")
            tmp.writeText(trimmed)
            if (!tmp.renameTo(target)) {
                // renameTo can fail across some FS states; fall back to copy.
                target.writeText(trimmed)
                tmp.delete()
            }
            dlog(ctx, "writeConfig: wrote ${target.length()} bytes")
            true
        } catch (e: Exception) {
            Logger.e(Logger.LOG_TAG_PROXY, "writeConfig error: ${e.message}", e)
            dlog(ctx, "writeConfig EXCEPTION ${e.message}")
            false
        }
    }

    suspend fun registerWithWarp(context: Context): Boolean = withContext(Dispatchers.IO) {
        // NOTE: do NOT call clearDebugLog here — logs must persist across register→start sequence
        dlog(context, "registerWithWarp: >>>ENTRY<<<")
        try {
            val bin = getBinary(context)

            if (!bin.exists()) {
                dlog(context, "BINARY NOT FOUND — put libusque.so in jniLibs/arm64-v8a/")
                return@withContext false
            }
            if (!bin.canExecute()) {
                dlog(context, "BINARY NOT EXECUTABLE — W^X policy?")
                return@withContext false
            }

            val configFile = File(context.filesDir, "config.json")
            if (configFile.exists()) {
                configFile.delete()
                dlog(context, "deleted old config.json")
            }

            // --accept-tos skips the stdin TOS prompt entirely
            val cmd = listOf(bin.absolutePath, "register", "--accept-tos", "-c", configFile.absolutePath)
            dlog(context, "cmd=${cmd.joinToString(" ")}")

            val pb = ProcessBuilder(cmd).redirectErrorStream(false)
            // Go 1.24+ uses vDSO __kernel_getrandom which Android's seccomp filter blocks (SIGSYS/exit 159).
            // Disabling vgetrandom forces the Go runtime to use the getrandom syscall instead.
            pb.environment()["GODEBUG"] = "vgetrandom=off"
            val proc = pb.start()

            // read stdout and stderr concurrently to avoid deadlock
            val stdoutWriter = StringWriter()
            val stderrWriter = StringWriter()

            val stdoutThread = Thread {
                try { stdoutWriter.write(proc.inputStream.bufferedReader().readText()) } catch (_: Exception) {}
            }.also { it.start() }

            val stderrThread = Thread {
                try { stderrWriter.write(proc.errorStream.bufferedReader().readText()) } catch (_: Exception) {}
            }.also { it.start() }

            val exit = proc.waitFor()
            stdoutThread.join(3000)
            stderrThread.join(3000)

            dlog(context, "exit=$exit")
            dlog(context, "stdout=${stdoutWriter}")
            dlog(context, "stderr=${stderrWriter}")
            dlog(context, "configExists=${configFile.exists()} size=${configFile.length()}")

            val ok = exit == 0 && configFile.exists() && configFile.length() > 0L
            dlog(context, "result=$ok")
            ok

        } catch (e: Exception) {
            dlog(context, "EXCEPTION ${e.message}\n${e.stackTraceToString()}")
            Logger.e(Logger.LOG_TAG_PROXY, "registerWithWarp exception", e)
            false
        }
    }

    suspend fun startSocksProxy(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        // NOTE: do NOT clearDebugLog here — we need the prior register logs for debugging
        //
        // Bug-fix: use withLock so the guard is inside the critical section, eliminating the
        // TOCTOU race where two coroutines both see isStarting=false and both acquire the lock
        // sequentially, causing the second to kill the process the first just started.
        startLock.withLock {
            isStarting = true
            try {
                startSocksProxyLocked(ctx)
            } finally {
                isStarting = false
            }
        }
    }

    /**
     * Must only be called while [startLock] is held.
     *
     * Fixes four bugs that caused an infinite restart storm and a phantom "STOPPED" UI state:
     *
     * 1. TOCTOU race — guard is now inside the lock (see [startSocksProxy]).
     * 2. probePort hits the dying old process — we now wait for port release before spawning.
     * 3. Death watcher fires when our own process couldn't bind — watcher is only registered
     *    when proc.isAlive is confirmed true after probePort, not just when the port answers.
     * 4. Orphan-process reattach — if the port is alive but our process reference is gone
     *    (e.g. VPN killed mid-session), we reattach instead of spawning a new (doomed) process.
     */
    private fun startSocksProxyLocked(ctx: Context): Boolean {
        dlog(ctx, "startSocksProxy: >>>ENTRY<<<")

        // ── Fast path: already healthy ─────────────────────────────────────────────────────────
        // If our process is alive AND the port is responding, there is nothing to do.
        // Returning true here prevents the needless kill→respawn cycle that triggered the storm.
        val existingProc = process
        if (existingProc != null && existingProc.isAlive && isPortAlive()) {
            dlog(ctx, "startSocksProxy: already running and healthy — skipping restart")
            portConfirmedAlive = true
            return true
        }

        // ── Stop old process and wait for port release ─────────────────────────────────────────
        // Bug fix 2 & 3: after destroy() the OS process may keep the port bound for tens of ms.
        // Spawning a new process before the port is free causes an immediate bind-failure exit,
        // which the death watcher misreads as an unexpected tunnel death and loops forever.
        if (process != null) {
            stopSocksProxy()
            val released = waitForPortRelease(ctx, SOCKS_PORT, timeoutMs = 2000)
            dlog(ctx, "startSocksProxy: port released=$released after stopSocksProxy")
            // If the port is still held after 2 s the new spawn will also fail to bind.
            // Log it and proceed anyway — at least we tried.
        }

        // ── Orphan reattach ────────────────────────────────────────────────────────────────────
        // The port can be alive with process==null when the VPN was killed mid-session and the
        // usque child process survived (different PID namespace). Spawning a duplicate process
        // would fail to bind. Instead, trust the port probe and reattach in-place; the watchdog
        // will detect real tunnel degradation within 20 s.
        if (isPortAlive()) {
            dlog(ctx, "startSocksProxy: port alive but no process ref — reattaching to orphan usque")
            portConfirmedAlive = true
            return true
        }
        // Belt-and-braces: the port is free, so any previously reattached orphan is gone.
        portConfirmedAlive = false

        return try {
            val bin = getBinary(ctx)
            if (!bin.exists() || !bin.canExecute()) {
                dlog(ctx, "startSocksProxy: binary not ready exists=${bin.exists()} canExec=${bin.canExecute()}")
                return false
            }

            val configFile = File(ctx.filesDir, "config.json")
            dlog(ctx, "startSocksProxy: configExists=${configFile.exists()} size=${configFile.length()}")

            // Build argument list. If the user saved a custom override string
            // (Proxy settings > WARP > "libusque.so arguments"), it is used
            // verbatim after {config}/{sni} substitution; otherwise fall back
            // to the default template derived from warpSpoofedSni.
            val args = buildSocksArgs(ctx, configFile.absolutePath)
            val cmd = mutableListOf(bin.absolutePath)
            cmd += args
            dlog(ctx, "startSocksProxy: cmd=${cmd.joinToString(" ")}")

            val pb = ProcessBuilder(cmd).redirectErrorStream(false)
            pb.environment()["GODEBUG"] = "vgetrandom=off"
            val proc = pb.start()
            process = proc

            // Drain stdout and stderr in background threads so the process doesn't block on a
            // full pipe buffer. Capture output for diagnostics if the process exits early.
            val outputWriter = StringWriter()
            val errorWriter = StringWriter()
            val outThread = Thread {
                try { outputWriter.write(proc.inputStream.bufferedReader().readText()) } catch (_: Exception) {}
            }.also { it.isDaemon = true; it.start() }
            val errThread = Thread {
                try { errorWriter.write(proc.errorStream.bufferedReader().readText()) } catch (_: Exception) {}
            }.also { it.isDaemon = true; it.start() }

            // Wait for the port to actually be listening (up to 5s) instead of a blind sleep.
            // This prevents a race on slow devices where 1500ms wasn't enough.
            val portReady = probePort(ctx, SOCKS_PORT, timeoutMs = 5000)
            val procAlive = proc.isAlive
            dlog(ctx, "startSocksProxy: proc.isAlive=$procAlive portReady=$portReady")

            // Bug fix 3: only register the death watcher when our process is *still* alive after
            // probePort returns. If proc died while we were probing (e.g. couldn't bind because
            // the orphan-reattach path above was skipped on a borderline race), treat it as a
            // failure rather than setting portConfirmedAlive and triggering an instant callback.
            if (portReady && procAlive) {
                portConfirmedAlive = true
                // Immediate death detection: daemon thread blocks on waitFor() and fires
                // deathCallback the instant the usque child process exits unexpectedly.
                // This collapses the recovery gap from ≤20 s (watchdog tick) to <1 s.
                val capturedProc = proc
                Thread {
                    try {
                        capturedProc.waitFor()
                        // Only fire if this is still the active process and the port was confirmed
                        // alive (i.e., this is an unexpected death, not an intentional stopSocksProxy).
                        if (process === capturedProc && portConfirmedAlive) {
                            portConfirmedAlive = false
                            Log.w("WARP_DEBUG", "usque process died unexpectedly — firing restart callback")
                            deathCallback?.invoke()
                        }
                    } catch (_: Exception) {}
                }.apply { isDaemon = true; name = "usque-death-watcher" }.start()
            } else {
                portConfirmedAlive = false
                // Process already exited — collect its output before reporting failure.
                outThread.join(2000)
                errThread.join(2000)
                val exit = try { proc.exitValue() } catch (_: Exception) { -1 }
                dlog(ctx, "startSocksProxy: exit=$exit")
                dlog(ctx, "startSocksProxy: stdout=${outputWriter}")
                dlog(ctx, "startSocksProxy: stderr=${errorWriter}")
                process = null
            }

            portReady && procAlive

        } catch (e: Exception) {
            dlog(ctx, "startSocksProxy: EXCEPTION ${e.message}\n${e.stackTraceToString()}")
            Logger.e(Logger.LOG_TAG_PROXY, "startSocksProxy exception", e)
            false
        }
    }

    /** Wait until port [port] stops accepting connections or [timeoutMs] elapses. */
    private fun waitForPortRelease(ctx: Context, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isPortAlive()) return true
            Thread.sleep(100)
        }
        dlog(ctx, "waitForPortRelease: port $port still bound after ${timeoutMs}ms")
        return false
    }

    /** Poll 127.0.0.1:port until it accepts a connection or [timeoutMs] elapses. */
    private fun probePort(ctx: Context, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", port), 300)
                    dlog(ctx, "probePort: port $port ready after ${attempt} attempts")
                    return true
                }
            } catch (_: Exception) {}
            Thread.sleep(200)
        }
        dlog(ctx, "probePort: port $port NOT ready after ${timeoutMs}ms / ${attempt} attempts")
        return false
    }

    /** Grace period given to usque to exit on SIGTERM before we SIGKILL it. */
    private const val STOP_GRACE_MS = 800L

    /**
     * Stop usque and make sure the process is really gone.
     *
     * [Process.destroy] only sends SIGTERM. If the child ignores it (or is blocked in a
     * QUIC read) it keeps :40000 bound, the next spawn dies on bind, and the death watcher
     * misreads that as an unexpected death — the restart storm we keep chasing. Escalate to
     * destroyForcibly() after a short grace period so the port is guaranteed free.
     */
    fun stopSocksProxy() {
        val p = process
        Log.d("WARP_DEBUG", "stopSocksProxy: isAlive=${p?.isAlive}")
        portConfirmedAlive = false
        process = null
        if (p == null) return
        try {
            p.destroy()
            if (!p.waitFor(STOP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                Log.w("WARP_DEBUG", "stopSocksProxy: SIGTERM ignored after ${STOP_GRACE_MS}ms — SIGKILL")
                p.destroyForcibly()
                p.waitFor(STOP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } catch (e: Exception) {
            Log.w("WARP_DEBUG", "stopSocksProxy: kill failed ${e.message}")
            try { p.destroyForcibly() } catch (_: Exception) {}
        }
    }

    @Volatile private var portConfirmedAlive = false

    /**
     * True when usque is known to be alive.
     * Auto-clears the stale portConfirmedAlive flag when the process has exited but the flag
     * was not cleared by a death-watcher yet (e.g., flag set before watcher thread started).
     */
    fun isRunning(): Boolean {
        if (isStarting) return true
        val p = process
        if (p != null) {
            if (p.isAlive) return true
            // Process died without the death-watcher clearing the flag (e.g., watcher thread
            // lost a race). Clear it now so callers get an accurate false instead of stale true.
            portConfirmedAlive = false
            return false
        }
        // No process reference: we are either reattached to an orphan usque or the flag is
        // stale. Never report "running" on the flag alone — a stale `true` makes every
        // `if (!isRunning()) restart()` call-site skip recovery, which is how a dead proxy
        // survives indefinitely while every DNS query fails with "connection refused".
        if (!portConfirmedAlive) return false
        val alive = isPortAlive()
        if (!alive) portConfirmedAlive = false
        return alive
    }

    /** Quick check (300ms) whether the SOCKS port is already accepting connections. */
    fun isPortAlive(): Boolean {
        return try {
            android.net.TrafficStats.setThreadStatsTag(android.os.Process.myTid())
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(SOCKS_HOST, SOCKS_PORT), 300)
                    true
                }
            } finally {
                android.net.TrafficStats.clearThreadStatsTag()
            }
        } catch (_: Exception) { false }
    }

    /**
     * Full SOCKS5 handshake probe — confirms the WARP tunnel is alive end-to-end,
     * not just that the port is open. A zombie process can hold the port open while
     * the underlying QUIC/WARP connection to Cloudflare is dead (e.g. after WiFi→LTE).
     */
    suspend fun probeUsqueLiveness(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // Sprint 17 fix: a simple SOCKS5 handshake to 127.0.0.1:40000 is loopback-only —
        // it succeeds even when the WARP QUIC tunnel to Cloudflare is completely dead.
        // We must send a real SOCKS5 CONNECT through the proxy to an external IP so that
        // the request actually travels through libusque.so → Cloudflare WARP → internet.
        // 1.1.1.1:80 (Cloudflare DNS-over-HTTP) is ideal: same operator as WARP, always up.
        // REP byte 0x00 = "succeeded" → upstream alive. Anything else or exception → dead.
        try {
            android.net.TrafficStats.setThreadStatsTag(android.os.Process.myTid())
            try { java.net.Socket().use { s ->
                s.soTimeout = 5000
                s.connect(java.net.InetSocketAddress(SOCKS_HOST, SOCKS_PORT), 2000)
                val out = s.getOutputStream()
                val inp = s.getInputStream()

                // SOCKS5 greeting: version=5, nmethods=1, method=0x00 (no auth)
                out.write(byteArrayOf(5, 1, 0))
                val greet = ByteArray(2)
                if (inp.read(greet) != 2 || greet[0] != 5.toByte() || greet[1] == 0xFF.toByte()) {
                    return@withContext false
                }

                // SOCKS5 CONNECT to 1.1.1.1:80
                // VER=5, CMD=CONNECT(1), RSV=0, ATYP=IPv4(1), DST.ADDR=1.1.1.1, DST.PORT=80
                out.write(byteArrayOf(5, 1, 0, 1, 1, 1, 1, 1, 0, 80))
                val rep = ByteArray(10) // minimal reply: VER RSV REP RSV ATYP ADDR(4) PORT(2)
                val n = inp.read(rep)
                // rep[1] == 0x00 means "succeeded" — upstream reached 1.1.1.1
                n >= 2 && rep[0] == 5.toByte() && rep[1] == 0x00.toByte()
            }
        } finally { android.net.TrafficStats.clearThreadStatsTag() }
        } catch (_: Exception) { false }
    }


    /**
     * Called from onResume when usqueEnabled=true but process ref is lost.
     * Sets portConfirmedAlive=true so isRunning() returns true and the UI shows ON.
     * The flag is cleared whenever stopSocksProxy is called or a new startSocksProxy runs.
     */
    fun reattachIfPortAlive(ctx: Context): Boolean {
        val alive = isPortAlive()
        portConfirmedAlive = alive
        dlog(ctx, "reattachIfPortAlive: portAlive=$alive (isRunning=$alive)")
        return alive
    }
}
