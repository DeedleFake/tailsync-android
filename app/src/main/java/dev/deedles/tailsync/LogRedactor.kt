package dev.deedles.tailsync

/**
 * Defense-in-depth scrubbing for free-text log lines shown in the UI.
 *
 * The Go engine never puts AuthKey in StatusJSON / structured event attrs, but
 * free-text log messages are not scrubbed. Never log [mobile.Config] itself —
 * its generated toString includes AuthKey.
 */
object LogRedactor {

    private val tsKeyPattern = Regex(
        """tskey-[A-Za-z0-9_-]+""",
        RegexOption.IGNORE_CASE,
    )

    private val authKeyishPattern = Regex(
        """(?i)(auth[_-]?key|ts_authkey)\s*[:=]\s*\S+""",
    )

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = tsKeyPattern.replace(text, "tskey-[redacted]")
        out = authKeyishPattern.replace(out, "$1=[redacted]")
        return out
    }
}
