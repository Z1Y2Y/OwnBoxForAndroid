package moe.matsuri.nb4a.utils

import java.net.URLEncoder
import java.util.Locale

/** Defense-in-depth sanitization for diagnostic text before it is written to an export file. */
internal object LogExportSanitizer {
    private const val REDACTED = "[REDACTED]"

    private val secretField = Regex(
        """(?i)([\"']?(?:(?:clash[\s._-]*api[\s._-]*)?secret|(?:clash[\s._-]*api[\s._-]*)?token|api[\s._-]*key)[\"']?\s*[:=]\s*)(?:\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|[^\s,;}\]]+)"""
    )

    fun isSensitiveSettingKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        return normalized.contains("clashapi") && normalized.contains("secret")
    }

    fun sanitize(text: String, secret: String): String {
        var sanitized = secretField.replace(text) { match -> match.groupValues[1] + REDACTED }
        secretVariants(secret)
            .sortedByDescending(String::length)
            .forEach { value -> sanitized = sanitized.replace(value, REDACTED) }
        return sanitized
    }

    private fun secretVariants(secret: String): Set<String> {
        if (secret.isEmpty()) return emptySet()
        return buildSet {
            add(secret)
            add(secret.uppercase(Locale.ROOT))
            add(secret.lowercase(Locale.ROOT))
            runCatching { URLEncoder.encode(secret, "UTF-8") }
                .getOrNull()
                ?.takeIf(String::isNotEmpty)
                ?.let(::add)
        }
    }
}
