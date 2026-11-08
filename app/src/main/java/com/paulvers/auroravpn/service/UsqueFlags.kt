package com.paulvers.auroravpn.service

/**
 * The flags one libusque.so subcommand takes, read from its own `--help`, and the
 * filter that keeps a command line to those flags.
 *
 * Why: a flag the binary does not know makes usque exit with "unknown flag" before its
 * SOCKS port opens. Saved in a hop's args (or in an app newer than its libusque.so) that
 * kept the chain down on every start and watchdog restart, until a reinstall wiped the
 * saved args. Filtered, such a flag is left out and logged, and the tunnel still comes up.
 *
 * Pure Kotlin on purpose (no Android types), so it is unit tested on the JVM.
 */
class UsqueFlags private constructor(
    /** Long name (no dashes) -> whether the flag takes a value. */
    private val takesValue: Map<String, Boolean>,
    /** Shorthand letter -> long name. */
    private val shorthands: Map<Char, String>,
) {
    operator fun contains(longName: String): Boolean = longName in takesValue

    val size: Int get() = takesValue.size

    /** [args] without the flags this binary does not take, and what was left out. */
    data class Filtered(val kept: List<String>, val dropped: List<String>)

    /**
     * Keeps positionals (the subcommand) and every flag this binary lists, with its value;
     * drops any other flag together with its value (the next token, unless that looks like
     * a flag: usque subcommands take no positionals after the subcommand).
     */
    fun supportedOnly(args: List<String>): Filtered {
        val kept = ArrayList<String>(args.size)
        val dropped = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val long = longName(arg)
            val attached = '=' in arg || isAttachedShorthand(arg)
            when {
                !isFlag(arg) -> kept += arg
                long != null && long in takesValue -> {
                    kept += arg
                    // A known value-taking flag owns the next token whatever it looks like
                    // ("-c -" passes the keys through stdin).
                    if (takesValue.getValue(long) && !attached && i + 1 < args.size) kept += args[++i]
                }
                else -> {
                    dropped += arg
                    if (!attached && i + 1 < args.size && !isFlag(args[i + 1])) dropped += args[++i]
                }
            }
            i++
        }
        return Filtered(kept, dropped)
    }

    // "--name", "--name=v" -> name; "-x", "-xVALUE" -> x's long name (null if unknown).
    private fun longName(arg: String): String? = when {
        arg.startsWith("--") -> arg.substring(2).substringBefore('=')
        isFlag(arg) -> shorthands[arg[1]]
        else -> null
    }

    // "-k10s" style: a shorthand with its value glued on.
    private fun isAttachedShorthand(arg: String): Boolean =
        !arg.startsWith("--") && isFlag(arg) && arg.length > 2

    companion object {
        // pflag's layout: "  -b, --bind string      usage" / "      --doh            usage".
        // One space before the value type, two or more (or end of line) before the usage.
        private val FLAG_LINE =
            Regex("""^\s+(?:-([A-Za-z0-9]), )?--([A-Za-z0-9][A-Za-z0-9-]*)(?: ([A-Za-z0-9]+))?(?:\s{2,}|$)""")

        /** "-" alone is a value (keys on stdin), not a flag. */
        fun isFlag(token: String): Boolean = token.length > 1 && token.startsWith("-")

        /**
         * Parses `usque <subcommand> --help`. Returns null unless every flag in [mustHave]
         * was found: a help text that parsed short (a changed layout, a truncated read)
         * must never make the filter drop flags the binary actually takes.
         */
        fun parseHelp(help: String, mustHave: Collection<String>): UsqueFlags? {
            val takes = LinkedHashMap<String, Boolean>()
            val shorts = HashMap<Char, String>()
            for (line in help.lineSequence()) {
                val m = FLAG_LINE.find(line) ?: continue
                val (short, long, type) = m.destructured
                takes[long] = type.isNotEmpty()
                if (short.isNotEmpty()) shorts[short[0]] = long
            }
            if (!takes.keys.containsAll(mustHave)) return null
            return UsqueFlags(takes, shorts)
        }
    }
}
