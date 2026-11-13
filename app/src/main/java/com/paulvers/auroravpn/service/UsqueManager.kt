package com.paulvers.auroravpn.service

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
    // Default SNI in the WARP tunnel's QUIC ClientHello. Not a Cloudflare name: on the
    // networks this app targets, a Cloudflare SNI gets the tunnel throttled to a trickle
    // (handshakes pass, downloads stall) while google.com passes.
    const val DEFAULT_WARP_SNI = "google.com"
    // A socks-capable libusque.so lists these in `<subcommand> --help` (see UsqueFlags.parseHelp).
    private val SOCKS_CORE_FLAGS = listOf("bind", "port", "config")
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
    // usque's own output streams in here too (see pumpOutput), so the file is capped: past
    // DEBUG_LOG_MAX_BYTES the older half is dropped. Synchronized because the stdout/stderr
    // pumps append concurrently with everything else.
    private const val DEBUG_LOG_MAX_BYTES = 2L * 1024 * 1024

    @Synchronized
    private fun dlog(ctx: Context, msg: String) {
        Log.d("WARP_DEBUG", msg)
        try {
            val f = File(ctx.filesDir, "warp_debug.txt")
            if (f.length() > DEBUG_LOG_MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.substring(text.length / 2))
            }
            f.appendText("${System.currentTimeMillis()} $msg\n")
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
    //   {config} → "-": the keys reach usque through stdin (--secrets-stdin,
    //              added at start), never as a file; an old libusque.so gets
    //              the path of a scratch copy wiped right after it starts
    //   {sni}    → current warpSpoofedSni value (may be empty)
    // The default template mirrors the historical hard-coded arg list so
    // existing installs behave identically until the user opts in.
    const val DEFAULT_SOCKS_ARGS_TEMPLATE =
        "socks -b $SOCKS_HOST -p $SOCKS_PORT -c {config}"

    // Fixed 1350-byte QUIC packets, the size Cloudflare's own WARP client uses. With usque's
    // default (start at 1280 and grow by path MTU discovery) a full 1280-byte tunnel packet
    // does not fit until discovery succeeds, and usque drops it without logging: TLS
    // handshakes through WARP stalled about a second after every connect.
    private const val DEFAULT_SOCKS_QUIC_ARGS = "-i 1350"

    // How fast a dead tunnel is rebuilt. usque reconnects at once when the network
    // changes (Wi-Fi <-> mobile data, on by default), and when packets go out with nothing
    // coming back for 2s and a probe through the tunnel gets no answer either; otherwise
    // QUIC drops a silent connection after 25s. -r 1s spaces failed reconnects, and
    // --always-reconnect rebuilds the tunnel right away instead of waiting for traffic.
    // -k 10s keeps mobile NAT bindings open.
    private const val DEFAULT_SOCKS_RESILIENCE_ARGS =
        "-k 10s -r 1s --always-reconnect --idle-timeout 25s --stall-timeout 2s"

    // How usque resolves SOCKS target names: DNS-over-HTTPS to Cloudflare by IP, DNSSEC
    // checked (usque >= v0.1.0). --doh and --dnssec validate are usque's defaults anyway;
    // they are spelled out so the args screen shows them and they can be edited there.
    private const val DEFAULT_SOCKS_DNS_ARGS =
        "--doh --dnssec validate --doh-url https://1.1.1.1/dns-query --doh-url https://1.0.0.1/dns-query"

    /** Returns the default arg string: {sni} appended when SNI is set, then the QUIC, reconnect and DNS flags. */
    fun defaultSocksArgsTemplate(sni: String): String {
        val base = DEFAULT_SOCKS_ARGS_TEMPLATE
        val withSni = if (sni.isNotBlank()) "$base -s {sni}" else base
        return "$withSni $DEFAULT_SOCKS_QUIC_ARGS $DEFAULT_SOCKS_RESILIENCE_ARGS $DEFAULT_SOCKS_DNS_ARGS"
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
        // Must contain {config} so the keys always reach usque.
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
     *  {sni} already resolved) so they can verify their edits landed.
     *  The keys go through stdin, so {config} shows as "-". */
    fun effectiveSocksArgsForDisplay(ctx: Context): String =
        (buildSocksArgs(ctx, KEYS_FROM_STDIN) + SECRETS_STDIN_FLAG).joinToString(" ")

    // ─────────────────────────────────────────────────────────────────────────

    // The WARP identity, kept sealed by KeyVault (never as a plain config.json).
    const val CONFIG_NAME = "config.json"
    // libusque.so reads the keys from stdin (see startSocksProxyLocked) ...
    private const val SECRETS_STDIN_FLAG = "--secrets-stdin"
    // ... and is told so with "-c -".
    private const val KEYS_FROM_STDIN = "-"

    // The installed libusque.so if it is the one this build pinned (UsqueBinary.check), else
    // null with the reason in [output], which lastStartError() shows.
    private fun verifiedBinary(ctx: Context): File? {
        val check = UsqueBinary.check(ctx)
        dlog(ctx, "getBinary: ${check.describe()}")
        if (check is UsqueBinary.Check.Ok) return check.file
        output.clear()
        output.add("error: ${check.reason()}")
        Logger.e(Logger.LOG_TAG_PROXY, "usque: ${check.reason()}")
        return null
    }

    /**
     * [args] (subcommand first) without the flags this libusque.so does not take, so an
     * edit on the args screen it does not know cannot keep WARP down. Unchanged when the
     * binary could not be asked.
     */
    private fun supported(ctx: Context, bin: File, args: List<String>): List<String> {
        val sub = args.firstOrNull { !UsqueFlags.isFlag(it) }
        val flags = sub?.let { UsqueBinary.flags(bin, it, SOCKS_CORE_FLAGS) }
        val result = flags?.supportedOnly(args) ?: return args
        if (result.dropped.isNotEmpty()) {
            val msg = "this libusque.so does not take ${result.dropped.joinToString(" ")} — left out (update libusque.so)"
            dlog(ctx, "startSocksProxy: $msg")
            Logger.w(Logger.LOG_TAG_PROXY, "usque: $msg")
        }
        return result.kept
    }

    fun isRegistered(ctx: Context): Boolean = KeyVault.exists(ctx, CONFIG_NAME)

    /** True when the stored WARP identity can no longer be decrypted (see KeyVault.damaged). */
    fun keysDamaged(ctx: Context): Boolean = KeyVault.damaged(ctx, CONFIG_NAME)

    /**
     * Returns the text of the WARP config.json (empty string if there is none)
     * for the Proxy settings editor, with the private key and token shown as
     * [KeyRedaction.HIDDEN]: advanced users can still edit endpoints and the
     * like, but the keys never appear on screen.
     */
    fun readConfig(ctx: Context): String {
        val data = KeyVault.read(ctx, CONFIG_NAME) ?: return ""
        return try {
            KeyRedaction.hideJson(String(data, Charsets.UTF_8))
        } finally {
            data.fill(0)
        }
    }

    /**
     * Replaces config.json with [text] (fields still reading [KeyRedaction.HIDDEN]
     * keep their stored values). Validates that [text] is well-formed JSON before
     * touching the stored identity so a bad paste cannot corrupt the tunnel state.
     * Returns true on success.
     */
    fun writeConfig(ctx: Context, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            dlog(ctx, "writeConfig: refused empty payload")
            return false
        }
        val stored = KeyVault.read(ctx, CONFIG_NAME)
        val restored = KeyRedaction.restoreJson(trimmed, stored?.toString(Charsets.UTF_8))
        stored?.fill(0)
        if (restored == null) {
            dlog(ctx, "writeConfig: refused — a hidden key has no stored value to keep")
            return false
        }
        // Must be the JSON object usque writes, with its key, or the next start fails.
        val parsed = try { org.json.JSONObject(restored) } catch (_: org.json.JSONException) { null }
        if (parsed == null || parsed.optString("private_key").isBlank()) {
            dlog(ctx, "writeConfig: refused — not a JSON object with a private_key")
            return false
        }
        val ok = KeyVault.write(ctx, CONFIG_NAME, restored.toByteArray(Charsets.UTF_8))
        dlog(ctx, "writeConfig: saved=$ok (${restored.length} chars)")
        return ok
    }

    suspend fun registerWithWarp(context: Context): Boolean = withContext(Dispatchers.IO) {
        // NOTE: do NOT call clearDebugLog here — logs must persist across register→start sequence
        dlog(context, "registerWithWarp: >>>ENTRY<<<")
        // usque writes the new identity here; it is sealed into the vault and wiped right
        // after. The current identity stays until the new one exists, so a failed
        // re-registration (Cloudflare rate limit, no network) no longer leaves WARP without keys.
        val scratch = KeyVault.scratchFile(context, CONFIG_NAME)
        try {
            val bin = verifiedBinary(context) ?: return@withContext false

            // --accept-tos skips the stdin TOS prompt entirely
            val cmd = listOf(bin.absolutePath, "register", "--accept-tos", "-c", scratch.absolutePath)
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
            dlog(context, "configWritten=${scratch.exists()} size=${scratch.length()}")

            val ok = exit == 0 && scratch.length() > 0L && KeyVault.adopt(context, scratch, CONFIG_NAME)
            dlog(context, "result=$ok")
            ok

        } catch (e: Exception) {
            dlog(context, "EXCEPTION ${e.message}\n${e.stackTraceToString()}")
            Logger.e(Logger.LOG_TAG_PROXY, "registerWithWarp exception", e)
            false
        } finally {
            KeyVault.wipe(scratch)
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
     * Stops usque and starts it again, even when the process is alive and its port answers.
     * [startSocksProxy] keeps a live process on purpose, which made every "tunnel is dead,
     * restart it" call a no-op for a process that was up but no longer carrying traffic.
     */
    suspend fun restartSocksProxy(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        startLock.withLock {
            isStarting = true
            try {
                dlog(ctx, "restartSocksProxy: hard restart")
                stopSocksProxy()
                waitForPortRelease(ctx, SOCKS_PORT, timeoutMs = 2000)
                startSocksProxyLocked(ctx)
            } finally {
                isStarting = false
            }
        }
    }

    /** Why the last start failed (usque's last error line), or "" when it did not say. */
    fun lastStartError(): String = output.lastError()

    // usque's recent output, see UsqueOutput.
    private val output = UsqueOutput()

    // Set once this libusque.so rejected the newer flags; later starts leave them out.
    @Volatile private var legacyBinary = false

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
            val bin = verifiedBinary(ctx) ?: return false

            val keys = KeyVault.read(ctx, CONFIG_NAME)
            if (keys == null) {
                output.clear()
                output.add("error: WARP keys missing or unreadable, register again")
                dlog(ctx, "startSocksProxy: no readable WARP keys")
                return false
            }
            try {
                if (!legacyBinary) {
                    // Build argument list. If the user saved a custom override string
                    // (Proxy settings > WARP > "libusque.so arguments"), it is used
                    // verbatim after {config}/{sni} substitution; otherwise fall back
                    // to the default template derived from warpSpoofedSni.
                    val args = supported(ctx, bin, buildSocksArgs(ctx, KEYS_FROM_STDIN) + SECRETS_STDIN_FLAG)
                    val bundle = keyBundle(keys)
                    val started = try { spawnSocksProxy(ctx, bin, args, bundle) } finally { bundle.fill(0) }
                    if (started || !output.rejectedAFlag()) return started
                    // An older libusque.so that does not know the newer flags: run it the old
                    // way rather than leave WARP down.
                    legacyBinary = true
                    dlog(ctx, "startSocksProxy: libusque.so rejected a flag — retrying the old way")
                }
                startLegacy(ctx, bin, keys)
            } finally {
                keys.fill(0)
            }
        } catch (e: Exception) {
            dlog(ctx, "startSocksProxy: EXCEPTION ${e.message}\n${e.stackTraceToString()}")
            Logger.e(Logger.LOG_TAG_PROXY, "startSocksProxy exception", e)
            false
        }
    }

    /**
     * For a libusque.so without --secrets-stdin or the reconnect flags: the keys go in a
     * private scratch file that is wiped as soon as usque has read them (it reads its config
     * once, at start, before the SOCKS port opens).
     */
    private fun startLegacy(ctx: Context, bin: File, keys: ByteArray): Boolean {
        val scratch = KeyVault.scratchFile(ctx, CONFIG_NAME)
        return try {
            scratch.writeBytes(keys)
            val args = UsqueOutput.withoutNewerFlags(buildSocksArgs(ctx, scratch.absolutePath))
            spawnSocksProxy(ctx, bin, args, null)
        } finally {
            KeyVault.wipe(scratch)
        }
    }

    /**
     * Spawns usque with [args], writes [stdin] (the key bundle, or nothing) into its stdin,
     * and waits for its SOCKS port; see [startSocksProxyLocked].
     */
    private fun spawnSocksProxy(ctx: Context, bin: File, args: List<String>, stdin: ByteArray?): Boolean {
        val cmd = mutableListOf(bin.absolutePath)
        cmd += args
        dlog(ctx, "startSocksProxy: cmd=${cmd.joinToString(" ")}")

        output.clear()
        val pb = ProcessBuilder(cmd).redirectErrorStream(false)
        pb.environment()["GODEBUG"] = "vgetrandom=off"
        val proc = pb.start()
        process = proc
        writeStdin(proc, stdin)

        // Drain stdout and stderr in background threads so the process doesn't block on a
        // full pipe buffer, logging each line as it arrives (see pumpOutput).
        val outThread = pumpOutput(ctx, proc.inputStream, "stdout")
        val errThread = pumpOutput(ctx, proc.errorStream, "stderr")

        // Wait for the port to actually be listening (up to 5s) instead of a blind sleep.
        // This prevents a race on slow devices where 1500ms wasn't enough.
        val portReady = probePort(ctx, SOCKS_PORT, timeoutMs = 5000, proc = proc)
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
            return true
        }

        portConfirmedAlive = false
        process = null
        // The port never came up but the process may still be running: kill it, or it binds
        // :40000 later as an orphan that stopSocksProxy() can no longer reach.
        if (proc.isAlive) proc.destroyForcibly()
        proc.waitFor(STOP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        // Let the pumps flush its last lines before reporting.
        outThread.join(2000)
        errThread.join(2000)
        val exit = try { proc.exitValue() } catch (_: Exception) { -1 }
        dlog(ctx, "startSocksProxy: exit=$exit reason=${output.lastError()}")
        return false
    }

    /**
     * Streams one of usque's output pipes into warp_debug.txt and the app log (Logs > App logs),
     * line by line, for as long as the process runs. The output used to be buffered whole in
     * memory for the life of the process and written out only if usque died during startup, so
     * a tunnel that never came up -- process alive, port open, nothing getting through -- left
     * no trace at all.
     */
    private fun pumpOutput(ctx: Context, stream: java.io.InputStream, label: String): Thread =
        Thread {
            try {
                stream.bufferedReader().forEachLine { line ->
                    output.add(line)
                    dlog(ctx, "usque $label: $line")
                    Logger.i(Logger.LOG_TAG_PROXY, "usque: $line")
                }
            } catch (_: Exception) {}
        }.apply {
            isDaemon = true
            name = "usque-$label"
            start()
        }

    /** {"config": <config.json>}, built from bytes so the keys are not copied into Strings. */
    private fun keyBundle(config: ByteArray): ByteArray =
        "{\"config\":".toByteArray(Charsets.UTF_8) + config + "}".toByteArray(Charsets.UTF_8)

    /**
     * Hands [data] to the process through its stdin pipe and closes it. A process that
     * already exited (it rejected a flag) breaks the pipe; that is reported by its output.
     */
    fun writeStdin(proc: Process, data: ByteArray?) {
        try {
            proc.outputStream.use { out -> if (data != null) out.write(data) }
        } catch (_: java.io.IOException) {}
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
    private fun probePort(ctx: Context, port: Int, timeoutMs: Long, proc: Process? = null): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        // A refused flag ends usque in milliseconds: stop waiting for its port then.
        val exited = { proc != null && !proc.isAlive }
        while (System.currentTimeMillis() < deadline && !exited()) {
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
        if (exited()) {
            dlog(ctx, "probePort: port $port — process exited after $attempt attempts")
        } else {
            dlog(ctx, "probePort: port $port NOT ready after ${timeoutMs}ms / ${attempt} attempts")
        }
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
