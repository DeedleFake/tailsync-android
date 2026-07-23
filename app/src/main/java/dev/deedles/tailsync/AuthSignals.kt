package dev.deedles.tailsync

/**
 * Pure parsing of mobile engine auth signals (no Android / JNI / org.json).
 * Uses lightweight field extraction so unit tests run on the JVM without
 * Robolectric (Android's org.json stubs return defaults under local unit tests).
 *
 * Events: `{"type":"auth","url":"https://login.tailscale.com/..."}`
 * StatusJSON may include `needs_login` and `auth_url` while interactive login
 * is in progress. AuthKey is never present in either payload.
 *
 * [JsonFields] only reads flat string/boolean keys on a single object — nested
 * objects, arrays, and non-boolean `needs_login` values are out of scope.
 */
data class AuthEvent(
    val url: String,
)

data class AuthStatusFields(
    val needsLogin: Boolean,
    val authUrl: String?,
)

object AuthSignals {
    /**
     * Parses an auth event from [eventJson]. Returns null if the payload is not
     * an auth event or has no usable URL.
     */
    fun parseAuthEvent(eventJson: String): AuthEvent? {
        if (eventJson.isBlank()) return null
        val type = JsonFields.string(eventJson, "type") ?: return null
        if (type != "auth") return null
        val url = JsonFields.string(eventJson, "url")?.trim().orEmpty()
        return if (url.isEmpty()) null else AuthEvent(url)
    }

    /**
     * Reads interactive-login fields from a StatusJSON snapshot.
     * Missing keys mean not in login flow.
     */
    fun parseAuthStatus(statusJson: String?): AuthStatusFields {
        if (statusJson.isNullOrBlank()) {
            return AuthStatusFields(needsLogin = false, authUrl = null)
        }
        val needsLogin = JsonFields.boolean(statusJson, "needs_login") ?: false
        val rawUrl = JsonFields.string(statusJson, "auth_url")?.trim().orEmpty()
        val authUrl = rawUrl.ifEmpty { null }
        return AuthStatusFields(needsLogin = needsLogin, authUrl = authUrl)
    }

    /**
     * Resolves the URL to present for browser login, preferring an explicit
     * event URL then StatusJSON.auth_url.
     */
    fun resolveAuthUrl(eventUrl: String?, status: AuthStatusFields): String? {
        val fromEvent = eventUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (fromEvent != null) return fromEvent
        if (status.needsLogin) {
            return status.authUrl
        }
        return null
    }
}

/**
 * Minimal JSON field reader for flat string/boolean values.
 * Sufficient for engine event/status objects; not a full JSON parser.
 */
internal object JsonFields {
    private fun stringPattern(key: String): Regex =
        Regex(""""${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"\\])*)"""")

    private fun booleanPattern(key: String): Regex =
        Regex(""""${Regex.escape(key)}"\s*:\s*(true|false)""")

    fun string(json: String, key: String): String? {
        val m = stringPattern(key).find(json) ?: return null
        return unescapeJsonString(m.groupValues[1])
    }

    fun boolean(json: String, key: String): Boolean? {
        val m = booleanPattern(key).find(json) ?: return null
        return m.groupValues[1] == "true"
    }

    private fun unescapeJsonString(raw: String): String {
        if (!raw.contains('\\')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val n = raw[i + 1]) {
                    '"', '\\', '/' -> {
                        out.append(n)
                        i += 2
                    }
                    'n' -> {
                        out.append('\n')
                        i += 2
                    }
                    'r' -> {
                        out.append('\r')
                        i += 2
                    }
                    't' -> {
                        out.append('\t')
                        i += 2
                    }
                    'u' if i + 5 < raw.length -> {
                        val hex = raw.substring(i + 2, i + 6)
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            out.append(code.toChar())
                            i += 6
                        } else {
                            out.append(c)
                            i++
                        }
                    }
                    else -> {
                        out.append(n)
                        i += 2
                    }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
