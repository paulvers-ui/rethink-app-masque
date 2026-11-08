package com.paulvers.auroravpn.service

import android.content.Context
import com.paulvers.auroravpn.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The libusque.so this app was built with, checked before every use, and what it can do.
 *
 * Every libusque.so built since Oct 9 is exactly 14,287,144 bytes, so the size never said
 * which usque runs (a phone ran a pre-v0.0.6 build while the repo had v0.0.8). [check]
 * compares the installed file's SHA-256 with BuildConfig.USQUE_SHA256, the pin CI writes
 * next to the .so (libusque.so.working.sha256). The verifyLibusque Gradle task refuses to
 * build with a .so that does not match it, so on a phone a mismatch means a broken install.
 *
 * [flags] asks the binary which flags a subcommand takes (`chain --help`), so a flag it does
 * not know is left out instead of making usque exit before its port opens (see [UsqueFlags]).
 */
@Suppress("TooGenericExceptionCaught")
object UsqueBinary {
    const val NAME = "libusque.so"

    private const val HASH_BUFFER = 1 shl 16
    private const val HELP_TIMEOUT_MS = 5_000L
    private const val SHORT_HASH = 12

    sealed interface Check {
        val file: File

        data class Ok(override val file: File, val sha256: String) : Check
        data class Missing(override val file: File) : Check
        data class NotExecutable(override val file: File) : Check
        data class Mismatch(override val file: File, val expected: String, val actual: String) : Check

        /** One line for the debug logs. */
        fun describe(): String {
            val head = "path=${file.absolutePath} size=${file.length()}"
            return when (this) {
                is Ok -> "$head sha256=${sha256.take(SHORT_HASH)} pin=" +
                    (if (BuildConfig.USQUE_SHA256.isEmpty()) "none" else "ok")
                is Missing -> "$head MISSING"
                is NotExecutable -> "$head NOT EXECUTABLE"
                is Mismatch -> "$head sha256=${actual.take(SHORT_HASH)} pin=MISMATCH(expected ${expected.take(SHORT_HASH)})"
            }
        }

        /** Why it cannot be used, for the user ("" when it can). */
        fun reason(): String = when (this) {
            is Ok -> ""
            is Missing -> "$NAME is missing from the installed app, reinstall it"
            is NotExecutable -> "$NAME is not executable, reinstall the app"
            is Mismatch -> "$NAME is not the one this app was built with " +
                "(sha256 ${actual.take(SHORT_HASH)}, expected ${expected.take(SHORT_HASH)}), reinstall the app"
        }
    }

    // One hash per installed file: the path changes with every install, size and mtime with any rewrite.
    @Volatile private var hashed: Pair<String, String>? = null
    private val flagCache = ConcurrentHashMap<String, UsqueFlags>()

    fun file(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, NAME)

    fun check(ctx: Context): Check {
        val f = file(ctx)
        // Hashed only when there is an executable file; null also when it cannot be read.
        val actual = if (f.isFile && f.canExecute()) sha256(f) else null
        val expected = BuildConfig.USQUE_SHA256.lowercase()
        return when {
            f.isFile && !f.canExecute() -> Check.NotExecutable(f)
            actual == null -> Check.Missing(f)
            expected.isEmpty() || expected == actual -> Check.Ok(f, actual)
            else -> Check.Mismatch(f, expected, actual)
        }
    }

    private fun identity(f: File) = "${f.absolutePath}|${f.length()}|${f.lastModified()}"

    private fun sha256(f: File): String? {
        val id = identity(f)
        hashed?.let { (key, hash) -> if (key == id) return hash }
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(HASH_BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }.also { hashed = id to it }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The flags `[bin] [subcommand] --help` lists, or null when the binary could not be
     * asked or its help did not list all of [mustHave]; callers then pass their args as-is.
     * Asked once per installed binary and subcommand.
     */
    fun flags(bin: File, subcommand: String, mustHave: Collection<String>): UsqueFlags? {
        val key = "${identity(bin)}|$subcommand"
        return flagCache[key]
            ?: help(bin, subcommand)?.let { UsqueFlags.parseHelp(it, mustHave) }?.also { flagCache[key] = it }
    }

    private fun help(bin: File, subcommand: String): String? = try {
        val pb = ProcessBuilder(bin.absolutePath, subcommand, "--help").redirectErrorStream(true)
        // Same as every other start: Android's seccomp kills Go's vDSO getrandom (SIGSYS).
        pb.environment()["GODEBUG"] = "vgetrandom=off"
        val proc = pb.start()
        proc.outputStream.close()
        // The help is a few KB, well inside the pipe buffer, so waiting first cannot block it.
        if (!proc.waitFor(HELP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            null
        } else {
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            if (proc.exitValue() == 0) text else null
        }
    } catch (_: Exception) {
        null
    }
}
