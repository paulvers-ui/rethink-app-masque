package com.paulvers.auroravpn.service

/**
 * The config editors (WARP config.json, the chain's two identities and wg0.conf) show
 * every field but the secrets: those read [HIDDEN], and saving text that still says
 * [HIDDEN] keeps the stored secret. So endpoints, MTU and the like stay editable while
 * the keys never appear on screen, in a screenshot or on the clipboard.
 */
object KeyRedaction {
    const val HIDDEN = "(hidden)"

    // Capture groups of the field patterns: what precedes the value, the value, and
    // (JSON only) the closing quote.
    private const val HEAD = 1
    private const val VALUE = 2
    private const val TAIL = 3

    // usque config.json: the ECDSA identity key and the Cloudflare API token.
    private val JSON_SECRETS = listOf("private_key", "access_token")
    // wg-quick: the interface key and an optional peer pre-shared key.
    private val WG_SECRETS = listOf("PrivateKey", "PresharedKey")

    private fun jsonField(name: String) = Regex("(\"$name\"\\s*:\\s*\")([^\"]*)(\")")

    private fun wgField(name: String) =
        Regex("^(\\s*$name\\s*=\\s*)(\\S+)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /** [json] with the secret values replaced by [HIDDEN]. */
    fun hideJson(json: String): String =
        JSON_SECRETS.fold(json) { acc, f ->
            jsonField(f).replace(acc) { m ->
                if (m.groupValues[VALUE].isEmpty()) m.value else m.groupValues[HEAD] + HIDDEN + m.groupValues[TAIL]
            }
        }

    /** [edited] with every [HIDDEN] secret put back from [stored]; null if one has nothing to come from. */
    fun restoreJson(edited: String, stored: String?): String? =
        restore(edited, stored, JSON_SECRETS, ::jsonField) { m, value -> m.groupValues[HEAD] + value + m.groupValues[TAIL] }

    /** [conf] with the secret keys replaced by [HIDDEN]. */
    fun hideWg(conf: String): String =
        WG_SECRETS.fold(conf) { acc, f -> wgField(f).replace(acc) { m -> m.groupValues[HEAD] + HIDDEN } }

    /** [edited] with every [HIDDEN] key put back from [stored]; null if one has nothing to come from. */
    fun restoreWg(edited: String, stored: String?): String? =
        restore(edited, stored, WG_SECRETS, ::wgField) { m, value -> m.groupValues[HEAD] + value }

    private fun restore(
        edited: String,
        stored: String?,
        fields: List<String>,
        pattern: (String) -> Regex,
        put: (MatchResult, String) -> String,
    ): String? {
        var out = edited
        for (f in fields) {
            val re = pattern(f)
            val hidden = re.findAll(out).any { it.groupValues[VALUE] == HIDDEN }
            if (!hidden) continue
            val value = stored?.let { re.find(it)?.groupValues?.get(VALUE) }
            if (value.isNullOrEmpty() || value == HIDDEN) return null
            out = re.replace(out) { m -> if (m.groupValues[VALUE] == HIDDEN) put(m, value) else m.value }
        }
        return out
    }
}
