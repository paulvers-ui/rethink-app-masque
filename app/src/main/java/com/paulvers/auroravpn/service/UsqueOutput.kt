package com.paulvers.auroravpn.service

/**
 * The last lines a libusque.so process printed, for two things the managers need
 * after it exits: a reason to show the user ("Failed to start WARP: ..."), and
 * whether the binary rejected a flag it does not know.
 *
 * The resilience flags (--idle-timeout, --stall-timeout, ...) are newer than some
 * libusque.so builds. One that does not know a flag exits at once with
 * `unknown flag: --stall-timeout`; rather than leave WARP down for good, the
 * managers start it again without those flags ([withoutNewerFlags]).
 *
 * A refused flag makes cobra print "Error: unknown flag: --x" and then ~45 lines of usage,
 * more than [capacity]. The error line used to fall out of the buffer: [rejectedAFlag] then
 * said no, and [lastError] picked a usage line that happens to contain "failed" (the
 * --http2-fallback-after help), so the app blamed the wrong flag. The refused flag and
 * cobra's error line are now kept aside as they arrive, and usage lines never count as errors.
 */
class UsqueOutput(private val capacity: Int = DEFAULT_CAPACITY) {
    private val lines = ArrayDeque<String>()
    private var rejected: String? = null
    private var cliError: String? = null

    @Synchronized
    fun add(line: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(line)
        val text = line.trim()
        if (rejected == null) rejected = refusedFlag(text)
        if (cliError == null && text.startsWith(CLI_ERROR_PREFIX)) cliError = text
    }

    @Synchronized
    fun clear() {
        lines.clear()
        rejected = null
        cliError = null
    }

    /** True when the binary refused one of its arguments. */
    @Synchronized
    fun rejectedAFlag(): Boolean = rejected != null

    /** The flag the binary refused ("--always-reconnect", "-x"), or null. */
    @Synchronized
    fun rejectedFlag(): String? = rejected

    /** cobra's own "Error: ..." line if it printed one, else the most recent line that reads like an error, or "". */
    @Synchronized
    fun lastError(): String =
        (cliError ?: lines.lastOrNull { line -> isErrorLine(line) })
            ?.trim()
            ?.take(MAX_REASON_LEN)
            .orEmpty()

    companion object {
        private const val DEFAULT_CAPACITY = 40
        private const val MAX_REASON_LEN = 160
        private const val UNKNOWN_FLAG = "unknown flag"
        private const val CLI_ERROR_PREFIX = "Error:"
        private val ERROR_MARKERS = listOf(
            UNKNOWN_FLAG, "error", "failed", "panic", "fatal", "not loaded", "denied", "refused",
        )
        // pflag: "unknown flag: --x" / "unknown shorthand flag: 'x' in -xyz".
        private val UNKNOWN_LONG = Regex("""unknown flag: (--?[^\s=]+)""")
        private val UNKNOWN_SHORT = Regex("""unknown shorthand flag: '(.)'""")
        // A usage line listing a flag ("  -b, --bind string ...", "      --doh ..."): its help
        // text may say "failed", but it is never the error.
        private val USAGE_FLAG_LINE = Regex("""^\s+-{1,2}[A-Za-z0-9]""")

        private fun refusedFlag(text: String): String? =
            UNKNOWN_LONG.find(text)?.groupValues?.get(1)
                ?: UNKNOWN_SHORT.find(text)?.let { "-" + it.groupValues[1] }

        private fun isErrorLine(line: String): Boolean =
            !USAGE_FLAG_LINE.containsMatchIn(line) && ERROR_MARKERS.any { line.contains(it, ignoreCase = true) }

        // Flags newer than the oldest libusque.so the app may still meet, and
        // whether each takes a separate value.
        private val NEWER_FLAGS = mapOf(
            "--idle-timeout" to true,
            "--stall-timeout" to true,
            "--exit-stall-timeout" to true,
            "--connect-timeout" to true,
            "--probe-addr" to true,
            "--watch-network" to false,
            "--secrets-stdin" to false,
        )

        /**
         * [args] without the flags [names] ("--x" or "-x"), each with its value: the next
         * token unless that looks like a flag (usque takes no positionals after the subcommand).
         */
        fun without(args: List<String>, names: Collection<String>): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                if (UsqueFlags.isFlag(arg) && arg.substringBefore('=') in names) {
                    if ('=' !in arg && i + 1 < args.size && !UsqueFlags.isFlag(args[i + 1])) i++
                } else {
                    out += arg
                }
                i++
            }
            return out
        }

        /** [args] without the flags an older libusque.so does not know. */
        fun withoutNewerFlags(args: List<String>): List<String> {
            val out = mutableListOf<String>()
            var skipValue = false
            for (arg in args) {
                if (skipValue) {
                    skipValue = false
                    continue
                }
                val takesValue = NEWER_FLAGS[arg.substringBefore('=')]
                if (takesValue == null) {
                    out += arg
                } else if (takesValue && '=' !in arg) {
                    skipValue = true
                }
            }
            return out
        }
    }
}
